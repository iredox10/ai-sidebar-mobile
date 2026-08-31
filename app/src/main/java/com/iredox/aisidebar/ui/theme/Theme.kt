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
    primary = Color(0xFF18181B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAEAEC),
    onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF52525B),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFA1A1AA),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0x26121212), // rgba(0,0,0,0.15) approx
    outlineVariant = Color(0x14000000), // 0.08
    surfaceContainerHighest = Color(0xFFEAEAEC)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF121212),
    tertiary = Color(0xFF71717A),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0x26FFFFFF), // 0.15
    outlineVariant = Color(0x14FFFFFF), // 0.08
    surfaceContainerHighest = Color(0xFF1A1A1A)
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
