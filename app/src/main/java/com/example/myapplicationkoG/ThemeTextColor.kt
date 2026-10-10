package com.example.myapplicationkoG

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Returns [lightModeColor] in light mode and white in dark mode.
 */
@Composable
fun textColorForTheme(lightModeColor: Color): Color {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDarkTheme) Color.White else lightModeColor
}
