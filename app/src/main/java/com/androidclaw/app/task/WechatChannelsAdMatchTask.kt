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
 * 微信视频号广告自动巡检与采集任务
 */
class WechatChannelsAdMatchTask : TaskScript {

    override val name = "微信视频号广告匹配捕获"
    override val description = "在微信视频号推荐流中寻找目标视频，未刷到则自动下滑刷新"
    override var configuredAdDurationMs: Long = 0L
    var playFullVideoBeforeJump: Boolean = false // 是否等播放完再跳转落地页
    var addCartMode: Int = 0 // 落地页交互模式 (0:无, 1:普通加购...)
    var targetVideoTasks: List<VideoTask> = emptyList()
    var recordResultCode: Int = 0
    var recordData: Intent? = null
    var algorithmType: Int = 0
    var enableRotationMatch: Boolean = false

    private var recordStartWallMs = 0L
    private var videoPlayStartTime = 0L // 精准记录目标视频在环境准备后的起始播放时间

    private fun log(message: String, level: LogManager.Level = LogManager.Level.INFO) {
        LogManager.log("[WxChannels] $message", level)
    }

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val ITEM_MONITOR_MS = 15_000L // 单条视频最长撑 15s (针对广告)
        private const val FAST_DECISION_MS = 2_500L  // 🔴 前 2.5s 没对上就划走 (针对普通视频)
        private const val CONSECUTIVE_MISS_THRESHOLD = 5 
        private const val PRE_ROLL_MS = 2_000L
        private const val POST_ROLL_MS = 0L

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
        val adIntervals = mutableListOf<AdInterval>()

