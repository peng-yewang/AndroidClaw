package com.androidclaw.app.task

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
 * 小红书广告图自动识别巡检与采集任务
 */
class XiaohongshuAdMatchTask : TaskScript {

    override val name = "小红书图片广告匹配捕获"
    override val description = "在小红书探索发现流中寻找目标广告封面，并进行二阶段精细化匹配"
    override var configuredAdDurationMs: Long = 0L
    var targetVideoTasks: List<VideoTask> = emptyList()
    var recordResultCode: Int = 0
    var recordData: Intent? = null
    var algorithmType: Int = 0
    var enableRotationMatch: Boolean = false

    private var recordStartWallMs = 0L

    private fun log(message: String, level: LogManager.Level = LogManager.Level.INFO) {
        LogManager.log("[XHS] $message", level)
    }

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val MONITOR_TIMEOUT_MS = 180_000L // 任务超时时间 3分钟
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
            log("🚀 启动小红书图片广告二阶段匹配循环", LogManager.Level.SUCCESS)

            if (targetVideoTasks.isEmpty()) {
                log("未提供匹配目标，任务中止", LogManager.Level.ERROR)
                return false
            }
            
            // 1. 初始化指纹加速引擎并提取图片指纹
            val fpManager = if (algorithmType == 0) VideoFingerprintManager() else null
            val aiManager = if (algorithmType == 1) MobileNetFingerprintManager(context) else null
            fpManager?.enableRotationMatch = enableRotationMatch
            aiManager?.enableRotationMatch = enableRotationMatch

            for (task in targetVideoTasks) {
                try {
                    val inputStream = context.contentResolver.openInputStream(task.uri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        if (algorithmType == 0) {
                            val hash = fpManager?.computePHash(bitmap)
                            if (hash != null && hash != 0L) {
                                fpManager.addFingerprint(task.id, listOf(hash))
                            }
                        } else {
                            val vector = aiManager?.extractSingleFrame(bitmap)
                            if (vector != null) {
                                aiManager.addFingerprint(task.id, listOf(vector))
                            }
                        }
                        bitmap.recycle()
                    } else {
                        // 如果 Bitmap 解码失败，说明很可能是视频文件，使用视频提取逻辑作为降级
                        if (algorithmType == 0) fpManager?.extractFromUri(context, task.id, task.uri, 4, true)
                        else aiManager?.extractFromUri(context, task.id, task.uri, 1)
                    }
                } catch (e: Exception) {
                    log("解析目标媒体指纹失败: ${task.name}, ${e.message}", LogManager.Level.ERROR)
                }
            }

            if ((fpManager?.isLoaded != true) && (aiManager?.isLoaded != true)) {
                log("指纹初始化失败，任务中止。", LogManager.Level.ERROR)
                return false
            }

