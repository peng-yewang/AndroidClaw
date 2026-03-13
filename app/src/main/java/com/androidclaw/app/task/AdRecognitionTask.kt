package com.androidclaw.app.task

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import com.androidclaw.app.R
import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.ScreenCapturer
import com.androidclaw.app.engine.VideoFingerprintManager
import com.androidclaw.app.engine.VideoTrimmer
import com.androidclaw.app.log.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频广告识别任务 — 全程录制 + 事后批量裁剪
 *
 * 关键架构: 只创建一个 MediaProjection，从中分出两个 VirtualDisplay:
 *   - VD1 → ImageReader (截图用于 dHash 比对)
 *   - VD2 → MediaRecorder (全程录屏)
 *
 * 流程:
 * 1. 提取目标视频指纹
 * 2. 创建唯一 MediaProjection → 同时启动截图 + 全程录屏
 * 3. 每 500ms 截图计算 dHash → 匹配时记录时间戳
 * 4. 用户点停止 → 停止录制 → 根据时间戳批量裁剪保存
 */
class AdRecognitionTask : TaskScript {

    override val name = "视频广告识别"
    override val description = "全程录制 + 智能识别 + 自动裁剪保存广告片段"
    override var configuredAdDurationMs: Long = 0L

    var targetVideoTasks: List<com.androidclaw.app.task.VideoTask> = emptyList()
    var recordResultCode: Int = 0
    var recordData: Intent? = null

    companion object {
        private const val CAPTURE_INTERVAL_MS = 500L
        private const val AD_END_TIMEOUT_MS = 5_000L
        private const val PRE_ROLL_MS = 3_000L
        private const val POST_ROLL_MS = 3_000L
        
        // 特殊日志前缀，用于 UI 通讯
        const val LOG_PREFIX_VIDEO_STATUS = "VIDEO_STATUS|"
        const val LOG_PREFIX_VIDEO_RESULT = "VIDEO_RESULT|"
    }

    private data class AdInterval(val videoId: String, val startMs: Long, val endMs: Long)

    override suspend fun execute(engine: AutomationEngine): Boolean {
        val context = getContext(engine) ?: run {
            LogManager.log("无法获取 Context", LogManager.Level.ERROR)
            return false
        }

        var sharedProjection: MediaProjection? = null
        var recorderVd: VirtualDisplay? = null
        var recorder: MediaRecorder? = null
        val capturer = ScreenCapturer(context)
        var tmpFile: File? = null
        var recordStartWallMs = 0L
        
        val adIntervals = mutableListOf<AdInterval>()
        val finishedTasks = mutableSetOf<String>()
        val currentAdStarts = mutableMapOf<String, Long>() // videoId -> startMs
        val lastMatches = mutableMapOf<String, Long>()     // videoId -> lastMatchMs
        val matchCounts = mutableMapOf<String, Int>()      // videoId -> count of successful matches

        try {
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)
            LogManager.log("开始执行: $name", LogManager.Level.SUCCESS)
            LogManager.log("目标包含 ${targetVideoTasks.size} 个视频", LogManager.Level.INFO)
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)

            // ===== 步骤1: 批量提取视频指纹 =====
            LogManager.log("【步骤1】批量提取目标视频指纹...", LogManager.Level.INFO)
            val fpManager = VideoFingerprintManager()
            
            targetVideoTasks.forEach { task ->
                // 指纹提取频率适当增加，提高精度 (由 2 增加到 4 fps)
                val count = fpManager.extractFromUri(context, task.id, task.uri, 4)
                if (count > 0) {
                    LogManager.log("指纹就绪: ${task.name} ($count 帧)", LogManager.Level.INFO)
                }
            }

            if (!fpManager.isLoaded) {
                LogManager.log("所有指纹提取失败，任务终止", LogManager.Level.ERROR)
                return false
            }

            // ===== 步骤2: 创建唯一 MediaProjection，同时启动截图+录屏 =====
            LogManager.log("【步骤2】启动服务...", LogManager.Level.INFO)
            val data = recordData ?: run {
                LogManager.log("未提供录屏权限数据", LogManager.Level.ERROR)
                return false
            }

            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            sharedProjection = pm.getMediaProjection(recordResultCode, data)
            if (sharedProjection == null) return false

            capturer.initScreenMetrics()
            val sw = capturer.screenWidth
            val sh = capturer.screenHeight
            val sd = capturer.screenDensity
            val rw = if (sw % 2 == 0) sw else sw - 1
            val rh = if (sh % 2 == 0) sh else sh - 1

            if (!capturer.startWithProjection(sharedProjection)) return false

