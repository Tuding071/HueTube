// ═══════════════════════════════════════════════════════════════════
// HueTube - V3.0 (YouTube WebView — Dual Layer, No Blink)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme Specification ===
// ═══════════════════════════════════════════════════════════════════
//
// ARCHITECTURE:
//   Two WebViews stacked permanently (never re-parented):
//     - Homepage layer (bottom, zIndex 0)
//     - Content layer (top, zIndex 1)
//
//   Visibility toggled via alpha + touch absorption:
//     - Homepage active: content alpha=0, content absorbs touch=none
//     - Content active:  homepage alpha=0.3 (dimmed behind)
//     - PiP mode:        content shrunk to corner overlay,
//                        homepage fully visible and touchable,
//                        PiP touchable, tapping bg switches to content
//
// BACK NAVIGATION:
//   Content canGoBack → goBack()
//   Content at start   → minimize to PiP
//   PiP visible        → close PiP (destroy content)
//   Homepage alone     → finish()
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

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
private val SURFACE = Color(0xFF121212)
private const val HOMEPAGE_URL = "https://m.youtube.com"

object AppContextHolder {
    lateinit var context: android.content.Context
}

// END OF PART 2/10


// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Content State Enum & TabState ===
// ═══════════════════════════════════════════════════════════════════

enum class ContentState {
    HIDDEN,   // Content not created or destroyed
    ACTIVE,   // Full screen, touchable
    PIP       // Minimized overlay on homepage
}

class TabState {
    var webView by mutableStateOf<WebView?>(null)
    var url by mutableStateOf(HOMEPAGE_URL)
    var contentState by mutableStateOf(ContentState.HIDDEN)
    var customView by mutableStateOf<View?>(null)
}

// END OF PART 3/10


// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — WebView Factory ===
// ═══════════════════════════════════════════════════════════════════

fun createContentWebView(
    url: String,
    tabState: TabState,
    onNewContentRequest: (String) -> Unit,
    onCustomViewShow: () -> Unit,
    onCustomViewHide: () -> Unit
): WebView {
    return WebView(AppContextHolder.context).apply {
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
                tabState.customView = view
                onCustomViewShow()
            }

            override fun onHideCustomView() {
                tabState.customView = null
                onCustomViewHide()
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                tabState.url = url
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

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                val clickedUrl = request.url.toString()
                if (request.isForMainFrame && clickedUrl != view.url && clickedUrl != HOMEPAGE_URL) {
                    onNewContentRequest(clickedUrl)
                    return true
                }
                return false
            }
        }

        loadUrl(url)
    }
}

