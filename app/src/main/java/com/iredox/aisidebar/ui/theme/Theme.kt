package com.iredox.aisidebar.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.iredox.aisidebar.data.ThemeStore

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
fun AISidebarTheme(theme: String? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { ThemeStore(context.applicationContext) }
    val t = theme ?: store.get()
    val dark = when (t) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
