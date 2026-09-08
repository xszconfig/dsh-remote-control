package com.daniel.dshremote

import androidx.compose.ui.graphics.Color

/** 主题模式三态。 */
enum class ThemeMode { FollowSystem, Light, Dark }

/** 由主题模式 + 系统深浅解析最终是否深色（纯函数，commonTest 直测）。 */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.FollowSystem -> systemDark
}

/**
 * 自定义语义色 token（Material 槽位之外的品牌/状态/代码块/diff/日志色），浅/深两套。
 * 浅色下 Status/Accent/Diff/Log 一律「加深」保证白底可读（对比度 ≥4.5:1）。
 */
data class ColorTokens(
    val statusGreen: Color,
    val statusGray: Color,
    val statusAmber: Color,
    val statusOrange: Color,
    val accentBlue: Color,
    val deepSeekBlue: Color,
    val markdownCodeBg: Color,
    val markdownCodeFg: Color,
    val diffBg: Color,
    val diffContextFg: Color,
    val diffDelBg: Color,
    val diffDelFg: Color,
    val diffAddBg: Color,
    val diffAddFg: Color,
    val logDebug: Color,
    val logInfo: Color,
    val logWarn: Color,
    val logError: Color,
    val approveCmdGreen: Color,
) {
    companion object {
        val Dark = ColorTokens(
            statusGreen = Color(0xFF34D399),
            statusGray = Color(0xFF8A93A6),
            statusAmber = Color(0xFFF2C14E),
            statusOrange = Color(0xFFFFA94D),
            accentBlue = Color(0xFF9DB8FF),
            deepSeekBlue = Color(0xFF4D6BFE),
            markdownCodeBg = Color(0xFF14181F),
            markdownCodeFg = Color(0xFFDCE4EF),
            diffBg = Color(0xFF14181F),
            diffContextFg = Color(0xFFDCE4EF),
            diffDelBg = Color(0xFF3B1D24),
            diffDelFg = Color(0xFFFF9AA2),
            diffAddBg = Color(0xFF17321F),
            diffAddFg = Color(0xFF7EE787),
            logDebug = Color(0xFF8B93A7),
            logInfo = Color(0xFF6E9BFF),
            logWarn = Color(0xFFF2C14E),
            logError = Color(0xFFFF6B6B),
            approveCmdGreen = Color(0xFFB8E6B8),
        )

        val Light = ColorTokens(
            statusGreen = Color(0xFF10B981),
            statusGray = Color(0xFF6B7280),
            statusAmber = Color(0xFFB45309),
            statusOrange = Color(0xFFEA580C),
            accentBlue = Color(0xFF2563EB),
            deepSeekBlue = Color(0xFF3057D5),
            markdownCodeBg = Color(0xFFF6F8FA),
            markdownCodeFg = Color(0xFF24292E),
            diffBg = Color(0xFFF8F9FC),
            diffContextFg = Color(0xFF24292E),
            diffDelBg = Color(0xFFFFEBEE),
            diffDelFg = Color(0xFFB71C1C),
            diffAddBg = Color(0xFFE8F5E9),
            diffAddFg = Color(0xFF1B5E20),
            logDebug = Color(0xFF6B7280),
            logInfo = Color(0xFF2563EB),
            logWarn = Color(0xFFB45309),
            logError = Color(0xFFB3261E),
            approveCmdGreen = Color(0xFF1B5E20),
        )
    }
}
