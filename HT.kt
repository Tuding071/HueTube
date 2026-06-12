// ═══════════════════════════════════════════════════════════════════
// HueTube - V1.6 (Hardcoded Ad Blocker + Bottom Sheet)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme + Maintenance Guide ===
// ═══════════════════════════════════════════════════════════════════
//
// THEME: Dark YouTube — Near-Black Background (#0A0A0A)
//
// ── AD BLOCKER MAINTENANCE ────────────────────────────────────────
//
// When ads break, go to PART 5 and update AD_BLOCK_JS.
// That's the only thing that ever needs changing for ad blocking.
//
// To update it:
//   1. Open this file with any AI (Claude, ChatGPT, etc.)
//   2. Say: "YouTube ads are showing again, update AD_BLOCK_JS
//            in PART 5 with the latest working ad block script"
//   3. Replace PART 5 with what the AI gives you
//   4. Rebuild via GitHub Actions
//
// What AD_BLOCK_JS does:
//   - Intercepts fetch() calls to YouTube's internal player API
//   - Strips adPlacements / playerAds / adSlots keys from JSON
//   - Injects CSS to hide any leftover ad UI elements
//   - Uses MutationObserver so it survives YouTube's SPA navigation
//
// ── FULLSCREEN ────────────────────────────────────────────────────
//   SENSOR orientation — portrait and landscape both work freely
//   Orientation saved before entry, restored on exit
//
// ── BACK NAVIGATION ──────────────────────────────────────────────
//   Fullscreen → exit fullscreen
//   Sheet open → close sheet
//   canGoBack  → goBack
//   On homepage → exit app
//   Elsewhere  → go to homepage
//
// ═══════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package + Imports + MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.huetube.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val BG       = Color(0xFF0A0A0A)
private val SHEET_BG = Color(0xFF141414)
private val ACCENT   = Color(0xFFFF0000)
private val TEXT_PRI = Color(0xFFEEEEEE)
private val TEXT_SEC = Color(0xFF888888)
private val DIVIDER  = Color(0xFF222222)

private const val HOMEPAGE_URL = "https://m.youtube.com"

// END OF PART 2/10


// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Fullscreen Manager ===
// ═══════════════════════════════════════════════════════════════════

class FullscreenManager(val activity: android.app.Activity) {

    private var customView: View? = null
    private var webViewContainer: FrameLayout? = null
    private var webView: WebView? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var isActive: Boolean = false

    fun enterFullscreen(
        view: View,
        callback: WebChromeClient.CustomViewCallback,
        container: FrameLayout,
        wv: WebView
    ) {
        if (isActive) return
        isActive = true

        customView       = view
        webViewContainer = container
        webView          = wv
        savedOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        container.removeView(wv)

        val decorView = activity.window.decorView as android.widget.FrameLayout
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        decorView.addView(view)
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
        if (!isActive) return
        isActive = false

        val decorView = activity.window.decorView as android.widget.FrameLayout
        customView?.let { decorView.removeView(it) }
        customView = null

        webView?.let { wv ->
            webViewContainer?.let { c ->
                if (wv.parent == null) c.addView(wv)
            }
        }

        decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        activity.requestedOrientation = savedOrientation
        callback?.onCustomViewHidden()

        webViewContainer = null
        webView          = null
    }
}

// END OF PART 3/10


// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — Dark Mode JS ===
// ═══════════════════════════════════════════════════════════════════
//
// Always injected regardless of ad block toggle.
// Forces YouTube dark mode and sets the dark preference cookie.
//
// ═══════════════════════════════════════════════════════════════════

private val DARK_MODE_JS = """
(function(){
    document.documentElement.style.setProperty('color-scheme','dark');
    document.documentElement.style.setProperty('background-color','#0A0A0A');
    var origMM = window.matchMedia;
    window.matchMedia = function(q) {
        var r = origMM(q);
        if (q.includes('prefers-color-scheme')) {
            return {
                matches: true, media: q, onchange: null,
                addListener: function(cb){ cb(this); },
                removeListener: function(){},
                addEventListener: function(t,cb){ if(t==='change') cb(this); },
                removeEventListener: function(){},
                dispatchEvent: function(){ return true; }
            };
        }
        return r;
    };
    document.cookie = 'PREF=f6=4;path=/;domain=.youtube.com';
})();
""".trimIndent()

// END OF PART 4/10


// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 — Ad Block JS ===
// ═══════════════════════════════════════════════════════════════════
//
// !! THIS IS THE ONLY PART THAT NEEDS UPDATING WHEN ADS BREAK !!
//
// To update:
//   1. Open this file with any AI assistant
//   2. Say: "YouTube ads are showing again, update AD_BLOCK_JS
//            in PART 5 with the latest working ad block script
//            for WebView injection"
//   3. Replace this entire PART 5 with what the AI provides
//   4. Push to GitHub and rebuild via Actions
//
// Last updated: 2025-06
//
// What this does:
//   - Patches window.fetch to intercept YouTube's player API calls
//     (/youtubei/v1/player) and strips ad-related keys from the
//     JSON response before YouTube's player reads them
//   - Injects CSS to hide any residual ad UI elements
//   - MutationObserver re-injects CSS on SPA navigation
//
// ═══════════════════════════════════════════════════════════════════

