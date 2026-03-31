package com.androidclaw.app.task

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Environment
import com.androidclaw.app.engine.AudioVideoRecorder
import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.MobileNetFingerprintManager
import com.androidclaw.app.engine.ScreenCapturer
import com.androidclaw.app.engine.VideoFingerprintManager
import com.androidclaw.app.engine.VideoTrimmer
import com.androidclaw.app.log.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 腾讯视频视频开屏广告自动巡检与采集任务
 */
class TencentVideoMatchTask : TaskScript {

    override val name = "腾讯视频开屏视频匹配捕获"
    override val description = "循环关闭并打开腾讯视频，利用视频匹配捕捉对应的开屏广告记录"
    override var configuredAdDurationMs: Long = 0L

    var targetVideoTasks: List<VideoTask> = emptyList()
    var recordResultCode: Int = 0
    var recordData: Intent? = null
    var algorithmType: Int = 0
    var enableRotationMatch: Boolean = false

    private fun log(message: String, level: LogManager.Level = LogManager.Level.INFO) {
        LogManager.log("[TxTask] $message", level)
    }

    companion object {
        private const val TENCENT_VIDEO_PACKAGE = "com.tencent.qqlive"
        private const val SPLASH_MONITOR_MS = 15_000L // 每次打开后最多监控 15 秒
        private const val PRE_ROLL_MS = 2_000L
        private const val POST_ROLL_MS = 2_000L

        const val LOG_PREFIX_VIDEO_STATUS = "VIDEO_STATUS|"
        const val LOG_PREFIX_VIDEO_RESULT = "VIDEO_RESULT|"
    }

    private data class AdInterval(val videoId: String, val startMs: Long, val endMs: Long)

