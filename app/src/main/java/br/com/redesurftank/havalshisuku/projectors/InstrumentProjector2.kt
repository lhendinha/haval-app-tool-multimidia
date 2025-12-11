package br.com.redesurftank.havalshisuku.projectors

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import br.com.redesurftank.App
import br.com.redesurftank.havalshisuku.R
import br.com.redesurftank.havalshisuku.managers.ServiceManager
import br.com.redesurftank.havalshisuku.models.CarConstants
import br.com.redesurftank.havalshisuku.models.ServiceManagerEventType
import br.com.redesurftank.havalshisuku.models.SharedPreferencesKeys
import br.com.redesurftank.havalshisuku.models.SteeringWheelAcControlType

class InstrumentProjector2(outerContext: Context, display: Display) :
        BaseProjector(outerContext, display) {
    private val preferences: SharedPreferences =
            App.getDeviceProtectedContext()
                    .getSharedPreferences("haval_prefs", Context.MODE_PRIVATE)

    private var webViewAc: WebView? = null
    private val webViewsLoaded = mutableMapOf<WebView, Boolean>()
    private val pendingJsQueues = mutableMapOf<WebView, MutableList<String>>()
    private lateinit var root: FrameLayout

    private val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key in
                                listOf(
                                        SharedPreferencesKeys
                                                .ENABLE_INSTRUMENT_CUSTOM_MEDIA_INTEGRATION
                                                .key,
                                        SharedPreferencesKeys.ENABLE_INSTRUMENT_PROJECTOR.key
                                )
                ) {
                    ensureUi {
                        root.isVisible =
                                shouldShowProjector() && ServiceManager.getInstance().isMainScreenOn
                    }
                }
            }

    private fun shouldShowProjector(): Boolean {
        return preferences.getBoolean(
                SharedPreferencesKeys.ENABLE_INSTRUMENT_PROJECTOR.key,
                false
        ) &&
                preferences.getBoolean(
                        SharedPreferencesKeys.ENABLE_INSTRUMENT_CUSTOM_MEDIA_INTEGRATION.key,
                        false
                )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences.registerOnSharedPreferenceChangeListener(prefsListener)
        WebView.setWebContentsDebuggingEnabled(true)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window?.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

        root = FrameLayout(context)
        setContentView(root)

        val radius = 226
        val centerX = 1630
        val centerY = 430

        val circularView =
                FrameLayout(context).apply {
                    val params = FrameLayout.LayoutParams(radius * 2, radius * 2)
                    params.leftMargin = centerX - radius
                    params.topMargin = centerY - radius
                    layoutParams = params
                    setBackgroundColor(Color.TRANSPARENT)
                    clipToOutline = true
                    outlineProvider =
                            object : ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: Outline) {
                                    outline.setOval(0, 0, view.width, view.height)
                                }
                            }
                    isVisible = false
                }
        root.addView(circularView)
        setupAcControlView(circularView)

        // Push AC changes into the WebView when CAN data changes.
        ServiceManager.getInstance().addDataChangedListener { key, value ->
            ensureUi {
                when (key) {
                    CarConstants.CAR_HVAC_FAN_SPEED.value ->
                            evaluateJsIfReady(webViewAc, "control('fan', $value)")
                    CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.value ->
                            evaluateJsIfReady(webViewAc, "control('temp', $value)")
                    CarConstants.CAR_HVAC_POWER_MODE.value ->
                            evaluateJsIfReady(webViewAc, "control('power', $value)")
                    CarConstants.CAR_HVAC_CYCLE_MODE.value ->
                            evaluateJsIfReady(webViewAc, "control('recycle', $value)")
                    CarConstants.CAR_HVAC_AUTO_ENABLE.value ->
                            evaluateJsIfReady(webViewAc, "control('auto', $value)")
                    CarConstants.CAR_HVAC_ANION_ENABLE.value ->
                            evaluateJsIfReady(webViewAc, "control('aion', $value)")
                    else -> {}
                }
            }
        }

        // Show AC only on the media card (card == 1). Hide overlay otherwise (prevents blocking
        // Calls card).
        ServiceManager.getInstance().addServiceManagerEventListener { event, args ->
            ensureUi {
                when (event) {
                    ServiceManagerEventType.CLUSTER_CARD_CHANGED -> {
                        val card = args[0] as Int

                        // ✅ Fix: show overlay ONLY on card 1; hide on all others.
                        val shouldShowAc = (card == 1)
                        circularView.isVisible = shouldShowAc
                        webViewAc?.isVisible = false
                        if (shouldShowAc) showAcControlView()

                        // Optional hard guard: hide the whole root when not on card 1 to be 100%
                        // non-intrusive.
                        root.isVisible =
                                shouldShowProjector() &&
                                        ServiceManager.getInstance().isMainScreenOn &&
                                        shouldShowAc
                    }
                    ServiceManagerEventType.STEERING_WHEEL_AC_CONTROL -> {
                        when (args[0] as SteeringWheelAcControlType) {
                            SteeringWheelAcControlType.FAN_SPEED ->
                                    evaluateJsIfReady(webViewAc, "focus('fan')")
                            SteeringWheelAcControlType.TEMPERATURE ->
                                    evaluateJsIfReady(webViewAc, "focus('temp')")
                            SteeringWheelAcControlType.POWER ->
                                    evaluateJsIfReady(webViewAc, "focus('power')")
                        }
                    }
                }
            }
        }

        root.isVisible = shouldShowProjector() && ServiceManager.getInstance().isMainScreenOn
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAcControlView(circularView: FrameLayout) {
        if (webViewAc == null) {
            webViewAc =
                    WebView(context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowContentAccess = true
                        setBackgroundColor(
                                Color.TRANSPARENT
                        ) // avoid covering area with opaque layer

                        webViewClient =
                                object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.let {
                                            webViewsLoaded[it] = true
                                            updateValuesWebViewAc()
                                            val queue = pendingJsQueues[it] ?: return
                                            queue.forEach { js -> it.evaluateJavascript(js, null) }
                                            pendingJsQueues.remove(it)
                                        }
                                    }
                                }
                        loadDataWithBaseURL(null, readRawHtml(context), "text/html", "UTF-8", null)
                        isVisible = false
                    }
            circularView.addView(webViewAc)
        }
    }

    private fun showAcControlView() {
        webViewAc?.isVisible = true
        webViewAc?.let { if (webViewsLoaded[it] == true) updateValuesWebViewAc() }
    }

    private fun updateValuesWebViewAc() {
        val sm = ServiceManager.getInstance()
        val currentTemp = sm.getData(CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.value)
        val currentFanSpeed = sm.getData(CarConstants.CAR_HVAC_FAN_SPEED.value)
        val currentAcState = sm.getData(CarConstants.CAR_HVAC_POWER_MODE.value)
        val currentRecycleMode = sm.getData(CarConstants.CAR_HVAC_CYCLE_MODE.value)
        val currentAutoMode = sm.getData(CarConstants.CAR_HVAC_AUTO_ENABLE.value)

        evaluateJsIfReady(webViewAc, "control('temp', $currentTemp)")
        evaluateJsIfReady(webViewAc, "control('fan', $currentFanSpeed)")
        evaluateJsIfReady(webViewAc, "control('power', $currentAcState)")
        evaluateJsIfReady(webViewAc, "control('recycle', $currentRecycleMode)")
        evaluateJsIfReady(webViewAc, "control('auto', $currentAutoMode)")
        evaluateJsIfReady(webViewAc, "focus('fan')")
    }

    private fun evaluateJsIfReady(webView: WebView?, js: String) {
        if (webView == null) return
        val loaded = webViewsLoaded.getOrDefault(webView, false)
        if (loaded) {
            webView.evaluateJavascript(js, null)
        } else {
            pendingJsQueues.getOrPut(webView) { mutableListOf() }.add(js)
        }
    }

    fun readRawHtml(context: Context): String {
        return context.resources.openRawResource(R.raw.app).bufferedReader().use { it.readText() }
    }

    override fun carMainScreenOff() {
        ensureUi { root.isVisible = false }
    }

    override fun carMainScreenOn() {
        ensureUi { root.isVisible = true }
    }
}
