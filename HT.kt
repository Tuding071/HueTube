package com.huetube.app

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val webView = remember {
                WebView(this).apply {
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
                    loadUrl("https://m.youtube.com")
                }
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0A))
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // WebView will be handled by lifecycle
    }

    override fun onDestroy() {
        super.onDestroy()
        // WebView cleanup handled by garbage collection
    }
}