fun createHomepageWebView(): WebView {
    return WebView(AppContextHolder.context).apply {
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
        webChromeClient = object : WebChromeClient() {}
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (url != HOMEPAGE_URL && url != "about:blank") {
                    view.loadUrl(HOMEPAGE_URL)
                }
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

// END OF PART 4/10


// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 — Back Handler Logic ===
// ═══════════════════════════════════════════════════════════════════

sealed class BackAction {
    object GoBackInHistory : BackAction()
    object MinimizeToPip : BackAction()
    object ClosePip : BackAction()
    object CloseApp : BackAction()
}

fun determineBackAction(contentState: ContentState, contentTab: TabState?): BackAction {
    return when {
        contentState == ContentState.ACTIVE && contentTab?.webView?.canGoBack() == true ->
            BackAction.GoBackInHistory
        contentState == ContentState.ACTIVE ->
            BackAction.MinimizeToPip
        contentState == ContentState.PIP ->
            BackAction.ClosePip
        else ->
            BackAction.CloseApp
    }
}

// END OF PART 5/10


// ═══════════════════════════════════════════════════════════════════
// === PART 6/10 — Fullscreen Manager ===
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

// END OF PART 6/10


// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — Main App Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity ?: return
    AppContextHolder.context = context.applicationContext

    val fullscreenManager = remember { FullscreenManager(activity) }
    val density = LocalDensity.current

    // ── Two permanent WebViews — NEVER recreated, NEVER re-parented ─
    val homepageWebView = remember { createHomepageWebView() }
    val contentTab = remember { TabState() }

    // ── UI State ─────────────────────────────────────────────────
    var isFullscreen by remember { mutableStateOf(false) }

    // PiP drag offset
    var pipOffsetX by remember { mutableStateOf(0f) }
    var pipOffsetY by remember { mutableStateOf(0f) }
    val pipWidthDp = 240.dp
    val pipHeightDp = 135.dp
    val pipWidthPx = with(density) { pipWidthDp.toPx() }
    val pipHeightPx = with(density) { pipHeightDp.toPx() }

    // ── Create / replace content tab ─────────────────────────────
    fun createContentTab(url: String) {
        contentTab.webView?.destroy()
        contentTab.webView = createContentWebView(
            url = url,
            tabState = contentTab,
            onNewContentRequest = { newUrl -> createContentTab(newUrl) },
            onCustomViewShow = {
                isFullscreen = true
                fullscreenManager.enterFullscreen()
            },
            onCustomViewHide = {
                isFullscreen = false
                fullscreenManager.exitFullscreen()
            }
        )
        contentTab.url = url
        contentTab.contentState = ContentState.ACTIVE
    }

    // ── Back Handler ────────────────────────────────────────────
    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
            fullscreenManager.exitFullscreen()
            contentTab.customView = null
            return@BackHandler
        }

        when (determineBackAction(contentTab.contentState, contentTab)) {
            BackAction.GoBackInHistory -> {
                contentTab.webView?.goBack()
            }
            BackAction.MinimizeToPip -> {
                contentTab.contentState = ContentState.PIP
            }
            BackAction.ClosePip -> {
                contentTab.webView?.destroy()
                contentTab.webView = null
                contentTab.contentState = ContentState.HIDDEN
                contentTab.url = HOMEPAGE_URL
                pipOffsetX = 0f
                pipOffsetY = 0f
            }
            BackAction.CloseApp -> {
                activity.finish()
            }
        }
    }

    // ── Determine what's touchable ───────────────────────────────
    val homepageTouchable = contentTab.contentState != ContentState.ACTIVE
    val contentTouchable = contentTab.contentState == ContentState.ACTIVE
    val pipTouchable = contentTab.contentState == ContentState.PIP

    // ── LAYOUT: Two stacked layers ───────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ══════════════════════════════════════════════════════════
        // LAYER 0 — Homepage (always visible, touchable when no content active)
        // ══════════════════════════════════════════════════════════
        AndroidView(
            factory = { homepageWebView },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
                .then(
                    if (!homepageTouchable) {
                        // Absorb all touches — they pass through to nothing
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { /* absorb */ }
                    } else {
                        Modifier
                    }
                )
        )

        // ══════════════════════════════════════════════════════════
        // LAYER 1 — Content (ACTIVE: full screen | PIP: corner overlay | HIDDEN: invisible)
        // ══════════════════════════════════════════════════════════
        if (contentTab.webView != null && contentTab.contentState != ContentState.HIDDEN) {
            val isPip = contentTab.contentState == ContentState.PIP

            Box(
                modifier = Modifier
                    .then(
                        if (isPip) {
                            Modifier
                                .zIndex(10f)
                                .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) }
                                .size(width = pipWidthDp, height = pipHeightDp)
                                .clip(RectangleShape)
                                .background(SURFACE)
                                .onGloballyPositioned { coords ->
                                    if (pipOffsetX == 0f && pipOffsetY == 0f) {
                                        val parentW = coords.parentCoordinates?.size?.width?.toFloat() ?: 0f
                                        val parentH = coords.parentCoordinates?.size?.height?.toFloat() ?: 0f
                                        pipOffsetX = parentW - pipWidthPx - with(density) { 16.dp.toPx() }
                                        pipOffsetY = parentH - pipHeightPx - with(density) { 16.dp.toPx() }
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        pipOffsetX += dragAmount.x
                                        pipOffsetY += dragAmount.y
                                    }
                                }
                        } else {
                            Modifier
                                .fillMaxSize()
                                .zIndex(1f)
                        }
                    )
            ) {
                // The content WebView
                AndroidView(
                    factory = { contentTab.webView!! },
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (!contentTouchable && !pipTouchable) {
                                Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) { /* absorb */ }
                            } else {
                                Modifier
                            }
                        )
                )

                // PiP close button
                if (isPip) {
                    IconButton(
                        onClick = {
                            contentTab.webView?.destroy()
                            contentTab.webView = null
                            contentTab.contentState = ContentState.HIDDEN
                            pipOffsetX = 0f
                            pipOffsetY = 0f
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            "Close PiP",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════
        // TAP BACKGROUND WHILE PIP ACTIVE → switch to content
        // ══════════════════════════════════════════════════════════
        if (contentTab.contentState == ContentState.PIP) {
            // Invisible tap target behind PiP — tapping homepage switches to content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(5f)
                    .clickable {
                        // Tapped background → expand content back to full
                        contentTab.contentState = ContentState.ACTIVE
                    }
            )
        }
    }
}

// END OF PART 7/10


// ═══════════════════════════════════════════════════════════════════
// === PART 8/10 — Reserved for Future Features ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 8/10


// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — Reserved for Future Features ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 9/10


// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — Reserved for Future Features ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 10/10
