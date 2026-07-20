package com.magictablet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MtDarkColors = darkColorScheme(
    primary = MtPrimary,
    onPrimary = MtOnPrimary,
    background = MtBackground,
    surface = MtSurface,
    onBackground = MtOnBackground,
    onSurface = MtOnSurface,
    outline = MtOutline,
)

@Composable
fun MagicTabletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MtDarkColors,
        typography = MtTypography,
        content = content,
    )
}
