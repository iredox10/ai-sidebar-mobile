package com.iredox.aisidebar.screen

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service contract for the screen-context phase. It deliberately does not collect
 * content on events; [captureVisibleText] must be invoked as the result of a user action.
 */
class ScreenReadAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

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
}

data class ScreenContext(val packageName: String?, val visibleText: String)
