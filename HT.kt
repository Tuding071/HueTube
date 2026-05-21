// ═══════════════════════════════════════════════════════════════════
// HueTube - V4.0 (WebView Manager + Compose PiP)
// ═══════════════════════════════════════════════════════════════════
// === PART 1/8 — Package, Imports, MainActivity ===
// ═══════════════════════════════════════════════════════════════════

package com.huetube.app

import android.os.Bundle
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

// END OF PART 1/8


// ═══════════════════════════════════════════════════════════════════
// === PART 2/8 — Content State Enum ===
// ═══════════════════════════════════════════════════════════════════

enum class ContentState {
    HIDDEN,
    ACTIVE,
    PIP
}

// END OF PART 2/8


// ═══════════════════════════════════════════════════════════════════
// === PART 3/8 — Fullscreen Manager ===
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

// END OF PART 3/8


// ═══════════════════════════════════════════════════════════════════
// === PART 4/8 — Back Action Logic ===
// ═══════════════════════════════════════════════════════════════════

sealed class BackAction {
    object GoBackInHistory : BackAction()
    object MinimizeToPip : BackAction()
    object ClosePip : BackAction()
    object CloseApp : BackAction()
}

fun determineBackAction(state: ContentState, canGoBack: Boolean): BackAction = when {
    state == ContentState.ACTIVE && canGoBack -> BackAction.GoBackInHistory
    state == ContentState.ACTIVE -> BackAction.MinimizeToPip
    state == ContentState.PIP -> BackAction.ClosePip
    else -> BackAction.CloseApp
}

// END OF PART 4/8


// ═══════════════════════════════════════════════════════════════════
// === PART 5/8 — Main App Composable ===
// ═══════════════════════════════════════════════════════════════════

@Composable
fun HueTubeApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity ?: return

    val fullscreenManager = remember { FullscreenManager(activity) }
    val density = LocalDensity.current

    // ── WebView Manager — owns both WebViews, container never changes ─
    val wm = remember { WebViewManager(context) }
    var contentState by remember { mutableStateOf(ContentState.HIDDEN) }
    var isFullscreen by remember { mutableStateOf(false) }

    // PiP drag state
    var pipOffsetX by remember { mutableStateOf(0f) }
    var pipOffsetY by remember { mutableStateOf(0f) }
    val pipWidthDp = 240.dp
    val pipHeightDp = 135.dp
    val pipWidthPx = with(density) { pipWidthDp.toPx() }
    val pipHeightPx = with(density) { pipHeightDp.toPx() }

    // ── Wire callbacks ──────────────────────────────────────────
    LaunchedEffect(wm) {
        wm.onNewContentRequest = { url ->
            wm.showContent(url)
            contentState = ContentState.ACTIVE
        }
        wm.onCustomViewShow = {
            isFullscreen = true
            fullscreenManager.enterFullscreen()
        }
        wm.onCustomViewHide = {
            isFullscreen = false
            fullscreenManager.exitFullscreen()
        }
    }

    // ── Back Handler ────────────────────────────────────────────
    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
            fullscreenManager.exitFullscreen()
            return@BackHandler
        }

        when (determineBackAction(contentState, wm.canContentGoBack())) {
            BackAction.GoBackInHistory -> wm.contentGoBack()
            BackAction.MinimizeToPip -> {
                wm.showHomepage()
                contentState = ContentState.PIP
            }
            BackAction.ClosePip -> {
                wm.destroyContent()
                contentState = ContentState.HIDDEN
                pipOffsetX = 0f
                pipOffsetY = 0f
            }
            BackAction.CloseApp -> activity.finish()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Container — ONE AndroidView for the entire app lifecycle ─
        AndroidView(
            factory = { wm.container },
            modifier = Modifier.fillMaxSize()
        )

        // ── PiP overlay (Compose layer on top of the container) ──
        if (contentState == ContentState.PIP && wm.contentWebView != null) {
            // Tap background → expand back to content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(5f)
                    .clickable {
                        wm.showContent(wm.contentWebView?.url ?: WebViewManager.HOMEPAGE_URL)
                        contentState = ContentState.ACTIVE
                    }
            )

            // PiP window
            Box(
                modifier = Modifier
                    .zIndex(10f)
                    .offset { IntOffset(pipOffsetX.roundToInt(), pipOffsetY.roundToInt()) }
                    .size(width = pipWidthDp, height = pipHeightDp)
                    .clip(RectangleShape)
                    .background(Color(0xFF121212))
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
                        // Tap PiP → expand
                        wm.showContent(wm.contentWebView?.url ?: WebViewManager.HOMEPAGE_URL)
                        contentState = ContentState.ACTIVE
                    }
            ) {
                // Show content WebView inside PiP
                AndroidView(
                    factory = { wm.contentWebView!! },
                    modifier = Modifier.fillMaxSize()
                )

                // Close button
                IconButton(
                    onClick = {
                        wm.destroyContent()
                        contentState = ContentState.HIDDEN
                        pipOffsetX = 0f
                        pipOffsetY = 0f
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

// END OF PART 5/8


// ═══════════════════════════════════════════════════════════════════
// === PART 6/8 — Reserved ===
// ═══════════════════════════════════════════════════════════════════
// === PART 7/8 — Reserved ===
// ═══════════════════════════════════════════════════════════════════
// === PART 8/8 — Reserved ===
// ═══════════════════════════════════════════════════════════════════
