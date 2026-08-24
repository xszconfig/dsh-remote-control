package com.daniel.dshremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DshColors = darkColorScheme(
    primary = Color(0xFF6E9BFF),
    onPrimary = Color(0xFF0B0F1A),
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
val StatusGray = Color(0xFF6B7280)
val StatusAmber = Color(0xFFF2C14E)
val StatusOrange = Color(0xFFFFA94D)

@Composable
fun DshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DshColors, content = content)
}
