// ═══════════════════════════════════════════════════════════════════
// HueTube - V1.1 (Back Fix + Fullscreen Video)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme Specification ===
// ═══════════════════════════════════════════════════════════════════
//
// THEME: Dark YouTube — Near-Black Background
//
// COLOURS:
//   Background:    #0A0A0A  (near black)
//   Surface:       #121212  (slightly elevated)
//   Accent:        #FF0000  (YouTube red)
//   Text Primary:  #FFFFFF  (white)
//   Text Muted:    #888888  (grey)
//
// SHAPES:
//   Everything:    RectangleShape  (0dp corner radius)
//
// BACK NAVIGATION (no refresh):
//   canGoBack → goBack()
//   at homepage → close app
//   elsewhere → go to homepage
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
    fun enterFullscreen() {
        activity.window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    fun exitFullscreen() {
        activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
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

    // ── Single WebView — created once, never recreated ──────────
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
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
                    isFullscreen = true
                    fullscreenManager.enterFullscreen()
                }
                override fun onHideCustomView() {
                    isFullscreen = false
                    fullscreenManager.exitFullscreen()
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

    // ── Back Handler (no refresh) ───────────────────────────────
    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
            fullscreenManager.exitFullscreen()
            return@BackHandler
        }

        when {
            webView.canGoBack() -> webView.goBack()
            webView.url == HOMEPAGE_URL || webView.url == "about:blank" -> activity.finish()
            else -> webView.loadUrl(HOMEPAGE_URL)
        }
    }

    // ── Layout ───────────────────────────────────────────────────
    AndroidView(
        factory = { webView },
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
