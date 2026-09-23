package com.example.babacontrol.grandma

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GrandmaScreenReader : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        walk(root, sb, 0)
        return sb.toString().trim().take(4000)
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 18) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = when {
            text.isNotBlank() -> text
            desc.isNotBlank() -> desc
            else -> ""
        }
        if (label.isNotBlank()) {
            val marks = mutableListOf<String>()
            if (node.isClickable) marks.add("кнопка")
            if (node.isEditable) marks.add("поле ввода")
            if (node.isCheckable) marks.add("переключатель")
            val m = if (marks.isEmpty()) "" else " [${marks.joinToString(", ")}]"
            sb.append("• ").append(label).append(m).append("\n")
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), sb, depth + 1)
        }
    }

    companion object {
        @Volatile
        var instance: GrandmaScreenReader? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun readCurrentScreen(): String {
            return try { instance?.readScreen().orEmpty() } catch (_: Exception) { "" }
        }
    }
}