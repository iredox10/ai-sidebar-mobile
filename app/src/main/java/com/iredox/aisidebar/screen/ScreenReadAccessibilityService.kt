package com.iredox.aisidebar.screen

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service contract for the screen-context phase. It deliberately does not collect
 * content on events; [captureVisibleText] must be invoked as the result of a user action.
 */
class ScreenReadAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    fun captureVisibleText(maxCharacters: Int = 8_000): ScreenContext {
        val packageName = rootInActiveWindow?.packageName?.toString()
        val text = buildString { rootInActiveWindow?.appendSafeText(this, maxCharacters) }
        return ScreenContext(packageName = packageName, visibleText = text)
    }

    private fun AccessibilityNodeInfo.appendSafeText(target: StringBuilder, limit: Int) {
        if (target.length >= limit || isPassword || !isVisibleToUser) return
        text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            if (!target.contains(value)) target.append(value).append('\n')
        }
        for (index in 0 until childCount) getChild(index)?.appendSafeText(target, limit)
    }

    companion object {
        @Volatile private var activeService: ScreenReadAccessibilityService? = null

        fun isEnabled(): Boolean = activeService != null

        /** Called only by an explicit user interaction in the app or overlay. */
        fun captureActiveScreen(): ScreenContext? = activeService?.captureVisibleText()
    }
}

data class ScreenContext(val packageName: String?, val visibleText: String)
