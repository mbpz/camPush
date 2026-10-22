package com.campush.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campush.app.BuildConfig
import com.campush.app.core.P1Controller
import com.campush.app.core.P1UiState
import com.campush.app.core.StreamSettingsUi
import com.campush.app.ui.theme.CamPushColors
import com.campush.lan.BuildInfo

private val portRange = 8089..8099

/**
 * P1 设置面板：画质/性能、音频、局域网（PIN/端口/AP 隔离清单）、关于。
 * 推流中修改会提示“重启推流生效”；PIN 做 4-6 位数字校验。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: P1UiState,
    controller: P1Controller,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CamPushColors.Background,
        contentColor = CamPushColors.TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = CamPushColors.Border) },
    ) {
        SettingsContent(state = state, controller = controller, onDismiss = onDismiss)
    }
}

@Composable
private fun SettingsContent(
    state: P1UiState,
    controller: P1Controller,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var settings by remember(state.settings) { mutableStateOf(state.settings) }
    var pinEnabled by remember(state.pinRequired) { mutableStateOf(state.pinRequired) }
    var pinText by remember(state.pin) { mutableStateOf(state.pin.orEmpty()) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var portMenuOpen by remember { mutableStateOf(false) }
    var checklistOpen by remember { mutableStateOf(false) }
    var logCopied by remember { mutableStateOf(false) }
    var wanApi by remember {
        mutableStateOf(state.cloudApiHost.ifBlank { "http://192.168.3.184:18090" })
    }
    val wan by controller.wanTest.collectAsStateWithLifecycle()

    fun applySettings(updated: StreamSettingsUi) {
        settings = updated
        controller.updateSettings(updated)
    }

    fun submitPin() {
        if (pinText.length in 4..6) {
            pinError = null
            controller.setPin(true, pinText)
        } else {
            pinError = "请输入 4-6 位数字口令"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = CamPushColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭设置",
                    tint = CamPushColors.TextSecondary,
                )
            }
        }

        if (state.streaming || state.starting) {
            InfoBanner(
                text = "推流中修改画质/PIN/端口等设置，将在重启推流后生效。",
                accent = CamPushColors.Warning,
            )
            Spacer(Modifier.height(6.dp))
        }

        // ---------- 画质与性能 ----------
        SettingsSectionTitle("画质与性能")
        Text(
            text = "分辨率",
            style = MaterialTheme.typography.titleSmall,
            color = CamPushColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(
                label = "720p",
                selected = settings.width == 1280 && settings.height == 720,
                onClick = { applySettings(settings.copy(width = 1280, height = 720)) },
            )
            ChoiceChip(
                label = "1080p",
                selected = settings.width == 1920 && settings.height == 1080,
                onClick = {
                    applySettings(
                        settings.copy(
                            width = 1920,
                            height = 1080,
                            bitrateKbps = maxOf(settings.bitrateKbps, 12000),
                        )
                    )
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "1080p 建议码率 8000–12000 kbps（越高越清晰）；需要稳定 30fps 可选 720p。实际编码分辨率显示在主界面。",
            style = MaterialTheme.typography.bodySmall,
            color = CamPushColors.TextTertiary,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "码率",
            style = MaterialTheme.typography.titleSmall,
            color = CamPushColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(listOf(1500, 2500, 4000), listOf(6000, 8000, 10000), listOf(12000)).forEach { rowKbps ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKbps.forEach { kbps ->
                        ChoiceChip(
                            label = "$kbps kbps",
                            selected = settings.bitrateKbps == kbps,
                            onClick = { applySettings(settings.copy(bitrateKbps = kbps)) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "帧率",
            style = MaterialTheme.typography.titleSmall,
            color = CamPushColors.TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24, 30).forEach { fps ->
                ChoiceChip(
                    label = "$fps fps",
                    selected = settings.fps == fps,
                    onClick = { applySettings(settings.copy(fps = fps)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "自适应码率",
            subtitle = "网络波动时自动升降码率",
            checked = settings.adaptiveBitrate,
            onCheckedChange = { applySettings(settings.copy(adaptiveBitrate = it)) },
        )
        SwitchRow(
            title = "省电模式",
            subtitle = "降低分辨率与码率，减少发热与耗电",
            checked = settings.powerSave,
            onCheckedChange = { applySettings(settings.copy(powerSave = it)) },
        )
        SwitchRow(
            title = "采集音频",
            subtitle = "关闭后推流不含声音，可随时用主界面静音按钮切换",
            checked = settings.audioEnabled,
            onCheckedChange = { applySettings(settings.copy(audioEnabled = it)) },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = settings.rtmpUrl,
            onValueChange = { applySettings(settings.copy(rtmpUrl = it.trim())) },
            label = { Text("RTMP 旁路（可选）") },
            placeholder = { Text("rtmp://host/app/stream") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "P2 D-4：开始局域网推流时同时向该 RTMP 地址推送；旁路失败不影响 LAN 观看。",
            style = MaterialTheme.typography.bodySmall,
            color = CamPushColors.TextSecondary,
        )
        if (state.dualCameraSupported) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { controller.openDualPipPreview() }) {
                Text("双摄 PiP 预览（实验）")
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = CamPushColors.Border.copy(alpha = 0.6f),
        )

        // ---------- 局域网设置 ----------
        SettingsSectionTitle("局域网设置")
        SwitchRow(
            title = "观看口令",
            subtitle = "开启后，观看者需输入 4-6 位数字口令",
            checked = pinEnabled,
            onCheckedChange = { checked ->
                pinEnabled = checked
                pinError = null
                if (checked) {
                    if (pinText.length in 4..6) controller.setPin(true, pinText) else pinError = "请输入 4-6 位数字口令"
                } else {
                    controller.setPin(false, null)
                }
            },
        )
        if (pinEnabled) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pinText,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() }.take(6)
                    pinText = filtered
                    if (filtered.length in 4..6) {
                        pinError = null
                        controller.setPin(true, filtered)
                    } else {
                        pinError = "请输入 4-6 位数字口令"
                    }
                },
                label = { Text("观看口令 PIN") },
                singleLine = true,
                isError = pinError != null,
                supportingText = {
                    Text(
                        text = pinError ?: "4-6 位数字，只分享给可信的观看者",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = CamPushColors.Primary,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (pinText.length in 4..6) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { submitPin() }) {
                    Text("应用口令", color = CamPushColors.Primary)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "端侧服务端口",
                    style = MaterialTheme.typography.titleSmall,
                    color = CamPushColors.TextPrimary,
                )
                Text(
                    text = "默认 8089，被占用时在 8089-8099 内顺延；修改后需重启推流",
                    style = MaterialTheme.typography.bodySmall,
                    color = CamPushColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box {
                OutlinedButton(onClick = { portMenuOpen = true }) {
                    Text("${state.port}")
                }
                DropdownMenu(
                    expanded = portMenuOpen,
                    onDismissRequest = { portMenuOpen = false },
                ) {
                    portRange.forEach { port ->
                        DropdownMenuItem(
                            text = { Text("$port") },
                            onClick = {
                                portMenuOpen = false
                                controller.setPort(port)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CamPushColors.Surface.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, CamPushColors.Border.copy(alpha = 0.7f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = "并发观看上限",
                    style = MaterialTheme.typography.titleSmall,
                    color = CamPushColors.TextPrimary,
                )
                Text(
                    text = "最多 ${state.maxViewers} 人同时观看；超出上限的观看者会被拒绝接入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CamPushColors.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = CamPushColors.Surface.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, CamPushColors.Border.copy(alpha = 0.7f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = CamPushColors.Warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AP 隔离排查清单",
                            style = MaterialTheme.typography.titleSmall,
                            color = CamPushColors.TextPrimary,
                        )
                        Text(
                            text = "观看设备打不开地址时，按清单排查",
                            style = MaterialTheme.typography.bodySmall,
                            color = CamPushColors.TextTertiary,
                        )
                    }
                    TextButton(onClick = { checklistOpen = !checklistOpen }) {
                        Icon(
                            imageVector = if (checklistOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (checklistOpen) "收起排查清单" else "展开排查清单",
                            tint = CamPushColors.TextSecondary,
                        )
                    }
                }
                if (checklistOpen) {
                    val checklist = apIsolationChecklist(state.port)
                    checklist.forEachIndexed { index, item ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CamPushColors.Primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                color = CamPushColors.TextSecondary,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            context.copyChecklistToClipboard(
                                checklist.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n"),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = CamPushColors.Primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("复制清单", color = CamPushColors.Primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = CamPushColors.Border.copy(alpha = 0.6f),
        )

        // ---------- WAN WHIP 联调（P2 T0-A2 原型） ----------
        SettingsSectionTitle("WAN 房间联调（P2）")
        Text(
            text = "cloud-core 创建房间 → 取 ingest.whipUrl → WHIP 推流。模拟器用 10.0.2.2:18090；手机用 Mac 局域网 IP。",
            style = MaterialTheme.typography.bodySmall,
            color = CamPushColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = wanApi,
            onValueChange = {
                wanApi = it
                controller.setCloudApiHost(it)
            },
            label = { Text("Cloud API") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { controller.startWanRoom(wanApi) },
                enabled = !wan.running,
            ) { Text("创建房间并推流") }
            OutlinedButton(
                onClick = { controller.stopWanRoom() },
                enabled = wan.running,
            ) { Text("停止") }
            OutlinedButton(
                onClick = { controller.openAudience() },
                enabled = wanApi.isNotBlank(),
            ) { Text("观众端") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                if (wan.roomId != null) append("房间：${wan.roomId}\n")
                if (wan.playUrl != null) append("播放：${wan.playUrl}\n")
                if (wan.error != null) append("失败：${wan.error}") else append("状态：${wan.status}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (wan.error != null) MaterialTheme.colorScheme.error else CamPushColors.TextSecondary,
        )
        if (wan.playUrl != null) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.copyPlayUrlToClipboard(wan.playUrl!!)
                    Toast.makeText(context, "已复制播放地址", Toast.LENGTH_SHORT).show()
                }) { Text("复制播放地址") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, wan.playUrl)
                    }
                    context.startActivity(Intent.createChooser(send, "分享播放地址"))
                }) { Text("分享") }
            }
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = CamPushColors.Border.copy(alpha = 0.6f))

        // ---------- 诊断日志服务 ----------
        SettingsSectionTitle("诊断日志")
        Text(
            text = "应用打开即开始记录，无需开启推流。同一 Wi-Fi 下用电脑浏览器打开下面的地址，即可下载全部日志。",
            style = MaterialTheme.typography.bodySmall,
            color = CamPushColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        val logUrl = state.logServiceUrl
        if (logUrl != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CamPushColors.Surface,
                border = BorderStroke(1.dp, CamPushColors.Border),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            text = logUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = CamPushColors.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "日志服务运行中",
                            style = MaterialTheme.typography.labelSmall,
                            color = CamPushColors.Primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            context.copyPlayUrlToClipboard(logUrl)
                            logCopied = true
                        }) {
                            Text(if (logCopied) "已复制" else "复制地址")
                        }
                    }
                }
            }
        } else {
            Text(
                text = if (state.logServiceRunning) "日志服务启动中…" else "连接 Wi-Fi 后自动启动日志服务（无需推流）。",
                style = MaterialTheme.typography.bodySmall,
                color = CamPushColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            val text = controller.exportDiagnostics()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "导出脱敏日志"))
        }) { Text("导出脱敏日志") }
        Text(
            text = "导出前已移除 token / PIN / 明文 roomId。",
            style = MaterialTheme.typography.bodySmall,
            color = CamPushColors.TextTertiary,
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = CamPushColors.Border.copy(alpha = 0.6f))

        // ---------- 关于 ----------
        SettingsSectionTitle("关于")
        AboutRow("版本", BuildConfig.VERSION_NAME)
        AboutRow("构建", BuildConfig.GIT_SHA)
        AboutRow("契约基线", BuildInfo.CONTRACT_BASELINE)
        AboutRow("运行模式", "P1 · 局域网推流（公网阶段二开放）")
        AboutRow(
            "双摄能力",
            if (state.dualCameraSupported) {
                "支持（逻辑多摄/并发组合 ${state.concurrentCameraPairs}）"
            } else {
                "不支持（物理 ${state.physicalCameraCount}）"
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "画面只在本机编码，通过局域网端侧服务分发，不上传云端。局域网模式为明文 HTTP，仅限可信网络使用。",
            style = MaterialTheme.typography.bodySmall,
            color = CamPushColors.TextTertiary,
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("完成", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = CamPushColors.Surface,
            labelColor = CamPushColors.TextSecondary,
            selectedContainerColor = CamPushColors.Primary.copy(alpha = 0.18f),
            selectedLabelColor = CamPushColors.Primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = CamPushColors.Border,
            selectedBorderColor = CamPushColors.Primary.copy(alpha = 0.6f),
        ),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = CamPushColors.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CamPushColors.TextTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = CamPushColors.TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = CamPushColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun apIsolationChecklist(port: Int): List<String> = listOf(
    "观看设备与本机连接的是同一个 Wi-Fi（SSID 完全一致，含 5G/2.4G 区分）。",
    "路由器管理页关闭“AP 隔离 / 客户端隔离 / 访客网络隔离”。",
    "观看设备关闭 VPN、代理、私有 DNS 相关工具。",
    "本机未开启“限制后台流量/省电模式”导致前台服务被冻结。",
    "路由器防火墙未拦截 $port 端口的局域网入站。",
    "换一台观看设备（另一部手机/电脑）复测，排除单设备问题。",
    "若仍失败：建议切换公网模式（阶段二开放）。",
)
