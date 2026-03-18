package com.androidclaw.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import com.androidclaw.app.log.LogManager

/**
 * 视频指纹管理器
 * 升级为 PHash (感知哈希 + 离散余弦变换 DCT) 算法，对画面内容的抗干扰和分辨能力远超 DHash
 */
class VideoFingerprintManager {

    /** 指纹库 —— 目标视频 ID 到 PHash 值的映射 */
    private val fingerprintGroups = mutableMapOf<String, List<Long>>()

    /** 是否开启横屏 90 度自动补偿测试 (开启后将在匹配失败后消耗双倍算力) */
    var enableRotationMatch: Boolean = false

    /** 匹配阈值: PHash 建议值在 8-12 之间，默认为 10 (数值越低越严格) */
    var matchThreshold = 10

    /** 指纹库是否已加载 */
    val isLoaded: Boolean get() = fingerprintGroups.isNotEmpty()

    /** 指纹组数量 */
    val groupCount: Int get() = fingerprintGroups.size

    /** 上次匹配计算的最小汉明距离 (调试用) */
    private var lastMinDistance = -1

    /** 预计算 DCT 余弦查找表，加速实时计算速率 */
    private val cosMatrix = Array(32) { DoubleArray(32) }
    private val cFactor = DoubleArray(32)

    init {
        val N = 32
        for (i in 0 until N) {
            cFactor[i] = if (i == 0) 1.0 / Math.sqrt(2.0) else 1.0
            for (j in 0 until N) {
                cosMatrix[i][j] = Math.cos((2 * i + 1) * j * Math.PI / (2 * N))
            }
        }
    }

    /** 获取上次匹配的最小距离 */
    fun getLastMinDistance(): Int = lastMinDistance

    /**
     * 从 URI 资源中提取视频指纹并存入指定 ID
     */
    fun extractFromUri(context: Context, videoId: String, uri: android.net.Uri, fps: Int = 2, saveDebugImages: Boolean = false): Int {
        val group = mutableListOf<Long>()

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val durationUs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.times(1000) ?: 0L

            if (durationUs <= 0) {
                LogManager.log("无法获取视频时长: $videoId", LogManager.Level.ERROR)
                return 0
            }

            val intervalUs = 1_000_000L / fps
            var timeUs = 0L
            var frameCount = 0

            while (timeUs < durationUs) {
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    // 调试功能
                    if (saveDebugImages) {
                        try {
                            val debugDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                            val taskDir = java.io.File(debugDir, "Debug_Extract_${videoId}")
                            if (!taskDir.exists()) taskDir.mkdirs()
                            val frameFile = java.io.File(taskDir, "Frame_${timeUs / 1000}ms.jpg")
                            val out = java.io.FileOutputStream(frameFile)
                            frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                            out.flush()
                            out.close()
                        } catch (e: Exception) { /* ignore */ }
                    }

                    val hash = computePHash(frame)
                    // 🔴 过滤掉绝对黑帧/无效过渡帧 
                    if (hash != 0L) {
                        group.add(hash)
                        frameCount++
                    }
                    frame.recycle()
                }
                timeUs += intervalUs
            }

            if (group.isNotEmpty()) {
                fingerprintGroups[videoId] = group
            }
            LogManager.log("ID: $videoId 指纹提取完成, 共 $frameCount 帧", LogManager.Level.SUCCESS)
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
            val afd = context.resources.openRawResourceFd(rawResId)
            retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()

            val durationUs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.times(1000) ?: 0L

            if (durationUs <= 0) return 0

            val intervalUs = 1_000_000L / fps
            var timeUs = 0L
            var frameCount = 0

