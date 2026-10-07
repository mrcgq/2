package com.xlink.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ─────────────────────────────────────────────────────────────
// 颜色令牌（与 colors.xml 保持一致，供 Compose 使用）
// ─────────────────────────────────────────────────────────────
object XlinkColors {
    val Primary           = Color(0xFF1A73E8)
    val PrimaryDark       = Color(0xFF1557B0)
    val PrimaryContainer  = Color(0xFFD3E3FD)
    val Secondary         = Color(0xFF00897B)

    // 状态色（日志分级 + 节点状态，与 C 版 TranslateLog 分类对应）
    val StatusRunning     = Color(0xFF2E7D32)
    val StatusStopped     = Color(0xFF757575)
    val StatusStarting    = Color(0xFFF57C00)
    val StatusError       = Color(0xFFC62828)

    val LogError          = Color(0xFFEF5350)
    val LogSuccess        = Color(0xFF66BB6A)
    val LogSystem         = Color(0xFF90A4AE)
    val LogRule           = Color(0xFF42A5F5)
    val LogLb             = Color(0xFFAB47BC)
    val LogDirect         = Color(0xFF26A69A)
    val LogDefault        = Color(0xFFB0BEC5)
}

// ─────────────────────────────────────────────────────────────
// 亮色方案
// ─────────────────────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary          = XlinkColors.Primary,
    onPrimary        = Color.White,
    primaryContainer = XlinkColors.PrimaryContainer,
    secondary        = XlinkColors.Secondary,
    onSecondary      = Color.White,
    background       = Color(0xFFFAFAFA),
    surface          = Color(0xFFFFFFFF),
    surfaceVariant   = Color(0xFFF1F3F4),
    onBackground     = Color(0xFF1C1B1F),
    onSurface        = Color(0xFF1C1B1F),
)

// ─────────────────────────────────────────────────────────────
// 暗色方案
// ─────────────────────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF8AB4F8),
    onPrimary        = Color(0xFF003068),
    primaryContainer = Color(0xFF1557B0),
    secondary        = Color(0xFF80CBC4),
    onSecondary      = Color(0xFF003733),
    background       = Color(0xFF1C1B1F),
    surface          = Color(0xFF1C1B1F),
    surfaceVariant   = Color(0xFF2B2930),
    onBackground     = Color(0xFFE6E1E5),
    onSurface        = Color(0xFFE6E1E5),
)

// ─────────────────────────────────────────────────────────────
// CompositionLocal：让子树可以直接取到语义化颜色
// ─────────────────────────────────────────────────────────────
data class XlinkExtendedColors(
    val statusRunning  : Color,
    val statusStopped  : Color,
    val statusStarting : Color,
    val statusError    : Color,
    val logError       : Color,
    val logSuccess     : Color,
    val logSystem      : Color,
    val logRule        : Color,
    val logLb          : Color,
    val logDirect      : Color,
    val logDefault     : Color,
)

val LocalXlinkColors = staticCompositionLocalOf {
    XlinkExtendedColors(
        statusRunning  = XlinkColors.StatusRunning,
        statusStopped  = XlinkColors.StatusStopped,
        statusStarting = XlinkColors.StatusStarting,
        statusError    = XlinkColors.StatusError,
        logError       = XlinkColors.LogError,
        logSuccess     = XlinkColors.LogSuccess,
        logSystem      = XlinkColors.LogSystem,
        logRule        = XlinkColors.LogRule,
        logLb          = XlinkColors.LogLb,
        logDirect      = XlinkColors.LogDirect,
        logDefault     = XlinkColors.LogDefault,
    )
}

// ─────────────────────────────────────────────────────────────
// 主题入口
// ─────────────────────────────────────────────────────────────
@Composable
fun XlinkTheme(
    darkTheme       : Boolean = isSystemInDarkTheme(),
    dynamicColor    : Boolean = true,               // Android 12+ 动态取色
    content         : @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx)
            else           dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    CompositionLocalProvider(LocalXlinkColors provides LocalXlinkColors.current) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography(),
            content     = content
        )
    }
}

/** 快捷取语义色扩展属性 */
val MaterialTheme.xlinkColors: XlinkExtendedColors
    @Composable get() = LocalXlinkColors.current
