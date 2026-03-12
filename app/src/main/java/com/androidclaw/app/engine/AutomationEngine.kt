package com.androidclaw.app.engine

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.androidclaw.app.log.LogManager
import com.androidclaw.app.service.ClawAccessibilityService
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 自动化执行引擎
 * 封装常用操作为挂起函数，供任务脚本调用
 */
class AutomationEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutomationEngine"
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_POLL_INTERVAL_MS = 500L
    }

    private val service: ClawAccessibilityService?
        get() = ClawAccessibilityService.instance

    @Volatile
    var isCancelled = false
        private set

    fun cancel() {
        isCancelled = true
        LogManager.log("任务已被取消", LogManager.Level.WARN)
    }

    fun reset() {
        isCancelled = false
    }

    /**
     * 检查引擎是否可用（无障碍服务已启动）
     */
    fun isReady(): Boolean = service != null

    // ============ 启动 App ============

    /**
     * 通过包名启动应用
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
                LogManager.log("启动应用: $packageName", LogManager.Level.INFO)
                true
            } else {
                LogManager.log("未找到应用: $packageName", LogManager.Level.ERROR)
                false
            }
        } catch (e: Exception) {
            LogManager.log("启动应用失败: ${e.message}", LogManager.Level.ERROR)
            false
        }
    }

    // ============ 等待元素 ============

    /**
     * 等待包含指定文本的节点出现
     */
    suspend fun waitForText(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): AccessibilityNodeInfo? {
        return waitForCondition(timeoutMs, pollIntervalMs) {
            NodeFinder.findByText(text).firstOrNull()
        }
    }

    /**
     * 等待包含指定文本（模糊匹配）的节点出现
     */
    suspend fun waitForTextContains(
        text: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): AccessibilityNodeInfo? {
        return waitForCondition(timeoutMs, pollIntervalMs) {
            NodeFinder.findByTextContains(text).firstOrNull()
        }
    }

    /**
     * 等待指定 viewId 的节点出现
     */
    suspend fun waitForViewId(
        viewId: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): AccessibilityNodeInfo? {
        return waitForCondition(timeoutMs, pollIntervalMs) {
            NodeFinder.findByViewId(viewId).firstOrNull()
        }
    }

    /**
     * 等待指定描述的节点出现
     */
    suspend fun waitForDescription(
        desc: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
    ): AccessibilityNodeInfo? {
        return waitForCondition(timeoutMs, pollIntervalMs) {
            NodeFinder.findByDescription(desc).firstOrNull()
        }
    }

    /**
     * 等待指定包名的应用进入前台
     */
    suspend fun waitForApp(
        packageName: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Boolean {
        val result = waitForCondition(timeoutMs, DEFAULT_POLL_INTERVAL_MS) {
            val rootPkg = service?.getRootNode()?.packageName?.toString()
            val currentPkg = service?.currentPackageName
            if (rootPkg == packageName || currentPkg == packageName) true else null
        }
        return result != null
    }

    /**
     * 通用等待条件
     */
    private suspend fun <T> waitForCondition(
        timeoutMs: Long,
        pollIntervalMs: Long,
        condition: () -> T?
    ): T? {
        val startTime = System.currentTimeMillis()
        while (!isCancelled) {
            val result = condition()
            if (result != null) return result
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return null
            }
            delay(pollIntervalMs)
        }
        return null
    }

    // ============ 点击操作 ============

    /**
     * 点击包含指定文本的节点
     */
    suspend fun clickText(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        LogManager.log("查找并点击文本: \"$text\"", LogManager.Level.INFO)
        val node = waitForText(text, timeoutMs) ?: run {
            LogManager.log("未找到文本: \"$text\"", LogManager.Level.WARN)
            return false
        }
        return clickNode(node)
    }

    /**
     * 点击包含指定文本（模糊）的节点
     */
    suspend fun clickTextContains(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        LogManager.log("查找并点击包含文本: \"$text\"", LogManager.Level.INFO)
        val node = waitForTextContains(text, timeoutMs) ?: run {
            LogManager.log("未找到包含文本: \"$text\"", LogManager.Level.WARN)
            return false
        }
        return clickNode(node)
    }

    /**
     * 点击指定 viewId 的节点
     */
    suspend fun clickViewId(viewId: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        LogManager.log("查找并点击 ViewId: $viewId", LogManager.Level.INFO)
        val node = waitForViewId(viewId, timeoutMs) ?: run {
            LogManager.log("未找到 ViewId: $viewId", LogManager.Level.WARN)
            return false
        }
        return clickNode(node)
    }

    /**
     * 点击指定描述的节点
     */
    suspend fun clickDescription(desc: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        LogManager.log("查找并点击描述: \"$desc\"", LogManager.Level.INFO)
        val node = waitForDescription(desc, timeoutMs) ?: run {
            LogManager.log("未找到描述: \"$desc\"", LogManager.Level.WARN)
            return false
        }
        return clickNode(node)
    }

    /**
     * 点击节点（先尝试 performAction，再尝试坐标点击）
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 方式1: 直接点击
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                LogManager.log("节点点击成功 (ACTION_CLICK)", LogManager.Level.INFO)
                return true
            }
        }

        // 方式2: 查找可点击的父节点
        val clickableParent = NodeFinder.findClickableParent(node)
        if (clickableParent != null) {
            val result = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                LogManager.log("父节点点击成功 (ACTION_CLICK)", LogManager.Level.INFO)
                return true
            }
        }

        // 方式3: 坐标点击
        return clickNodeByCoordinate(node)
    }

    /**
     * 通过坐标点击节点中心位置
     */
    fun clickNodeByCoordinate(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        LogManager.log("坐标点击: ($x, $y)", LogManager.Level.INFO)
        service?.clickAt(x, y) ?: return false
        return true
    }

    /**
     * 点击屏幕坐标
     */
    suspend fun clickAt(x: Float, y: Float): Boolean {
        LogManager.log("点击坐标: ($x, $y)", LogManager.Level.INFO)
        return suspendCoroutine { cont ->
            service?.clickAt(x, y) { success ->
                cont.resume(success)
            } ?: cont.resume(false)
        }
    }

    // ============ 滑动操作 ============

    /**
     * 向下滑动一屏
     */
    suspend fun scrollDown(duration: Long = 500): Boolean {
        val svc = service ?: return false
        val (width, height) = svc.getScreenSize()
        val startX = width / 2f
        val startY = height * 0.75f
        val endY = height * 0.25f

        LogManager.log("向下滑动", LogManager.Level.INFO)
        return suspendCoroutine { cont ->
            svc.swipe(startX, startY, startX, endY, duration) { success ->
                cont.resume(success)
            }
        }
    }

    /**
     * 向上滑动一屏
     */
    suspend fun scrollUp(duration: Long = 500): Boolean {
        val svc = service ?: return false
        val (width, height) = svc.getScreenSize()
        val startX = width / 2f
        val startY = height * 0.25f
        val endY = height * 0.75f

        LogManager.log("向上滑动", LogManager.Level.INFO)
        return suspendCoroutine { cont ->
            svc.swipe(startX, startY, startX, endY, duration) { success ->
                cont.resume(success)
            }
        }
    }

    /**
     * 自定义滑动
     */
    suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 500
    ): Boolean {
        return suspendCoroutine { cont ->
            service?.swipe(startX, startY, endX, endY, duration) { success ->
                cont.resume(success)
            } ?: cont.resume(false)
        }
    }

    // ============ 导航操作 ============

    /**
     * 按返回键
     */
    fun goBack(): Boolean {
        LogManager.log("按返回键", LogManager.Level.INFO)
        return service?.pressBack() ?: false
    }

    /**
     * 按 Home 键
     */
    fun goHome(): Boolean {
        LogManager.log("按 Home 键", LogManager.Level.INFO)
        return service?.pressHome() ?: false
    }

    // ============ 工具方法 ============

    /**
     * 延迟等待
     */
    suspend fun sleep(ms: Long) {
        if (!isCancelled) {
            delay(ms)
        }
    }

    /**
     * 获取当前前台包名
     */
    fun getCurrentPackage(): String {
        return service?.getRootNode()?.packageName?.toString() ?: service?.currentPackageName ?: ""
    }

    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> {
        return service?.getScreenSize() ?: Pair(1080, 1920)
    }

    /**
     * 打印当前节点树（调试）
     */
    fun dumpCurrentTree(): String {
        val root = service?.getRootNode() ?: return "无法获取根节点"
        return NodeFinder.dumpNodeTree(root)
    }
}
