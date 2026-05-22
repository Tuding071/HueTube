// ═══════════════════════════════════════════════════════════════════
// HueTube - V1.4 (Fullscreen Portrait+Landscape + Orientation Restore)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme Specification ===
// ═══════════════════════════════════════════════════════════════════
//
// THEME: Dark YouTube — Near-Black Background
//
// Fullscreen: website requests orientation → SENSOR allows both portrait
//             and landscape → orientation saved before entry, restored on exit.
//             No Activity recreation (configChanges handles it).
//
// Back navigation:
//   FULLSCREEN → exit fullscreen via callback + restore orientation
//   canGoBack  → goBack()
//   on homepage → exit app
//   elsewhere  → go to homepage
//
// ═══════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package, Imports, MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.huetube.app

import android.content.pm.ActivityInfo
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
//
// FIXES in V1.4:
//   1. savedOrientation — captured before entry, restored on exit
//      (fixes: back/swipe-down not restoring original orientation)
//   2. SCREEN_ORIENTATION_SENSOR — allows both portrait and landscape
//      fullscreen freely based on device hold
//      (fixes: portrait fullscreen broken by forced landscape)
//   3. isActive guard — prevents double-exit when back button and
//      onHideCustomView both fire in the same event chain
//
// ═══════════════════════════════════════════════════════════════════

class FullscreenManager(val activity: android.app.Activity) {

    private var customView: View? = null
    private var webViewContainer: FrameLayout? = null
    private var webView: WebView? = null

    // Saved before enterFullscreen so exitFullscreen restores it exactly
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    // Guard: prevents double-call from back handler + onHideCustomView
    private var isActive: Boolean = false

    fun enterFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        container: FrameLayout,
        wv: WebView
    ) {
        if (isActive) return
        isActive = true

        // Save references
        customView = view
        webViewContainer = container
        webView = wv

        // ── Save current orientation BEFORE changing it ──────────
        // This is what exitFullscreen restores, so back button and
        // onHideCustomView both snap back to exactly where we were.
        savedOrientation = activity.requestedOrientation

        // ── Free sensor rotation — portrait AND landscape both work ──
        // SENSOR_LANDSCAPE (old) forced landscape, breaking portrait
        // fullscreen (e.g. Shorts). SENSOR lets the device decide based
        // on how the user is holding it — YouTube content fills correctly.
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

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
        decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    fun exitFullscreen(callback: WebChromeClient.CustomViewCallback?) {
        // Guard: if back button and onHideCustomView both fire,
        // only the first call executes — second is a no-op.
        if (!isActive) return
        isActive = false

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
        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // ── Restore saved orientation ────────────────────────────
        // Previously this was UNSPECIFIED unconditionally, so if the
        // user entered fullscreen while locked to portrait, it would
        // unlock after exit. Now it restores exactly what it was.
        activity.requestedOrientation = savedOrientation

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
            // Exit fullscreen properly via callback — orientation restores
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
