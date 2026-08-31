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

    fun captureVisibleText(maxCharacters: Int = 12_000): ScreenContext {
        val packageName = rootInActiveWindow?.packageName?.toString()
        val seen = mutableSetOf<String>()
        val text = buildString { rootInActiveWindow?.appendSafeText(this, seen, maxCharacters) }
        return ScreenContext(packageName = packageName, visibleText = text.trim().take(maxCharacters))
    }

    private fun AccessibilityNodeInfo.appendSafeText(target: StringBuilder, seen: MutableSet<String>, limit: Int) {
        if (target.length >= limit || isPassword || !isVisibleToUser) return
        // collect primary text or contentDescription or hint
        val candidates = listOfNotNull(
            text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        )
        for (value in candidates) {
            if (value.length < 2 || value.length > 500) continue
            if (seen.add(value)) {
                if (target.isNotEmpty() && !target.endsWith("\n")) target.append(' ')
                target.append(value)
                if (value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) target.append('\n')
                else target.append(' ')
            }
            break // only first non-empty candidate per node to avoid duplication
        }
        for (index in 0 until childCount) {
            if (target.length >= limit) break
            getChild(index)?.appendSafeText(target, seen, limit)
        }
    }

    companion object {
        @Volatile private var activeService: ScreenReadAccessibilityService? = null

        fun isEnabled(): Boolean = activeService != null

        /** Called only by an explicit user interaction in the app or overlay. */
        fun captureActiveScreen(): ScreenContext? = activeService?.captureVisibleText()
    }
}

data class ScreenContext(val packageName: String?, val visibleText: String)
