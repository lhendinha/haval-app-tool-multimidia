package com.tools.cluster

import android.app.Service
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.telephony.TelephonyManager
import android.view.*
import android.widget.RemoteViews
import androidx.annotation.MainThread

/**
 * Multiplexa o slot secundário do cluster: preserva o provider original e injeta a UI do
 * ar-condicionado sem quebrar o pipeline que o terciário depende. Evita depender de R.* usando
 * getIdentifier() para layouts.
 */
class ClusterSecondaryMultiplexerService : Service() {

    private val scope = HandlerThread("cluster-mux").apply { start() }
    private val bg = Handler(scope.looper)
    private lateinit var wm: WindowManager

    private var originalComponent: ComponentName? = null
    private var originalBound = false
    private var originalServiceConn: ServiceConnection? = null

    private var overlayView: View? = null
    private var overlayShown = false

    private val telephony by lazy { getSystemService(TelephonyManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        discoverOriginalProvider()
        bindOriginalProvider()
        publishSecondaryPlaceholder()
        registerReceivers()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceivers()
        } catch (_: Throwable) {}
        unbindOriginal()
        removeOverlay()
        scope.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun discoverOriginalProvider() {
        val pm = packageManager
        val query = Intent(ACTION_CLUSTER_SECONDARY)
        val candidates = pm.queryIntentServices(query, 0)
        val mine = packageName
        val target = candidates.firstOrNull { it.serviceInfo?.packageName != mine }?.serviceInfo
        originalComponent = target?.let { ComponentName(it.packageName, it.name) }
    }

    private fun bindOriginalProvider() {
        val comp = originalComponent ?: return
        val it = Intent(ACTION_CLUSTER_SECONDARY).setComponent(comp)
        originalServiceConn =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        originalBound = true
                        sendClusterReadyBroadcast()
                        mirrorMediaIdleState()
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        originalBound = false
                        bg.postDelayed({ bindOriginalProvider() }, 1200)
                    }
                }
        try {
            bindService(it, originalServiceConn!!, BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            scheduleRebind()
        }
    }

    private fun unbindOriginal() {
        originalServiceConn?.let { runCatching { unbindService(it) } }
        originalServiceConn = null
        originalBound = false
    }

    private fun scheduleRebind() = bg.postDelayed({ bindOriginalProvider() }, 1000)

    private fun publishSecondaryPlaceholder() {
        val layoutId =
                resources.getIdentifier("cluster_secondary_placeholder", "layout", packageName)
        if (layoutId != 0) {
            val rv = RemoteViews(packageName, layoutId)
            sendBroadcast(Intent(ACTION_CLUSTER_SECONDARY_RENDER).putExtra(EXTRA_REMOTE_VIEWS, rv))
        } else {
            // Mesmo sem layout, emite ready para não travar o host
            sendClusterReadyBroadcast()
            mirrorMediaIdleState()
        }
    }

    private fun sendClusterReadyBroadcast() {
        val i = Intent(ACTION_CLUSTER_WIDGET_READY).putExtra(EXTRA_WIDGET, "secondary")
        i.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING)
        sendBroadcast(i)
    }

    private fun mirrorMediaIdleState() {
        val i = Intent(ACTION_MEDIA_SESSION_STATE).putExtra("state", "idle")
        i.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING)
        sendBroadcast(i)
    }

    @MainThread
    private fun showOverlay() {
        if (overlayShown) return
        val type =
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params =
                WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT,
                                type,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                PixelFormat.TRANSLUCENT
                        )
                        .apply {
                            title = "AirControlOverlay"
                            layoutInDisplayCutoutMode =
                                    WindowManager.LayoutParams
                                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                        }

        val overlayLayoutId = resources.getIdentifier("air_control_overlay", "layout", packageName)
        if (overlayLayoutId != 0) {
            overlayView = LayoutInflater.from(this).inflate(overlayLayoutId, null, false)
            overlayView?.setOnApplyWindowInsetsListener { _, insets -> insets }
            wm.addView(overlayView, params)
            overlayShown = true
            bg.postDelayed({ removeOverlay() }, 6000)
        }
    }

    @MainThread
    private fun removeOverlay() {
        if (!overlayShown) return
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
        overlayShown = false
    }

    private fun registerReceivers() {
        registerReceiver(
                hvacReceiver,
                IntentFilter().apply {
                    addAction(ACTION_HVAC_CHANGED)
                    addAction(ACTION_SHOW_AIR_CONTROL)
                }
        )
        registerReceiver(
                callReceiver,
                IntentFilter().apply { addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED) }
        )
    }

    private fun unregisterReceivers() {
        unregisterReceiver(hvacReceiver)
        unregisterReceiver(callReceiver)
    }

    private val hvacReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ACTION_HVAC_CHANGED, ACTION_SHOW_AIR_CONTROL -> showOverlay()
                    }
                }
            }

    private val callReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val state = telephony?.callState
                    if (state == TelephonyManager.CALL_STATE_OFFHOOK ||
                                    state == TelephonyManager.CALL_STATE_RINGING
                    ) {
                        removeOverlay()
                    }
                }
            }

    companion object {
        const val ACTION_CLUSTER_SECONDARY = "com.gwm.cluster.SECONDARY"
        const val ACTION_CLUSTER_SECONDARY_RENDER = "com.gwm.cluster.SECONDARY_RENDER"
        const val ACTION_CLUSTER_WIDGET_READY = "com.gwm.cluster.WIDGET_READY"
        const val ACTION_MEDIA_SESSION_STATE = "com.gwm.cluster.MEDIA_SESSION_STATE"

        const val ACTION_HVAC_CHANGED = "com.tools.ACTION_HVAC_CHANGED"
        const val ACTION_SHOW_AIR_CONTROL = "com.tools.ACTION_SHOW_AIR_CONTROL"

        const val EXTRA_REMOTE_VIEWS = "remote_views"
        const val EXTRA_WIDGET = "widget"
    }
}
