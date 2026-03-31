package com.androidclaw.app.engine

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.androidclaw.app.log.LogManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Environment

/**
 * 独立屏幕录制器 (供非自管理任务使用)
 *
 * 内部使用 AudioVideoRecorder 实现音视频同步录制。
 */
class ScreenRecorder(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var avRecorder: AudioVideoRecorder? = null
    private var savedPath: String = ""

    fun startRecording(resultCode: Int, data: Intent): Boolean {
        return try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                LogManager.log("获取屏幕录制权限失败", LogManager.Level.ERROR)
                return false
            }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            // 避免因奇数分辨率导致硬件编码器 (H264) prepare 报错
            var screenWidth = metrics.widthPixels
            var screenHeight = metrics.heightPixels
            if (screenWidth % 2 != 0) screenWidth -= 1
            if (screenHeight % 2 != 0) screenHeight -= 1
            val screenDensity = metrics.densityDpi

            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "ClawVideo_${sdf.format(Date())}.mp4"
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (dir?.exists() == false) dir.mkdirs()
            val file = File(dir, fileName)
            savedPath = file.absolutePath

            avRecorder = AudioVideoRecorder(context).also {
                it.prepare(mediaProjection!!, screenWidth, screenHeight, screenDensity, file.absolutePath)
                it.start()
            }

            LogManager.log("开始录制视频 (含内部音频): $fileName", LogManager.Level.SUCCESS)
            true
        } catch (e: Exception) {
            LogManager.log("录屏启动失败: ${e.message}", LogManager.Level.ERROR)
            stopRecording()
            false
        }
    }

    fun stopRecording() {
        try {
            if (avRecorder != null) {
                avRecorder?.stop()
                LogManager.log("录屏已保存至本地: $savedPath", LogManager.Level.SUCCESS)
            }
        } catch (e: Exception) {
            LogManager.log("停止录屏异常: ${e.message}", LogManager.Level.WARN)
        } finally {
            avRecorder?.release()
            avRecorder = null

            mediaProjection?.stop()
            mediaProjection = null
        }
    }
}
