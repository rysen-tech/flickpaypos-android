package uk.flickpay.flickpaypos

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.net.http.SslError
import android.os.Bundle
import android.view.Display
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class CustomerDisplayPresentation(
    context: Context,
    display: Display,
) : Presentation(context, display) {
    companion object {
        private const val TAG = "CustomerDisplayWeb"
    }

    private lateinit var webView: WebView
    private var currentUrl: String = ""

    private fun currentZoomPercent(): Int {
        return AppSettings(context.applicationContext).getCustomerDisplayZoomPercent()
    }

    private fun applyConfiguredZoom() {
        val zoom = currentZoomPercent().coerceIn(50, 200)
        webView.settings.textZoom = zoom
        val zoomCss = "${zoom}%"
        runCatching {
            webView.evaluateJavascript(
                """
                (() => {
                  const z = "${zoomCss}";
                  if (document && document.documentElement) {
                    document.documentElement.style.zoom = z;
                    document.documentElement.style.transformOrigin = "top left";
                  }
                  if (document && document.body) {
                    document.body.style.zoom = z;
                    document.body.style.transformOrigin = "top left";
                  }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_customer_display)
        webView = findViewById(R.id.customerDisplayWebView)
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            val currentUa = userAgentString.orEmpty()
            if (!currentUa.contains("FlickpayPOSAndroid", ignoreCase = true)) {
                userAgentString = "$currentUa FlickpayPOSAndroid/${BuildConfig.VERSION_NAME}".trim()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val source = consoleMessage.sourceId().orEmpty()
                val line = consoleMessage.lineNumber()
                val message = consoleMessage.message().trim()
                val formatted = if (source.isNotBlank()) "$source:$line $message" else message
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR ->
                        AppRuntimeLog.e(context.applicationContext, TAG, formatted)
                    ConsoleMessage.MessageLevel.WARNING ->
                        AppRuntimeLog.w(context.applicationContext, TAG, formatted)
                    else -> Unit
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: SslError
            ) {
                val host = runCatching {
                    android.net.Uri.parse(error.url).host.orEmpty().lowercase()
                }.getOrDefault("")
                if (host == "127.0.0.1" || host == "localhost") {
                    handler.proceed()
                } else {
                    AppRuntimeLog.w(
                        context.applicationContext,
                        TAG,
                        "SSL blocked host=$host url=${error.url}"
                    )
                    handler.cancel()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val code = error?.errorCode ?: -1
                    val description = error?.description?.toString().orEmpty()
                    val url = request.url?.toString().orEmpty()
                    AppRuntimeLog.w(
                        context.applicationContext,
                        TAG,
                        "Main-frame error code=$code url=$url desc=$description"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true) {
                    val status = errorResponse?.statusCode ?: -1
                    val reason = errorResponse?.reasonPhrase.orEmpty()
                    val url = request.url?.toString().orEmpty()
                    AppRuntimeLog.w(
                        context.applicationContext,
                        TAG,
                        "Main-frame HTTP error status=$status reason=$reason url=$url"
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                applyConfiguredZoom()
            }
        }
    }

    fun showUrl(url: String) {
        val next = url.trim()
        if (next.isBlank()) return
        applyConfiguredZoom()
        if (currentUrl == next) {
            runCatching { webView.loadUrl(next) }
            return
        }
        currentUrl = next
        runCatching { webView.loadUrl(next) }
    }

    fun shutdown() {
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.destroy() }
    }
}
