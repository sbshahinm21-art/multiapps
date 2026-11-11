package com.multiapps.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Accent = Color(0xFF6C8CFF)
val Accent2 = Color(0xFF9B6CFF)
val Ok = Color(0xFF3CCF91)
val Warn = Color(0xFFF5B84A)
val Bad = Color(0xFFFF6B6B)

/** Colors for clone badges, chosen per clone. */
val CloneColors = listOf(
    Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFF10B981), Color(0xFFF59E0B),
    Color(0xFFEC4899), Color(0xFF06B6D4), Color(0xFFEF4444), Color(0xFF84CC16),
)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    onSecondary = Color.White,
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF181C25),
    onSurfaceVariant = Color(0xFF8B92A5),
    surfaceContainer = Color(0xFF12151C),
    surfaceContainerHigh = Color(0xFF181C25),
    surfaceContainerHighest = Color(0xFF1E2330),
    outline = Color(0xFF2A3040),
    outlineVariant = Color(0xFF252A36),
    error = Bad,
)

@Composable
fun MultiAppsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
