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

    /** 指纹库 —— 目标视频 ID 到 dHash 值的映射 */
    private val fingerprintGroups = mutableMapOf<String, List<Long>>()

    /** 匹配阈值: 汉明距离 ≤ 此值认为匹配 (推荐值 5-10 用于广告监测) */
    var matchThreshold = 8

    /** 指纹库是否已加载 */
    val isLoaded: Boolean get() = fingerprintGroups.isNotEmpty()

    /** 指纹组数量 */
    val groupCount: Int get() = fingerprintGroups.size

    /** 上次匹配计算的最小汉明距离 (调试用) */
    private var lastMinDistance = -1

    /** 获取上次匹配的最小距离 */
    fun getLastMinDistance(): Int = lastMinDistance

    /**
     * 从 URI 资源中提取视频指纹并存入指定 ID
     */
    fun extractFromUri(context: Context, videoId: String, uri: android.net.Uri, fps: Int = 2): Int {
        val group = mutableListOf<Long>()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            // 获取视频时长 (微秒)
            val durationUs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.times(1000) ?: 0L

            if (durationUs <= 0) {
                LogManager.log("无法获取视频时长: $videoId", LogManager.Level.ERROR)
                return 0
            }

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
                    group.add(hash)
                    frameCount++
                    frame.recycle()
                }
                timeUs += intervalUs
            }

            if (group.isNotEmpty()) {
                fingerprintGroups[videoId] = group
            }
            LogManager.log(
                "ID: $videoId 指纹提取完成, 共 $frameCount 帧",
                LogManager.Level.SUCCESS
            )
            frameCount
        } catch (e: Exception) {
            LogManager.log("ID: $videoId 指纹提取失败: ${e.message}", LogManager.Level.ERROR)
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * 从 raw 资源中提取视频指纹
     */
    fun extractFromRawResource(context: Context, videoId: String, rawResId: Int, fps: Int = 2): Int {
        val group = mutableListOf<Long>()

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

            if (durationUs <= 0) return 0

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
                    group.add(hash)
                    frameCount++
                    frame.recycle()
                }
                timeUs += intervalUs
            }

            if (group.isNotEmpty()) {
                fingerprintGroups[videoId] = group
            }
            frameCount
        } catch (e: Exception) {
            LogManager.log("ID: $videoId 指纹提取失败: ${e.message}", LogManager.Level.ERROR)
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * 匹配截屏，返回匹配成功的视频 ID 列表
     * @param screenshot 当前截屏
     * @param excludeIds 需要排除的 ID (已完成的任务)
     * @return 匹配成功的 ID 列表 (按匹配程度排序)
     */
    fun matchScreenshots(screenshot: Bitmap, excludeIds: Set<String> = emptySet()): List<String> {
        if (fingerprintGroups.isEmpty()) return emptyList()

        val screenshotHash = computeDHash(screenshot)
        if (screenshotHash == 0L) return emptyList()
        val matches = mutableListOf<Pair<String, Int>>()
        var absoluteMinDistance = Int.MAX_VALUE

        for ((id, group) in fingerprintGroups) {
            if (excludeIds.contains(id)) continue

            var localMinDistance = Int.MAX_VALUE
            for (fp in group) {
                val distance = hammingDistance(screenshotHash, fp)
                if (distance < localMinDistance) {
                    localMinDistance = distance
                }
                if (localMinDistance <= 5) break 
            }

            if (localMinDistance <= matchThreshold) {
                matches.add(id to localMinDistance)
            }
            if (localMinDistance < absoluteMinDistance) {
                absoluteMinDistance = localMinDistance
            }
        }

        lastMinDistance = absoluteMinDistance
        return matches.sortedBy { it.second }.map { it.first }
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
        fingerprintGroups.clear()
    }
}
