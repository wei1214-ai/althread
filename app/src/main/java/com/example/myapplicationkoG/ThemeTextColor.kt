package com.example.myapplicationkoG

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Keeps the app's current brand colour in light mode and makes text white
 * when the currently selected app theme is dark.
 */
@Composable
fun textColorForTheme(lightModeColor: Color): Color {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDarkTheme) Color.White else lightModeColor
}
