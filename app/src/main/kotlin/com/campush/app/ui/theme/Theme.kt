package com.campush.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * CamPush P1 品牌色板：深色底 + 品牌绿。
 * 与 app/src/main/res/values/themes.xml 的窗口底色 #101114 保持一致。
 */
object CamPushColors {
    val Background = Color(0xFF101114)
    val Surface = Color(0xFF191B20)
    val SurfaceElevated = Color(0xFF22252C)
    val Border = Color(0xFF2C3038)

    val Primary = Color(0xFF4ADE80)
    val OnPrimary = Color(0xFF07210F)
    val PrimaryContainer = Color(0xFF14361F)
    val OnPrimaryContainer = Color(0xFF9AF0B8)

    val TextPrimary = Color(0xFFF3F5F7)
    val TextSecondary = Color(0xFFA8AFBA)
    val TextTertiary = Color(0xFF7A8290)

    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
    val Info = Color(0xFF7DD3FC)

    /** 覆盖在摄像头预览上的半透明卡片/遮罩底色。 */
    val Scrim = Color(0xE60B0C0E)
}

private val CamPushDarkColorScheme = darkColorScheme(
    primary = CamPushColors.Primary,
    onPrimary = CamPushColors.OnPrimary,
    primaryContainer = CamPushColors.PrimaryContainer,
    onPrimaryContainer = CamPushColors.OnPrimaryContainer,
    secondary = Color(0xFF8BD5B0),
    onSecondary = Color(0xFF06281A),
    secondaryContainer = Color(0xFF173B2A),
    onSecondaryContainer = Color(0xFFB7F2D0),
    tertiary = CamPushColors.Info,
    onTertiary = Color(0xFF04202E),
    background = CamPushColors.Background,
    onBackground = CamPushColors.TextPrimary,
    surface = CamPushColors.Surface,
    onSurface = CamPushColors.TextPrimary,
    surfaceVariant = CamPushColors.SurfaceElevated,
    onSurfaceVariant = CamPushColors.TextSecondary,
    outline = CamPushColors.Border,
    outlineVariant = Color(0xFF3A3F48),
    error = CamPushColors.Danger,
    onError = Color(0xFF2A0B0B),
    errorContainer = Color(0xFF43181A),
    onErrorContainer = Color(0xFFFFD7D4),
    inverseSurface = Color(0xFFE7EAEE),
    inverseOnSurface = Color(0xFF1B1D21),
    scrim = Color(0xFF000000),
)

private val CamPushTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

/** 深色 Material3 主题；预览类界面使用 edge-to-edge，状态栏交给界面自行绘制遮罩。 */
@Composable
fun CamPushTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = CamPushColors.Background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = CamPushDarkColorScheme,
        typography = CamPushTypography,
        content = content,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