            while (timeUs < durationUs) {
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    val hash = computePHash(frame)
                    if (hash != 0L) {
                        group.add(hash)
                        frameCount++
                    }
                    frame.recycle()
                }
                timeUs += intervalUs
            }

            if (group.isNotEmpty()) {
                fingerprintGroups[videoId] = group
            }
            frameCount
        } catch (e: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * 匹配截屏，包含横屏自动补偿逻辑
     */
    fun matchScreenshots(screenshot: Bitmap, excludeIds: Set<String> = emptySet()): List<String> {
        val matches = matchScreenshotsNormal(screenshot, excludeIds)
        if (matches.isNotEmpty() || !enableRotationMatch) {
            return matches
        }

        // 🟢 辅助模式：仅当正常匹配物位为空时（疑似横屏），旋转 90 度并重新对网
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(screenshot, 0, 0, screenshot.width, screenshot.height, matrix, true)
        val matchesRotated = matchScreenshotsNormal(rotated, excludeIds)
        if (matchesRotated.isNotEmpty()) {
            com.androidclaw.app.log.LogManager.log("🔄 PHash：横屏 90° 自动姿态补偿成功匹配！", com.androidclaw.app.log.LogManager.Level.SUCCESS)
        }
        rotated.recycle()
        return matchesRotated
    }

    /**
     * 底层单姿势匹配
     */
    private fun matchScreenshotsNormal(screenshot: Bitmap, excludeIds: Set<String> = emptySet()): List<String> {
        if (fingerprintGroups.isEmpty()) return emptyList()

        val screenshotHash = computePHash(screenshot)
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
                if (localMinDistance <= 4) break 
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
     * 计算图像的 pHash (感知哈希)
     *
     * 算法步骤:
     * 1. 裁剪掉黑边、状态栏等可能存在的全黑/带小图标的干扰条
     * 2. 缩放到 32×32 像素并灰度化
     * 3. 执行 2D-DCT 离散余弦变换计算频率系数
     * 4. 截取左上角 8×8 区域，排除 (0,0) 的直流平均亮度分量
     * 5. 基于这 63 个交流信号的平均系数进行 0-1 二值化映射，得出 63 bit 指纹
     */
    fun computePHash(bitmap: Bitmap): Long {
        val cropped = cropBlackBorders(bitmap)
        val N = 32
        val scaled = Bitmap.createScaledBitmap(cropped, N, N, true)
        if (cropped != bitmap) {
            cropped.recycle()
        }

        // 1. 灰度矩阵构建 (范围 0.0 - 255.0)
        val matrix = Array(N) { DoubleArray(N) }
        for (y in 0 until N) {
            for (x in 0 until N) {
                matrix[y][x] = toGray(scaled.getPixel(x, y)).toDouble()
            }
        }
        scaled.recycle()

        // 2. 离散余弦变换 2D-DCT (分离式算法：先算行再算列，速度快 100 倍)
        val temp = Array(N) { DoubleArray(N) }
        val dct = Array(N) { DoubleArray(N) }

        // 行变换
        for (y in 0 until N) {
            for (u in 0 until N) {
                var sum = 0.0
                for (x in 0 until N) {
                    sum += matrix[y][x] * cosMatrix[x][u]
                }
                temp[y][u] = sum * cFactor[u] * Math.sqrt(2.0 / N)
            }
        }

        // 列变换
        for (u in 0 until N) {
            for (v in 0 until N) {
                var sum = 0.0
                for (y in 0 until N) {
                    sum += temp[y][u] * cosMatrix[y][v]
                }
                dct[v][u] = sum * cFactor[v] * Math.sqrt(2.0 / N)
            }
        }

        // 3. 截取左上角 8x8 排除 0,0 偏置项，并求均值
        var sumCoeff = 0.0
        for (u in 0 until 8) {
            for (v in 0 until 8) {
                if (u == 0 && v == 0) continue
                sumCoeff += dct[v][u]
            }
        }
        val avgCoeff = sumCoeff / 63.0

        // 4. 二值化二极指纹 (填压为 63 位 Long 变量中)
        var hash = 0L
        var bitIndex = 0
        for (u in 0 until 8) {
            for (v in 0 until 8) {
                if (u == 0 && v == 0) continue
                if (dct[v][u] > avgCoeff) {
                    hash = hash or (1L shl bitIndex)
                }
                bitIndex++
            }
        }

        return hash
    }

    /**
     * 去除 Bitmap 四周的黑边 / 状态栏
     * 通过检查行/列的黑色像素比例，跳过顶层状态栏图标，精准定位内容选区
     */
    private fun cropBlackBorders(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        var top = 0
        var bottom = height - 1
        var left = 0
        var right = width - 1

        fun isBlack(pixel: Int): Boolean {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r < 15 && g < 15 && b < 15
        }

        fun isLineBlack(index: Int, isRow: Boolean): Boolean {
            var blackCount = 0
            val size = if (isRow) width else height
            val step = 4
            var count = 0
            for (i in 0 until size step step) {
                val pixel = if (isRow) bitmap.getPixel(i, index) else bitmap.getPixel(index, i)
                if (isBlack(pixel)) blackCount++
                count++
            }
            return count > 0 && (blackCount.toFloat() / count) >= 0.85f
        }

        // Find top
        while (top < height) {
            if (!isLineBlack(top, true)) break
            top++
        }

        // Find bottom
        while (bottom > top) {
            if (!isLineBlack(bottom, true)) break
            bottom--
        }

        // Find left
        while (left < width) {
            if (!isLineBlack(left, false)) break
            left++
        }

        // Find right
        while (right > left) {
            if (!isLineBlack(right, false)) break
            right--
        }

        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1

        // 保护机制：如果裁剪后尺寸太小，则不予裁剪，使用原图
        if (cropWidth <= 10 || cropHeight <= 10) return bitmap

        return if (left > 0 || right < width - 1 || top > 0 || bottom < height - 1) {
            Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        } else {
            bitmap
        }
    }

    private fun toGray(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    private fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    fun clear() {
        fingerprintGroups.clear()
    }
}
