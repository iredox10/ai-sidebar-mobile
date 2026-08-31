package com.iredox.aisidebar.data

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun get(): String = prefs.getString(KEY, "system") ?: "system"
    fun set(theme: String) { prefs.edit().putString(KEY, theme).apply() }
    companion object {
        const val PREFS = "theme_prefs"
        const val KEY = "theme"
        @Composable fun isDark(theme: String): Boolean = when (theme) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
    }
}
