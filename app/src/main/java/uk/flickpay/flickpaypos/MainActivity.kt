package uk.flickpay.flickpaypos

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private val tag = "FlickpayPOS"
    private lateinit var webView: WebView
    private var launchOverlay: View? = null
    private var hiddenSettingsHotspotEnabled = false
    private var customerDisplayController: CustomerDisplayController? = null
    private var usbReceiverRegistered = false
    @Volatile
    private var isExiting = false
    private var hiddenSettingsHoldArmed = false
    private var startupUpdateCheckScheduled = false
    private var attemptedOfflineFallback = false
    private var attemptedOfflineCacheOnlyReload = false
    private var kioskMissingOwnerLogged = false
    @Volatile
    private var lastWebInteractionAtMs = 0L
    @Volatile
    private var lastStatusJsonLogAtMs = 0L
    private val hiddenSettingsHoldHandler = Handler(Looper.getMainLooper())
    private val startupUpdateHandler = Handler(Looper.getMainLooper())
    private val kioskUiGuardHandler = Handler(Looper.getMainLooper())
    private val hiddenSettingsHoldRunnable = Runnable {
        if (!hiddenSettingsHoldArmed) return@Runnable
        clearHiddenSettingsHold()
        openSettings()
    }
    private val kioskUiGuardRunnable = object : Runnable {
        override fun run() {
            if (isExiting || isFinishing || isDestroyed) return
            if (!AppSettings(this@MainActivity).isHomeLauncherEnabled()) return
            enableImmersiveMode()
            kioskUiGuardHandler.postDelayed(this, 120L)
        }
    }
    private val startupUpdateRunnable = Runnable {
        if (isExiting || isFinishing || isDestroyed) return@Runnable
        AppUpdateManager.checkForUpdateAsync { result ->
            if (isExiting || isFinishing || isDestroyed) return@checkForUpdateAsync
            if (result is AppUpdateManager.CheckResult.UpdateAvailable) {
                AppUpdateManager.showUpdatePrompt(this, result.update)
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    // Permission result consumed passively; print service will use granted permissions.
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (isExiting) return
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    requestUsbPermissionForDevice(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // No-op. Printer detection is dynamic and evaluated per print request.
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isExiting = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        runSafe("syncComponentStates") { AppSettings(this).syncComponentStates() }
        runSafe("applyRotationLockPreference") { applyRotationLockPreference() }
        runSafe("enforceKioskMode.onCreate") { enforceKioskMode("on_create") }
        runSafe("enableImmersiveMode") { enableImmersiveMode() }
        runSafe("installSystemUiGuards") { installSystemUiGuards() }

        webView = findViewById(R.id.mainWebView)
        launchOverlay = findViewById(R.id.launchOverlay)
        webView.setBackgroundColor(Color.WHITE)
        runSafe("customerDisplayController.start") {
            customerDisplayController = CustomerDisplayController(this).also { it.start() }
        }
        runSafe("updateCustomerDisplayFromPrefs") { updateCustomerDisplayFromPrefs() }

        runSafe("registerUsbPermissionReceiver") { registerUsbPermissionReceiver() }
        runSafe("requestUsbPermissionsForAttachedPrinters") { requestUsbPermissionsForAttachedPrinters() }
        runSafe("startPosBackgroundService") { startPosBackgroundService() }
        runSafe("configureWebView") { configureWebView() }
        runSafe("loadConfiguredUrlWhenProxyReady") { loadConfiguredUrlWhenProxyReady() }
    }

    override fun onDestroy() {
        clearHiddenSettingsHold()
        startupUpdateHandler.removeCallbacks(startupUpdateRunnable)
        stopKioskUiGuard()
        runCatching { customerDisplayController?.stop() }
        customerDisplayController = null
        if (usbReceiverRegistered) {
            unregisterReceiver(usbPermissionReceiver)
            usbReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (isExiting) return
        runSafe("onResume.applyRotationLockPreference") { applyRotationLockPreference() }
        runSafe("onResume.enforceKioskMode") { enforceKioskMode("on_resume") }
        runSafe("onResume.enableImmersiveMode") { enableImmersiveMode() }
        runSafe("onResume.kioskUiGuard") { syncKioskUiGuard() }
        runSafe("onResume.customerDisplayController.start") {
            if (customerDisplayController == null) {
                customerDisplayController = CustomerDisplayController(this)
            }
            customerDisplayController?.start()
        }
        runSafe("onResume.resumePendingInstallIfPossible") { AppUpdateManager.resumePendingInstallIfPossible(this) }
        runSafe("onResume.scheduleStartupUpdateCheck") { scheduleStartupUpdateCheck() }
        runSafe("onResume.startPosBackgroundService") { startPosBackgroundService() }
        runSafe("onResume.updateCustomerDisplayFromPrefs") { updateCustomerDisplayFromPrefs() }
        Thread {
            waitForLocalProxyReady(timeoutMs = 3000)
            runOnUiThread {
                runSafe("onResume.warmupProxyInWebView") { warmupProxyInWebView(webView) }
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        stopKioskUiGuard()
        runCatching { customerDisplayController?.stop() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
            syncKioskUiGuard()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            lastWebInteractionAtMs = SystemClock.elapsedRealtime()
        }
        handleHiddenSettingsGesture(event)
        return super.dispatchTouchEvent(event)
    }

    private fun startPosBackgroundService() {
        val intent = Intent(this, PosBackgroundService::class.java)
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { err ->
            AppRuntimeLog.e(applicationContext, tag, "Failed to start background service", err)
        }
    }

    private fun restartPosBackgroundService() {
        val intent = Intent(this, PosBackgroundService::class.java)
        runCatching { stopService(intent) }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { err ->
            AppRuntimeLog.e(applicationContext, tag, "Failed to restart background service", err)
        }
    }

    private fun runSafe(step: String, block: () -> Unit) {
        runCatching(block).onFailure { err ->
            AppRuntimeLog.e(applicationContext, tag, "Startup step failed: $step", err)
        }
    }

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private fun requestUsbPermissionsForAttachedPrinters() {
        if (isExiting) return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )

        for (device in usbManager.deviceList.values) {
            if (!usbManager.hasPermission(device)) usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun requestUsbPermissionForDevice(device: UsbDevice?) {
        if (isExiting) return
        if (device == null) return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) return
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag()
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Stable runtime marker so POS templates can always detect Android platform.
            val currentUa = userAgentString.orEmpty()
            if (!currentUa.contains("FlickpayPOSAndroid", ignoreCase = true)) {
                userAgentString = "$currentUa FlickpayPOSAndroid/${BuildConfig.VERSION_NAME}".trim()
            }
        }

        runCatching { webView.addJavascriptInterface(PosNativeBridge(), "FlickpayPOSNative") }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Keep web content colors controlled by Odoo/app CSS, not WebView auto-darkening.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message().trim()
                if (message.isNotBlank()) {
                    val source = consoleMessage.sourceId().orEmpty()
                    val line = consoleMessage.lineNumber()
                    val formatted = if (source.isNotBlank()) {
                        "$source:$line $message"
                    } else {
                        message
                    }
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR ->
                            AppRuntimeLog.e(applicationContext, "OdooWeb", formatted)
                        ConsoleMessage.MessageLevel.WARNING ->
                            AppRuntimeLog.w(applicationContext, "OdooWeb", formatted)
                        else -> Unit
                    }
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val isCustomAppScheme =
                    uri.scheme?.equals("pos", ignoreCase = true) == true ||
                        uri.scheme?.equals("flickpay", ignoreCase = true) == true
                if (isCustomAppScheme) {
                    val hasUserGesture = request.hasGesture() || isRecentUserInteraction()
                    return handlePosCommand(uri, hasUserGesture)
                }
                return false
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val raw = url?.trim().orEmpty()
                if (raw.isBlank()) return false
                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
                val isCustomAppScheme =
                    uri.scheme?.equals("pos", ignoreCase = true) == true ||
                        uri.scheme?.equals("flickpay", ignoreCase = true) == true
                if (!isCustomAppScheme) return false
                return handlePosCommand(uri, isRecentUserInteraction())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideLaunchOverlay()
                updateHiddenSettingsHotspotVisibility(url)
                attemptedOfflineFallback = false
                attemptedOfflineCacheOnlyReload = false
                persistLastGoodPosUrl(url)
                // Keep local proxy fallback available for POS-side reconnect logic.
                view?.evaluateJavascript(
                    """
                    (() => {
                      try {
                        window.__flickpayAndroid = true;
                        window.__FP_APP_VERSION__ = "${BuildConfig.VERSION_NAME}";
                        window.__FP_APP_PLATFORM__ = "Android";
                        // Android app always uses local bridge for the main POS proxy endpoint.
                        localStorage.setItem("hw_proxy_url", "http://127.0.0.1:8070");
                        // Avoid browser-level CORS noise for localhost hello probes on Android.
                        if (
                          !window.__fpPatchedLocalHelloFetch &&
                          typeof window.fetch === "function" &&
                          typeof Response !== "undefined"
                        ) {
                          const originalFetch = window.fetch.bind(window);
                          const isLocalHelloProbe = (value) => {
                            try {
                              const parsed = new URL(String(value || ""), window.location.href);
                              const host = String(parsed.hostname || "").toLowerCase();
                              const normalizedPath = String(parsed.pathname || "")
                                .toLowerCase()
                                .replace(/\/+$/, "");
                              return (
                                (host === "127.0.0.1" || host === "localhost") &&
                                normalizedPath.endsWith("/hw_proxy/hello")
                              );
                            } catch (_) {
                              return false;
                            }
                          };
                          window.fetch = (input, init) => {
                            try {
                              const raw = typeof input === "string" ? input : String(input?.url || "");
                              if (isLocalHelloProbe(raw)) {
                                return Promise.resolve(
                                  new Response("ping", {
                                    status: 200,
                                    headers: { "Content-Type": "text/plain" },
                                  })
                                );
                              }
                            } catch (_) {}
                            return originalFetch(input, init);
                          };
                          window.__fpPatchedLocalHelloFetch = true;
                        }

                        // Force platform badge text for builds where POS templates still hardcode "(Windows)".
                        const normalizeVersionBadges = () => {
                          try {
                            const badges = document.querySelectorAll(".fp-versionBadge");
                            for (const badge of badges) {
                              const raw = String(badge.textContent || "").trim();
                              if (!raw || !/APP\\s+VERSION\\s*:/i.test(raw)) continue;
                              let next = raw.replace(/\\(\\s*Windows\\s*\\)/ig, "(Android)");
                              if (next === raw && !/\\([^)]*\\)\\s*$/.test(next)) {
                                next = `${'$'}{next} (Android)`;
                              }
                              if (next !== raw) {
                                badge.textContent = next;
                              }
                            }
                          } catch (_) {}
                        };
                        normalizeVersionBadges();
                        let runs = 0;
                        const tick = () => {
                          normalizeVersionBadges();
                          runs += 1;
                          if (runs < 30) setTimeout(tick, 400);
                        };
                        tick();

                        const fatalOfflineRegex = /valsArray\\s+is\\s+not\\s+iterable/i;
                        const triggerOfflineFallback = () => {
                          try {
                            if (window.__fpFatalOfflineHandled) return;
                            window.__fpFatalOfflineHandled = true;
                            window.location.href = "pos://offlinecorrupt";
                          } catch (_) {}
                        };
                        const errorText = (value) => String(value?.message || value || "");
                        window.addEventListener("error", (event) => {
                          try {
                            if (fatalOfflineRegex.test(errorText(event))) triggerOfflineFallback();
                          } catch (_) {}
                        }, true);
                        window.addEventListener("unhandledrejection", (event) => {
                          try {
                            const reasonText = errorText(event?.reason);
                            if (fatalOfflineRegex.test(reasonText)) triggerOfflineFallback();
                          } catch (_) {}
                        }, true);
                        const scanFatalOfflineModal = () => {
                          try {
                            const txt = String(document.body?.innerText || "");
                            if (fatalOfflineRegex.test(txt) && /oops!/i.test(txt)) {
                              triggerOfflineFallback();
                            }
                          } catch (_) {}
                        };
                        setTimeout(scanFatalOfflineModal, 250);
                        setTimeout(scanFatalOfflineModal, 900);
                        setTimeout(scanFatalOfflineModal, 2200);
                      } catch (_) {}
                    })();
                    """.trimIndent(),
                    null
                )
                warmupProxyInWebView(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                hideLaunchOverlay()
                updateHiddenSettingsHotspotVisibility(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && !isNetworkAvailable()) {
                    handleOfflineMainFrameFailure(view)
                    return
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true && !isNetworkAvailable()) {
                    handleOfflineMainFrameFailure(view)
                    return
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: SslError
            ) {
                val host = error.url?.let {
                    runCatching { Uri.parse(it).host ?: "" }.getOrDefault("")
                } ?: ""

                if (host == "127.0.0.1" || host == "localhost") {
                    handler.proceed()
                } else {
                    handler.cancel()
                }
            }
        }
    }

    private fun loadConfiguredUrlWhenProxyReady() {
        Thread {
            runCatching {
                waitForLocalProxyReady(timeoutMs = 5000)
                val url = resolveStartupUrlWithBootstrap()
                runOnUiThread {
                    runSafe("loadConfiguredUrlWhenProxyReady.loadUrl") {
                        attemptedOfflineFallback = false
                        applyNetworkAwareCacheMode()
                        webView.loadUrl(url)
                    }
                }
            }.onFailure { err ->
                AppRuntimeLog.e(applicationContext, tag, "Failed to resolve startup URL", err)
                runOnUiThread {
                    runSafe("loadConfiguredUrlWhenProxyReady.fallbackActivation") {
                        attemptedOfflineFallback = false
                        applyNetworkAwareCacheMode()
                        webView.loadUrl(buildActivationLoginUrl())
                    }
                }
            }
        }.start()
    }

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun applyNetworkAwareCacheMode() {
        webView.settings.cacheMode = if (isNetworkAvailable()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
    }

    private fun handleOfflineMainFrameFailure(view: WebView?) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedPosUrl = prefs.getString(KEY_POS_URL, null)?.trim().orEmpty()
        val lastGoodPosUrl = prefs.getString(KEY_LAST_GOOD_POS_URL, null)?.trim().orEmpty()
        val current = view?.url?.trim().orEmpty()
        val fallbackPosUrl = when {
            cachedPosUrl.isNotBlank() && !current.equals(cachedPosUrl, ignoreCase = true) -> cachedPosUrl
            lastGoodPosUrl.isNotBlank() && !current.equals(lastGoodPosUrl, ignoreCase = true) -> lastGoodPosUrl
            else -> ""
        }

        if (!attemptedOfflineFallback && fallbackPosUrl.isNotBlank()) {
            attemptedOfflineFallback = true
            attemptedOfflineCacheOnlyReload = false
            applyNetworkAwareCacheMode()
            view?.post {
                runCatching { view.loadUrl(fallbackPosUrl) }
            }
            return
        }

        if (!attemptedOfflineCacheOnlyReload) {
            attemptedOfflineCacheOnlyReload = true
            webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
            view?.post {
                runCatching { view.reload() }
            }
            return
        }

        showOfflineFallbackPage()
    }

    private fun persistLastGoodPosUrl(url: String?) {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return
        val lower = value.lowercase()
        if (!lower.startsWith("https://")) return
        if (!lower.contains("/pos/ui/") && !lower.contains("/pos-self/")) return
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GOOD_POS_URL, value)
            .apply()
    }

    private fun showOfflineFallbackPage() {
        hideLaunchOverlay()
        val html = """
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <style>
                  body { margin:0; font-family: sans-serif; background:#ffffff; color:#111827; }
                  .wrap { min-height:100vh; display:flex; align-items:center; justify-content:center; padding:24px; box-sizing:border-box; }
                  .card { max-width:520px; width:100%; border:1px solid #e5e7eb; border-radius:12px; padding:24px; }
                  h1 { margin:0 0 10px; font-size:24px; }
                  p { margin:0 0 16px; font-size:16px; line-height:1.45; color:#374151; }
                  button { background:#111827; color:#fff; border:0; border-radius:8px; padding:12px 18px; font-size:16px; }
                </style>
              </head>
              <body>
                <div class="wrap">
                  <div class="card">
                    <h1>Offline</h1>
                    <p>POS could not be loaded from cache. Connect to the internet and open this till once, then offline launch will work on this device.</p>
                    <button onclick="window.location.href='pos://retryoffline'">Retry</button>
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()
        runCatching { webView.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null) }
    }

    private fun hideLaunchOverlay() {
        val overlay = launchOverlay ?: return
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
            }
            .start()
    }

    private fun handleHiddenSettingsGesture(event: MotionEvent) {
        if (!hiddenSettingsHotspotEnabled) {
            clearHiddenSettingsHold()
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isInHiddenSettingsCorner(event)) {
                    armHiddenSettingsHold()
                } else {
                    clearHiddenSettingsHold()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (hiddenSettingsHoldArmed && !isInHiddenSettingsCorner(event)) {
                    clearHiddenSettingsHold()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> clearHiddenSettingsHold()
        }
    }

    private fun isInHiddenSettingsCorner(event: MotionEvent): Boolean {
        val root = window.decorView
        val width = root.width.toFloat()
        val height = root.height.toFloat()
        if (width <= 0f || height <= 0f) return false

        val sizePx = HIDDEN_SETTINGS_HOTSPOT_DP * resources.displayMetrics.density
        return event.rawX >= (width - sizePx) && event.rawY >= (height - sizePx)
    }

    private fun armHiddenSettingsHold() {
        clearHiddenSettingsHold()
        hiddenSettingsHoldArmed = true
        hiddenSettingsHoldHandler.postDelayed(hiddenSettingsHoldRunnable, HIDDEN_SETTINGS_HOLD_MS)
    }

    private fun clearHiddenSettingsHold() {
        hiddenSettingsHoldArmed = false
        hiddenSettingsHoldHandler.removeCallbacks(hiddenSettingsHoldRunnable)
    }

    private fun isSelfOrderingConfigured(): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_SCREEN1_MODE, "pos")?.trim()?.lowercase().orEmpty()
        return mode == "self"
    }

    private fun shouldShowHiddenSettingsHotspot(currentUrl: String?): Boolean {
        val lower = currentUrl?.trim()?.lowercase().orEmpty()
        if (lower.contains("/pos-self/")) return true
        return lower.isBlank() && isSelfOrderingConfigured()
    }

    private fun updateHiddenSettingsHotspotVisibility(currentUrl: String?) {
        hiddenSettingsHotspotEnabled = shouldShowHiddenSettingsHotspot(currentUrl)
        if (!hiddenSettingsHotspotEnabled) {
            clearHiddenSettingsHold()
        }
    }

    private fun warmupProxyInWebView(view: WebView?) {
        view?.evaluateJavascript(
            """
            (() => {
              try {
                if (window.__fpAndroidProxyWarmupInFlight) return;
                window.__fpAndroidProxyWarmupInFlight = true;
                setTimeout(() => { window.__fpAndroidProxyWarmupInFlight = false; }, 4000);
                try {
                  localStorage.setItem("hw_proxy_url", "http://127.0.0.1:8070");
                } catch (_) {}
                const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
                const connectProxyOnce = () => {
                  const waitConnected = async (ms = 1500) => {
                    const end = Date.now() + ms;
                    while (Date.now() < end) {
                      if (document.querySelector(".js_connected")) return true;
                      await sleep(120);
                    }
                    return !!document.querySelector(".js_connected");
                  };
                  const tryConnect = async () => {
                    for (let attempt = 0; attempt < 4; attempt++) {
                      if (document.querySelector(".js_connected")) return true;
                      const btn = document.querySelector(".js_proxy");
                      if (btn) {
                        btn.click();
                      }
                      const ok = await waitConnected(1700);
                      if (ok) return true;
                    }
                    return false;
                  };
                  tryConnect();
                };
                connectProxyOnce();
              } catch (_) {}
            })();
            """.trimIndent(),
            null
        )
    }

    private fun getOrCreateDeviceId(): String {
        return DeviceIdentity.getOrCreate(this)
    }

    private fun syncDeviceSettingsFromServer(reason: String): Boolean {
        val ok = DeviceSettingsSync.pullAndApply(applicationContext)
        if (!ok) return false
        runCatching { AppSettings(applicationContext).syncComponentStates() }
        runCatching { restartPosBackgroundService() }
        runCatching {
            webView.evaluateJavascript(
                """
                (() => {
                  try {
                    const evt = new CustomEvent("flickpay:android-settings-synced", { detail: { reason: "${reason}" } });
                    window.dispatchEvent(evt);
                  } catch (_) {}
                })();
                """.trimIndent(),
                null
            )
        }
        return true
    }

    private fun buildActivationLoginUrl(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accountHost = AccountHostNormalizer.normalize(
            prefs.getString(KEY_ACCOUNT_HOST, null) ?: DEFAULT_ACCOUNT_HOST
        )
        val redirectPath =
            "/odoo/flickpay/device/activate?device_id=${Uri.encode(getOrCreateDeviceId())}&client=android"
        return "https://$accountHost/auth/login?fp_force_store=1&redirect=${Uri.encode(redirectPath)}"
    }

    private fun buildPosUrl(
        accountHostRaw: String,
        databaseRaw: String,
        posIdRaw: String,
        modeRaw: String,
        accessTokenRaw: String
    ): String {
        val accountHost = AccountHostNormalizer.normalize(accountHostRaw, DEFAULT_ACCOUNT_HOST)
        val database = databaseRaw.trim()
        val posId = posIdRaw.trim()
        val mode = if (modeRaw.trim().lowercase() == "self") "self" else "pos"
        val accessToken = accessTokenRaw.trim()

        val base = "https://$accountHost"
        val fpVersionParam = "&fpver=${Uri.encode(BuildConfig.VERSION_NAME)}"
        val fpPlatformParam = "&fpplatform=${Uri.encode("Android")}"
        return if (mode == "self") {
            val tokenParam = if (accessToken.isNotBlank()) "&access_token=${Uri.encode(accessToken)}" else ""
            "$base/pos-self/${Uri.encode(posId)}?db=${Uri.encode(database)}$tokenParam$fpVersionParam$fpPlatformParam"
        } else {
            "$base/pos/ui/${Uri.encode(posId)}/?db=${Uri.encode(database)}$fpVersionParam$fpPlatformParam"
        }
    }

    private data class BootstrapResult(
        val success: Boolean,
        val requiresActivation: Boolean = false,
        val error: String = "",
        val accountHost: String = "",
        val database: String = "",
        val posId: String = "",
        val screen1Mode: String = "pos",
        val accessToken: String = "",
        val deviceAuthAccessToken: String = "",
        val deviceAuthRefreshToken: String = ""
    )

    private data class ActivationApplyResult(
        val success: Boolean,
        val error: String = "",
        val invalidPayload: Boolean = false,
        val redirectUrl: String = ""
    )

    private fun unwrapBootstrapPayload(raw: JSONObject?): JSONObject? {
        if (raw == null) return null
        val result = raw.optJSONObject("result")
        if (result != null) return result
        val data = raw.optJSONObject("data")
        if (data != null) return data
        return raw
    }

    private fun bootstrapSucceeded(payload: JSONObject?, responseOk: Boolean): Boolean {
        if (payload == null) return false
        if (payload.optBoolean("success", false)) return true
        if (payload.optBoolean("ok", false)) return true
        if (payload.optBoolean("authenticated", false)) return true
        if (payload.optString("status", "").trim().lowercase() == "ok") return true
        if (payload.optString("session_id", "").trim().isNotBlank()) return true
        return responseOk && payload.opt("error") != true
    }

    private fun isRevocationMessage(rawMessage: String): Boolean {
        val msg = rawMessage.trim().lowercase()
        if (msg.isBlank()) return false
        return (
            msg.contains("invalid device token") ||
                msg.contains("token is already assigned to another device") ||
                msg.contains("token is already bound to another device") ||
                msg.contains("device binding is disabled") ||
                msg.contains("this device is no longer activated for this till") ||
                msg.contains("no binding found for this device") ||
                msg.contains("no activated user is linked to this device") ||
                msg.contains("no valid activated user found for this device") ||
                msg.contains("pos config not found") ||
                msg.contains("no auth user configured for this pos") ||
                msg.contains("configured auth user has no pos access") ||
                msg.contains("device auth helper is unavailable")
            )
    }

    private fun isTerminalBootstrapFailure(status: Int, message: String): Boolean {
        if (status == 401 || status == 403 || status == 409 || status == 422) return true
        if (status == 404 && isRevocationMessage(message)) return true
        return isRevocationMessage(message)
    }

    private fun isServerWideBootstrapEndpoint(endpoint: String): Boolean {
        val p = endpoint.trim().lowercase()
        return p == "/flickpay/device/bootstrap" || p == "/odoo/flickpay/device/bootstrap"
    }

    private fun persistResponseCookies(connection: HttpURLConnection, baseUrl: String) {
        val cookieManager = CookieManager.getInstance()
        val headers = connection.headerFields ?: return
        for ((name, values) in headers) {
            if (!"set-cookie".equals(name, ignoreCase = true)) continue
            if (values.isNullOrEmpty()) continue
            for (cookie in values) {
                val c = cookie?.trim().orEmpty()
                if (c.isNotBlank()) cookieManager.setCookie(baseUrl, c)
            }
        }
        cookieManager.flush()
    }

    private fun postBootstrapEndpoint(
        accountHost: String,
        endpoint: String,
        database: String,
        posId: String,
        screen1Mode: String,
        accessToken: String,
        deviceToken: String,
        deviceAuthAccessToken: String,
        deviceAuthRefreshToken: String,
        reason: String,
    ): BootstrapResult {
        val baseUrl = "https://$accountHost"
        val url = URL("$baseUrl$endpoint?db=${Uri.encode(database)}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000
            readTimeout = 6000
            instanceFollowRedirects = false
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!isServerWideBootstrapEndpoint(endpoint) && database.isNotBlank()) {
                // Match Electron bootstrap behavior for tenant-scoped endpoints.
                setRequestProperty("X-Odoo-Database", database)
            }
            if (deviceAuthAccessToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $deviceAuthAccessToken")
            }
        }

        val body = JSONObject().apply {
            put("device_id", getOrCreateDeviceId())
            put("db", database)
            put("pos_id", posId)
            put("mode", if (screen1Mode.lowercase() == "self") "self" else "pos")
            put("access_token", accessToken)
            if (deviceToken.isNotBlank()) put("device_token", deviceToken)
            put("reason", reason)
            if (deviceAuthAccessToken.isNotBlank()) put("auth_access_token", deviceAuthAccessToken)
            if (deviceAuthRefreshToken.isNotBlank()) put("auth_refresh_token", deviceAuthRefreshToken)
        }

        return try {
            connection.outputStream.use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val status = runCatching { connection.responseCode }.getOrDefault(0)
            val responseText = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            }.getOrDefault("")

            persistResponseCookies(connection, baseUrl)

            val parsed = runCatching { JSONObject(responseText) }.getOrNull()
            val payload = unwrapBootstrapPayload(parsed)
            val success = bootstrapSucceeded(payload, status in 200..299)

            if (success) {
                val nextMode = if (
                    payload?.optString("mode", "")?.trim()?.lowercase() == "self"
                ) "self" else "pos"
                val payloadAccessToken = payload?.optString("access_token", accessToken)?.trim().orEmpty()
                val payloadDeviceAuthAccessToken = payload?.optString("device_access_token", "")?.trim().orEmpty()
                val payloadAuthAccessToken = payload?.optString("auth_access_token", "")?.trim().orEmpty()
                val payloadDeviceAuthRefreshToken = payload?.optString("device_refresh_token", "")?.trim().orEmpty()
                val payloadAuthRefreshToken = payload?.optString("auth_refresh_token", "")?.trim().orEmpty()
                BootstrapResult(
                    success = true,
                    accountHost = accountHost,
                    database = payload?.optString("db", database)?.trim().orEmpty().ifBlank { database },
                    posId = payload?.optString("pos_id", posId)?.trim().orEmpty().ifBlank { posId },
                    screen1Mode = nextMode,
                    accessToken = payloadAccessToken.ifBlank { accessToken },
                    deviceAuthAccessToken =
                        payloadDeviceAuthAccessToken.ifBlank { payloadAuthAccessToken.ifBlank { deviceAuthAccessToken } },
                    deviceAuthRefreshToken =
                        payloadDeviceAuthRefreshToken.ifBlank { payloadAuthRefreshToken.ifBlank { deviceAuthRefreshToken } },
                )
            } else {
                val err =
                    payload?.optString("error", "")?.trim().orEmpty().ifBlank {
                        payload?.optString("message", "")?.trim().orEmpty().ifBlank {
                            if (status > 0) "Bootstrap rejected ($status)" else "Bootstrap request failed."
                        }
                    }
                BootstrapResult(
                    success = false,
                    requiresActivation = isTerminalBootstrapFailure(status, err),
                    error = err,
                )
            }
        } catch (e: Exception) {
            val errType = runCatching { e::class.java.simpleName }.getOrDefault("Exception")
            val errMsg = (e.message ?: "").trim()
            val full = if (errMsg.isNotBlank()) "$errType: $errMsg" else errType
            BootstrapResult(success = false, error = full.ifBlank { "Bootstrap request failed." })
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun bootstrapDeviceSession(
        accountHostRaw: String,
        databaseRaw: String,
        posIdRaw: String,
        screen1ModeRaw: String,
        accessTokenRaw: String,
        deviceTokenRaw: String,
        deviceAuthAccessTokenRaw: String,
        deviceAuthRefreshTokenRaw: String,
        reason: String
    ): BootstrapResult {
        val accountHost = AccountHostNormalizer.normalize(accountHostRaw, DEFAULT_ACCOUNT_HOST)
        val database = databaseRaw.trim()
        val posId = posIdRaw.trim()
        val screen1Mode = if (screen1ModeRaw.trim().lowercase() == "self") "self" else "pos"
        val accessToken = accessTokenRaw.trim()
        val deviceToken = deviceTokenRaw.trim()
        var deviceAuthAccessToken = deviceAuthAccessTokenRaw.trim()
        var deviceAuthRefreshToken = deviceAuthRefreshTokenRaw.trim()

        if (database.isBlank() || posId.isBlank()) {
            return BootstrapResult(
                success = false,
                requiresActivation = true,
                error = "Device activation is missing."
            )
        }

        var lastError = ""
        for (endpoint in BOOTSTRAP_ENDPOINTS) {
            val result = postBootstrapEndpoint(
                accountHost = accountHost,
                endpoint = endpoint,
                database = database,
                posId = posId,
                screen1Mode = screen1Mode,
                accessToken = accessToken,
                deviceToken = deviceToken,
                deviceAuthAccessToken = deviceAuthAccessToken,
                deviceAuthRefreshToken = deviceAuthRefreshToken,
                reason = reason
            )
            if (result.success) return result
            if (result.requiresActivation) return result
            lastError = result.error.ifBlank { lastError }
            if (result.deviceAuthAccessToken.isNotBlank()) {
                deviceAuthAccessToken = result.deviceAuthAccessToken
            }
            if (result.deviceAuthRefreshToken.isNotBlank()) {
                deviceAuthRefreshToken = result.deviceAuthRefreshToken
            }
        }

        return BootstrapResult(success = false, error = lastError)
    }

    private fun clearActivationPrefs() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_POS_URL)
            .remove(KEY_DATABASE)
            .remove(KEY_POS_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_SCREEN1_MODE)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_DEVICE_AUTH_ACCESS_TOKEN)
            .remove(KEY_DEVICE_AUTH_REFRESH_TOKEN)
            .apply()
        runCatching { updateHiddenSettingsHotspotVisibility(null) }
        runCatching { applyCustomerDisplayRouting("", "", "", "pos") }
    }

    private fun buildCustomerDisplayUrl(
        accountHostRaw: String,
        databaseRaw: String,
        posIdRaw: String,
        modeRaw: String
    ): String {
        val mode = if (modeRaw.trim().lowercase() == "self") "self" else "pos"
        val accountHost = AccountHostNormalizer.normalize(accountHostRaw, DEFAULT_ACCOUNT_HOST)
        val database = databaseRaw.trim()
        val posId = posIdRaw.trim()
        if (mode == "self" || accountHost.isBlank() || posId.isBlank()) return ""

        val base = "https://$accountHost/pos_customer_display/${Uri.encode(posId)}/customer-display"
        val builder = Uri.parse(base).buildUpon()
        if (database.isNotBlank()) {
            builder.appendQueryParameter("db", database)
        }
        builder.appendQueryParameter("fpver", BuildConfig.VERSION_NAME)
        builder.appendQueryParameter("fpplatform", "Android")
        return builder.build().toString()
    }

    private fun applyCustomerDisplayRouting(
        accountHostRaw: String,
        databaseRaw: String,
        posIdRaw: String,
        modeRaw: String
    ) {
        val url = buildCustomerDisplayUrl(accountHostRaw, databaseRaw, posIdRaw, modeRaw)
        runOnUiThread {
            customerDisplayController?.setCustomerUrl(url)
        }
    }

    private fun updateCustomerDisplayFromPrefs() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accountHost = AccountHostNormalizer.normalize(
            prefs.getString(KEY_ACCOUNT_HOST, null) ?: DEFAULT_ACCOUNT_HOST
        )
        val database = prefs.getString(KEY_DATABASE, null)?.trim().orEmpty()
        val posId = prefs.getString(KEY_POS_ID, null)?.trim().orEmpty()
        val mode = prefs.getString(KEY_SCREEN1_MODE, "pos")?.trim().orEmpty()
        applyCustomerDisplayRouting(accountHost, database, posId, mode)
    }

    private fun resolveStartupUrlWithBootstrap(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentPosUrl = prefs.getString(KEY_POS_URL, null)?.trim().orEmpty()

        val accountHost = AccountHostNormalizer.normalize(
            prefs.getString(KEY_ACCOUNT_HOST, null) ?: DEFAULT_ACCOUNT_HOST
        )
        val database = prefs.getString(KEY_DATABASE, null)?.trim().orEmpty()
        val posId = prefs.getString(KEY_POS_ID, null)?.trim().orEmpty()
        val screen1Mode = prefs.getString(KEY_SCREEN1_MODE, "pos")?.trim().orEmpty()
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        val deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null)?.trim().orEmpty()
        val deviceAuthAccessToken = prefs.getString(KEY_DEVICE_AUTH_ACCESS_TOKEN, null)?.trim().orEmpty()
        val deviceAuthRefreshToken = prefs.getString(KEY_DEVICE_AUTH_REFRESH_TOKEN, null)?.trim().orEmpty()

        val fallbackPosUrl = when {
            currentPosUrl.isNotBlank() -> currentPosUrl
            database.isNotBlank() && posId.isNotBlank() -> buildPosUrl(
                accountHostRaw = accountHost,
                databaseRaw = database,
                posIdRaw = posId,
                modeRaw = screen1Mode,
                accessTokenRaw = accessToken
            )

            else -> ""
        }

        // If device starts fully offline, skip bootstrap and launch cached POS directly.
        if (!isNetworkAvailable() && fallbackPosUrl.isNotBlank()) {
            if (currentPosUrl.isBlank()) {
                prefs.edit().putString(KEY_POS_URL, fallbackPosUrl).apply()
            }
            applyCustomerDisplayRouting(
                accountHostRaw = accountHost,
                databaseRaw = database,
                posIdRaw = posId,
                modeRaw = screen1Mode
            )
            return fallbackPosUrl
        }

        if (database.isBlank() || posId.isBlank()) {
            runCatching { applyCustomerDisplayRouting("", "", "", "pos") }
            return buildActivationLoginUrl()
        }

        val boot = bootstrapDeviceSession(
            accountHostRaw = accountHost,
            databaseRaw = database,
            posIdRaw = posId,
            screen1ModeRaw = screen1Mode,
            accessTokenRaw = accessToken,
            deviceTokenRaw = deviceToken,
            deviceAuthAccessTokenRaw = deviceAuthAccessToken,
            deviceAuthRefreshTokenRaw = deviceAuthRefreshToken,
            reason = "boot"
        )

        if (boot.success) {
            val nextMode = if (boot.screen1Mode == "self") "self" else "pos"
            val posUrl = buildPosUrl(
                accountHostRaw = if (boot.accountHost.isNotBlank()) boot.accountHost else accountHost,
                databaseRaw = if (boot.database.isNotBlank()) boot.database else database,
                posIdRaw = if (boot.posId.isNotBlank()) boot.posId else posId,
                modeRaw = nextMode,
                accessTokenRaw = if (boot.accessToken.isNotBlank()) boot.accessToken else accessToken
            )
            prefs.edit()
                .putString(KEY_ACCOUNT_HOST, if (boot.accountHost.isNotBlank()) boot.accountHost else accountHost)
                .putString(KEY_DATABASE, if (boot.database.isNotBlank()) boot.database else database)
                .putString(KEY_POS_ID, if (boot.posId.isNotBlank()) boot.posId else posId)
                .putString(KEY_SCREEN1_MODE, nextMode)
                .putString(KEY_ACCESS_TOKEN, if (boot.accessToken.isNotBlank()) boot.accessToken else accessToken)
                .putString(
                    KEY_DEVICE_AUTH_ACCESS_TOKEN,
                    if (boot.deviceAuthAccessToken.isNotBlank()) boot.deviceAuthAccessToken else deviceAuthAccessToken
                )
                .putString(
                    KEY_DEVICE_AUTH_REFRESH_TOKEN,
                    if (boot.deviceAuthRefreshToken.isNotBlank()) boot.deviceAuthRefreshToken else deviceAuthRefreshToken
                )
                .putString(KEY_POS_URL, posUrl)
                .apply()
            applyCustomerDisplayRouting(
                accountHostRaw = if (boot.accountHost.isNotBlank()) boot.accountHost else accountHost,
                databaseRaw = if (boot.database.isNotBlank()) boot.database else database,
                posIdRaw = if (boot.posId.isNotBlank()) boot.posId else posId,
                modeRaw = nextMode
            )
            runCatching { syncDeviceSettingsFromServer("boot") }
            return posUrl
        }

        if (boot.requiresActivation) {
            clearActivationPrefs()
            return buildActivationLoginUrl()
        }

        // Offline-first fallback: keep using the last activated POS URL when bootstrap
        // cannot be reached, so POS can still load from WebView cache/local storage.
        if (fallbackPosUrl.isNotBlank()) {
            applyCustomerDisplayRouting(
                accountHostRaw = accountHost,
                databaseRaw = database,
                posIdRaw = posId,
                modeRaw = screen1Mode
            )
            return fallbackPosUrl
        }

        runCatching { applyCustomerDisplayRouting("", "", "", "pos") }
        return buildActivationLoginUrl()
    }

    private fun applyActivationFromUri(uri: Uri): ActivationApplyResult {
        val accountHost = AccountHostNormalizer.normalize(
            uri.getQueryParameter("account") ?: DEFAULT_ACCOUNT_HOST
        )
        val database = (uri.getQueryParameter("database") ?: uri.getQueryParameter("db") ?: "").trim()
        val posId = (uri.getQueryParameter("pos_id") ?: uri.getQueryParameter("posId") ?: "").trim()
        val screen1Mode = if (
            (uri.getQueryParameter("screen1_mode") ?: uri.getQueryParameter("mode") ?: "")
                .trim()
                .lowercase() == "self"
        ) "self" else "pos"
        val accessToken = (uri.getQueryParameter("access_token") ?: "").trim()
        val deviceToken = (uri.getQueryParameter("device_token") ?: "").trim()
        val deviceAuthAccessToken = (
            uri.getQueryParameter("device_access_token")
                ?: uri.getQueryParameter("auth_access_token")
                ?: ""
            ).trim()
        val deviceAuthRefreshToken = (
            uri.getQueryParameter("device_refresh_token")
                ?: uri.getQueryParameter("auth_refresh_token")
                ?: ""
            ).trim()

        if (database.isBlank() || posId.isBlank()) {
            runCatching { applyCustomerDisplayRouting("", "", "", "pos") }
            return ActivationApplyResult(
                success = false,
                error = "Missing database or POS id in activation callback.",
                invalidPayload = true,
                redirectUrl = buildActivationLoginUrl()
            )
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCOUNT_HOST, accountHost)
            .putString(KEY_DATABASE, database)
            .putString(KEY_POS_ID, posId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_SCREEN1_MODE, screen1Mode)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_DEVICE_AUTH_ACCESS_TOKEN, deviceAuthAccessToken)
            .putString(KEY_DEVICE_AUTH_REFRESH_TOKEN, deviceAuthRefreshToken)
            .remove(KEY_POS_URL)
            .apply()

        val boot = bootstrapDeviceSession(
            accountHostRaw = accountHost,
            databaseRaw = database,
            posIdRaw = posId,
            screen1ModeRaw = screen1Mode,
            accessTokenRaw = accessToken,
            deviceTokenRaw = deviceToken,
            deviceAuthAccessTokenRaw = deviceAuthAccessToken,
            deviceAuthRefreshTokenRaw = deviceAuthRefreshToken,
            reason = "post_activation_boot"
        )
        if (!boot.success) {
            if (boot.requiresActivation) {
                clearActivationPrefs()
            } else {
                runCatching { applyCustomerDisplayRouting("", "", "", "pos") }
            }
            return ActivationApplyResult(
                success = false,
                error = boot.error.ifBlank { "Activation bootstrap failed." },
                invalidPayload = false,
                redirectUrl = buildActivationLoginUrl()
            )
        }

        val nextMode = if (boot.screen1Mode == "self") "self" else "pos"
        val nextAccount = if (boot.accountHost.isNotBlank()) boot.accountHost else accountHost
        val nextDatabase = if (boot.database.isNotBlank()) boot.database else database
        val nextPosId = if (boot.posId.isNotBlank()) boot.posId else posId
        val nextAccessToken = if (boot.accessToken.isNotBlank()) boot.accessToken else accessToken
        val nextDeviceAuthAccessToken =
            if (boot.deviceAuthAccessToken.isNotBlank()) boot.deviceAuthAccessToken else deviceAuthAccessToken
        val nextDeviceAuthRefreshToken =
            if (boot.deviceAuthRefreshToken.isNotBlank()) boot.deviceAuthRefreshToken else deviceAuthRefreshToken
        val posUrl = buildPosUrl(
            accountHostRaw = nextAccount,
            databaseRaw = nextDatabase,
            posIdRaw = nextPosId,
            modeRaw = nextMode,
            accessTokenRaw = nextAccessToken
        )

        prefs.edit()
            .putString(KEY_ACCOUNT_HOST, nextAccount)
            .putString(KEY_DATABASE, nextDatabase)
            .putString(KEY_POS_ID, nextPosId)
            .putString(KEY_SCREEN1_MODE, nextMode)
            .putString(KEY_ACCESS_TOKEN, nextAccessToken)
            .putString(KEY_DEVICE_AUTH_ACCESS_TOKEN, nextDeviceAuthAccessToken)
            .putString(KEY_DEVICE_AUTH_REFRESH_TOKEN, nextDeviceAuthRefreshToken)
            .putString(KEY_POS_URL, posUrl)
            .apply()
        applyCustomerDisplayRouting(
            accountHostRaw = nextAccount,
            databaseRaw = nextDatabase,
            posIdRaw = nextPosId,
            modeRaw = nextMode
        )
        runCatching { syncDeviceSettingsFromServer("post_activation") }

        return ActivationApplyResult(success = true, redirectUrl = posUrl)
    }

    private fun mutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
    }

    private fun enableImmersiveMode() {
        val kioskEnabled = runCatching { AppSettings(this).isHomeLauncherEnabled() }.getOrDefault(false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = if (kioskEnabled) {
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun installSystemUiGuards() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val kioskEnabled = runCatching { AppSettings(this).isHomeLauncherEnabled() }.getOrDefault(false)
            if (kioskEnabled) {
                window.decorView.post { enableImmersiveMode() }
            }
            insets
        }
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener {
            val kioskEnabled = runCatching { AppSettings(this).isHomeLauncherEnabled() }.getOrDefault(false)
            if (kioskEnabled) {
                window.decorView.post { enableImmersiveMode() }
            }
        }
    }

    private fun applyRotationLockPreference() {
        val appSettings = AppSettings(this)
        val rotationLocked = appSettings.isRotationLocked()
        requestedOrientation = if (rotationLocked) {
            appSettings.getLockedRequestedOrientation()
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun enforceKioskMode(reason: String) {
        val appSettings = AppSettings(this)
        val enabled = appSettings.isHomeLauncherEnabled()
        val state = KioskModeManager.enforce(this, enabled, reason)
        if (enabled && !state.deviceOwner) {
            if (!kioskMissingOwnerLogged) {
                kioskMissingOwnerLogged = true
                AppRuntimeLog.w(
                    applicationContext,
                    tag,
                    "Kiosk toggle enabled but device is not provisioned as Device Owner"
                )
            }
        } else {
            kioskMissingOwnerLogged = false
        }
        syncKioskUiGuard(enabled)
    }

    private fun syncKioskUiGuard(forceEnabled: Boolean? = null) {
        val enabled = forceEnabled ?: runCatching { AppSettings(this).isHomeLauncherEnabled() }.getOrDefault(false)
        if (enabled) {
            startKioskUiGuard()
        } else {
            stopKioskUiGuard()
        }
    }

    private fun startKioskUiGuard() {
        kioskUiGuardHandler.removeCallbacks(kioskUiGuardRunnable)
        kioskUiGuardHandler.post(kioskUiGuardRunnable)
    }

    private fun stopKioskUiGuard() {
        kioskUiGuardHandler.removeCallbacks(kioskUiGuardRunnable)
    }

    private fun waitForLocalPort(port: Int, timeoutMs: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 180)
                }
                true
            }.getOrDefault(false)
            if (ok) return true
            SystemClock.sleep(120)
        }
        return false
    }

    private fun waitForLocalProxyReady(timeoutMs: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (waitForLocalPort(port = 8070, timeoutMs = 250) && probeHelloEndpoint()) {
                return true
            }
            SystemClock.sleep(120)
        }
        return false
    }

    private fun probeHelloEndpoint(): Boolean {
        return runCatching {
            val connection = URL("http://127.0.0.1:8070/hw_proxy/hello").openConnection() as HttpURLConnection
            connection.connectTimeout = 350
            connection.readTimeout = 450
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connect()
            val ok = connection.responseCode in 200..299
            connection.disconnect()
            ok
        }.getOrDefault(false)
    }

    private fun handlePosCommand(uri: Uri, hasUserGesture: Boolean = false): Boolean {
        val host = uri.host?.trim()?.lowercase().orEmpty()
        val path = uri.path?.trim('/')?.lowercase().orEmpty()
        val command = when {
            host.isNotBlank() -> host
            path.isNotBlank() -> path
            else -> ""
        }

        return when (command) {
            "exit" -> {
                // Prevent accidental/scripted exits during startup page scripts.
                if (!hasUserGesture) {
                    AppRuntimeLog.w(applicationContext, tag, "Ignoring pos://exit without user gesture")
                    return true
                }
                exitApp()
                true
            }

            "settings" -> {
                openSettings()
                true
            }

            "activate" -> {
                Thread {
                    val result = applyActivationFromUri(uri)
                    runOnUiThread {
                        if (result.redirectUrl.isNotBlank()) {
                            runCatching { webView.loadUrl(result.redirectUrl) }
                        }
                        if (!result.success) {
                            val msg = if (result.invalidPayload) {
                                "Invalid activation response. Please try activation again."
                            } else {
                                val detail = result.error.trim()
                                if (detail.isBlank()) {
                                    "Activation could not be completed. Please try again."
                                } else {
                                    "Activation could not be completed: $detail"
                                }
                            }
                            runCatching {
                                AlertDialog.Builder(this)
                                    .setTitle("Activation Error")
                                    .setMessage(msg)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }.start()
                true
            }

            "getsupport", "support" -> {
                openSupportPage()
                true
            }

            "modal" -> {
                val target = uri.getQueryParameter("url")
                if (!target.isNullOrBlank()) {
                    runCatching { webView.loadUrl(target) }
                }
                true
            }

            "closemodal" -> {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
                true
            }

            "offlinecorrupt" -> {
                showOfflineFallbackPage()
                true
            }

            "retryoffline" -> {
                attemptedOfflineFallback = false
                attemptedOfflineCacheOnlyReload = false
                applyNetworkAwareCacheMode()
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val fallback = prefs.getString(KEY_LAST_GOOD_POS_URL, null)?.trim().orEmpty()
                    .ifBlank { prefs.getString(KEY_POS_URL, null)?.trim().orEmpty() }
                if (fallback.isNotBlank()) {
                    runCatching { webView.loadUrl(fallback) }
                } else {
                    runCatching { webView.loadUrl(buildActivationLoginUrl()) }
                }
                true
            }

            else -> false
        }
    }

    private inner class PosNativeBridge {
        private fun proxyRpcError(message: String): String {
            return JSONObject()
                .put("ok", false)
                .put("status", 0)
                .put("message", message)
                .toString()
        }

        private fun currentAppOrigin(): String {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val host = AccountHostNormalizer.normalize(
                prefs.getString(KEY_ACCOUNT_HOST, DEFAULT_ACCOUNT_HOST),
                DEFAULT_ACCOUNT_HOST
            )
            return "https://$host"
        }

        private fun shouldLogStatus(path: String, status: Int, attempts: Int): Boolean {
            if (path != "/hw_proxy/status_json") {
                return true
            }
            if (status !in 200..299 || attempts > 1) {
                return true
            }
            val now = SystemClock.elapsedRealtime()
            val previous = lastStatusJsonLogAtMs
            if (previous > 0L && now - previous < 30_000L) {
                return false
            }
            lastStatusJsonLogAtMs = now
            return true
        }

        @JavascriptInterface
        fun proxyRpc(rawUrl: String?, rawPayload: String?): String {
            val urlText = rawUrl?.trim().orEmpty()
            if (urlText.isBlank()) {
                runCatching { AppRuntimeLog.w(applicationContext, tag, "proxyRpc rejected: missing_url") }
                return proxyRpcError("missing_url")
            }
            val parsedUrl = runCatching { URL(urlText) }.getOrNull()
            if (parsedUrl == null) {
                runCatching { AppRuntimeLog.w(applicationContext, tag, "proxyRpc rejected: invalid_url $urlText") }
                return proxyRpcError("invalid_url")
            }
            val host = parsedUrl.host?.trim()?.lowercase().orEmpty()
            if (host != "127.0.0.1" && host != "localhost") {
                runCatching { AppRuntimeLog.w(applicationContext, tag, "proxyRpc rejected: host_not_allowed $host") }
                return proxyRpcError("host_not_allowed")
            }
            val path = parsedUrl.path?.trim().orEmpty()
            if (!path.startsWith("/hw_proxy/")) {
                runCatching { AppRuntimeLog.w(applicationContext, tag, "proxyRpc rejected: path_not_allowed $path") }
                return proxyRpcError("path_not_allowed")
            }

            return runCatching {
                val payload = rawPayload ?: "{}"
                val payloadBytes = payload.toByteArray(Charsets.UTF_8)

                val performRequest = {
                    val connection = (parsedUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1800
                        readTimeout = 5000
                        requestMethod = "POST"
                        instanceFollowRedirects = false
                        useCaches = false
                        doInput = true
                        doOutput = true
                        // NanoHTTPD can occasionally return 400 on reused keep-alive sockets
                        // under rapid consecutive POS RPC calls; force short-lived connections.
                        setRequestProperty("Connection", "close")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Origin", currentAppOrigin())
                        setFixedLengthStreamingMode(payloadBytes.size)
                    }
                    connection.outputStream.use { out ->
                        out.write(payloadBytes)
                        out.flush()
                    }
                    val status = runCatching { connection.responseCode }.getOrDefault(0)
                    val body = runCatching {
                        val stream =
                            if (status in 200..399) connection.inputStream else connection.errorStream
                        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    }.getOrDefault("")
                    runCatching { connection.disconnect() }
                    status to body
                }

                var attempts = 1
                var (status, body) = performRequest()
                if (status == 400) {
                    SystemClock.sleep(90)
                    attempts = 2
                    val retried = performRequest()
                    status = retried.first
                    body = retried.second
                }

                if (status in 200..299) {
                    if (shouldLogStatus(path, status, attempts)) {
                        runCatching {
                            AppRuntimeLog.i(
                                applicationContext,
                                tag,
                                "proxyRpc status=$status path=$path attempts=$attempts"
                            )
                        }
                    }
                } else {
                    val compact = body.replace('\n', ' ').take(220)
                    runCatching {
                        AppRuntimeLog.w(
                            applicationContext,
                            tag,
                            "proxyRpc status=$status path=$path attempts=$attempts body=$compact"
                        )
                    }
                }
                JSONObject()
                    .put("ok", status in 200..299)
                    .put("status", status)
                    .put("body", body)
                    .toString()
            }.getOrElse { error ->
                runCatching { AppRuntimeLog.w(applicationContext, tag, "proxyRpc failed: $urlText ${error.message}") }
                proxyRpcError(error.message ?: "proxy_rpc_failed")
            }
        }

        @JavascriptInterface
        fun postMessage(raw: String?) {
            val text = raw?.trim().orEmpty()
            if (text.isBlank()) return
            val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return
            val scheme = uri.scheme?.trim()?.lowercase().orEmpty()
            if (scheme != "pos" && scheme != "flickpay") return

            val hasUserGesture = isRecentUserInteraction(windowMs = 2500L)
            runOnUiThread {
                runCatching { handlePosCommand(uri, hasUserGesture) }
            }
        }
    }

    private fun isRecentUserInteraction(windowMs: Long = 1500L): Boolean {
        val last = lastWebInteractionAtMs
        if (last <= 0L) return false
        val now = SystemClock.elapsedRealtime()
        return now >= last && (now - last) <= windowMs
    }

    private fun openSupportPage() {
        val supportUrl = "https://flickpay.co.uk/get-support"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl))
        if (runCatching { startActivity(intent); true }.getOrDefault(false)) return
        webView.loadUrl(supportUrl)
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun scheduleStartupUpdateCheck() {
        if (startupUpdateCheckScheduled) return
        startupUpdateCheckScheduled = true
        startupUpdateHandler.removeCallbacks(startupUpdateRunnable)
        startupUpdateHandler.postDelayed(startupUpdateRunnable, 1200L)
    }

    private fun exitApp() {
        isExiting = true
        runCatching { stopService(Intent(this, PosBackgroundService::class.java)) }
        runCatching { finishAndRemoveTask() }
        runCatching { finishAffinity() }
    }

    companion object {
        private val ACTION_USB_PERMISSION: String by lazy {
            "${BuildConfig.APPLICATION_ID}.USB_PERMISSION"
        }
        private const val PREFS = "flickpaypos_app"
        private const val DEFAULT_ACCOUNT_HOST = "app.flickpay.co.uk"
        private const val KEY_ACCOUNT_HOST = "account_host"
        private const val KEY_DATABASE = "database"
        private const val KEY_POS_ID = "pos_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_SCREEN1_MODE = "screen1_mode"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_DEVICE_AUTH_ACCESS_TOKEN = "device_auth_access_token"
        private const val KEY_DEVICE_AUTH_REFRESH_TOKEN = "device_auth_refresh_token"
        private const val KEY_POS_URL = "pos_url"
        private const val KEY_LAST_GOOD_POS_URL = "last_good_pos_url"
        private const val HIDDEN_SETTINGS_HOLD_MS = 10_000L
        private const val HIDDEN_SETTINGS_HOTSPOT_DP = 96f
        private val BOOTSTRAP_ENDPOINTS = listOf(
            "/odoo/flickpay_pos/device/bootstrap",
            "/flickpay_pos/device/bootstrap",
            "/odoo/flickpay/device/bootstrap",
            "/flickpay/device/bootstrap",
        )
    }
}
