package com.daniel.dshremote

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThemeTest {

    @Test
    fun resolveDarkTheme_allSixCombinations() {
        assertEquals(false, resolveDarkTheme(ThemeMode.Light, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.Light, systemDark = false))
        assertEquals(true, resolveDarkTheme(ThemeMode.Dark, systemDark = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.Dark, systemDark = false))
        assertEquals(true, resolveDarkTheme(ThemeMode.FollowSystem, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.FollowSystem, systemDark = false))
    }

    @Test
    fun colorTokens_lightAndDark_differForKeyTokens() {
        assertNotEquals(ColorTokens.Light.accentBlue, ColorTokens.Dark.accentBlue)
        assertNotEquals(ColorTokens.Light.markdownCodeBg, ColorTokens.Dark.markdownCodeBg)
        assertNotEquals(ColorTokens.Light.statusGreen, ColorTokens.Dark.statusGreen)
    }

    private fun luminance(c: Color): Double {
        fun srgb(x: Float): Double = if (x <= 0.03928f) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        return 0.2126 * srgb(c.red) + 0.7152 * srgb(c.green) + 0.0722 * srgb(c.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    @Test
    fun contrast_onSurface_vs_surface_bothThemes() {
        assertTrue(contrastRatio(ColorTokens.Dark.statusGreen, Color(0xFF151B2C)) > 2.5)
        // 深底正文对比由 Material 槽位保证；这里抽检语义 token 与典型底色的可读性
        val darkBg = Color(0xFF151B2C)
        val lightBg = Color(0xFFFFFFFF)
        assertTrue(contrastRatio(ColorTokens.Dark.accentBlue, darkBg) > 2.5)
        assertTrue(contrastRatio(ColorTokens.Light.accentBlue, lightBg) > 3.0)
    }
}
