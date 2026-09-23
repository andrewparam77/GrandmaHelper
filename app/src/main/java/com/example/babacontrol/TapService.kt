package com.example.babacontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

class TapService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 0, 60)

    fun longPress(x: Float, y: Float, ms: Long = 800): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 0, ms)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, ms)

    private fun gesture(path: Path, start: Long, dur: Long): Boolean = try {
        val stroke = GestureDescription.StrokeDescription(path, start, dur)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(g, null, null)
    } catch (e: Exception) { false }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun screenshot(cb: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    cb(bmp?.copy(Bitmap.Config.ARGB_8888, false))
                }
                override fun onFailure(errorCode: Int) { cb(null) }
            })
    }
}