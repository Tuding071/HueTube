// ═══════════════════════════════════════════════════════════════════
// HueTube - V1.6 (Remote JS Ad Blocker)
// ═══════════════════════════════════════════════════════════════════
// === PART 0/10 — Theme Specification ===
// ═══════════════════════════════════════════════════════════════════
//
// THEME: Dark YouTube — Near-Black Background
//
// Ad Blocker:
//   - Fetches a remote adblocker.js from GitHub Gist (or any raw URL)
//   - Caches it to app private storage
//   - Injects on every page load via onPageStarted
//   - Toggle in bottom sheet enables/disables injection
//   - Update button re-fetches and saves latest JS
//   - When ads break: edit the remote JS, user taps Update — done
//   - No app rebuild needed for ad blocker changes
//
// Bottom Sheet:
//   - Floating 3-line button bottom-left opens it
//   - Ad block toggle + update button on top
//   - Space below reserved for future features
//
// Fullscreen: SENSOR orientation, saved+restored on exit
// Back: fullscreen → sheet → goBack → homepage → exit
//
// ═══════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════
// === PART 1/10 — Package + Imports + MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.huetube.app

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

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

private const val HOMEPAGE_URL  = "https://m.youtube.com"
private const val JS_FILENAME   = "ht_adblock.js"
private const val META_FILENAME = "ht_adblock_meta.txt"

// ── Remote JS URL ─────────────────────────────────────────────────
// Point this at your GitHub Gist raw URL or any hosted JS file.
// Edit that file when ads break — users tap Update and it applies.
private const val REMOTE_JS_URL =
    "https://gist.githubusercontent.com/YOUR_USERNAME/YOUR_GIST_ID/raw/ht_adblock.js"

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

        customView        = view
        webViewContainer  = container
        webView           = wv
        savedOrientation  = activity.requestedOrientation
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
// === PART 4/10 — Ad Blocker Repository ===
// ═══════════════════════════════════════════════════════════════════
//
// AdBlockRepo:
//   loadJs()   — reads cached JS from disk, returns null if none
//   loadMeta() — returns last-updated timestamp string
//   update()   — fetches REMOTE_JS_URL, saves JS + timestamp to disk
//
// Files saved to app.filesDir:
//   ht_adblock.js       — the raw injected JS
//   ht_adblock_meta.txt — "Updated YYYY-MM-DD HH:mm"
//
// ═══════════════════════════════════════════════════════════════════

class AdBlockRepo(private val ctx: Context) {

    private val jsFile   get() = File(ctx.filesDir, JS_FILENAME)
    private val metaFile get() = File(ctx.filesDir, META_FILENAME)

    fun loadJs(): String? {
        if (!jsFile.exists()) return null
        return try { jsFile.readText().takeIf { it.isNotBlank() } } catch (e: Exception) { null }
    }

    fun loadMeta(): String {
        if (!metaFile.exists()) return ""
        return try { metaFile.readText().trim() } catch (e: Exception) { "" }
    }

    suspend fun update(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val js = URL(REMOTE_JS_URL).readText()
            if (js.isBlank()) return@withContext Result.failure(Exception("Empty response"))
            jsFile.writeText(js)
            val ts = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date())
            val meta = "Updated $ts"
            metaFile.writeText(meta)
            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// END OF PART 4/10


// ═══════════════════════════════════════════════════════════════════
// === PART 5/10 — Dark Mode JS (always injected) ===
// ═══════════════════════════════════════════════════════════════════
//
// This runs on every page load regardless of ad block toggle.
// Keeps YouTube in dark mode and sets the dark cookie.
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
    updateStatus: String,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
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
                        "Injected on every page load",
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

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(
                color = DIVIDER,
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))

            // ── Update Rules ──────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Blocker Script",
                        color = TEXT_PRI,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        updateStatus.ifEmpty { "No script cached yet" },
                        color = if (updateStatus.startsWith("Failed")) Color(0xFFCC4444)
                                else TEXT_SEC,
                        fontSize = 12.sp
                    )
                }
                Button(
                    onClick = onUpdate,
                    enabled = !isUpdating,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = Color(0xFF222222),
                        contentColor           = TEXT_PRI,
                        disabledContainerColor = Color(0xFF1A1A1A),
                        disabledContentColor   = TEXT_SEC
                    )
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(14.dp),
                            color       = TEXT_SEC,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Update", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color = DIVIDER,
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // ── Future features below ─────────────────────────────
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
    val scope    = rememberCoroutineScope()
    val repo     = remember { AdBlockRepo(context) }

    val fullscreenManager = remember { FullscreenManager(activity) }

    // ── State ────────────────────────────────────────────────────
    var isFullscreen       by remember { mutableStateOf(false) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var showSheet          by remember { mutableStateOf(false) }
    var adBlockEnabled     by remember { mutableStateOf(true) }
    var isUpdating         by remember { mutableStateOf(false) }
    var updateStatus       by remember { mutableStateOf("") }
    var cachedJs           by remember { mutableStateOf<String?>(null) }

    // Load cached JS + meta on start
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val js   = repo.loadJs()
            val meta = repo.loadMeta()
            cachedJs     = js
            updateStatus = meta
        }
    }

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

    // Keep a ref to inject updated JS after an update without reload
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

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
                    // Always inject dark mode
                    view.evaluateJavascript(DARK_MODE_JS, null)

                    // Inject ad blocker JS if enabled and cached
                    if (adBlockEnabled) {
                        cachedJs?.let { js ->
                            view.evaluateJavascript(js, null)
                        }
                    }
                }
            }

            loadUrl(HOMEPAGE_URL)
        }
    }

    // Store ref for post-update injection
    LaunchedEffect(webView) { webViewRef.value = webView }

    // Add WebView to container once
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
                updateStatus    = updateStatus,
                isUpdating      = isUpdating,
                onUpdate        = {
                    scope.launch {
                        isUpdating   = true
                        updateStatus = "Fetching..."
                        val result   = repo.update()
                        result.onSuccess { meta ->
                            cachedJs     = repo.loadJs()
                            updateStatus = meta
                        }
                        result.onFailure { e ->
                            updateStatus = "Failed: ${e.message?.take(40) ?: "unknown error"}"
                        }
                        isUpdating = false
                    }
                },
                onDismiss = { showSheet = false }
            )
        }
    }
}

// END OF PART 10/10
