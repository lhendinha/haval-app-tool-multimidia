package br.com.redesurftank.havalshisuku.projectors

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

/**
 * Mostra o overlay do AC SOMENTE no card 1 e SOMENTE durante o timeout. Fora isso, a janela
 * (Presentation) é removida do display via dismiss().
 */
class InstrumentProjector2(outerContext: Context, display: Display) :
        BaseProjector(outerContext, display), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var preferences: SharedPreferences

    private lateinit var root: FrameLayout
    private lateinit var circularView: FrameLayout

    private var webViewAc: WebView? = null
    private val webViewsLoaded = mutableMapOf<WebView, Boolean>()
    private val pendingJsQueues = mutableMapOf<WebView, MutableList<String>>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private var hideOverlayRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = defaultSharedPreferences()
        preferences.registerOnSharedPreferenceChangeListener(this)

        WebView.setWebContentsDebuggingEnabled(true)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        root =
                FrameLayout(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                    setBackgroundColor(Color.TRANSPARENT)
                    isVisible = false
                }
        setContentView(root)

        circularView =
                FrameLayout(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                    outlineProvider =
                            object : ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: Outline) {
                                    val w = view.width
                                    val h = view.height
                                    val r = (minOf(w, h) / 2f)
                                    outline.setOval(
                                            (w / 2f - r).toInt(),
                                            (h / 2f - r).toInt(),
                                            (w / 2f + r).toInt(),
                                            (h / 2f + r).toInt()
                                    )
                                }
                            }
                    clipToOutline = true
                    isVisible = false
                    setBackgroundColor(Color.TRANSPARENT)
                }
        root.addView(circularView)

        // Se o ProjectorManager deu show() ao criar, e o card atual NÃO é o 1, remova a janela já.
        val currentCard = ServiceManager.getInstance().clusterCardView
        if (currentCard != 1 && isShowing) {
            dismiss() // evita bloquear OEM em clusters 2/3
        }

        ServiceManager.getInstance().addDataChangedListener { key, value ->
            ensureUi {
                when (key) {
                    CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.value -> {
                        evaluateJsIfReady(webViewAc, "control('temp', $value)")
                        showAcOverlayTemporarily()
                    }
                    CarConstants.CAR_HVAC_FAN_SPEED.value -> {
                        evaluateJsIfReady(webViewAc, "control('fan', $value)")
                        showAcOverlayTemporarily()
                    }
                    CarConstants.CAR_HVAC_POWER_MODE.value -> {
                        evaluateJsIfReady(webViewAc, "control('power', $value)")
                        showAcOverlayTemporarily()
                    }
                    CarConstants.CAR_HVAC_CYCLE_MODE.value -> {
                        evaluateJsIfReady(webViewAc, "control('recycle', $value)")
                        showAcOverlayTemporarily()
                    }
                    CarConstants.CAR_HVAC_AUTO_ENABLE.value -> {
                        evaluateJsIfReady(webViewAc, "control('auto', $value)")
                        showAcOverlayTemporarily()
                    }
                }
            }
        }

        ServiceManager.getInstance().addServiceManagerEventListener { event, args ->
            ensureUi {
                when (event) {
                    ServiceManagerEventType.CLUSTER_CARD_CHANGED -> {
                        val card = args[0] as Int
                        if (card == 1) {
                            // Não mostra nada por padrão; aparece só em interação
                            // (showAcOverlayTemporarily)
                            circularView.isVisible = false
                            webViewAc?.isVisible = false
                            if (isShowing) {
                                // Se estava com janela aberta por algum motivo, feche até a próxima
                                // interação
                                dismiss()
                            }
                        } else {
                            // Qualquer outro card: garanta que a janela NÃO exista
                            hideAcOverlay()
                        }
                    }
                    ServiceManagerEventType.STEERING_WHEEL_AC_CONTROL -> {
                        when (args[0] as SteeringWheelAcControlType) {
                            SteeringWheelAcControlType.FAN_SPEED -> {
                                evaluateJsIfReady(webViewAc, "focus('fan')")
                                showAcOverlayTemporarily()
                            }
                            SteeringWheelAcControlType.TEMPERATURE -> {
                                evaluateJsIfReady(webViewAc, "focus('temp')")
                                showAcOverlayTemporarily()
                            }
                            SteeringWheelAcControlType.POWER -> {
                                evaluateJsIfReady(webViewAc, "focus('power')")
                                showAcOverlayTemporarily()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
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
                        setBackgroundColor(Color.TRANSPARENT)
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
                    }
            circularView.addView(webViewAc)
            webViewAc!!.loadDataWithBaseURL(null, readRawHtml(context), "text/html", "UTF-8", null)
            webViewAc!!.isVisible = false
        }
        updateValuesWebViewAc()
    }

    // --- Multiplexação real (cria/remove janela do display) ---
    private fun showAcOverlayTemporarily() {
        if (!isMultiplexerEnabled()) return
        if (ServiceManager.getInstance().clusterCardView != 1) return

        setupAcControlView(circularView)

        if (!isShowing) {
            try {
                show() // cria janela no display
            } catch (_: Throwable) {
                /* evita crash se display mutou */
            }
        }

        root.isVisible = true
        circularView.isVisible = true
        webViewAc?.isVisible = true

        scheduleHideOverlay()
    }

    private fun hideAcOverlay() {
        cancelHideOverlay()
        try {
            if (isShowing) {
                dismiss() // remove janela do display -> OEM volta a listar/mostrar
            }
        } catch (_: Throwable) {}
        // limpar estados visuais para próxima reutilização
        root.isVisible = false
        circularView.isVisible = false
        webViewAc?.isVisible = false
    }

    private fun scheduleHideOverlay() {
        cancelHideOverlay()
        val timeout = getMultiplexerTimeoutMs()
        hideOverlayRunnable = Runnable { hideAcOverlay() }
        uiHandler.postDelayed(hideOverlayRunnable!!, timeout.toLong())
    }

    private fun cancelHideOverlay() {
        hideOverlayRunnable?.let { uiHandler.removeCallbacks(it) }
        hideOverlayRunnable = null
    }

    private fun isMultiplexerEnabled(): Boolean =
            preferences.getBoolean(
                    SharedPreferencesKeys.ENABLE_SECONDARY_CLUSTER_MULTIPLEXER.key,
                    true
            )

    private fun getMultiplexerTimeoutMs(): Int =
            preferences.getInt(SharedPreferencesKeys.SECONDARY_MULTIPLEXER_TIMEOUT_MS.key, 6000)

    private fun updateValuesWebViewAc() {
        val currentTemp =
                ServiceManager.getInstance().getData(CarConstants.CAR_HVAC_DRIVER_TEMPERATURE.value)
        val currentFanSpeed =
                ServiceManager.getInstance().getData(CarConstants.CAR_HVAC_FAN_SPEED.value)
        val currentAcState =
                ServiceManager.getInstance().getData(CarConstants.CAR_HVAC_POWER_MODE.value)
        val currentRecycleMode =
                ServiceManager.getInstance().getData(CarConstants.CAR_HVAC_CYCLE_MODE.value)
        val currentAutoMode =
                ServiceManager.getInstance().getData(CarConstants.CAR_HVAC_AUTO_ENABLE.value)

        evaluateJsIfReady(webViewAc, "control('temp', $currentTemp)")
        evaluateJsIfReady(webViewAc, "control('fan', $currentFanSpeed)")
        evaluateJsIfReady(webViewAc, "control('power', $currentAcState)")
        evaluateJsIfReady(webViewAc, "control('recycle', $currentRecycleMode)")
        evaluateJsIfReady(webViewAc, "control('auto', $currentAutoMode)")

        showAcOverlayTemporarily()
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
        ensureUi { hideAcOverlay() }
    }

    override fun carMainScreenOn() {
        // Mantemos a janela desligada até a próxima interação
    }

    override fun onStop() {
        super.onStop()
        ensureUi { hideAcOverlay() }
    }

    override fun cancel() {
        ensureUi {
            hideAcOverlay()
            preferences.unregisterOnSharedPreferenceChangeListener(this)
        }
        super.cancel()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {}

    private fun defaultSharedPreferences(): SharedPreferences {
        val name = App.getContext().packageName + "_preferences"
        return App.getContext().getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}
