package com.iredox.aisidebar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    secondary = Color(0xFF0F766E),
    tertiary = Color(0xFFB45309)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC7C4FF),
    secondary = Color(0xFF7DDBD0),
    tertiary = Color(0xFFFFB870)
)

@Composable
fun AISidebarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
