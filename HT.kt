// ═══════════════════════════════════════════════════════════════════
// HueTube - V1.2 (Fullscreen Fix + Back Fix)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme Specification ===
// ═══════════════════════════════════════════════════════════════════
//
// THEME: Dark YouTube — Near-Black Background
//
// Fullscreen: custom View goes to decorView, WebView detached
//             back button calls onCustomViewHidden() for proper exit
//
// Back navigation:
//   FULLSCREEN → exit fullscreen via callback
//   canGoBack  → goBack()
//   on homepage → exit app
//   elsewhere  → go to homepage
//
// ═══════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package, Imports, MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.huetube.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HueTubeApp() }
    }
}

// END OF PART 1/10


// ═══════════════════════════════════════════════════════════════════
// === PART 2/10 — Constants ===
// ═══════════════════════════════════════════════════════════════════

private val BG = Color(0xFF0A0A0A)
private const val HOMEPAGE_URL = "https://m.youtube.com"

// END OF PART 2/10


// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Fullscreen Manager ===
// ═══════════════════════════════════════════════════════════════════

class FullscreenManager(val activity: android.app.Activity) {
    
    private var customView: View? = null
    private var webViewContainer: FrameLayout? = null
    private var webView: WebView? = null

    fun enterFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        container: FrameLayout,
        wv: WebView
    ) {
        // Save references
        customView = view
        webViewContainer = container
        webView = wv

        // Remove WebView from container
        container.removeView(wv)

        // Add custom view to decor view (fullscreen overlay)
        val decorView = activity.window.decorView as android.widget.FrameLayout
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        decorView.addView(view)

        // Hide system bars
        activity.window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    fun exitFullscreen(callback: WebChromeClient.CustomViewCallback?) {
        val decorView = activity.window.decorView as android.widget.FrameLayout

        // Remove custom view from decor view
        customView?.let { decorView.removeView(it) }
        customView = null

        // Restore WebView to its container
        webView?.let { wv ->
            webViewContainer?.let { container ->
                if (wv.parent == null) {
                    container.addView(wv)
                }
            }
        }

        // Restore system bars
        activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE

        // Notify WebView that fullscreen is done
        callback?.onCustomViewHidden()

        // Clear references
        webViewContainer = null
        webView = null
    }
}

// END OF PART 3/10


// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — Main App Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity ?: return
    val fullscreenManager = remember { FullscreenManager(activity) }

    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // ── Container — permanent, never recreated ──────────────────
    val container = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
        }
    }

    // ── Single WebView — created once ───────────────────────────
    val webView = remember {
        WebView(context).apply {
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
                    fullscreenCallback = callback
                    isFullscreen = true
                    fullscreenManager.enterFullscreen(view, callback, container, this@apply)
                }

                override fun onHideCustomView() {
                    fullscreenManager.exitFullscreen(fullscreenCallback)
                    fullscreenCallback = null
                    isFullscreen = false
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    view.evaluateJavascript("""
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
                    """.trimIndent(), null)
                }
            }

            loadUrl(HOMEPAGE_URL)
        }
    }

    // ── Add WebView to container on init ────────────────────────
    LaunchedEffect(Unit) {
        container.addView(webView)
    }

    // ── Back Handler ────────────────────────────────────────────
    BackHandler {
        if (isFullscreen) {
            // Exit fullscreen properly via callback
            fullscreenManager.exitFullscreen(fullscreenCallback)
            fullscreenCallback = null
            isFullscreen = false
            return@BackHandler
        }

        val currentUrl = webView.url ?: ""

        when {
            webView.canGoBack() -> webView.goBack()
            currentUrl.startsWith(HOMEPAGE_URL) || currentUrl == "about:blank" -> activity.finish()
            else -> webView.loadUrl(HOMEPAGE_URL)
        }
    }

    // ── Layout ───────────────────────────────────────────────────
    AndroidView(
        factory = { container },
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding()
    )
}

// END OF PART 4/10


// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 to 10/10 — Reserved ===
// ═══════════════════════════════════════════════════════════════════
