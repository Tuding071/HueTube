package com.huetube.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class WebViewManager(context: android.content.Context) {

    // ── Two permanent WebViews ──────────────────────────────────
    val homepageWebView: WebView
    var contentWebView: WebView? = null
        private set

    // ── Single container that Compose embeds ONCE ───────────────
    val container: FrameLayout = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
    }

    // ── Callbacks ───────────────────────────────────────────────
    var onNewContentRequest: ((String) -> Unit)? = null
    var onCustomViewShow: (() -> Unit)? = null
    var onCustomViewHide: (() -> Unit)? = null

    private val darkModeScript = """
        (function() {
            document.documentElement.style.setProperty('color-scheme', 'dark');
            document.documentElement.style.setProperty('background-color', '#0A0A0A');
            var originalMatchMedia = window.matchMedia;
            window.matchMedia = function(query) {
                var result = originalMatchMedia(query);
                if (query.includes('prefers-color-scheme')) {
                    return {
                        matches: true, media: query, onchange: null,
                        addListener: function(cb) { cb(this); },
                        removeListener: function() {},
                        addEventListener: function(type, cb) { if (type === 'change') cb(this); },
                        removeEventListener: function() {},
                        dispatchEvent: function() { return true; }
                    };
                }
                return result;
            };
            document.cookie = 'PREF=f6=4;path=/;domain=.youtube.com';
        })();
    """.trimIndent()

    init {
        // ── Homepage WebView ────────────────────────────────────
        homepageWebView = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {}
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (url != HOMEPAGE_URL && url != "about:blank") {
                        view.loadUrl(HOMEPAGE_URL)
                    }
                    view.evaluateJavascript(darkModeScript, null)
                }
            }
            loadUrl(HOMEPAGE_URL)
        }

        // Start with homepage visible
        container.addView(homepageWebView)
    }

    // ── Show homepage (hide content) ────────────────────────────
    fun showHomepage() {
        contentWebView?.let { container.removeView(it) }
        if (container.indexOfChild(homepageWebView) == -1) {
            container.addView(homepageWebView, 0)
        }
    }

    // ── Create and show content ─────────────────────────────────
    fun showContent(url: String) {
        // Destroy old content
        contentWebView?.let {
            container.removeView(it)
            it.destroy()
        }

        val wv = WebView(container.context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                mediaPlaybackRequiresUserGesture = false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    onCustomViewShow?.invoke()
                }
                override fun onHideCustomView() {
                    onCustomViewHide?.invoke()
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript(darkModeScript, null)
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val clickedUrl = request.url.toString()
                    if (request.isForMainFrame && clickedUrl != view.url && clickedUrl != HOMEPAGE_URL) {
                        onNewContentRequest?.invoke(clickedUrl)
                        return true
                    }
                    return false
                }
            }

            loadUrl(url)
        }

        contentWebView = wv
        container.removeView(homepageWebView)
        container.addView(wv)
    }

    // ── Destroy content, restore homepage ───────────────────────
    fun destroyContent() {
        contentWebView?.let {
            container.removeView(it)
            it.destroy()
        }
        contentWebView = null
        if (container.indexOfChild(homepageWebView) == -1) {
            container.addView(homepageWebView, 0)
        }
    }

    fun canContentGoBack(): Boolean = contentWebView?.canGoBack() == true

    fun contentGoBack() {
        contentWebView?.goBack()
    }

    companion object {
        const val HOMEPAGE_URL = "https://m.youtube.com"
    }
}
