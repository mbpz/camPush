package com.campush.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campush.app.core.AccessScopeUi
import com.campush.app.core.P1UiState
import com.campush.app.ui.theme.CamPushColors

/** 推流状态点：推流中绿色、启动中琥珀色、待机灰。 */
@Composable
fun StatusDot(
    streaming: Boolean,
    starting: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = when {
        streaming -> CamPushColors.Success
        starting -> CamPushColors.Warning
        else -> CamPushColors.TextTertiary
    }
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 顶部小型胶囊标签（局域网 / 网络类型 / 状态说明）。 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = CamPushColors.Surface.copy(alpha = 0.82f),
    contentColor: Color = CamPushColors.TextSecondary,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = BorderStroke(1.dp, CamPushColors.Border.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/** 顶部指标：标签 + 数值。 */
@Composable
fun MetricItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = CamPushColors.TextTertiary)
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = CamPushColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 顶部指标卡：标签 + 数值，轻量半透明底保证在预览画面上可读。 */
@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = CamPushColors.Surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, CamPushColors.Border.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = CamPushColors.TextTertiary,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = CamPushColors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** 指标之间的分隔点。 */
@Composable
fun MetricDivider(modifier: Modifier = Modifier) {
    Text(
        text = "·",
        style = MaterialTheme.typography.labelSmall,
        color = CamPushColors.TextTertiary,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

/**
 * 访问范围分段控件：P2 支持 LAN/WAN 切换；未配置 Cloud API 时 WAN 标注“未配置”。
 * 推流中整体锁定并提示先停止推流。
 */
@Composable
fun AccessScopeSelector(
    state: P1UiState,
    onLanSelected: () -> Unit,
    onWanSelected: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val locked = state.streaming || state.starting
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CamPushColors.Surface.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, CamPushColors.Border.copy(alpha = 0.7f)),
        ) {
            Row(modifier = Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                ScopeSegment(
                    label = "仅局域网",
                    selected = state.accessScope == AccessScopeUi.LAN,
                    enabled = !locked,
                    onClick = onLanSelected,
                )
                Spacer(Modifier.width(2.dp))
                ScopeSegment(
                    label = "公网可访问",
                    badge = when {
                        state.wanLocked -> "未配置"
                        compact -> null
                        else -> "公网"
                    },
                    selected = state.accessScope == AccessScopeUi.WAN,
                    enabled = !locked,
                    onClick = onWanSelected,
                )
            }
        }
        if (locked) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "推流中不可切换 · 请先停止推流后切换访问范围",
                style = MaterialTheme.typography.labelSmall,
                color = CamPushColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun ScopeSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val container = if (selected) CamPushColors.Primary.copy(alpha = 0.16f) else Color.Transparent
    val contentColor = when {
        selected -> CamPushColors.Primary
        enabled -> CamPushColors.TextPrimary
        else -> CamPushColors.TextTertiary
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .selectable(
                selected = selected,
                enabled = true,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = if (badge != null) 10.dp else 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = CamPushColors.SurfaceElevated.copy(alpha = 0.9f),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = CamPushColors.TextTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** 警告条（AP 隔离 / IP 变化 / 并发上限等）。 */
@Composable
fun WarningBanner(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = CamPushColors.Warning.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, CamPushColors.Warning.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = CamPushColors.Warning,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = CamPushColors.Warning,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭提示",
                    tint = CamPushColors.Warning,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 底部操作区的圆形图标按钮。 */
@Composable
fun RoundIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val container = when {
        !enabled -> CamPushColors.Surface.copy(alpha = 0.5f)
        active -> CamPushColors.Primary.copy(alpha = 0.2f)
        else -> CamPushColors.Surface.copy(alpha = 0.88f)
    }
    val borderColor = when {
        active -> CamPushColors.Primary.copy(alpha = 0.65f)
        else -> CamPushColors.Border.copy(alpha = 0.7f)
    }
    val tint = when {
        !enabled -> CamPushColors.TextTertiary
        active -> CamPushColors.Primary
        else -> CamPushColors.TextPrimary
    }
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, borderColor),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 设置页分组标题。 */
@Composable
fun SettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = CamPushColors.Primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 20.dp, bottom = 10.dp),
    )
}

/** 设置项说明文字。 */
@Composable
fun SettingsHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = CamPushColors.TextTertiary,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 内联提示条（信息类，不可关闭）。 */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = CamPushColors.Primary,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