            // 2. 准备环境与录像
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (recordData == null) {
                log("录屏权限数据(recordData)为空！请重新授权", LogManager.Level.ERROR)
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

            // 音视频同步录像
            avRecorder = AudioVideoRecorder(context).also {
                it.prepare(sharedProjection!!, rw, rh, sd, tmpFile.absolutePath)
                it.start()
            }
            recordStartWallMs = System.currentTimeMillis()

            // 3. 业务主循环
            val finishedTasks = mutableSetOf<String>()
            var currentCalendarStartMs = 0L
            
            log("正在启动小红书...", LogManager.Level.INFO)
            if (!engine.launchApp(XHS_PACKAGE)) {
                log("重开失败，任务中止", LogManager.Level.ERROR)
                return false
            }
            engine.sleep(6000)

            val startTimeLimit = System.currentTimeMillis()
            var scrollCount = 0

            while (!engine.isCancelled && finishedTasks.size < targetVideoTasks.size && (System.currentTimeMillis() - startTimeLimit < MONITOR_TIMEOUT_MS)) {
                log("👀 正在监测发现流卡片 (当前滚动次数: $scrollCount)", LogManager.Level.INFO)
                
                val cardBoundsList = getCardImageBounds(engine)
                log("扫描到 ${cardBoundsList.size} 个封面卡片", LogManager.Level.INFO)
                
                var clickedAndMatched = false
                val frame = capturer.captureFrame()
                if (frame != null) {
                    for (bounds in cardBoundsList) {
                        val croppedCard = cropRegion(frame, bounds)
                        if (croppedCard != null) {
                            // 一阶段：粗筛比对
                            if (algorithmType == 0) {
                                fpManager?.matchThreshold = 16
                            } else {
                                aiManager?.cosineMatchThreshold = 0.65f
                            }
                            
                            val matches = if (algorithmType == 0) {
                                fpManager?.matchScreenshots(croppedCard, finishedTasks) ?: emptyList()
                            } else {
                                aiManager?.matchScreenshots(croppedCard, finishedTasks) ?: emptyList()
                            }
                            
                            croppedCard.recycle()
                            
                            // 测试阶段修改：如果粗筛匹配不成功，默认从第一个未完成的任务开始尝试精筛
                            var matchedVideoId = if (matches.isNotEmpty()) {
                                matches.first()
                            } else {
                                targetVideoTasks.firstOrNull { it.id !in finishedTasks }?.id
                            }

                            if (matchedVideoId != null) {
                                if (matches.isNotEmpty()) {
                                    log("🎯 粗筛命中目标卡片 $matchedVideoId, 执行点击进入详情页...", LogManager.Level.SUCCESS)
                                } else {
                                    log("⚠️ 粗筛未比对成功，测试模式下强制点击该卡片尝试精筛（假定目标任务：$matchedVideoId）", LogManager.Level.INFO)
                                }
                                
                                // 点击进入详情页
                                engine.clickAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                                engine.sleep(4000) // 等待详情页完全加载
                                
                                // 二阶段：精筛比对
                                log("🔎 开始二阶段精筛比对...", LogManager.Level.INFO)
                                if (algorithmType == 0) {
                                    fpManager?.matchThreshold = 10
                                } else {
                                    aiManager?.cosineMatchThreshold = 0.80f
                                }
                                
                                var fineMatchSuccess = false
                                val fineStartTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - fineStartTime < 6000 && !engine.isCancelled) {
                                    val detailFrame = capturer.captureFrame()
                                    if (detailFrame != null) {
                                        val fineMatches = if (algorithmType == 0) {
                                            fpManager?.matchScreenshots(detailFrame, finishedTasks) ?: emptyList()
                                        } else {
                                            aiManager?.matchScreenshots(detailFrame, finishedTasks) ?: emptyList()
                                        }
                                        detailFrame.recycle()
                                        
                                        // 检查精筛是否命中。如果没被粗筛选中，但精筛命中了任何未完成的任务，我们也认可它
                                        val actualMatch = fineMatches.firstOrNull()
                                        if (actualMatch != null) {
                                            matchedVideoId = actualMatch
                                            fineMatchSuccess = true
                                            break
                                        }
                                    }
                                    engine.sleep(500)
                                }
                                
                                if (fineMatchSuccess && matchedVideoId != null) {
                                    val finalId = matchedVideoId!!
                                    log("⭐ 二阶段精筛匹配成功！", LogManager.Level.SUCCESS)
                                    finishedTasks.add(finalId)
                                    LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${finalId}|PROCESSING", LogManager.Level.INFO)
                                    
                                    // 时间标记：日历对齐
                                    log("正在切换至系统日历 (并标记时间戳)...", LogManager.Level.INFO)
                                    if (!engine.launchApp("com.android.calendar")) {
                                        if (!engine.launchApp("com.huawei.calendar")) {
                                            engine.launchApp("com.hihonor.calendar")
                                        }
                                    }
                                    engine.sleep(2000)
                                    currentCalendarStartMs = System.currentTimeMillis() - recordStartWallMs
                                    engine.sleep(4000)
                                    
                                    // 回桌面 -> 重回小红书 (详情页)
                                    log("返回小红书详情页...", LogManager.Level.INFO)
                                    engine.goHome()
                                    engine.sleep(2000)
                                    engine.launchApp(XHS_PACKAGE)
                                    engine.sleep(4000)
                                    
                                    // 跳转落地页并滑动采集
                                    jumpToLandingPageAndScroll(engine)
                                    
                                    // 返回详情页，再返回主页
                                    log("返回并清理会话...", LogManager.Level.INFO)
                                    engine.launchApp(XHS_PACKAGE)
                                    engine.sleep(3000)
                                    engine.goBack()
                                    engine.sleep(2000)
                                    
                                    val finalEndRel = System.currentTimeMillis() - recordStartWallMs
                                    adIntervals.add(AdInterval(finalId, currentCalendarStartMs, finalEndRel))
                                    
                                    cleanBackgroundTasks(engine)
                                    clickedAndMatched = true
                                    break
                                } else {
                                    log("⚠️ 精筛未命中，返回发现流", LogManager.Level.WARN)
                                    engine.goBack()
                                    engine.sleep(2000)
                                }
                            }
                        }
                    }
                    frame.recycle()
                }

                if (clickedAndMatched) {
                    scrollCount = 0
                    engine.sleep(3000)
                } else {
                    log("⬇️ 未匹配到，滑动发现流列表", LogManager.Level.INFO)
                    engine.scrollDown(500)
                    engine.sleep(2000)
                    scrollCount++
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
                                val outName = "XiaohongshuImageAd_${taskInfo?.name?.substringBeforeLast('.') ?: "clip"}_${System.currentTimeMillis()}.mp4"
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

    private fun getCardImageBounds(engine: AutomationEngine): List<android.graphics.Rect> {
        val boundsList = mutableListOf<android.graphics.Rect>()
        val nodes = com.androidclaw.app.engine.NodeFinder.findAll()
        val rect = android.graphics.Rect()
        val (sw, sh) = engine.getScreenSize()

        // 查找所有可能的封面图片或卡片容器节点
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(rect)
            
            // 过滤合理的卡片尺寸 (小红书是双列瀑布流，单列宽度大约是屏幕宽度的 40% - 50%)
            val w = rect.width()
            val h = rect.height()
            if (w > sw * 0.35f && w < sw * 0.55f && h > sh * 0.15f && h < sh * 0.60f) {
                if (rect.top > sh * 0.08f && rect.bottom < sh * 0.95f) {
                    if (boundsList.none { Math.abs(it.centerX() - rect.centerX()) < 20 && Math.abs(it.centerY() - rect.centerY()) < 20 }) {
                        boundsList.add(android.graphics.Rect(rect))
                    }
                }
            }
        }
        return boundsList
    }

    private fun cropRegion(bitmap: Bitmap, physicalBounds: android.graphics.Rect): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val left = Math.max(0, physicalBounds.left / 2)
        val top = Math.max(0, physicalBounds.top / 2)
        val right = Math.min(w, physicalBounds.right / 2)
        val bottom = Math.min(h, physicalBounds.bottom / 2)

        val cropW = right - left
        val cropH = bottom - top

        if (cropW <= 10 || cropH <= 10) return null

        return try {
            Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun jumpToLandingPageAndScroll(engine: AutomationEngine): Long {
        val size = engine.getScreenSize()
        var detailNode = com.androidclaw.app.engine.NodeFinder.findByTextContains("查看详情").firstOrNull()
        if (detailNode == null) {
            detailNode = com.androidclaw.app.engine.NodeFinder.findByTextContains("立即购买").firstOrNull()
        }
        if (detailNode == null) {
            detailNode = com.androidclaw.app.engine.NodeFinder.findByTextContains("了解更多").firstOrNull()
        }
        if (detailNode != null) {
            engine.clickNode(detailNode)
        } else {
            engine.clickAt(size.first / 2f, size.second * 0.92f)
        }
        engine.sleep(10000)

        // 动态滑动至触底
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
        return File(dir, "XhsImage_TEMP.mp4").also { it.delete() }
    }

}
