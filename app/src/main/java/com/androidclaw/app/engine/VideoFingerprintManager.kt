package com.androidclaw.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import com.androidclaw.app.log.LogManager

/**
 * 视频指纹管理器
 * 使用 dHash (差异哈希) 算法为视频帧生成感知指纹，用于实时匹配识别
 */
class VideoFingerprintManager {

    /** 指纹库 —— 目标视频每帧的 dHash 值 */
    private val fingerprints = mutableListOf<Long>()

    /** 匹配阈值: 汉明距离 ≤ 此值认为匹配 */
    var matchThreshold = 18

    /** 指纹库是否已加载 */
    val isLoaded: Boolean get() = fingerprints.isNotEmpty()

    /** 指纹数量 */
    val fingerprintCount: Int get() = fingerprints.size

    /** 上次匹配计算的最小汉明距离 (调试用) */
    private var lastMinDistance = -1

    /** 获取上次匹配的最小距离 */
    fun getLastMinDistance(): Int = lastMinDistance

    /**
     * 从 URI 资源中提取视频指纹
     * @param context  上下文
     * @param uri      视频 URI
     * @param fps      每秒提取帧数 (默认 2)
     * @return 提取的指纹数量
     */
    fun extractFromUri(context: Context, uri: android.net.Uri, fps: Int = 2): Int {
        fingerprints.clear()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            // 获取视频时长 (微秒)
            val durationUs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.times(1000) ?: 0L

            if (durationUs <= 0) {
                LogManager.log("无法获取视频时长", LogManager.Level.ERROR)
                return 0
            }

            val durationSec = durationUs / 1_000_000.0
            LogManager.log("视频时长: ${"%.1f".format(durationSec)} 秒", LogManager.Level.INFO)

            // 按 fps 间隔抽帧
            val intervalUs = 1_000_000L / fps
            var timeUs = 0L
            var frameCount = 0

            while (timeUs < durationUs) {
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    val hash = computeDHash(frame)
                    fingerprints.add(hash)
                    frameCount++
                    frame.recycle()
                }
                timeUs += intervalUs
            }

            LogManager.log(
                "指纹提取完成, 共 $frameCount 帧",
                LogManager.Level.SUCCESS
            )
            frameCount
        } catch (e: Exception) {
            LogManager.log("指纹提取失败: ${e.message}", LogManager.Level.ERROR)
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * 从 raw 资源中提取视频指纹
     * @param context  上下文
     * @param rawResId raw 资源 ID (如 R.raw.target_ad)
     * @param fps      每秒提取帧数 (默认 2)
     * @return 提取的指纹数量
     */
    fun extractFromRawResource(context: Context, rawResId: Int, fps: Int = 2): Int {
        fingerprints.clear()

        val retriever = MediaMetadataRetriever()
        return try {
            // 打开 raw 资源
            val afd = context.resources.openRawResourceFd(rawResId)
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()

            // 获取视频时长 (微秒)
            val durationUs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.times(1000) ?: 0L

            if (durationUs <= 0) {
                LogManager.log("无法获取视频时长", LogManager.Level.ERROR)
                return 0
            }

            val durationSec = durationUs / 1_000_000.0
            LogManager.log("视频时长: ${"%.1f".format(durationSec)} 秒", LogManager.Level.INFO)

            // 按 fps 间隔抽帧
            val intervalUs = 1_000_000L / fps
            var timeUs = 0L
            var frameCount = 0

            while (timeUs < durationUs) {
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    val hash = computeDHash(frame)
                    fingerprints.add(hash)
                    frameCount++
                    frame.recycle()
                }
                timeUs += intervalUs
            }

            LogManager.log(
                "指纹提取完成, 共 $frameCount 帧",
                LogManager.Level.SUCCESS
            )
            frameCount
        } catch (e: Exception) {
            LogManager.log("指纹提取失败: ${e.message}", LogManager.Level.ERROR)
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * 判断截屏是否匹配目标视频
     * @param screenshot 当前截屏 Bitmap
     * @return 匹配则返回最小汉明距离, 不匹配返回 -1
     */
    fun matchScreenshot(screenshot: Bitmap): Int {
        if (fingerprints.isEmpty()) return -1

        val screenshotHash = computeDHash(screenshot)
        var minDistance = Int.MAX_VALUE

        for (fp in fingerprints) {
            val distance = hammingDistance(screenshotHash, fp)
            if (distance < minDistance) {
                minDistance = distance
            }
            // 提前终止: 已经非常匹配
            if (minDistance <= 5) break
        }

        lastMinDistance = minDistance
        return if (minDistance <= matchThreshold) minDistance else -1
    }

    /**
     * 计算图像的 dHash (差异哈希)
     *
     * 算法步骤:
     * 1. 缩放到 9×8 像素
     * 2. 转为灰度
     * 3. 逐行比较相邻像素: 左 > 右 置 1, 否则置 0
     * 4. 得到 8×8 = 64 bit 哈希
     */
    fun computeDHash(bitmap: Bitmap): Long {
        // 1. 缩放到 9x8
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)

        // 2 & 3. 灰度 + 差分
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val leftPixel = scaled.getPixel(x, y)
                val rightPixel = scaled.getPixel(x + 1, y)

                val leftGray = toGray(leftPixel)
                val rightGray = toGray(rightPixel)

                if (leftGray > rightGray) {
                    hash = hash or (1L shl (y * 8 + x))
                }
            }
        }

        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return hash
    }

    /**
     * 像素转灰度值
     */
    private fun toGray(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // 使用标准灰度公式
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    /**
     * 计算两个哈希的汉明距离 (不同 bit 数)
     */
    private fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    /**
     * 清空指纹库
     */
    fun clear() {
        fingerprints.clear()
    }
}
