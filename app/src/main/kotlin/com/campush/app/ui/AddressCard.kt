package com.campush.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campush.app.core.P1UiState
import com.campush.app.ui.theme.CamPushColors

/**
 * 推流成功后的悬浮地址卡：唯一播放地址、复制、二维码、本机预览、分享、PIN 与局域网提示。
 * 可折叠，折叠时保留地址与展开入口。
 */
@Composable
fun AddressCard(
    state: P1UiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopy: () -> Unit,
    onQr: () -> Unit,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val playUrl = state.playUrl ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CamPushColors.Surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, CamPushColors.Border),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 9.dp else 12.dp,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = CamPushColors.Primary.copy(alpha = 0.16f)) {
                    Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = null,
                            tint = CamPushColors.Primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "唯一播放地址",
                            style = MaterialTheme.typography.titleSmall,
                            color = CamPushColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(6.dp))
                        StatusPill(
                            text = "仅局域网",
                            containerColor = CamPushColors.Primary.copy(alpha = 0.14f),
                            contentColor = CamPushColors.Primary,
                        )
                    }
                    Text(
                        text = if (expanded) "同一局域网内的设备打开即可观看" else playUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = CamPushColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (expanded) "收起地址卡" else "展开地址卡",
                    tint = CamPushColors.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CamPushColors.Background.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, CamPushColors.Border.copy(alpha = 0.8f)),
                ) {
                    SelectionContainer {
                        Text(
                            text = playUrl,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = CamPushColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = CamPushColors.TextTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "仅同一局域网可打开 · 明文 HTTP，仅限可信网络使用；任何拿到链接的人都能观看。",
                        style = MaterialTheme.typography.labelSmall,
                        color = CamPushColors.TextTertiary,
                    )
                }
                if (state.pinRequired) {
                    Spacer(Modifier.height(8.dp))
                    PinRow(pin = state.pin)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "当前 ${state.viewers}/${state.maxViewers} 人观看 · 最多 ${state.maxViewers} 人同时观看",
                    style = MaterialTheme.typography.labelSmall,
                    color = CamPushColors.TextTertiary,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AddressAction(
                        icon = Icons.Filled.ContentCopy,
                        label = "复制",
                        onClick = onCopy,
                        modifier = Modifier.weight(1f),
                    )
                    AddressAction(
                        icon = Icons.Filled.QrCode2,
                        label = "二维码",
                        onClick = onQr,
                        modifier = Modifier.weight(1f),
                    )
                    AddressAction(
                        icon = Icons.Filled.RemoveRedEye,
                        label = "预览",
                        onClick = onPreview,
                        modifier = Modifier.weight(1f),
                    )
                    AddressAction(
                        icon = Icons.Filled.Share,
                        label = "分享",
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PinRow(pin: String?) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CamPushColors.Primary.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, CamPushColors.Primary.copy(alpha = 0.32f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = CamPushColors.Primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "观看口令",
                style = MaterialTheme.typography.labelSmall,
                color = CamPushColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = pin ?: "已启用",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = CamPushColors.Primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AddressAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = CamPushColors.Primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CamPushColors.TextSecondary,
        )
    }
}
