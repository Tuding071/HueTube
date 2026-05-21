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

@Composable
fun PipOverlay(
    webView: WebView,
    onTapExpand: () -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var pipWidth by remember { mutableStateOf(0) }
    var pipHeight by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(width = 240.dp, height = 135.dp)
                .clip(RectangleShape)
                .background(SURFACE)
                .onGloballyPositioned { coords ->
                    pipWidth = coords.size.width
                    pipHeight = coords.size.height
                    if (offsetX == 0f && offsetY == 0f) {
                        // Position bottom-right initially
                        val parentWidth = coords.parentCoordinates?.size?.width?.toFloat() ?: 0f
                        val parentHeight = coords.parentCoordinates?.size?.height?.toFloat() ?: 0f
                        val dpToPx = density.density
                        offsetX = parentWidth - pipWidth - (16f * dpToPx)
                        offsetY = parentHeight - pipHeight - (16f * dpToPx)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            // WebView content
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onTapExpand() }
            )

            // Close button
            IconButton(
                onClick = onClose,
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
                            // Homepage tried to navigate away — force back
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

    // ── Show homepage? ───────────────────────────────────────────
    var showHomepage by remember { mutableStateOf(true) }

    // ── Fullscreen state ─────────────────────────────────────────
    var isFullscreen by remember { mutableStateOf(false) }

    // ── Create content tab ───────────────────────────────────────
    fun createContentTab(url: String) {
        // Destroy old if exists
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
        showHomepage = false
    }

    // ── Back Handler ────────────────────────────────────────────
    BackHandler {
        if (isFullscreen) {
            // In fullscreen, exit fullscreen first
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
                showHomepage = true
            }
            BackAction.ClosePip -> {
                contentTab.webView?.destroy()
                contentTab.webView = null
                contentTab.contentState = ContentState.NONE
                contentTab.url = HOMEPAGE_URL
            }
            BackAction.CloseApp -> {
                activity.finish()
            }
        }
    }

    // ── Main Layout ─────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ── Homepage (Slot 0) ────────────────────────────────────
        AnimatedVisibility(
            visible = showHomepage,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            AndroidView(
                factory = { homeTab.webView!! },
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            )
        }

        // ── Content fullscreen (Slot 1 ACTIVE) ──────────────────
        AnimatedVisibility(
            visible = contentTab.contentState == ContentState.ACTIVE && !showHomepage,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300)
            ) + fadeIn(tween(300)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(200)
            ) + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            contentTab.webView?.let { wv ->
                AndroidView(
                    factory = { wv },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ── PiP Overlay ─────────────────────────────────────────
        if (contentTab.contentState == ContentState.PIP && contentTab.webView != null) {
            PipOverlay(
                webView = contentTab.webView!!,
                onTapExpand = {
                    contentTab.contentState = ContentState.ACTIVE
                    showHomepage = false
                },
                onClose = {
                    contentTab.webView?.destroy()
                    contentTab.webView = null
                    contentTab.contentState = ContentState.NONE
                }
            )
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