            tmpFile = createTempFile(context)
            recorder = createRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(rw, rh)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(rw * rh * 2)
                setOutputFile(tmpFile.absolutePath)
                prepare()
            }

            recorderVd = sharedProjection.createVirtualDisplay("AdFullRecorder", rw, rh, sd, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null)
            recorder.start()
            recordStartWallMs = System.currentTimeMillis()

            LogManager.log("录制已启动，正在监听多目标...", LogManager.Level.SUCCESS)
            engine.sleep(500)

            // ===== 步骤3: 实时监控循环 =====
            var totalCaptures = 0

            while (!engine.isCancelled) {
                // 检查是否所有任务都已标记完成
                if (finishedTasks.size == targetVideoTasks.size) {
                    LogManager.log("🎉 队列中所有视频均已匹配完成 (已完成: ${finishedTasks.size}/${targetVideoTasks.size})", LogManager.Level.SUCCESS)
                    break
                }

                val frame = capturer.captureFrame()
                if (frame == null) {
                    engine.sleep(CAPTURE_INTERVAL_MS)
                    continue
                }

                totalCaptures++
                val nowMs = System.currentTimeMillis() - recordStartWallMs
                
                // 仅对尚未完成的任务进行匹配
                val matchedVideoIds = fpManager.matchScreenshots(frame, finishedTasks)
                frame.recycle()

                // 处理匹配到的视频
                matchedVideoIds.forEach { matchedVideoId ->
                    lastMatches[matchedVideoId] = nowMs
                    matchCounts[matchedVideoId] = (matchCounts[matchedVideoId] ?: 0) + 1
                    
                    if (!currentAdStarts.containsKey(matchedVideoId)) {
                        currentAdStarts[matchedVideoId] = nowMs
                        val task = targetVideoTasks.find { it.id == matchedVideoId }
                        LogManager.log("🎯 发现疑似目标: ${task?.name} (ID: $matchedVideoId)", LogManager.Level.SUCCESS)
                        LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${matchedVideoId}|PROCESSING", LogManager.Level.INFO)
                    }
                }

                // 检查活跃中的视频是否播放结束 (超时未匹配)
                val activeVideoIds = currentAdStarts.keys.toList()
                activeVideoIds.forEach { videoId ->
                    val start = currentAdStarts[videoId] ?: 0L
                    val lastMatch = lastMatches[videoId] ?: 0L
                    val idleTime = nowMs - lastMatch
                    val matchDuration = lastMatch - start
                    val count = matchCounts[videoId] ?: 0
                    
                    if (idleTime >= AD_END_TIMEOUT_MS) {
                        // 逻辑修正：要求时长 > 3 秒且在该时段内有超过 4 次以上匹配成功才算
                        if (matchDuration >= 3000L && count >= 4) {
                            adIntervals.add(AdInterval(videoId, start, lastMatch))
                            finishedTasks.add(videoId)
                            currentAdStarts.remove(videoId)
                            lastMatches.remove(videoId)
                            matchCounts.remove(videoId)
                            
                            val task = targetVideoTasks.find { it.id == videoId }
                            LogManager.log("✅ 确认捕获广告: ${task?.name} (持续: ${matchDuration / 1000.0}s, 有效匹配: $count)", LogManager.Level.SUCCESS)
                            LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${videoId}|COMPLETED", LogManager.Level.INFO)
                        } else {
                            // 匹配质量太低被判定为误报
                            currentAdStarts.remove(videoId)
                            lastMatches.remove(videoId)
                            matchCounts.remove(videoId)
                            LogManager.log("⚠️ 判定为误报 (${matchDuration}ms, $count 帧)，重置状态", LogManager.Level.WARN)
                            LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${videoId}|WAITING", LogManager.Level.INFO)
                        }
                    }
                }

                if (totalCaptures % 20 == 0) {
                    val remaining = targetVideoTasks.count { !finishedTasks.contains(it.id) }
                    val dist = fpManager.getLastMinDistance()
                    LogManager.log("监控中... [剩余目标: $remaining/${targetVideoTasks.size}] [当前最小距离: $dist]", LogManager.Level.INFO)
                }

                engine.sleep(CAPTURE_INTERVAL_MS)
            }

        } catch (e: Exception) {
            LogManager.log("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                // 扫尾工作
                capturer.stop()
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                try { recorderVd?.release() } catch (_: Exception) {}
                recorderVd = null
                try { sharedProjection?.stop() } catch (_: Exception) {}
                sharedProjection = null

                // ===== 步骤4: 批量裁剪 =====
                if (adIntervals.isNotEmpty() && tmpFile != null && tmpFile.exists()) {
                    LogManager.log("【步骤4】开始批量裁剪视频片段...", LogManager.Level.INFO)
                    withContext(Dispatchers.IO) {
                        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        if (outDir?.exists() == false) outDir.mkdirs()

                        adIntervals.forEach { interval ->
                            val task = targetVideoTasks.find { it.id == interval.videoId }
                            val trimStart = (interval.startMs - PRE_ROLL_MS).coerceAtLeast(0)
                            val trimEnd = interval.endMs + POST_ROLL_MS
                            val outName = "Ad_${task?.name?.substringBeforeLast('.') ?: "clip"}_${System.currentTimeMillis()}.mp4"
                            val outFile = File(outDir, outName)

                            if (VideoTrimmer.trim(tmpFile.absolutePath, outFile.absolutePath, trimStart, trimEnd)) {
                                LogManager.log("🎬 已保存裁剪片段: $outName", LogManager.Level.SUCCESS)
                                LogManager.log("${LOG_PREFIX_VIDEO_RESULT}${interval.videoId}|${outFile.absolutePath}", LogManager.Level.INFO)
                            }
                        }
                    }
                    try { tmpFile.delete() } catch (_: Exception) {}
                } else {
                    try { tmpFile?.delete() } catch (_: Exception) {}
                    LogManager.log("未产生可裁剪片段", LogManager.Level.WARN)
                }
            }
        }

        return finishedTasks.isNotEmpty()
    }

    private fun createTempFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (dir?.exists() == false) dir.mkdirs()
        return File(dir, "AdRecognition_TEMP.mp4").also {
            if (it.exists()) it.delete()
        }
    }

    private fun createRecorder(context: Context): MediaRecorder {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
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
