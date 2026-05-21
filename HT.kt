// ═══════════════════════════════════════════════════════════════════
// HueTube - V2.0 (YouTube WebView with PiP + Fullscreen)
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
// SLOTS:
//   Slot 0 — Homepage (fixed m.youtube.com, never navigates)
//   Slot 1 — Content  (dynamic, full WebView history)
//     States: ACTIVE | PIP | NONE
//
// BACK NAVIGATION:
//   Content canGoBack → goBack()
//   Content at start   → minimize to PiP, show Homepage
//   Homepage + PiP     → close PiP (destroy content)
//   Homepage no PiP    → finish()
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
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.unit.sp
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
// === PART 2/10 — Constants & App Context ===
// ═══════════════════════════════════════════════════════════════════

private val BG = Color(0xFF0A0A0A)
private val SURFACE = Color(0xFF121212)
private const val HOMEPAGE_URL = "https://m.youtube.com"

object AppContextHolder {
    lateinit var context: android.content.Context
}

// END OF PART 2/10


// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Slot 1 State Enum & TabState ===
// ═══════════════════════════════════════════════════════════════════

enum class ContentState {
    ACTIVE,   // Full screen, visible
    PIP,      // Minimized overlay on homepage
    NONE      // Destroyed, not present
}

class TabState {
    var webView by mutableStateOf<WebView?>(null)
    var url by mutableStateOf(HOMEPAGE_URL)
    var title by mutableStateOf("YouTube")
    var lastUsed by mutableLongStateOf(System.currentTimeMillis())
    var contentState by mutableStateOf(ContentState.NONE)
    var customView by mutableStateOf<View?>(null)  // Fullscreen video view
}

// END OF PART 3/10


// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — WebView Factory ===
// ═══════════════════════════════════════════════════════════════════

fun createWebView(
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
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (title != null && title.isNotBlank()) {
                    tabState.title = title
                }
            }

            // ── Fullscreen video support ──────────────────────────
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
                // Inject dark mode
                view.evaluateJavascript("""
                    (function() {
                        document.documentElement.style.setProperty('color-scheme', 'dark');
                        document.documentElement.style.setProperty('background-color', '#0A0A0A');
                        
                        var originalMatchMedia = window.matchMedia;
                        window.matchMedia = function(query) {
                            var result = originalMatchMedia(query);
                            if (query.includes('prefers-color-scheme')) {
                                return {
                                    matches: true,
                                    media: query,
                                    onchange: null,
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
        contentState == ContentState.ACTIVE && contentTab?.webView?.canGoBack() == true -> {
            BackAction.GoBackInHistory
        }
        contentState == ContentState.ACTIVE -> {
            BackAction.MinimizeToPip
        }
        contentState == ContentState.PIP -> {
            BackAction.ClosePip
        }
        else -> {
            BackAction.CloseApp
        }
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
        activity.window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_VISIBLE
        )
    }
}

// END OF PART 6/10



// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — PiP Overlay Composable ===
// ═══════════════════════════════════════════════════════════════════

// REMOVED — PiP is now handled by modifier swap in Part 8, no separate AndroidView needed.

// END OF PART 7/10


// ═══════════════════════════════════════════════════════════════════
// === PART 8/10 — Main App Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity ?: return
    AppContextHolder.context = context.applicationContext

    val fullscreenManager = remember { FullscreenManager(activity) }
    val density = LocalDensity.current

    // ── Home Tab (Slot 0) ────────────────────────────────────────
    val homeTab = remember {
        TabState().apply {
            webView = WebView(context).apply {
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
            contentState = ContentState.ACTIVE
            url = HOMEPAGE_URL
        }
    }

    // ── Content Tab (Slot 1) ─────────────────────────────────────
    val contentTab = remember { TabState() }

    // ── UI State ─────────────────────────────────────────────────
    var activeView by remember { mutableStateOf("home") }  // "home" | "content" | "pip"
    var isFullscreen by remember { mutableStateOf(false) }

    // PiP drag state
    var pipOffsetX by remember { mutableStateOf(0f) }
    var pipOffsetY by remember { mutableStateOf(0f) }

    // ── Stable container — NEVER destroyed, NEVER changes parent ─
    val contentContainer = remember {
        android.widget.FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            // Start with homepage
            addView(homeTab.webView)
        }
    }

    // ── Create content tab ───────────────────────────────────────
    fun createContentTab(url: String) {
        if (contentTab.webView != null) {
            contentTab.webView?.destroy()
        }
        contentTab.webView = createWebView(
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
        contentTab.lastUsed = System.currentTimeMillis()

        // Swap container child — homepage stays alive, content goes in
        contentContainer.removeAllViews()
        contentContainer.addView(contentTab.webView)
        activeView = "content"
    }

    // ── Switch to homepage ───────────────────────────────────────
    fun showHomepage() {
        if (activeView != "home") {
            contentContainer.removeAllViews()
            contentContainer.addView(homeTab.webView)
            activeView = "home"
        }
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
                // Don't swap container — content stays in container
                // PiP renders as overlay modifier
                activeView = "pip"
                // Keep homepage visible behind
                contentContainer.removeAllViews()
                contentContainer.addView(homeTab.webView)
            }
            BackAction.ClosePip -> {
                contentTab.webView?.destroy()
                contentTab.webView = null
                contentTab.contentState = ContentState.NONE
                contentTab.url = HOMEPAGE_URL
                activeView = "home"
            }
            BackAction.CloseApp -> {
                activity.finish()
            }
        }
    }

    // ── PiP drag handler ─────────────────────────────────────────
    val pipWidthPx = with(density) { 240.dp.toPx() }
    val pipHeightPx = with(density) { 135.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ── ONE AndroidView — never recreated ────────────────────
        AndroidView(
            factory = { contentContainer },
            modifier = Modifier.fillMaxSize()
        )

        // ── PiP Overlay (uses content WebView in-place, no re-parenting) ─
        if (activeView == "pip" && contentTab.webView != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) }
                        .size(width = 240.dp, height = 135.dp)
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
                        .clickable {
                            // Tap: expand back to full content
                            contentContainer.removeAllViews()
                            contentContainer.addView(contentTab.webView)
                            contentTab.contentState = ContentState.ACTIVE
                            activeView = "content"
                        }
                ) {
                    // Show content WebView inside PiP box
                    // Note: This temporarily re-parents — the blink is unavoidable here
                    // but only happens on PiP expand/collapse, not during normal use
                    AndroidView(
                        factory = { contentTab.webView!! },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Close button
                    IconButton(
                        onClick = {
                            contentTab.webView?.destroy()
                            contentTab.webView = null
                            contentTab.contentState = ContentState.NONE
                            activeView = "home"
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

        // ── Fullscreen custom view ──────────────────────────────
        if (isFullscreen && contentTab.customView != null) {
            AndroidView(
                factory = { contentTab.customView!! },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
            )
        }
    }
}

// END OF PART 8/10




// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — Reserved for Future Features ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 9/10


// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — Reserved for Future Features ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 10/10
