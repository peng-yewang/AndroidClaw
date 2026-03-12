package com.androidclaw.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.androidclaw.app.log.LogManager

/**
 * 核心无障碍服务
 * 提供对所有 App 的 UI 树访问和手势操作能力
 */
class ClawAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClawAccessibility"

        @Volatile
        var instance: ClawAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    // 当前前台应用包名
    var currentPackageName: String = ""
        private set

    // 当前窗口类名
    var currentClassName: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
        LogManager.log("无障碍服务已连接", LogManager.Level.SUCCESS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { pkg ->
                    currentPackageName = pkg
                }
                event.className?.toString()?.let { cls ->
                    currentClassName = cls
                }
            }
            else -> { /* 其他事件暂不处理 */ }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        LogManager.log("无障碍服务已断开", LogManager.Level.WARN)
    }

    // ============ 核心能力 ============

    /**
     * 获取当前屏幕的根节点
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "获取根节点失败: ${e.message}")
            null
        }
    }

    /**
     * 点击屏幕指定坐标
     */
    fun clickAt(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * 滑动手势
     */
    fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 500,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * 按返回键
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 按 Home 键
     */
    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 打开最近任务
     */
    fun openRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * 获取屏幕尺寸（通过 resources）
     */
    fun getScreenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }
}