        try {
            log("🚀 启动微信视频号广告匹配循环", LogManager.Level.SUCCESS)

            if (targetVideoTasks.isEmpty()) {
                log("未提供匹配目标视频，任务中止", LogManager.Level.ERROR)
                return false
            }
            
            // 1. 初始化指纹加速引擎 (对齐提取逻辑)
            val fpManager = if (algorithmType == 0) VideoFingerprintManager() else null
            val aiManager = if (algorithmType == 1) MobileNetFingerprintManager(context) else null
            fpManager?.enableRotationMatch = enableRotationMatch
            aiManager?.enableRotationMatch = enableRotationMatch

            for (task in targetVideoTasks) {
                if (algorithmType == 0) fpManager?.extractFromUri(context, task.id, task.uri, 4, true)
                else aiManager?.extractFromUri(context, task.id, task.uri, 1)
            }

            if ((fpManager?.isLoaded != true) && (aiManager?.isLoaded != true)) {
                log("指纹初始化失败，任务终止。", LogManager.Level.ERROR)
                return false
            }

            // 2. 准备环境与录像 (🔴 重点修复：权限数据注入检查)
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (recordData == null) {
                log("录屏权限数据(recordData)为空！请重新授予权限", LogManager.Level.ERROR)
                return false
            }
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

            // 3. 业务主循环 (FinishedTasks 使用对齐)
            val finishedTasks = mutableSetOf<String>()
            val preparedVideos = mutableSetOf<String>()
            var missCount = 0
            var currentCalendarStartMs = 0L
            
            log("首次启动：正在导航至微信视频号...", LogManager.Level.INFO)
            if (!navigateToChannels(engine)) {
                log("导航失败，任务中止", LogManager.Level.ERROR)
                return false
            }

            while (!engine.isCancelled && finishedTasks.size < targetVideoTasks.size) {
                log("👀 正在监测当前内容 (连刷未中: $missCount)", LogManager.Level.INFO)
                val itemStart = System.currentTimeMillis()
                var hitInThisItem = false
                
                // 处理状态登记
                val currentAdStarts = mutableMapOf<String, Long>()
                val lastMatches = mutableMapOf<String, Long>()
                val matchCounts = mutableMapOf<String, Int>()

                while (System.currentTimeMillis() - itemStart < ITEM_MONITOR_MS && !engine.isCancelled) {
                    // 🔴 [优化核心]：如果超过快速决策期且没对上，就不要死等了
                    if (System.currentTimeMillis() - itemStart > FAST_DECISION_MS && currentAdStarts.isEmpty()) {
                        log("⏱️ 2.5s 内未捕捉到特征点，切... (效率提升模式)", LogManager.Level.INFO)
                        break
                    }

                    val frame = capturer.captureFrame()
                    if (frame != null) {
                        val nowMs = System.currentTimeMillis() - recordStartWallMs
                        val matchedSet = if (algorithmType == 0) {
                            fpManager?.matchScreenshots(frame, finishedTasks) ?: emptyList()
                        } else {
                            aiManager?.matchScreenshots(frame, finishedTasks) ?: emptyList()
                        }

                        matchedSet.forEach { matchedVideoId ->
                            lastMatches[matchedVideoId] = nowMs
                            matchCounts[matchedVideoId] = (matchCounts[matchedVideoId] ?: 0) + 1
                            if (!currentAdStarts.containsKey(matchedVideoId)) {
                                currentAdStarts[matchedVideoId] = nowMs
                                log("🎯 发现疑似目标视频号广告: $matchedVideoId", LogManager.Level.SUCCESS)
                                LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${matchedVideoId}|PROCESSING", LogManager.Level.INFO)
                            }
                        }
                        frame.recycle()

                        // 判读逻辑
                        for (videoId in currentAdStarts.keys.toList()) {
                            val start = currentAdStarts[videoId] ?: 0L
                            val lastMatch = lastMatches[videoId] ?: 0L
                            val matchCount = matchCounts[videoId] ?: 0
                            val idleTime = (System.currentTimeMillis() - recordStartWallMs) - lastMatch
                            
                            // 只要满足匹配帧数且静默 > 3s，就收网
                            if (idleTime >= 3000L || (System.currentTimeMillis() - itemStart > ITEM_MONITOR_MS - 500)) {
                                if (matchCount >= 4 && (lastMatch - start) >= 800L) {
                                    if (videoId !in preparedVideos) {
                                        log("🎯 匹配到目标 $videoId，开始执行环境准备连招 (回滑 -> 日历+展开 -> 桌面重开)...", LogManager.Level.INFO)
                                        
                                        // 1. 往上刷一下回到上一条视频
                                        engine.scrollUp(650)
                                        engine.sleep(2000)

                                        // 2. 切换系统日历并标记开始时间
                                        log("正在切换至系统日历 (并标记裁切起点)...", LogManager.Level.INFO)
                                        if (!engine.launchApp("com.android.calendar")) {
                                            if (!engine.launchApp("com.huawei.calendar")) {
                                                engine.launchApp("com.hihonor.calendar")
                                            }
                                        }
                                        engine.sleep(2000) // 🌟 延迟 2 秒记录，确保日历 UI 完全稳定
                                        currentCalendarStartMs = System.currentTimeMillis() - recordStartWallMs
                                        engine.sleep(4000) // 保持总停留 5s

                                        // 3. 回家 -> 从桌面打开微信 -> 导航至视频号 -> 重刷下来
                                        log("桌面重开微信视频号连招...", LogManager.Level.INFO)
                                        engine.goHome()
                                        engine.sleep(2000)
                                        if (navigateToChannels(engine)) {
                                            log("已重回视频号，执行第二次下滑寻回目标...", LogManager.Level.INFO)
                                            engine.scrollDown(650)
                                            engine.sleep(2000)
                                            videoPlayStartTime = System.currentTimeMillis() // 🌟 核心锚点：标记视频刷出来的绝对时间
                                        }

                                        preparedVideos.add(videoId)
                                        // 打断当前循环，让整体逻辑重新去监测这个新刷出来的"真目标"
                                        hitInThisItem = true
                                        break
                                    } else {
                                        log("⭐ 二次确认成功 (环境已准备)！执行逻辑选择...", LogManager.Level.SUCCESS)
                                        
                                        val now = System.currentTimeMillis()
                                        val elapsedSinceStart = if (videoPlayStartTime > 0) now - videoPlayStartTime else 0L
                                        
                                        if (playFullVideoBeforeJump) {
                                            // 方案 B: 播放完毕后再跳转
                                            val totalRequired = if (configuredAdDurationMs > 0) configuredAdDurationMs else 15000L
                                            val waitTime = Math.max(0L, totalRequired - elapsedSinceStart)
                                            log("模式 B: 精准等待播放完毕 (已播: $elapsedSinceStart ms, 补足: $waitTime ms)...", LogManager.Level.INFO)
                                            engine.sleep(waitTime)
                                            
                                            // 微信视频号重播或者点击重看 (如果有的话)，先做个兜底延迟
                                            engine.sleep(1000)
                                            
                                            // 跳转落地页并滚动
                                            jumpToLandingPageAndScroll(engine)

                                            // 返回微信界面完成闭环
                                            log("模式 B: 落地页采集完成，唤回微信以便结项...", LogManager.Level.INFO)
                                            engine.launchApp(WECHAT_PACKAGE)
                                            engine.sleep(5000)
                                        } else {
                                            // 方案 A: 播放 15s 后跳转 (默认)
                                            val preJumpWait = Math.max(0L, 15000L - elapsedSinceStart)
                                            log("模式 A: 精准等待 15s 后跳转 (已播: $elapsedSinceStart ms, 补足: $preJumpWait ms)...", LogManager.Level.INFO)
                                            engine.sleep(preJumpWait)
                                            
                                            // 跳转落地页并滚动
                                            jumpToLandingPageAndScroll(engine)

                                            // 返回微信界面完成闭环
                                            val remainingWait = Math.max(0L, configuredAdDurationMs - 15000L)
                                            log("模式 A: 落地页采集完成，唤回微信等待剩余播放时长 ($remainingWait ms)...", LogManager.Level.INFO)
                                            engine.launchApp(WECHAT_PACKAGE)
                                            engine.sleep(remainingWait)
                                        }

                                        // 计算最终结束时间并登记
                                        val finalEndRel = System.currentTimeMillis() - recordStartWallMs
                                        adIntervals.add(AdInterval(videoId, currentCalendarStartMs, finalEndRel))
                                        
                                        finishedTasks.add(videoId)
                                        // 清理后台
                                        cleanBackgroundTasks(engine)
                                        hitInThisItem = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                    if (hitInThisItem) break
                    engine.sleep(400)
                }

                if (hitInThisItem) {
                    missCount = 0
                    engine.sleep(3000)
                } else {
                    missCount++
                    if (missCount >= CONSECUTIVE_MISS_THRESHOLD) {
                        log("⚠️ 5条刷不出，执行重启连招", LogManager.Level.WARN)
                        killApp(engine, WECHAT_PACKAGE)
                        engine.sleep(2000)
                        
                        log("正在执行重试周期内的重启...", LogManager.Level.INFO)
                        navigateToChannels(engine)
                        missCount = 0
                    } else {
                        log("⬇️ 没刷到，物理上滑刷新 (10s 已满)", LogManager.Level.INFO)
                        engine.scrollDown(300)
                    }
                }
            }
            return finishedTasks.isNotEmpty()
        } catch (e: Exception) {
            log("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
            return false
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                capturer.stop()
                try { avRecorder?.stop() } catch (_: Exception) {}
                try { avRecorder?.release() } catch (_: Exception) {}
                avRecorder = null
                try { sharedProjection?.stop() } catch (_: Exception) {}
                sharedProjection = null

                if (adIntervals.isNotEmpty() && tmpFile != null && tmpFile.exists()) {
                    withContext(Dispatchers.IO) {
                        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) 
                            ?: File(context.filesDir, "Movies").also { if(!it.exists()) it.mkdirs() }
                        if (!outDir.exists()) outDir.mkdirs()

                        for (interval in adIntervals) {
                            try {
                                val taskInfo = targetVideoTasks.find { it.id == interval.videoId }
                                val outName = "WxChannelsAd_${taskInfo?.name?.substringBeforeLast('.') ?: "clip"}_${System.currentTimeMillis()}.mp4"
                                val outFile = File(outDir, outName)
                                log("正在裁剪片段: $outName (Range: ${interval.startMs} - ${interval.endMs}ms)", LogManager.Level.INFO)
                                
                                if (VideoTrimmer.trim(tmpFile.absolutePath, outFile.absolutePath, interval.startMs, interval.endMs)) {
                                    log("🎬 裁剪成功: $outName", LogManager.Level.SUCCESS)
                                    log("${LOG_PREFIX_VIDEO_RESULT}${interval.videoId}|${outFile.absolutePath}", LogManager.Level.INFO)
                                }
                            } catch (te: Exception) {
                                log("❌ 裁剪片段失败: ${te.message}", LogManager.Level.ERROR)
                            }
                        }
                    }
                }
                try { tmpFile?.delete() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun navigateToChannels(engine: AutomationEngine): Boolean {
        log("正在开启微信...", LogManager.Level.INFO)
        if (!engine.launchApp(WECHAT_PACKAGE)) {
            log("启动微信失败", LogManager.Level.ERROR)
            return false
        }
        engine.sleep(4000)

        // 寻找并点击“发现”
        var discoveryNode = com.androidclaw.app.engine.NodeFinder.findByText("发现").firstOrNull()
        if (discoveryNode == null) {
            discoveryNode = com.androidclaw.app.engine.NodeFinder.findByTextContains("发现").firstOrNull()
        }
        if (discoveryNode != null) {
            engine.clickNode(discoveryNode)
            engine.sleep(1500)
        } else {
            log("未找到『发现』标签，尝试使用物理坐标 (540, 2600) 作为备用...", LogManager.Level.WARN)
            engine.clickAt(540f, 2600f)
            engine.sleep(1500)
        }

        // 寻找并点击“视频号”
        val channelsNode = com.androidclaw.app.engine.NodeFinder.findByText("视频号").firstOrNull()
        if (channelsNode != null) {
            engine.clickNode(channelsNode)
            engine.sleep(4000)
            return true
        } else {
            log("未找到『视频号』入口，尝试点击第一个列表项域 (500, 400)...", LogManager.Level.WARN)
            engine.clickAt(500f, 400f)
            engine.sleep(4000)
            return true
        }
    }

    private suspend fun jumpToLandingPageAndScroll(engine: AutomationEngine): Long {
        val size = engine.getScreenSize()
        // 尝试寻找各种广告落地页点击入口
        var detailNode = com.androidclaw.app.engine.NodeFinder.findByTextContains("查看详情").firstOrNull()
        if (detailNode == null) {
            detailNode = com.androidclaw.app.engine.NodeFinder.findByTextContains("了解更多").firstOrNull()
        }
        if (detailNode == null) {
            detailNode = com.androidclaw.app.engine.NodeFinder.findByTextContains("立即购买").firstOrNull()
        }
        if (detailNode != null) {
            engine.clickNode(detailNode)
        } else {
            engine.clickAt(size.first / 2f, size.second * 0.90f)
        }
        engine.sleep(10000)

        // 动态滚动至触底
        var lastPageFingerprint = ""
        var scrollAttempts = 0
        val rect = android.graphics.Rect()

        log("落地页已进入，开始动态触底探测...", LogManager.Level.INFO)
        while (scrollAttempts < 15) {
            engine.scrollDown(400)
            engine.sleep(1500)
            
            val nodes = com.androidclaw.app.engine.NodeFinder.findAll()
            val fingerprint = nodes.takeLast(5).joinToString("|") { node ->
                node.getBoundsInScreen(rect)
                "${node.viewIdResourceName}-${node.text}-${node.contentDescription}-${rect.top}"
            }
            
            if (fingerprint == lastPageFingerprint && lastPageFingerprint.isNotEmpty() && scrollAttempts >= 8) {
                log("检测到页面内容不再变化，确认已到达最底部", LogManager.Level.SUCCESS)
                break
            }
            lastPageFingerprint = fingerprint
            scrollAttempts++
        }
        
        return System.currentTimeMillis() - recordStartWallMs
    }

    private suspend fun killApp(engine: AutomationEngine, packageName: String) {
        try {
            val svc = com.androidclaw.app.service.ClawAccessibilityService.instance
            val targetContext = svc ?: getContext(engine) ?: return
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(intent)
            engine.sleep(1500)
            com.androidclaw.app.engine.NodeFinder.findByTextContains("强行停止").firstOrNull()?.let { engine.clickNode(it) }
            engine.sleep(1000)
            com.androidclaw.app.engine.NodeFinder.findByTextContains("强行停止").let { nodes ->
                nodes.forEach { if(it.text == "强行停止") engine.clickNode(it) }
            }
            com.androidclaw.app.engine.NodeFinder.findByText("确定").firstOrNull()?.let { engine.clickNode(it) }
            engine.sleep(1000)
            svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            engine.sleep(800)
        } catch (_: Exception) {}
    }

    private suspend fun cleanBackgroundTasks(engine: AutomationEngine) {
        engine.showRecents()
        engine.sleep(2000)
        engine.clickAt(675f, 2524f)
        engine.sleep(2000)
    }

    private fun getContext(engine: AutomationEngine): Context? {
        return try {
            val field = AutomationEngine::class.java.getDeclaredField("context")
            field.isAccessible = true
            field.get(engine) as? Context
        } catch (_: Exception) { null }
    }
    
    private fun createTempFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return File(dir, "WxChannels_TEMP.mp4").also { it.delete() }
    }

}