private val AD_BLOCK_JS = """
(function(){
    'use strict';

    // ── 1. Patch fetch — strip ad keys from player API response ──
    var _fetch = window.fetch;
    window.fetch = function(input, init) {
        return _fetch.apply(this, arguments).then(function(resp) {
            var url = typeof input === 'string' ? input : (input && input.url) || '';
            if (!url.includes('/youtubei/')) return resp;
            return resp.clone().text().then(function(text) {
                try {
                    var obj = JSON.parse(text);
                    delete obj.adPlacements;
                    delete obj.playerAds;
                    delete obj.adSlots;
                    delete obj.auxiliaryUi;
                    if (obj.playerConfig && obj.playerConfig.adConfig) {
                        delete obj.playerConfig.adConfig;
                    }
                    return new Response(JSON.stringify(obj), {
                        status: resp.status,
                        statusText: resp.statusText,
                        headers: resp.headers
                    });
                } catch(e) {
                    return resp;
                }
            });
        });
    };

    // ── 2. CSS — hide leftover ad UI elements ────────────────────
    var CSS = [
        '#player-ads',
        '.ad-showing',
        '.ad-interrupting',
        'ytd-promoted-sparkles-web-renderer',
        'ytd-action-companion-ad-renderer',
        'ytd-display-ad-renderer',
        'ytd-video-masthead-ad-v3-renderer',
        'ytd-overlay-interstitial-promo-renderer',
        'ytd-banner-promo-renderer',
        '.ytp-ad-overlay-container',
        '.ytp-ad-text-overlay',
        '.ytp-ad-skip-button-container',
        '.ytp-ad-skip-button-modern',
        '.ytp-ad-module'
    ].join(',');

    function injectCss() {
        if (document.getElementById('__ht_adcss__')) return;
        var s = document.createElement('style');
        s.id = '__ht_adcss__';
        s.textContent = CSS + '{display:none!important;visibility:hidden!important;}';
        (document.head || document.documentElement).appendChild(s);
    }

    injectCss();
    new MutationObserver(injectCss).observe(
        document.documentElement,
        { childList: true, subtree: true }
    );

})();
""".trimIndent()

// END OF PART 5/10


// ═══════════════════════════════════════════════════════════════════
// === PART 6/10 — Reserved ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 6/10


// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — Reserved ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 7/10


// ═══════════════════════════════════════════════════════════════════
// === PART 8/10 — Reserved ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 8/10


// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — Bottom Sheet UI ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeBottomSheet(
    adBlockEnabled: Boolean,
    onToggleAdBlock: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    )

    // Sheet
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(SHEET_BG)
                .clickable(enabled = false) {}
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, drag ->
                        if (drag > 40f) onDismiss()
                    }
                }
                .padding(bottom = 32.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF444444))
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Ad Block Toggle ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Ad Blocking",
                        color = TEXT_PRI,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (adBlockEnabled) "Active — injected on every page"
                        else "Disabled",
                        color = TEXT_SEC,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = adBlockEnabled,
                    onCheckedChange = onToggleAdBlock,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor   = Color.White,
                        checkedTrackColor   = ACCENT,
                        uncheckedThumbColor = Color(0xFF888888),
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = DIVIDER,
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // ── Future features below this line ───────────────────
            Spacer(Modifier.height(24.dp))
        }
    }
}

// END OF PART 9/10


// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — Main App Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeApp() {
    val context  = LocalContext.current
    val activity = context as? android.app.Activity ?: return

    val fullscreenManager = remember { FullscreenManager(activity) }

    var isFullscreen       by remember { mutableStateOf(false) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var showSheet          by remember { mutableStateOf(false) }
    var adBlockEnabled     by remember { mutableStateOf(true) }

    // ── Permanent container ──────────────────────────────────────
    val container = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
        }
    }

    // ── Single WebView ───────────────────────────────────────────
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            with(settings) {
                javaScriptEnabled                = true
                domStorageEnabled                = true
                loadWithOverviewMode             = true
                useWideViewPort                  = true
                builtInZoomControls              = true
                displayZoomControls              = false
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
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    view.evaluateJavascript(DARK_MODE_JS, null)
                    if (adBlockEnabled) {
                        view.evaluateJavascript(AD_BLOCK_JS, null)
                    }
                }
            }

            loadUrl(HOMEPAGE_URL)
        }
    }

    LaunchedEffect(Unit) { container.addView(webView) }

    // ── Back Handler ─────────────────────────────────────────────
    BackHandler {
        if (showSheet) { showSheet = false; return@BackHandler }
        if (isFullscreen) {
            fullscreenManager.exitFullscreen(fullscreenCallback)
            fullscreenCallback = null
            isFullscreen = false
            return@BackHandler
        }
        val url = webView.url ?: ""
        when {
            webView.canGoBack()                                   -> webView.goBack()
            url.startsWith(HOMEPAGE_URL) || url == "about:blank" -> activity.finish()
            else                                                  -> webView.loadUrl(HOMEPAGE_URL)
        }
    }

    // ── Layout ───────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding()
    ) {
        AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())

        // Floating menu button — bottom-left, hidden in fullscreen
        if (!isFullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC141414))
                    .clickable { showSheet = true },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color(0xCCEEEEEE))
                        )
                    }
                }
            }
        }

        // Bottom sheet
        if (showSheet) {
            HueTubeBottomSheet(
                adBlockEnabled  = adBlockEnabled,
                onToggleAdBlock = { adBlockEnabled = it },
                onDismiss       = { showSheet = false }
            )
        }
    }
}

// END OF PART 10/10
