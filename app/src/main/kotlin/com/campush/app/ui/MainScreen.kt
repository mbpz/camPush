package com.campush.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campush.app.core.AccessScopeUi
import com.campush.app.core.CameraFacingUi
import com.campush.app.core.CameraModeUi
import com.campush.app.core.P1Controller
import com.campush.app.core.P1Error
import com.campush.app.core.P1UiState
import com.campush.app.ui.theme.CamPushColors
import kotlinx.coroutines.launch

/**
 * P1 主界面：全屏预览 + 顶部状态/指标 + 访问范围分段 + 底部主操作区 + 推流成功后的地址卡。
 * 只消费 P1Controller/P1UiState；预览绑定由 CameraPreview 负责。
 */
@Composable
fun MainScreen(controller: P1Controller) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var addressExpanded by rememberSaveable { mutableStateOf(true) }

    fun notify(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(state.copiedHint) {
        val hint = state.copiedHint
        if (!hint.isNullOrBlank()) snackbarHostState.showSnackbar(hint)
    }
    // 推流结束后地址失效，关闭依赖地址的弹窗。
    LaunchedEffect(state.streaming) {
        if (!state.streaming) {
            showQr = false
            showPreview = false
        }
    }
    // 有警告时收起地址卡，避免与指标区/警告条重叠（截图与真机均验证）。
    LaunchedEffect(state.warning) {
        if (!state.warning.isNullOrBlank()) {
            addressExpanded = false
        }
    }

    val copyAddress: () -> Unit = {
        val url = state.playUrl
        if (url != null) {
            context.copyPlayUrlToClipboard(url)
            notify("播放地址已复制")
        } else {
            notify("播放地址尚未生成，请稍候")
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CamPushColors.Background),
    ) {
        val isLandscape = maxWidth > maxHeight
        LaunchedEffect(isLandscape) {
            if (isLandscape) addressExpanded = false
        }

        // 中央全屏摄像头预览（app-stream 提供）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "摄像头实时预览" },
        ) {
            CameraPreview(controller = controller, modifier = Modifier.fillMaxSize())
        }

        // 上下渐变遮罩，保证状态/操作文字在明亮画面上可读。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xD9000000), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD9000000)),
                    ),
                ),
        )

        TopStatusBar(
            state = state,
            isLandscape = isLandscape,
            onAddressClick = { addressExpanded = true },
            onSettings = { showSettings = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (state.starting) {
            StartingOverlay(modifier = Modifier.align(Alignment.Center))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 84.dp, start = 16.dp, end = 16.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = CamPushColors.SurfaceElevated,
                contentColor = CamPushColors.TextPrimary,
                shape = RoundedCornerShape(12.dp),
            )
        }

        BottomControls(
            state = state,
            controller = controller,
            isLandscape = isLandscape,
            addressExpanded = addressExpanded,
            onToggleAddress = { addressExpanded = !addressExpanded },
            onCopy = copyAddress,
            onQr = { showQr = true },
            onPreview = { showPreview = true },
            onShare = {
                state.playUrl?.let { context.sharePlayUrl(it) } ?: notify("播放地址尚未生成")
            },
            onLanSelected = {
                if (state.streaming || state.starting) {
                    notify("推流中不可切换，请先停止推流后切换访问范围")
                } else if (state.accessScope != AccessScopeUi.LAN) {
                    controller.selectAccessScope(AccessScopeUi.LAN)
                }
            },
            onWanLockedClick = { notify("公网可访问 · 阶段二开放") },
            onSettings = { showSettings = true },
            onNotify = ::notify,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            controller = controller,
            onDismiss = { showSettings = false },
        )
    }

    val playUrl = state.playUrl
    if (showQr && playUrl != null) {
        QrDialog(playUrl = playUrl, onDismiss = { showQr = false })
    }
    if (showPreview && playUrl != null) {
        PreviewDialog(playUrl = playUrl, onDismiss = { showPreview = false })
    }
    state.error?.let { error ->
        P1ErrorDialog(
            error = error,
            onDismiss = controller::dismissError,
            onOpenSettings = { context.openAppSettings() },
        )
    }
}