    override suspend fun execute(engine: AutomationEngine): Boolean {
        val context = getContext(engine) ?: run {
            log("无法获取 Context", LogManager.Level.ERROR)
            return false
        }

        var sharedProjection: android.media.projection.MediaProjection? = null
        var avRecorder: AudioVideoRecorder? = null
        val capturer = ScreenCapturer(context)
        var tmpFile: File? = null
        var recordStartWallMs = 0L

        val adIntervals = mutableListOf<AdInterval>()

        try {
            log("═══════════════════════════════════", LogManager.Level.INFO)
            log("开始执行: $name", LogManager.Level.SUCCESS)
            log("═══════════════════════════════════", LogManager.Level.INFO)

            if (targetVideoTasks.isEmpty()) {
                log("未提供匹配目标视频，任务中止", LogManager.Level.ERROR)
                return false
            }

            // ===== 准备匹配器 =====
            val fpManager = if (algorithmType == 0) VideoFingerprintManager() else null
            val aiManager = if (algorithmType == 1) MobileNetFingerprintManager(context) else null
            fpManager?.enableRotationMatch = enableRotationMatch
            aiManager?.enableRotationMatch = enableRotationMatch

            for (task in targetVideoTasks) {
                if (algorithmType == 0) {
                    fpManager?.extractFromUri(context, task.id, task.uri, 4, true)
                } else {
                    aiManager?.extractFromUri(context, task.id, task.uri, 1)
                }
            }

            if ((fpManager?.isLoaded != true) && (aiManager?.isLoaded != true)) {
                log("指纹初始化失败，任务终止。", LogManager.Level.ERROR)
                return false
            }

            // ===== 准备环境与录像 =====
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            sharedProjection = pm.getMediaProjection(recordResultCode, recordData!!)
            
            capturer.initScreenMetrics()
            if (!capturer.startWithProjection(sharedProjection)) return false

            tmpFile = createTempFile(context)
            val sw = capturer.screenWidth
            val sh = capturer.screenHeight
            val sd = capturer.screenDensity
            val rw = if (sw % 2 == 0) sw else sw - 1
            val rh = if (sh % 2 == 0) sh else sh - 1

            // 🔴 音视频同步录制器 (内部音频 + 屏幕画面)
            avRecorder = AudioVideoRecorder(context).also {
                it.prepare(sharedProjection!!, rw, rh, sd, tmpFile.absolutePath)
                it.start()
            }
            recordStartWallMs = System.currentTimeMillis()

            // ===== 核心业务循环 =====
            val finishedTasks = mutableSetOf<String>()
            val currentAdStarts = mutableMapOf<String, Long>()
            val lastMatches = mutableMapOf<String, Long>()
            val matchCounts = mutableMapOf<String, Int>()

            var attempts = 0
            log("🎬 正在同时并发监听: 重点攻坚 ${targetVideoTasks.size} 个目标任务...", LogManager.Level.INFO)
            
            while (!engine.isCancelled && finishedTasks.size < targetVideoTasks.size) { 
                attempts++
                log("👉 第 $attempts 次尝试唤起腾讯视频...", LogManager.Level.INFO)
                
                // 1. 关闭腾讯视频
                killApp(engine, TENCENT_VIDEO_PACKAGE)
                engine.sleep(1000)
                
                // 2. 重新启动腾讯视频
                if (!engine.launchApp(TENCENT_VIDEO_PACKAGE)) {
                    engine.sleep(2000)
                    continue
                }
                
                // 3. 监控开屏并比对
                val splashWaitStart = System.currentTimeMillis()
                var loopHasHitTarget = false
                
                // 清理这一轮开屏的独立捕获状态
                currentAdStarts.clear()
                lastMatches.clear()
                matchCounts.clear()

                log("开始监控开屏广告 (超时: ${SPLASH_MONITOR_MS}ms)", LogManager.Level.INFO)
                
                // 只要不超过最大兜底期（针对长视频，如60秒），且还在连续跟进目标（currentAdStarts 有内容），就允许延长监控，防止被 15s 休眠斩断开屏检测
                while ((System.currentTimeMillis() - splashWaitStart < SPLASH_MONITOR_MS || currentAdStarts.isNotEmpty()) && (System.currentTimeMillis() - splashWaitStart < 60000L) && !engine.isCancelled) {
                    val frame = capturer.captureFrame()

                    if (frame != null) {
                        val nowMs = System.currentTimeMillis() - recordStartWallMs
                        val matchedSet = if (algorithmType == 0) {
                            fpManager?.matchScreenshots(frame, finishedTasks) ?: emptyList()
                        } else {
                            aiManager?.matchScreenshots(frame, finishedTasks) ?: emptyList()
                        }

                        // 将所有匹配上的都登记在案
                        matchedSet.forEach { matchedVideoId ->
                            lastMatches[matchedVideoId] = nowMs
                            matchCounts[matchedVideoId] = (matchCounts[matchedVideoId] ?: 0) + 1
                            
                            // 🔴 调试保存
                            try {
                                val debugDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                                val taskDir = java.io.File(debugDir, "Debug_Matched_${matchedVideoId}")
                                if (!taskDir.exists()) taskDir.mkdirs()
                                val frameFile = java.io.File(taskDir, "Matched_${nowMs}ms.jpg")
                                val out = java.io.FileOutputStream(frameFile)
                                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                                out.flush()
                                out.close()
                            } catch (e: Exception) {
                                log("📸 保存命中帧失败: ${e.message}", LogManager.Level.WARN)
                            }

                            if (!currentAdStarts.containsKey(matchedVideoId)) {
                                currentAdStarts[matchedVideoId] = nowMs
                                val task = targetVideoTasks.find { it.id == matchedVideoId }
                                log("🎯 发现疑似目标: ${task?.name} (ID: $matchedVideoId)", LogManager.Level.SUCCESS)
                                LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${matchedVideoId}|PROCESSING", LogManager.Level.INFO)
                            }
                        }
                        frame.recycle()
                        
                        // 动态检查当前的追踪对象并判断是否完成（延迟判定机制）
                        val activeVideoIds = currentAdStarts.keys.toList()
                        val nowTimeForTimeout = System.currentTimeMillis() - recordStartWallMs
                        
                        activeVideoIds.forEach { videoId ->
                            if (!finishedTasks.contains(videoId)) {
                                val start = currentAdStarts[videoId] ?: 0L
                                val lastMatch = lastMatches[videoId] ?: 0L
                                val matchCount = matchCounts[videoId] ?: 0
                                val idleTime = nowTimeForTimeout - lastMatch
                                val matchDuration = lastMatch - start

                                // 只有当超过 5 秒没有新的帧匹配上时，视为这个目标已脱离视线（播放结束或被跳过），此时统一做最终验证！
                                if (idleTime >= 5000L) {
                                    if (matchCount >= 4 && matchDuration >= 3000L) {
                                        val task = targetVideoTasks.find { it.id == videoId }
                                        log("⭐ 成功确认目标广告完整播放！[task: ${task?.name}] (完整匹配段: ${matchDuration / 1000.0}s, $matchCount 帧)", LogManager.Level.SUCCESS)
                                        LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${videoId}|COMPLETED", LogManager.Level.INFO)
                                        
                                        adIntervals.add(AdInterval(videoId, start, lastMatch))
                                        finishedTasks.add(videoId) 
                                        
                                        currentAdStarts.remove(videoId)
                                        lastMatches.remove(videoId)
                                        matchCounts.remove(videoId)
                                        
                                        loopHasHitTarget = true
                                    } else {
                                        log("⚠️ 判定为误报 (任务项 ${videoId} 匹配段仅 ${matchDuration}ms, $matchCount 帧，且随后 5 秒未出现)，清理此波匹配的内部状态", LogManager.Level.WARN)
                                        LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${videoId}|WAITING", LogManager.Level.INFO)
                                        currentAdStarts.remove(videoId)
                                        lastMatches.remove(videoId)
                                        matchCounts.remove(videoId)
                                    }
                                }
                            }
                        }

                        if (loopHasHitTarget) {
                            log("本轮已成功完成了一段完整广告片段的确认及捕获，立即跳出探测闭环，进入下一轮...", LogManager.Level.INFO)
                            break
                        }

                        // 🟢 智能识别“无广告主页”脱离
                        // 约束：必须当前没有任何正在确信追踪的目标 (currentAdStarts.isEmpty())，且必须开启后渡过了至少 10 秒安全期
                        if (currentAdStarts.isEmpty() && (System.currentTimeMillis() - splashWaitStart > 10000L)) {
                            val homeIndicators = listOf("首页", "推荐", "电视剧", "电影", "频道")
                            var foundHome = false
                            for (indicator in homeIndicators) {
                                if (com.androidclaw.app.engine.NodeFinder.findByTextContains(indicator).isNotEmpty()) {
                                    foundHome = true
                                    break
                                }
                            }

                            if (foundHome) {
                                log("检测到已进入主页(开屏阶段已过)，未触发任何目标开屏广告。立即脱离当前循环...", LogManager.Level.WARN)
                                break
                            }
                        }
                    }
                    
                    engine.sleep(300)
                }

                if (!loopHasHitTarget) {
                    log("未命中任何尚未达标的目标广告。准备销毁进入下一轮...", LogManager.Level.WARN)
                }
            }
            
            // ============== 对于由于取消或无法触达而失败的任务打上失败标签 ==============
            val failedTasks = targetVideoTasks.filter { !finishedTasks.contains(it.id) }
            for (failed in failedTasks) {
                log("处理中断或由于上限失败跳过，此视频放弃: ${failed.name}", LogManager.Level.ERROR)
                LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${failed.id}|FAILED", LogManager.Level.ERROR)
            }
            
            return finishedTasks.isNotEmpty()

        } catch (e: Exception) {
            log("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
            return false
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                // 清理截屏录屏资源
                capturer.stop()
                try { avRecorder?.stop() } catch (_: Exception) {}
                try { avRecorder?.release() } catch (_: Exception) {}
                avRecorder = null
                try { sharedProjection?.stop() } catch (_: Exception) {}
                sharedProjection = null

                // 统一裁剪
                if (adIntervals.isNotEmpty() && tmpFile != null && tmpFile.exists()) {
                    log("【扫尾】开始裁剪取得的广告片段...", LogManager.Level.INFO)
                    withContext(Dispatchers.IO) {
                        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        if (outDir?.exists() == false) outDir.mkdirs()

                        for (interval in adIntervals) {
                            val taskInfo = targetVideoTasks.find { it.id == interval.videoId }
                            val trimStart = (interval.startMs - PRE_ROLL_MS).coerceAtLeast(0)
                            val trimEnd = interval.endMs + POST_ROLL_MS
                            val outName = "SplashAdTx_${taskInfo?.name?.substringBeforeLast('.') ?: "clip"}_${System.currentTimeMillis()}.mp4"
                            val outFile = File(outDir, outName)

                            if (VideoTrimmer.trim(tmpFile.absolutePath, outFile.absolutePath, trimStart, trimEnd)) {
                                log("🎬 裁剪成功，保存于: $outName", LogManager.Level.SUCCESS)
                                LogManager.log("${LOG_PREFIX_VIDEO_RESULT}${interval.videoId}|${outFile.absolutePath}", LogManager.Level.INFO)
                            }
                        }
                    }
                }
                
                try { tmpFile?.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 🟢 [核心改进]：针对鸿蒙/非Root设备设计的混合强杀逻辑
     */
    private suspend fun killApp(engine: AutomationEngine, packageName: String) {
        try {
            log("正在尝试终结进程: $packageName", LogManager.Level.INFO)

            val svc = com.androidclaw.app.service.ClawAccessibilityService.instance
            
            // 1. 调起系统应用详情页
            val targetContext = svc ?: getContext(engine) ?: return
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(intent)
            
            // 2. 在详情页寻找应用状态并点击
            engine.sleep(1200) // 等待设置页面渲染
            val stopNodes = com.androidclaw.app.engine.NodeFinder.findByTextContains("强行停止")
                .plus(com.androidclaw.app.engine.NodeFinder.findByTextContains("结束运行"))
                .plus(com.androidclaw.app.engine.NodeFinder.findByTextContains("Force stop"))
            
            var needConfirm = false
            if (stopNodes.isNotEmpty()) {
                for (node in stopNodes) {
                    if (node.isEnabled && node.isClickable) {
                        engine.clickNode(node)
                        needConfirm = true
                        break
                    } else if (node.isEnabled && node.parent?.isClickable == true) {
                        engine.clickNode(node.parent)
                        needConfirm = true
                        break
                    }
                }
                
                if (needConfirm) {
                    log("主界面强停触发，等待二次确认弹窗...", LogManager.Level.INFO)
                    engine.sleep(1500) // 等待鸿蒙系统的二次确认对话框弹起
                    
                    // 3. 处理鸿蒙系统的二次确认对话框 (倒序优先点最上层UI)
                    val confirmNodes = com.androidclaw.app.engine.NodeFinder.findByTextContains("强行停止")
                        .plus(com.androidclaw.app.engine.NodeFinder.findByTextContains("确定"))
                        
                    for (node in confirmNodes.reversed()) {
                        if (engine.clickNode(node)) {
                            log("成功点击弹窗彻底终结应用", LogManager.Level.SUCCESS)
                            break
                        }
                    }
                    engine.sleep(800)
                } else {
                    log("检测到强停按钮已置灰，应用本就不在运行，直接跳过", LogManager.Level.INFO)
                }
            } else {
                log("未能在详情页识别到强杀按钮，执行原路返回", LogManager.Level.WARN)
            }
            
            val backArrow = com.androidclaw.app.engine.NodeFinder.findByDescription("返回").firstOrNull() 
                ?: com.androidclaw.app.engine.NodeFinder.findByDescription("向上导航").firstOrNull()
                ?: com.androidclaw.app.engine.NodeFinder.findByClassName("android.widget.ImageButton").firstOrNull() 

            if (backArrow != null) {
                engine.clickNode(backArrow)
                log("利用左上角实体返回键成功退出详情页", LogManager.Level.INFO)
            } else {
                svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                log("发送全局后退指令", LogManager.Level.INFO)
            }
            engine.sleep(1000)

        } catch (e: Exception) {
            log("强制关闭执行异常: ${e.message}", LogManager.Level.ERROR)
        }
    }

    private fun createTempFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (dir?.exists() == false) dir.mkdirs()
        return File(dir, "TxVerification_TEMP.mp4").also {
            if (it.exists()) it.delete()
        }
    }



    private fun getContext(engine: AutomationEngine): Context? {
        return try {
            val field = AutomationEngine::class.java.getDeclaredField("context")
            field.isAccessible = true
            field.get(engine) as? Context
        } catch (_: Exception) { null }
    }
}
