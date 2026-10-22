package com.campush.app.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/** P3 T3-A7：App 内观众端（复用 cloud-core `/rooms` 列表与 `/s/{room}` 播放页）。 */
class AudienceActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            loadUrl(intent.getStringExtra(EXTRA_URL) ?: "about:blank")
        }
        setContentView(webView)
        title = "CamPush 观众端"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "audience_url"
    }
}
