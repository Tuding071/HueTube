// ═══════════════════════════════════════════════════════════════════
// HueTube - V1.0 (YouTube WebView)
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
// ═══════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package, Imports, MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.huetube.app

import android.os.Bundle
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val BG = Color(0xFF0A0A0A)
private val SURFACE = Color(0xFF121212)
private const val HOMEPAGE_URL = "https://m.youtube.com"
private const val MAX_ACTIVE_PAGES = 2
private const val MAX_WARM_PAGES = 3

// END OF PART 2/10


// ═══════════════════════════════════════════════════════════════════
// === PART 3/10 — Tab State Data Class ===
// ═══════════════════════════════════════════════════════════════════

class TabState {
    var webView by mutableStateOf<WebView?>(null)
    var url by mutableStateOf(HOMEPAGE_URL)
    var title by mutableStateOf("YouTube")
    var isDiscarded by mutableStateOf(false)
    var lastUsed by mutableLongStateOf(System.currentTimeMillis())
}

// END OF PART 3/10


// ═══════════════════════════════════════════════════════════════════
// === PART 4/10 — WebView Factory ===
// ═══════════════════════════════════════════════════════════════════

fun createWebView(url: String, onNewTabRequest: (String) -> Unit): WebView {
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
                // Title updates handled by TabState
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // Inject dark mode
                view.evaluateJavascript("""
                    (function() {
                        // Force dark theme
                        document.documentElement.style.setProperty('color-scheme', 'dark');
                        document.documentElement.style.setProperty('background-color', '#0A0A0A');
                        
                        // Override prefers-color-scheme
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
                        
                        // Try to force YouTube dark theme
                        if (window.yt && window.yt.config_) {
                            document.cookie = 'PREF=f6=4;path=/;domain=.youtube.com';
                        }
                    })();
                """.trimIndent(), null)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                // Open all link taps in new tab
                val clickedUrl = request.url.toString()
                if (request.isForMainFrame && clickedUrl != view.url) {
                    onNewTabRequest(clickedUrl)
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
// === PART 5/10 — App Context Holder ===
// ═══════════════════════════════════════════════════════════════════

object AppContextHolder {
    lateinit var context: android.content.Context
}

// END OF PART 5/10


// ═══════════════════════════════════════════════════════════════════
// === PART 6/10 — Tab Lifecycle Manager ===
// ═══════════════════════════════════════════════════════════════════

fun manageTabLifecycle(tabs: MutableList<TabState>, activeIndex: Int) {
    if (activeIndex < 0 || activeIndex >= tabs.size) return

    val activeTab = tabs[activeIndex]
    
    // Activate current tab
    if (activeTab.webView == null && activeTab.isDiscarded) {
        activeTab.webView = createWebView(activeTab.url) { url ->
            // New tab request callback — handled externally
        }
        activeTab.isDiscarded = false
        activeTab.lastUsed = System.currentTimeMillis()
    }
    activeTab.lastUsed = System.currentTimeMillis()

    // Track active (non-discarded) pages
    val activePages = tabs.filterIndexed { i, t ->
        i != activeIndex && !t.isDiscarded && t.webView != null
    }

    // Keep only MAX_ACTIVE_PAGES - 1 other pages active
    if (activePages.size > MAX_ACTIVE_PAGES - 1) {
        val toDiscard = activePages
            .sortedBy { it.lastUsed }
            .take(activePages.size - (MAX_ACTIVE_PAGES - 1))
        for (tab in toDiscard) {
            tab.webView?.destroy()
            tab.webView = null
            tab.isDiscarded = true
        }
    }

    // Ensure total warm tabs (active + discarded) doesn't exceed MAX_WARM_PAGES
    val warmTabs = tabs.filter { !it.isDiscarded || it.webView != null }
    if (warmTabs.size > MAX_WARM_PAGES) {
        val toDestroy = warmTabs
            .filter { it.isDiscarded }
            .sortedBy { it.lastUsed }
        val extraCount = warmTabs.size - MAX_WARM_PAGES
        for (tab in toDestroy.take(extraCount)) {
            val index = tabs.indexOf(tab)
            if (index >= 0 && index != activeIndex) {
                tab.webView?.destroy()
                tabs.removeAt(index)
                if (activeIndex > index) {
                    // Adjust active index — handled in composable
                }
            }
        }
    }
}

// END OF PART 6/10


// ═══════════════════════════════════════════════════════════════════
// === PART 7/10 — Back Handler Logic ===
// ═══════════════════════════════════════════════════════════════════
//
// Back button behavior:
//   1. If current tab can go back in WebView history → go back
//   2. If at homepage → refresh page
//   3. If already refreshed → close app
//
// ═══════════════════════════════════════════════════════════════════

class BackHandlerState {
    var homepageRefreshCount by mutableIntStateOf(0)
    var lastBackTime by mutableLongStateOf(0L)
    
    fun handleBack(currentTab: TabState?, onCloseApp: () -> Unit) {
        val now = System.currentTimeMillis()
        
        if (currentTab?.webView?.canGoBack() == true) {
            currentTab.webView?.goBack()
            homepageRefreshCount = 0
            return
        }
        
        val currentUrl = currentTab?.url ?: ""
        val isOnHomepage = currentUrl == HOMEPAGE_URL || 
                          currentUrl.startsWith("$HOMEPAGE_URL?") ||
                          currentUrl == "about:blank"
        
        if (isOnHomepage) {
            homepageRefreshCount++
            lastBackTime = now
            
            if (homepageRefreshCount >= 2) {
                onCloseApp()
            } else {
                currentTab?.webView?.reload()
            }
        } else {
            currentTab?.webView?.loadUrl(HOMEPAGE_URL)
            homepageRefreshCount = 0
        }
    }
    
    fun reset() {
        homepageRefreshCount = 0
        lastBackTime = 0L
    }
}

// END OF PART 7/10


// ═══════════════════════════════════════════════════════════════════
// === PART 8/10 — Main App Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    AppContextHolder.context = context.applicationContext

    // ── Tab Management ──────────────────────────────────────────
    val tabs = remember { mutableStateListOf<TabState>() }
    var currentTabIndex by remember { mutableIntStateOf(-1) }
    val backHandlerState = remember { BackHandlerState() }

    // Initialize with home tab
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val homeTab = TabState().apply {
                webView = createWebView(HOMEPAGE_URL) { url ->
                    // New tab creation handled in the callback
                }
                url = HOMEPAGE_URL
                isDiscarded = false
                lastUsed = System.currentTimeMillis()
            }
            tabs.add(homeTab)
            currentTabIndex = 0
        }
    }

    // ── New Tab Creation Helper ─────────────────────────────────
    fun createNewTab(url: String) {
        val newTab = TabState().apply {
            webView = createWebView(url) { newUrl ->
                // Recursive: taps in new tabs also create tabs
            }
            this.url = url
            isDiscarded = false
            lastUsed = System.currentTimeMillis()
        }
        tabs.add(newTab)
        val newIndex = tabs.lastIndex
        currentTabIndex = newIndex
        manageTabLifecycle(tabs, currentTabIndex)
    }

    // ── Back Handler ────────────────────────────────────────────
    BackHandler {
        val currentTab = tabs.getOrNull(currentTabIndex)
        backHandlerState.handleBack(currentTab) {
            // Close app
            (context as? android.app.Activity)?.finish()
        }
    }

    // ── Manage lifecycle when tab changes ───────────────────────
    LaunchedEffect(currentTabIndex) {
        if (currentTabIndex >= 0) {
            manageTabLifecycle(tabs, currentTabIndex)
            backHandlerState.reset()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding()
    ) {
        // ── Render current tab with pop-up animation ─────────────
        val currentTab = tabs.getOrNull(currentTabIndex)
        
        if (currentTab != null && currentTab.webView != null) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { it },  // Start from bottom
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(animationSpec = tween(durationMillis = 300)),
                exit = slideOutVertically(
                    targetOffsetY = { it },    // Exit to bottom
                    animationSpec = tween(durationMillis = 200)
                ) + fadeOut(animationSpec = tween(durationMillis = 200))
            ) {
                AndroidView(
                    factory = { currentTab.webView!! },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (currentTab != null && currentTab.isDiscarded) {
            // Placeholder while tab loads
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Loading...",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 16.sp
                )
            }
        }
    }
}

// END OF PART 8/10


// ═══════════════════════════════════════════════════════════════════
// === PART 9/10 — Reserved for Future Features ===
// ═══════════════════════════════════════════════════════════════════

// Future parts will go here as features are added

// END OF PART 9/10


// ═══════════════════════════════════════════════════════════════════
// === PART 10/10 — Reserved for Future Features ===
// ═══════════════════════════════════════════════════════════════════

// END OF PART 10/10
