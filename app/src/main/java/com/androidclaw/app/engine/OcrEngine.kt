package com.androidclaw.app.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect
)

/**
 * 视觉文字识别引擎（基于 Google ML Kit）
 * 用于绕过微信等 App 禁用无障碍节点树的限制
 */
object OcrEngine {
    private const val TAG = "OcrEngine"
    
    // 初始化中文识别器
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 对给定的 Bitmap 进行文字识别
     * 返回所有识别到的文本块及坐标
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val visionText = recognizer.process(image).await()
            val blocks = mutableListOf<OcrTextBlock>()
            for (block in visionText.textBlocks) {
                val rect = block.boundingBox
                val text = block.text.replace("\n", " ")
                if (rect != null) {
                    blocks.add(OcrTextBlock(text, rect))
                }
            }
            blocks
        } catch (e: Exception) {
            Log.e(TAG, "OCR 识别异常", e)
            emptyList()
        }
    }

    /**
     * 查找包含指定关键字的文本块
     */
    suspend fun findTextContains(bitmap: Bitmap, keyword: String): List<OcrTextBlock> {
        val allBlocks = recognize(bitmap)
        return allBlocks.filter { it.text.contains(keyword, ignoreCase = true) }
    }
    
    /**
     * 查找完全匹配指定关键字的文本块
     */
    suspend fun findTextEquals(bitmap: Bitmap, keyword: String): List<OcrTextBlock> {
        val allBlocks = recognize(bitmap)
        return allBlocks.filter { it.text.equals(keyword, ignoreCase = true) }
    }
}
