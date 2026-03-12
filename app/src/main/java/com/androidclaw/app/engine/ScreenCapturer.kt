package com.androidclaw.app.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.androidclaw.app.log.LogManager

/**
 * 屏幕截图器
 * 基于 MediaProjection + ImageReader，用于实时获取屏幕帧进行分析
 *
 * 支持两种模式:
 * 1. 自建 MediaProjection (独占模式，调用 start(resultCode, data))
 * 2. 外部传入 MediaProjection (共享模式，调用 startWithProjection(projection))
 *    共享模式下，stop() 不会关闭 MediaProjection，由外部管理
 */
class ScreenCapturer(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var ownsProjection = false   // 是否自行管理 projection 的生命周期

    var screenWidth = 0
        private set
    var screenHeight = 0
        private set
    var screenDensity = 0
        private set

    val isCapturing: Boolean get() = virtualDisplay != null

    /**
     * 读取屏幕参数
     */
    fun initScreenMetrics() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    /**
     * 独占模式：自行创建 MediaProjection 并启动截图
     */
    fun start(resultCode: Int, data: Intent): Boolean {
        return try {
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            val projection = pm.getMediaProjection(resultCode, data)
            if (projection == null) {
                LogManager.log("获取屏幕捕获权限失败", LogManager.Level.ERROR)
                return false
            }
            ownsProjection = true
            startWithProjection(projection)
        } catch (e: Exception) {
            LogManager.log("屏幕捕获启动失败: ${e.message}", LogManager.Level.ERROR)
            stop()
            false
        }
    }

    /**
     * 共享模式：使用外部传入的 MediaProjection 启动截图
     * stop() 时不会关闭该 projection
     */
    fun startWithProjection(projection: MediaProjection): Boolean {
        return try {
            mediaProjection = projection

            if (screenWidth == 0) initScreenMetrics()

            // 半分辨率足够用于 dHash 计算
            val captureWidth = screenWidth / 2
            val captureHeight = screenHeight / 2

            imageReader = ImageReader.newInstance(
                captureWidth, captureHeight,
                PixelFormat.RGBA_8888, 2
            )

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapturer",
                captureWidth, captureHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            LogManager.log("屏幕捕获已启动 (${captureWidth}x${captureHeight})", LogManager.Level.SUCCESS)
            true
        } catch (e: Exception) {
            LogManager.log("屏幕捕获启动失败: ${e.message}", LogManager.Level.ERROR)
            stop()
            false
        }
    }

    /**
     * 获取当前屏幕帧
     */
    fun captureFrame(): Bitmap? {
        val reader = imageReader ?: return null

        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            if (bitmapWidth != image.width) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            null
        } finally {
            image?.close()
        }
    }

    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> = Pair(screenWidth, screenHeight)

    /**
     * 停止屏幕捕获
     */
    fun stop() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            // 仅在独占模式下关闭 MediaProjection
            if (ownsProjection) {
                mediaProjection?.stop()
            }
            mediaProjection = null

            LogManager.log("屏幕捕获已停止", LogManager.Level.INFO)
        } catch (e: Exception) {
            LogManager.log("停止屏幕捕获异常: ${e.message}", LogManager.Level.WARN)
        }
    }
}
