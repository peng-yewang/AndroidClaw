package com.androidclaw.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.androidclaw.app.log.LogManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 神经网络指纹管理器
 * 使用 MobileNetV3 提取 1024 维语义特征向量，通过余弦相似度（Cosine Similarity）进行匹配
 */
class MobileNetFingerprintManager(context: Context) {

    /** 智能特征库 —— 目标视频 ID 到特征向量列表的映射 */
    private val fingerprintGroups = mutableMapOf<String, List<FloatArray>>()

    /** TFLite 推理机 */
    private var interpreter: Interpreter? = null

    /** 匹配阈值: 余弦相似度 ≥ 此值认为匹配 (推荐值 0.75 - 0.85 之间) */
    var cosineMatchThreshold = 0.82f

    companion object {
        private const val MODEL_NAME = "mobilenet_v3.tflite"
        private const val INPUT_SIZE = 224
    }

    init {
        try {
            val fileDescriptor = context.assets.openFd(MODEL_NAME)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            
            interpreter = Interpreter(modelBuffer)
            LogManager.log("🧠 MobileNetV3 推理模块加载成功", LogManager.Level.SUCCESS)
        } catch (e: Exception) {
            LogManager.log("❌ MobileNetV3 载入失败: ${e.message}。请确保 assets 目录下有 $MODEL_NAME 文件", LogManager.Level.ERROR)
        }
    }

    /** 指纹库是否已加载 */
    val isLoaded: Boolean get() = fingerprintGroups.isNotEmpty()

    /** 指纹组数量 */
    val groupCount: Int get() = fingerprintGroups.size

    /**
     * 从 URI 资源中提取视频特征向量
     */
    fun extractFromUri(context: Context, videoId: String, uri: android.net.Uri, fps: Int = 1): Int {
        if (interpreter == null) return 0
        val group = mutableListOf<FloatArray>()
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(context, uri)
            val durationUs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.times(1000) ?: 0L

            if (durationUs <= 0) return 0

            // 针对 AI 推理，采样率默认可以置为 1 fps（1秒一帧），节省端侧提取时间
            val intervalUs = 1_000_000L / fps
            var timeUs = 0L
            var frameCount = 0

            while (timeUs < durationUs) {
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    val featureVector = runInference(frame)
                    group.add(featureVector)
                    frameCount++
                    frame.recycle()
                }
                timeUs += intervalUs
            }

            if (group.isNotEmpty()) {
                fingerprintGroups[videoId] = group
            }
            LogManager.log("AI：ID $videoId 提取完成, 共 $frameCount 个语义向量", LogManager.Level.SUCCESS)
            frameCount
        } catch (e: Exception) {
            LogManager.log("AI：指纹提取出错: ${e.message}", LogManager.Level.ERROR)
            0
        } finally {
            retriever.release()
        }
    }

    /**
     * 匹配截屏，应用余弦相似度得出雷同视频
     */
    fun matchScreenshots(screenshot: Bitmap, excludeIds: Set<String> = emptySet()): List<String> {
        if (fingerprintGroups.isEmpty() || interpreter == null) return emptyList()

        val capturedVector = runInference(screenshot)
        val matches = mutableListOf<Pair<String, Float>>()

        for ((id, group) in fingerprintGroups) {
            if (excludeIds.contains(id)) continue

            var maxSimilarity = -1.0f
            for (targetVector in group) {
                val similarity = cosineSimilarity(capturedVector, targetVector)
                if (similarity > maxSimilarity) {
                    maxSimilarity = similarity
                }
                if (maxSimilarity >= 0.95f) break // 极度接近，快速逃逸
            }

            if (maxSimilarity >= cosineMatchThreshold) {
                matches.add(id to maxSimilarity)
                LogManager.log("🎯 AI 命中: $id, 相似度得分: ${String.format("%.2f", maxSimilarity)}", LogManager.Level.SUCCESS)
            } else {
                // 🔴 追加未命中调试日志，方便观察最佳得分有多大
                LogManager.log("⚠️ AI 未命中: $id, 最佳相似度: ${String.format("%.2f", maxSimilarity)} (阈值: $cosineMatchThreshold)", LogManager.Level.INFO)
            }
        }

        return matches.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * 执行神经网络推理，输入 224x224，输出 1024 维 特征张量
     */
    private fun runInference(bitmap: Bitmap): FloatArray {
        if (interpreter == null) return FloatArray(1024)

        // 0. 🔴 裁切掉黑边、状态栏、保证内容充满度，和原片图层级对齐
        val cropped = cropBlackBorders(bitmap)

        // 1. 归一化缩放
        val scaled = Bitmap.createScaledBitmap(cropped, INPUT_SIZE, INPUT_SIZE, true)
        
        if (cropped !== bitmap) {
            cropped.recycle() // 及时防泄漏泄露
        }

        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3) // 4 bytes per float
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // 2. 将 0-255 映射到 [-1, 1] 供 MobileNet 吸收
        for (pixelValue in intValues) {
            byteBuffer.putFloat(((pixelValue shr 16 and 0xFF) / 127.5f) - 1.0f) // R
            byteBuffer.putFloat(((pixelValue shr 8 and 0xFF) / 127.5f) - 1.0f)  // G
            byteBuffer.putFloat(((pixelValue and 0xFF) / 127.5f) - 1.0f)         // B
        }

        // 3. 结果容器包含 1024 个 Float 特征
        val outputBuffer = Array(1) { FloatArray(1024) }

        // 4. 发起推理
        interpreter?.run(byteBuffer, outputBuffer)

        scaled.recycle()
        return outputBuffer[0]
    }

    /**
     * 计算两个向量之间的余弦相似度
     */
    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        if (normA == 0.0f || normB == 0.0f) return 0.0f
        return dotProduct / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
    }

    /**
     * 去除 Bitmap 四周的黑边 / 状态栏 (移植自原先 PHash 库，消除异型屏背景干扰)
     */
    private fun cropBlackBorders(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        var top = 0
        var bottom = height - 1
        var left = 0
        var right = width - 1

        fun isBlack(pixel: Int): Boolean {
            // 这里兼容 0.0 - 255.0 做黑幅计算
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

        while (top < height) {
            if (!isLineBlack(top, true)) break
            top++
        }
        while (bottom > top) {
            if (!isLineBlack(bottom, true)) break
            bottom--
        }
        while (left < width) {
            if (!isLineBlack(left, false)) break
            left++
        }
        while (right > left) {
            if (!isLineBlack(right, false)) break
            right--
        }

        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1

        if (cropWidth <= 10 || cropHeight <= 10) return bitmap

        return if (left > 0 || right < width - 1 || top > 0 || bottom < height - 1) {
            Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        } else {
            bitmap
        }
    }

    fun clear() {
        fingerprintGroups.clear()
    }
}
