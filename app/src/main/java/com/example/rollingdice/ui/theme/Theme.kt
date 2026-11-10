package com.example.rollingdice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val MonochromeScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Muted,
    onSecondary = Paper,
    tertiary = Muted,
    onTertiary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = Muted,
    outline = Hairline,
    outlineVariant = Hairline
)

@Composable
fun RollingDiceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MonochromeScheme,
        typography = Typography,
        content = content
    )
}
