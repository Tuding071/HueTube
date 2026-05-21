// ═══════════════════════════════════════════════════════════════════
// HueTube - V7.0 (V1.0 + Back Fix + Fullscreen + Banner)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme Specification ===
// ═══════════════════════════════════════════════════════════════════
//
// THEME: Dark YouTube — Near-Black Background
//
// Two tabs: Tab 0 (Homepage, permanent), Tab 1 (Content, created on link tap)
// Only ONE WebView visible at a time.
// Content tab stays alive when detached (banner mode) — audio keeps playing.
//
// Back navigation:
//   ACTIVE + canGoBack → goBack()
//   ACTIVE + no history → BANNER (homepage visible, content detached, banner shown)
//   BANNER              → destroy content, HIDDEN
//   HIDDEN              → exit app
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
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

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

private const val HOMEPAGE_URL = "https://m.youtube.com"
private val BANNER_BG = Color(0xFF1A1A1A)
private val ACCENT = Color(0xFFFF0000)

// END OF PART 2/10


// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Content State Enum ===
// ═══════════════════════════════════════════════════════════════════

enum class ContentState {
    HIDDEN,   // Tab 0 visible, Tab 1 destroyed
    ACTIVE,   // Tab 1 visible, Tab 0 detached
    BANNER    // Tab 0 visible + banner, Tab 1 detached (playing)
}

// END OF PART 3/10


// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — Fullscreen Manager ===
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

// END OF PART 4/10


// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 — Back Action Logic ===
// ═══════════════════════════════════════════════════════════════════

sealed class BackAction {
    object GoBackInHistory : BackAction()
    object ShowBanner : BackAction()
    object CloseBanner : BackAction()
    object CloseApp : BackAction()
}

fun determineBackAction(state: ContentState, canGoBack: Boolean): BackAction = when {
    state == ContentState.ACTIVE && canGoBack -> BackAction.GoBackInHistory
    state == ContentState.ACTIVE -> BackAction.ShowBanner
    state == ContentState.BANNER -> BackAction.CloseBanner
    else -> BackAction.CloseApp
}

// END OF PART 5/10


// ═══════════════════════════════════════════════════════════════════
// === PART 6/10 — WebView Helpers ===
// ═══════════════════════════════════════════════════════════════════

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

fun createHomepageWebView(context: android.content.Context): WebView {
    return WebView(context).apply {
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
}

fun createContentWebView(
    context: android.content.Context,
    url: String,
    onNewContent: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onFullscreenShow: () -> Unit,
    onFullscreenHide: () -> Unit
): WebView {
    return WebView(context).apply {
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
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (title != null && title.isNotBlank() && title != "YouTube") {
                    onTitleChanged(title)
                }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                onFullscreenShow()
            }
            override fun onHideCustomView() {
                onFullscreenHide()
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
                    onNewContent(clickedUrl)
                    return true
                }
                return false
            }
        }

        loadUrl(url)
    }
}

// END OF PART 6/10


// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — Banner Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun PlayingBanner(
    title: String,
    onTap: () -> Unit,
    onClose: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "banner_pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(20f)
            .clickable { onTap() },
        color = BANNER_BG,
        shape = RectangleShape,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ACCENT.copy(alpha = dotAlpha))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Playing",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title.ifBlank { "Now Playing" },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
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
    val fullscreenManager = remember { FullscreenManager(activity) }

    var contentState by remember { mutableStateOf(ContentState.HIDDEN) }
    var contentTitle by remember { mutableStateOf("") }
    var isFullscreen by remember { mutableStateOf(false) }

    val container = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
        }
    }

    val homepageWebView = remember { createHomepageWebView(context) }
    var contentWebView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        container.addView(homepageWebView)
    }

    fun switchTo(target: WebView) {
        if (container.childCount == 0 || container.getChildAt(0) != target) {
            container.removeAllViews()
            container.addView(target)
        }
    }

    fun createContent(url: String) {
        contentWebView?.destroy()
        contentWebView = null
        val wv = createContentWebView(
            context = context, url = url,
            onNewContent = { newUrl -> createContent(newUrl) },
            onTitleChanged = { title -> contentTitle = title },
            onFullscreenShow = { isFullscreen = true; fullscreenManager.enterFullscreen() },
            onFullscreenHide = { isFullscreen = false; fullscreenManager.exitFullscreen() }
        )
        contentWebView = wv
        contentTitle = ""
        switchTo(wv)
        contentState = ContentState.ACTIVE
    }

    fun showBanner() {
        switchTo(homepageWebView)
        contentState = ContentState.BANNER
    }

    fun destroyContent() {
        contentWebView?.destroy()
        contentWebView = null
        contentTitle = ""
        switchTo(homepageWebView)
        contentState = ContentState.HIDDEN
    }

    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
            fullscreenManager.exitFullscreen()
            return@BackHandler
        }
        when (determineBackAction(contentState, contentWebView?.canGoBack() == true)) {
            BackAction.GoBackInHistory -> contentWebView?.goBack()
            BackAction.ShowBanner -> showBanner()
            BackAction.CloseBanner -> destroyContent()
            BackAction.CloseApp -> activity.finish()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { container }, modifier = Modifier.fillMaxSize())

        if (contentState == ContentState.BANNER) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                PlayingBanner(
                    title = contentTitle,
                    onTap = {
                        contentWebView?.let { switchTo(it) }
                        contentState = ContentState.ACTIVE
                    },
                    onClose = { destroyContent() }
                )
            }
        }
    }
}

// END OF PART 8/10


// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — Reserved ===
// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — Reserved ===
// ═══════════════════════════════════════════════════════════════════
