package com.androidclaw.app.task

import android.graphics.Rect
import android.util.Log
import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.NodeFinder
import com.androidclaw.app.log.LogManager

/**
 * 专门用来验证微信朋友圈的元素获取能力
 */
class WechatElementVerifyTask : TaskScript {

    override val name = "微信朋友圈元素获取验证"
    override val description = "直接通过坐标进入朋友圈并获取节点树打印到Logcat"
    override var configuredAdDurationMs: Long = 0L
    
    private val TAG = "WechatVerifyTask"

    override suspend fun execute(engine: AutomationEngine): Boolean {
        try {
            logBoth("🚀 开始执行微信朋友圈元素验证任务", LogManager.Level.INFO)
            
            // 1. 打开微信
            logBoth("正在启动微信...", LogManager.Level.INFO)
            if (!engine.launchApp("com.tencent.mm")) {
                logBoth("启动微信失败", LogManager.Level.ERROR)
                return false
            }
            engine.sleep(5000) // 等待微信完全启动

            // 2. 按照图片坐标点击“发现”
            logBoth("点击发现坐标: (845.0, 2649.0)", LogManager.Level.INFO)
            engine.clickAt(845f, 2649f)
            engine.sleep(2000)

            // 3. 按照图片坐标点击“朋友圈”
            logBoth("点击朋友圈坐标: (738.0, 396.0)", LogManager.Level.INFO)
            engine.clickAt(738f, 396f)
            engine.sleep(6000) // 等待朋友圈加载并拉取数据

            // 4. 获取当前页面的所有节点
            logBoth("开始获取当前页面的所有节点树(Active Window)...", LogManager.Level.INFO)
            val nodes = NodeFinder.findAll()
            logBoth("Active Window 共获取到 ${nodes.size} 个节点", LogManager.Level.INFO)

            val rect = Rect()
            for ((index, node) in nodes.withIndex()) {
                node.getBoundsInScreen(rect)
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val className = node.className?.toString() ?: ""
                val id = node.viewIdResourceName ?: ""
                Log.i(TAG, "节点[$index]: class=$className, text=$text, desc=$desc, id=$id, bounds=${rect.toShortString()}")
            }

            // 补充逻辑：遍历所有可见的 Windows，因为 Active Window 有可能是某个透明悬浮窗导致为空
            logBoth("开始遍历所有的 Windows 树...", LogManager.Level.INFO)
            val service = com.androidclaw.app.service.ClawAccessibilityService.instance
            if (service != null) {
                val windows = service.windows
                logBoth("系统当前共有 ${windows.size} 个 Window", LogManager.Level.INFO)
                for ((wIndex, window) in windows.withIndex()) {
                    val root = window.root
                    val windowType = window.type
                    val title = window.title ?: "null"
                    logBoth("Window[$wIndex] type=$windowType, title=$title, root_is_null=${root == null}", LogManager.Level.INFO)
                    if (root != null) {
                        val wNodes = NodeFinder.findAll(root)
                        logBoth(" -> Window[$wIndex] 解析到 ${wNodes.size} 个节点", LogManager.Level.INFO)
                        // 打印前几个节点看看
                        for ((nIndex, n) in wNodes.take(15).withIndex()) {
                            n.getBoundsInScreen(rect)
                            Log.i(TAG, "  w[$wIndex]节点[$nIndex]: class=${n.className}, text=${n.text}, desc=${n.contentDescription}, bounds=${rect.toShortString()}")
                        }
                    }
                }
            } else {
                logBoth("无法获取 ClawAccessibilityService 实例", LogManager.Level.ERROR)
            }

            logBoth("✅ 微信朋友圈元素获取验证任务执行完毕，请检查Logcat中TAG为 WechatVerifyTask 的输出", LogManager.Level.SUCCESS)
            return true
        } catch (e: Exception) {
            logBoth("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
            Log.e(TAG, "任务执行异常", e)
            return false
        }
    }

    private fun logBoth(message: String, level: LogManager.Level) {
        // 同时输出到App内部日志和Logcat
        LogManager.log("[Verify] $message", level)
        when (level) {
            LogManager.Level.ERROR -> Log.e(TAG, message)
            LogManager.Level.WARN -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
    }
}
