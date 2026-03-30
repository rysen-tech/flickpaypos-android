package uk.flickpay.flickpaypos

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.net.http.SslError
import android.os.Bundle
import android.view.Display
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class CustomerDisplayPresentation(
    context: Context,
    display: Display,
) : Presentation(context, display) {

    private lateinit var webView: WebView
    private var currentUrl: String = ""

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
        }

        webView.webViewClient = object : WebViewClient() {
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
                    handler.cancel()
                }
            }
        }
    }

    fun showUrl(url: String) {
        val next = url.trim()
        if (next.isBlank()) return
        if (currentUrl == next) {
            runCatching { webView.reload() }
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
