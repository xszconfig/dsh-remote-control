package com.daniel.dshremote

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 浅/深两套主题（对齐 DSH Web）。配色对比度原则见 docs/ui-contrast-guidelines.md：
 * - 全局兜底默认内容色 = onBackground，避免 Material3 LocalContentColor 默认纯黑
 *   在深底上产生「黑字」。
 * - 自定义语义色（Status/Accent/DeepSeek/Markdown/Diff/Log）经 [LocalColorTokens] 注入，
 *   与 Material 槽位同源切换。
 */

private val DshDarkColors = darkColorScheme(
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

private val DshLightColors = lightColorScheme(
    primary = Color(0xFF3057D5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF1B2B5C),
    secondary = Color(0xFF0F7B6C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB3F2E8),
    onSecondaryContainer = Color(0xFF06211D),
    tertiary = Color(0xFF7A5B00),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1F2B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1F2B),
    surfaceVariant = Color(0xFFEAEEF6),
    onSurfaceVariant = Color(0xFF4A5468),
    outline = Color(0xFFC7CFDD),
    outlineVariant = Color(0xFFD7DEEA),
)

/** 自定义语义色 token 注入点（DshTheme 根据深浅提供 Light/Dark 实例）。 */
val LocalColorTokens = staticCompositionLocalOf { ColorTokens.Dark }

// 兼容旧调用点：这些顶层常量改为「读 LocalColorTokens」的 @Composable getter，
// 使 App.kt/MessageDial.kt/DiffView.kt 现有引用零改动即可随主题切换。
val StatusGreen: Color @Composable get() = LocalColorTokens.current.statusGreen
val StatusGray: Color @Composable get() = LocalColorTokens.current.statusGray
val StatusAmber: Color @Composable get() = LocalColorTokens.current.statusAmber
val StatusOrange: Color @Composable get() = LocalColorTokens.current.statusOrange
val AccentBlue: Color @Composable get() = LocalColorTokens.current.accentBlue
val DeepSeekBlue: Color @Composable get() = LocalColorTokens.current.deepSeekBlue
val MarkdownCodeBg: Color @Composable get() = LocalColorTokens.current.markdownCodeBg
val MarkdownCodeFg: Color @Composable get() = LocalColorTokens.current.markdownCodeFg

@Composable
fun DshTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val scheme = if (darkTheme) DshDarkColors else DshLightColors
    // 同步系统栏图标色 + 窗口背景到当前主题，消除浅色模式下状态栏区域的深色残留。
    SystemBarsSync(darkTheme)
    CompositionLocalProvider(
        LocalColorTokens provides if (darkTheme) ColorTokens.Dark else ColorTokens.Light,
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            MaterialTheme(colorScheme = scheme, content = content)
        }
    }
}

/** 平台层同步系统栏外观（expect/actual；Android 实现见 Platform.android.kt）。 */
@Composable
expect fun SystemBarsSync(darkTheme: Boolean)