@Composable
private fun TopStatusBar(
    state: P1UiState,
    isLandscape: Boolean,
    onAddressClick: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = if (isLandscape) 6.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(streaming = state.streaming, starting = state.starting)
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    state.streaming -> "局域网推流中"
                    state.starting -> "正在启动…"
                    else -> "未推流"
                },
                style = MaterialTheme.typography.titleMedium,
                color = CamPushColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = "局域网",
                icon = Icons.Filled.Lan,
                containerColor = CamPushColors.Primary.copy(alpha = 0.16f),
                contentColor = CamPushColors.Primary,
            )
            Spacer(Modifier.weight(1f))
            if (state.streaming) {
                RoundIconAction(
                    icon = Icons.Filled.Link,
                    contentDescription = "查看播放地址",
                    onClick = onAddressClick,
                    active = true,
                )
                Spacer(Modifier.width(6.dp))
            }
            RoundIconAction(
                icon = Icons.Filled.Settings,
                contentDescription = "打开设置",
                onClick = onSettings,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetricCard("模式", cameraModeLabel(state.cameraMode, state.cameraFacing))
            MetricCard("画质", if (state.settings.powerSave) "480p · 24fps（省电）" else "${state.settings.height}p · ${state.settings.fps}fps")
            MetricCard(
                "码率",
                if (state.bitrateKbps > 0) "${state.bitrateKbps} kbps" else "${state.settings.bitrateKbps} kbps",
            )
            MetricCard(
                "帧率",
                if (state.fps > 0) "${state.fps} fps" else "${state.settings.fps} fps",
            )
            MetricCard("观看", "${state.viewers}/${state.maxViewers}")
            MetricCard("网络", state.networkLabel)
        }
    }
}

