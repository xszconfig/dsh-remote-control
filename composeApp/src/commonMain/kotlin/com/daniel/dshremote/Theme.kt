package com.daniel.dshremote

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 深色主题。配色对比度原则见 docs/ui-contrast-guidelines.md：
 * - 深背景一律配浅色前景（Material3 的 MaterialTheme 不提供 LocalContentColor，
 *   默认是纯黑——不在 DshTheme 里兜底会让所有「没显式写颜色」的文本变黑字）。
 * - primary 用深蓝 + 纯白 onPrimary（按钮/选中 chip/纸飞机图标都高对比）；
 * - 深背景上的小号强调文字用 [AccentBlue]（浅蓝），不要用 primary（对比不足）。
 */
private val DshColors = darkColorScheme(
    primary = Color(0xFF3057D5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2A3C66),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF3DD6C3),
    onSecondary = Color(0xFF06211D),
    secondaryContainer = Color(0xFF17352F),
    onSecondaryContainer = Color(0xFFB3F2E8),
    tertiary = Color(0xFFF2C14E),
    onTertiary = Color(0xFF2A2005),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0B0B),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFFD9D9),
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF151B2C),
    onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1E2638),
    onSurfaceVariant = Color(0xFF9AA3B8),
    outline = Color(0xFF2A3348),
    outlineVariant = Color(0xFF232C40),
)

val StatusGreen = Color(0xFF34D399)
/** 既做状态点又做小字文字色，需在深底上可读。 */
val StatusGray = Color(0xFF8A93A6)
val StatusAmber = Color(0xFFF2C14E)
val StatusOrange = Color(0xFFFFA94D)

/** 深色背景上的浅色强调文字（小号标签/链接类，primary 太深对比不足时用这个）。 */
val AccentBlue = Color(0xFF9DB8FF)

/** DeepSeek 品牌蓝：Deep Diving 指示条等品牌元素专用（用户指定与 DSH Web 对齐）。 */
val DeepSeekBlue = Color(0xFF4D6BFE)

@Composable
fun DshTheme(content: @Composable () -> Unit) {
    // 全局兜底默认内容色 = onBackground（浅色）：
    // 未被 Surface/Card/Button 包裹、且未显式指定颜色的文本都以此渲染，
    // 避免 Material3 LocalContentColor 默认纯黑造成的「深底黑字」。
    CompositionLocalProvider(LocalContentColor provides DshColors.onBackground) {
        MaterialTheme(colorScheme = DshColors, content = content)
    }
}
