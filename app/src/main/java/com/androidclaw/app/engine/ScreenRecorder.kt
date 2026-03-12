package com.androidclaw.app.engine

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.util.DisplayMetrics
import android.view.WindowManager
import com.androidclaw.app.log.LogManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecorder(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
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

            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                val bitRate = screenWidth * screenHeight * 3
                setVideoEncodingBitRate(bitRate)
                setVideoFrameRate(30)
                setVideoSize(screenWidth, screenHeight)
                setOutputFile(file.absolutePath)
                prepare()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface, null, null
            )

            mediaRecorder?.start()
            LogManager.log("开始录制视频: $fileName", LogManager.Level.SUCCESS)
            true
        } catch (e: Exception) {
            LogManager.log("录屏启动失败: ${e.message}", LogManager.Level.ERROR)
            stopRecording()
            false
        }
    }

    fun stopRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                LogManager.log("录屏已保存至本地: $savedPath", LogManager.Level.SUCCESS)
            }
        } catch (e: Exception) {
            LogManager.log("停止录屏异常: ${e.message}", LogManager.Level.WARN)
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null

            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null
        }
    }
}
