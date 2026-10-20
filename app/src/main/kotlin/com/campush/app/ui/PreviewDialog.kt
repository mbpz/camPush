package com.campush.app.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.campush.app.ui.theme.CamPushColors

/**
 * 本机预览：全屏 WebView 打开唯一播放地址（播放页自行处理 FLV→MJPEG 与 PIN）。
 * 允许 http 明文（Manifest 已 usesCleartextTraffic），关闭时销毁 WebView。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewDialog(
    playUrl: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var loading by remember { mutableStateOf(true) }
        val webViewHolder = remember { arrayOfNulls<WebView>(1) }
        DisposableEffect(Unit) {
            onDispose {
                webViewHolder[0]?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    (parent as? ViewGroup)?.removeView(this)
                    destroy()
                }
                webViewHolder[0] = null
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            setBackgroundColor(AndroidColor.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.loadsImagesAutomatically = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                }
                            }
                            loadUrl(playUrl)
                            webViewHolder[0] = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loading) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = CamPushColors.Primary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在加载播放页…",
                            style = MaterialTheme.typography.bodySmall,
                            color = CamPushColors.TextSecondary,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xD9000000), Color.Transparent),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "本机预览",
                        style = MaterialTheme.typography.titleMedium,
                        color = CamPushColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        text = "局域网观看端",
                        containerColor = CamPushColors.Primary.copy(alpha = 0.18f),
                        contentColor = CamPushColors.Primary,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭本机预览",
                            tint = CamPushColors.TextPrimary,
                        )
                    }
                }
            }
        }
    }
}