@Composable
private fun StartingOverlay(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = CamPushColors.Scrim,
        border = BorderStroke(1.dp, CamPushColors.Border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                color = CamPushColors.Primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "正在启动局域网推流…",
                style = MaterialTheme.typography.titleSmall,
                color = CamPushColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "正在启动端侧服务与摄像头",
                style = MaterialTheme.typography.bodySmall,
                color = CamPushColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun BottomControls(
    state: P1UiState,
    controller: P1Controller,
    isLandscape: Boolean,
    addressExpanded: Boolean,
    onToggleAddress: () -> Unit,
    onCopy: () -> Unit,
    onQr: () -> Unit,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    onLanSelected: () -> Unit,
    onWanLockedClick: () -> Unit,
    onSettings: () -> Unit,
    onNotify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.warning?.let { warning ->
            WarningBanner(text = warning, onDismiss = { controller.dismissWarning() })
            Spacer(Modifier.height(10.dp))
        }

        if (state.streaming) {
            val url = state.playUrl
            if (url != null) {
                AddressCard(
                    state = state,
                    expanded = addressExpanded,
                    onToggleExpanded = onToggleAddress,
                    onCopy = onCopy,
                    onQr = onQr,
                    onPreview = onPreview,
                    onShare = onShare,
                    compact = isLandscape,
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = CamPushColors.Surface.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, CamPushColors.Border),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = CamPushColors.Primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在生成局域网播放地址…",
                            style = MaterialTheme.typography.bodySmall,
                            color = CamPushColors.TextSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val startStop: () -> Unit = {
            if (state.streaming) controller.stop() else controller.start()
        }
        val switchCamera: () -> Unit = { controller.switchCamera() }
        val toggleTorch: () -> Unit = { controller.setTorch(!state.torchOn) }
        val toggleMute: () -> Unit = { controller.setMuted(!state.muted) }

        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccessScopeSelector(
                    state = state,
                    onLanSelected = onLanSelected,
                    onWanLockedClick = onWanLockedClick,
                    compact = true,
                )
                Spacer(Modifier.weight(1f))
                StartStopButton(state = state, size = 68.dp, onClick = startStop)
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIconAction(
                        icon = Icons.Filled.Cameraswitch,
                        contentDescription = "切换前后摄像头",
                        onClick = switchCamera,
                        enabled = !state.starting,
                    )
                    RoundIconAction(
                        icon = if (state.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (state.torchOn) "关闭闪光灯" else "打开闪光灯",
                        onClick = toggleTorch,
                        active = state.torchOn,
                        enabled = !state.starting && state.cameraFacing == CameraFacingUi.BACK,
                    )
                    RoundIconAction(
                        icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (state.muted) "取消静音" else "静音",
                        onClick = toggleMute,
                        active = state.muted,
                    )
                    RoundIconAction(
                        icon = Icons.Filled.Settings,
                        contentDescription = "打开设置",
                        onClick = onSettings,
                    )
                }
            }
        } else {
            AccessScopeSelector(
                state = state,
                onLanSelected = onLanSelected,
                onWanLockedClick = onWanLockedClick,
            )
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                RoundIconAction(
                    icon = Icons.Filled.Cameraswitch,
                    contentDescription = "切换前后摄像头",
                    onClick = switchCamera,
                    enabled = !state.starting,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                StartStopButton(
                    state = state,
                    size = 84.dp,
                    onClick = startStop,
                    modifier = Modifier.align(Alignment.Center),
                )
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIconAction(
                        icon = if (state.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (state.torchOn) "关闭闪光灯" else "打开闪光灯",
                        onClick = toggleTorch,
                        active = state.torchOn,
                        enabled = !state.starting && state.cameraFacing == CameraFacingUi.BACK,
                    )
                    RoundIconAction(
                        icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (state.muted) "取消静音" else "静音",
                        onClick = toggleMute,
                        active = state.muted,
                    )
                    RoundIconAction(
                        icon = Icons.Filled.Settings,
                        contentDescription = "打开设置",
                        onClick = onSettings,
                    )
                }
            }
        }

        if (!state.streaming && !state.starting) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "点击开始推流，同一 Wi-Fi/热点下的设备打开地址即可观看",
                style = MaterialTheme.typography.labelSmall,
                color = CamPushColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StartStopButton(
    state: P1UiState,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val streaming = state.streaming
    val container = if (streaming) CamPushColors.Danger else CamPushColors.Primary
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = !state.starting,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = if (streaming) Color.White else CamPushColors.OnPrimary,
                disabledContainerColor = CamPushColors.SurfaceElevated,
                disabledContentColor = CamPushColors.TextSecondary,
            ),
            modifier = Modifier
                .size(size)
                .semantics {
                    contentDescription = when {
                        state.starting -> "正在启动推流"
                        streaming -> "停止推流"
                        else -> "开始推流"
                    }
                },
        ) {
            if (state.starting) {
                CircularProgressIndicator(
                    color = CamPushColors.Primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(size * 0.34f),
                )
            } else {
                Icon(
                    imageVector = if (streaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.46f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                state.starting -> "启动中"
                streaming -> "停止推流"
                else -> "开始推流"
            },
            style = MaterialTheme.typography.labelLarge,
            color = when {
                state.starting -> CamPushColors.TextSecondary
                streaming -> CamPushColors.Danger
                else -> CamPushColors.Primary
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun P1ErrorDialog(
    error: P1Error,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val needsSystemSettings = error.code == 1001 || error.code == 1007
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = CamPushColors.Danger,
            )
        },
        title = { Text("推流遇到问题") },
        text = {
            Column {
                Text(error.message, style = MaterialTheme.typography.bodyMedium)
                error.hint?.let { hint ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = CamPushColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "错误码 ${error.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CamPushColors.TextTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = if (needsSystemSettings) {
            { TextButton(onClick = onOpenSettings) { Text("去系统设置") } }
        } else {
            null
        },
    )
}

private fun cameraModeLabel(mode: CameraModeUi, facing: CameraFacingUi): String = when (mode) {
    CameraModeUi.SINGLE_FRONT -> "前置"
    CameraModeUi.SINGLE_BACK -> if (facing == CameraFacingUi.FRONT) "前置" else "后置"
}
