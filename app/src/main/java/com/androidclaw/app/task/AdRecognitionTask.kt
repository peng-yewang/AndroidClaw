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

    var targetVideoUri: android.net.Uri? = null
    var recordResultCode: Int = 0
    var recordData: Intent? = null

    companion object {
        private const val CAPTURE_INTERVAL_MS = 500L
        private const val AD_END_TIMEOUT_MS = 5_000L
        private const val PRE_ROLL_MS = 3_000L
        private const val POST_ROLL_MS = 3_000L
    }

    private data class AdInterval(val startMs: Long, val endMs: Long)

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
        var currentAdStartMs = -1L
        var lastMatchMs = -1L
        var totalCaptures = 0
        var nullFrameCount = 0

        try {
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)
            LogManager.log("开始执行: $name", LogManager.Level.SUCCESS)
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)

            // ===== 步骤1: 提取视频指纹 =====
            LogManager.log("【步骤1】提取目标视频指纹...", LogManager.Level.INFO)
            val fpManager = VideoFingerprintManager()
            
            val uri = targetVideoUri
            val count = if (uri != null) {
                fpManager.extractFromUri(context, uri, 2)
            } else {
                fpManager.extractFromRawResource(context, R.raw.target_ad, 2)
            }

            if (count == 0 || !fpManager.isLoaded) {
                LogManager.log("指纹提取失败，任务终止", LogManager.Level.ERROR)
                return false
            }
            LogManager.log("指纹库就绪: $count 帧", LogManager.Level.SUCCESS)

            // ===== 步骤2: 创建唯一 MediaProjection，同时启动截图+录屏 =====
            LogManager.log("【步骤2】启动屏幕监控 + 全程录屏...", LogManager.Level.INFO)
            val data = recordData ?: run {
                LogManager.log("未提供录屏权限数据", LogManager.Level.ERROR)
                return false
            }

            // 创建唯一的 MediaProjection
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            sharedProjection = pm.getMediaProjection(recordResultCode, data)
            if (sharedProjection == null) {
                LogManager.log("获取 MediaProjection 失败", LogManager.Level.ERROR)
                return false
            }

            // 读取屏幕参数
            capturer.initScreenMetrics()
            val sw = capturer.screenWidth
            val sh = capturer.screenHeight
            val sd = capturer.screenDensity
            val rw = if (sw % 2 == 0) sw else sw - 1
            val rh = if (sh % 2 == 0) sh else sh - 1

            // VirtualDisplay #1: 截图 (通过 ScreenCapturer，共享 projection)
            if (!capturer.startWithProjection(sharedProjection)) {
                LogManager.log("截图启动失败", LogManager.Level.ERROR)
                return false
            }

            // VirtualDisplay #2: 全程录屏 (同一个 projection)
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

            recorderVd = sharedProjection.createVirtualDisplay(
                "AdFullRecorder",
                rw, rh, sd,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )

            recorder.start()
            recordStartWallMs = System.currentTimeMillis()

            LogManager.log("全程录制 + 截图监控 已同时启动", LogManager.Level.SUCCESS)
            engine.sleep(500)

            // ===== 步骤3: 实时监控循环 =====
            LogManager.log("【步骤3】实时监控中，请切换到目标 App 刷广告...", LogManager.Level.INFO)

            while (!engine.isCancelled) {
                val frame = capturer.captureFrame()
                if (frame == null) {
                    nullFrameCount++
                    if (nullFrameCount % 20 == 0) {
                        LogManager.log("⚠ 截图为空 (连续 $nullFrameCount 次)", LogManager.Level.WARN)
                    }
                    engine.sleep(CAPTURE_INTERVAL_MS)
                    continue
                }

                nullFrameCount = 0
                totalCaptures++
                val nowMs = System.currentTimeMillis() - recordStartWallMs
                val matchDistance = fpManager.matchScreenshot(frame)
                frame.recycle()

                val isMatch = matchDistance >= 0

                if (isMatch) {
                    lastMatchMs = nowMs
                    if (currentAdStartMs < 0) {
                        currentAdStartMs = nowMs
                        LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
                        LogManager.log("🎯 发现目标广告! 距离=$matchDistance | 时间=${nowMs / 1000.0}s", LogManager.Level.SUCCESS)
                        LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
                    } else if (totalCaptures % 10 == 0) {
                        val dur = (nowMs - currentAdStartMs) / 1000.0
                        LogManager.log("🔴 广告播放中 ${dur}s | 距离=$matchDistance", LogManager.Level.INFO)
                    }
                } else {
                    if (currentAdStartMs >= 0 && lastMatchMs >= 0) {
                        val silentMs = nowMs - lastMatchMs
                        if (silentMs >= AD_END_TIMEOUT_MS) {
                            val interval = AdInterval(currentAdStartMs, lastMatchMs)
                            adIntervals.add(interval)
                            val dur = (interval.endMs - interval.startMs) / 1000.0
                            LogManager.log(
                                "✅ 广告 #${adIntervals.size} 结束 (${dur}s)，已标记时间戳待裁剪",
                                LogManager.Level.SUCCESS
                            )
                            currentAdStartMs = -1L
                            lastMatchMs = -1L
                        }
                    } else if (totalCaptures % 40 == 0) {
                        val dist = fpManager.getLastMinDistance()
                        LogManager.log(
                            "👀 监控中 | 最小距离=$dist / 阈值${fpManager.matchThreshold} | 截图 $totalCaptures 帧",
                            LogManager.Level.INFO
                        )
                    }
                }

                engine.sleep(CAPTURE_INTERVAL_MS)
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            LogManager.log("任务被用户取消，准备保存已识别的广告...", LogManager.Level.INFO)
        } catch (e: Exception) {
            LogManager.log("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
        } finally {
            // ===== 用 NonCancellable 确保即使协程被取消也能完成裁剪 =====
            withContext(kotlinx.coroutines.NonCancellable) {
                // 记录最后一段未结束的广告
                if (currentAdStartMs >= 0) {
                    val endMs = System.currentTimeMillis() - recordStartWallMs
                    adIntervals.add(AdInterval(currentAdStartMs, endMs))
                    LogManager.log("记录最后一段广告 (任务停止时仍在播放)", LogManager.Level.INFO)
                }

                // 停止截图
                capturer.stop()

                // 停止录制 (必须先停止才能读取文件)
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                try { recorderVd?.release() } catch (_: Exception) {}
                recorderVd = null
                try { sharedProjection?.stop() } catch (_: Exception) {}
                sharedProjection = null

                LogManager.log("录制已停止", LogManager.Level.INFO)

                // ===== 步骤4: 批量裁剪 =====
                if (adIntervals.isNotEmpty() && tmpFile != null && tmpFile.exists()) {
                    LogManager.log("【步骤4】裁剪 ${adIntervals.size} 段广告...", LogManager.Level.INFO)
                    var saved = 0
                    withContext(Dispatchers.IO) {
                        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        if (outDir?.exists() == false) outDir.mkdirs()

                        adIntervals.forEachIndexed { idx, interval ->
                            val trimStart = (interval.startMs - PRE_ROLL_MS).coerceAtLeast(0)
                            val trimEnd = interval.endMs + POST_ROLL_MS
                            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                            val outName = "AdProof_${sdf.format(Date())}_${idx + 1}of${adIntervals.size}.mp4"
                            val outFile = File(outDir, outName)

                            LogManager.log("裁剪 #${idx + 1}: [${trimStart / 1000.0}s → ${trimEnd / 1000.0}s]", LogManager.Level.INFO)
                            if (VideoTrimmer.trim(tmpFile.absolutePath, outFile.absolutePath, trimStart, trimEnd)) {
                                saved++
                                LogManager.log("✅ 已保存: $outName", LogManager.Level.SUCCESS)
                            } else {
                                LogManager.log("❌ 裁剪失败 #${idx + 1}", LogManager.Level.ERROR)
                            }
                        }
                    }
                    try { tmpFile.delete() } catch (_: Exception) {}

                    LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
                    LogManager.log("✅ 共发现 ${adIntervals.size} 次广告，保存 $saved 段录屏", LogManager.Level.SUCCESS)
                    LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
                } else {
                    try { tmpFile?.delete() } catch (_: Exception) {}
                    LogManager.log("本次任务未检测到目标广告", LogManager.Level.WARN)
                }
            }
        }

        return adIntervals.isNotEmpty()
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
