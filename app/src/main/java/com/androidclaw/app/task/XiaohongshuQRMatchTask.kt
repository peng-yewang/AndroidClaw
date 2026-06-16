package com.androidclaw.app.task

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import com.androidclaw.app.engine.AudioVideoRecorder
import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.NodeFinder
import com.androidclaw.app.engine.OcrEngine
import com.androidclaw.app.engine.ScreenCapturer
import com.androidclaw.app.engine.VideoTrimmer
import com.androidclaw.app.log.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 小红书广告二维码自动扫码验证任务
 */
class XiaohongshuQRMatchTask : TaskScript {

    override val name = "小红书二维码匹配捕获"
    override val description = "在小红书通过扫描相册广告二维码定位目标广告页，并使用 OCR 验证博主后录制"
    override var configuredAdDurationMs: Long = 30_000L
    
    var recordResultCode: Int = 0
    var recordData: Intent? = null
    var targetBloggerName: String = ""

    private var recordStartWallMs = 0L
    private val taskId = "QR_" + System.currentTimeMillis()
    private var trimStartMs = 0L
    private var trimEndMs = 0L

    private fun log(message: String, level: LogManager.Level = LogManager.Level.INFO) {
        LogManager.log("[XHS-QR] $message", level)
    }

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        const val LOG_PREFIX_VIDEO_STATUS = "VIDEO_STATUS|"
        const val LOG_PREFIX_VIDEO_RESULT = "VIDEO_RESULT|"
    }

    override suspend fun execute(engine: AutomationEngine): Boolean {
        val context = getContext(engine) ?: run {
            log("无法获取 Context", LogManager.Level.ERROR)
            return false
        }

        if (targetBloggerName.isBlank()) {
            log("未提供目标博主名称，任务中止", LogManager.Level.ERROR)
            return false
        }

        var sharedProjection: MediaProjection? = null
        var avRecorder: AudioVideoRecorder? = null
        val capturer = ScreenCapturer(context)
        var tmpFile: File? = null
        trimStartMs = 0L
        trimEndMs = 0L

        try {
            log("🚀 启动小红书二维码匹配任务，目标博主: $targetBloggerName", LogManager.Level.SUCCESS)

            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (recordData == null) {
                log("录屏权限数据为空！请重新授权", LogManager.Level.ERROR)
                return false
            }
            sharedProjection = pm.getMediaProjection(recordResultCode, recordData!!)
            
            capturer.initScreenMetrics()
            if (!capturer.startWithProjection(sharedProjection)) return false

            tmpFile = createTempFile(context)
            val sw = capturer.screenWidth
            val sh = capturer.screenHeight
            val sd = capturer.screenDensity
            val rw = if (sw % 2 == 0) sw else sw - 1
            val rh = if (sh % 2 == 0) sh else sh - 1

            avRecorder = AudioVideoRecorder(context).also {
                it.prepare(sharedProjection, rw, rh, sd, tmpFile.absolutePath)
                it.start()
            }
            recordStartWallMs = System.currentTimeMillis()

            // 执行主流程
            if (!startAppAndNavigateToMyProfile(engine, capturer, sw, sh)) return false
            if (!navigateToScanAlbum(engine, capturer)) return false
            if (!findAndClickAdAlbum(engine, capturer)) return false
            
            // 点击第一张二维码图片，等待扫描并跳转，然后在落地页查找博主
            if (!scanFirstQRCodeAndFindBlogger(engine, capturer, sw, sh)) return false

            // 根据广告类型处理浏览逻辑
            handleAdPlaybackOrBrowse(engine, capturer, sw, sh)

            return true

        } catch (e: Exception) {
            log("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
            return false
        } finally {
            cleanupAndTrimVideo(context, capturer, avRecorder, sharedProjection, tmpFile)
        }
    }

    // ==========================================
    // 模块化子流程
    // ==========================================

    private suspend fun startAppAndNavigateToMyProfile(engine: AutomationEngine, capturer: ScreenCapturer, sw: Int, sh: Int): Boolean {
        log("正在启动小红书...", LogManager.Level.INFO)
        if (!engine.launchApp(XHS_PACKAGE)) {
            return false
        }
        engine.sleep(6000)

        log("正在点击【我】...", LogManager.Level.INFO)
        var clickedMyProfile = false
        
        // 1. 优先使用无障碍节点，但必须进行空间过滤，只找屏幕最底部的“我”
        val myNodes = NodeFinder.findByText("我")
        val bottomNode = myNodes.maxByOrNull { 
            val rect = android.graphics.Rect()
            it.getBoundsInScreen(rect)
            rect.bottom
        }
        
        if (bottomNode != null) {
            val rect = android.graphics.Rect()
            bottomNode.getBoundsInScreen(rect)
            // 确认该节点处于屏幕底部 20% 区域，绝不可能是瀑布流里的内容
            if (rect.centerY() > sh * 0.8f) {
                clickedMyProfile = engine.clickNode(bottomNode)
            }
        }

        // 2. 如果节点点击失败，使用 OCR 查找
        if (!clickedMyProfile) {
            log("节点未找到底部的【我】，尝试 OCR 查找...", LogManager.Level.WARN)
            val frame = capturer.captureFrame()
            if (frame != null) {
                val texts = OcrEngine.findTextContains(frame, "我")
                frame.recycle()
                // 过滤掉上半屏幕的“我”，并选出 Y 坐标最大的那一个（最靠底部的）
                val bottomText = texts.filter { it.boundingBox.centerY() * 2f > sh * 0.8f }
                                      .maxByOrNull { it.boundingBox.bottom }
                                      
                if (bottomText != null) {
                    engine.clickAt(bottomText.boundingBox.centerX() * 2f, bottomText.boundingBox.centerY() * 2f)
                    clickedMyProfile = true
                }
            }
        }
        
        // 3. 终极兜底物理坐标
        if (!clickedMyProfile) {
            log("使用绝对坐标兜底点击右下角【我】...", LogManager.Level.WARN)
            engine.clickAt(sw * 0.9f, sh * 0.95f)
        }
        engine.sleep(3000)
        return true
    }

    private suspend fun navigateToScanAlbum(engine: AutomationEngine, capturer: ScreenCapturer): Boolean {
        log("正在点击右上角【扫一扫】...", LogManager.Level.INFO)
        val scanNodeClicked = engine.clickDescription("扫一扫", 2000L)
        if (!scanNodeClicked) {
            log("使用兜底绝对坐标点击扫一扫 (1116, 211)", LogManager.Level.WARN)
            engine.clickAt(1116f, 211f)
        }
        engine.sleep(3000)

        log("正在点击【相册】...", LogManager.Level.INFO)
        if (!engine.clickText("相册", 2000L)) {
            val frame = capturer.captureFrame()
            if (frame != null) {
                val texts = OcrEngine.findTextContains(frame, "相册")
                frame.recycle()
                if (texts.isNotEmpty()) {
                    engine.clickAt(texts.first().boundingBox.centerX() * 2f, texts.first().boundingBox.centerY() * 2f)
                } else {
                    engine.clickAt(1013f, 2336f)
                }
            }
        }
        engine.sleep(3000)

        log("正在点击【全部】...", LogManager.Level.INFO)
        if (!engine.clickTextContains("全部", 2000L)) {
            val frame = capturer.captureFrame()
            if (frame != null) {
                val texts = OcrEngine.findTextContains(frame, "全部")
                frame.recycle()
                if (texts.isNotEmpty()) {
                    engine.clickAt(texts.first().boundingBox.centerX() * 2f, texts.first().boundingBox.centerY() * 2f)
                } else {
                    engine.clickAt(657f, 244f)
                }
            }
        }
        engine.sleep(2000)
        return true
    }

    private suspend fun findAndClickAdAlbum(engine: AutomationEngine, capturer: ScreenCapturer): Boolean {
        log("正在寻找【广告二维码】相册...", LogManager.Level.INFO)
        var foundAlbum = false
        for (i in 0..10) {
            if (engine.clickTextContains("广告二维码", 1000L)) {
                foundAlbum = true
                break
            }
            val frame = capturer.captureFrame()
            if (frame != null) {
                val texts = OcrEngine.findTextContains(frame, "广告二维码")
                frame.recycle()
                if (texts.isNotEmpty()) {
                    engine.clickAt(texts.first().boundingBox.centerX() * 2f, texts.first().boundingBox.centerY() * 2f)
                    foundAlbum = true
                    break
                }
            }
            engine.scrollDown(500)
            engine.sleep(1500)
        }

        if (!foundAlbum) {
            log("未找到名为【广告二维码】的相册，任务中止", LogManager.Level.ERROR)
            return false
        }
        engine.sleep(2000)
        return true
    }

    private suspend fun scanFirstQRCodeAndFindBlogger(engine: AutomationEngine, capturer: ScreenCapturer, sw: Int, sh: Int): Boolean {
        log("点击第一张二维码图片...", LogManager.Level.INFO)
        // 通常相册里第一张图就在头部偏下位置
        engine.clickAt(165f, 521f)
        engine.sleep(5000) // 等待扫描识别并跳转

        // 记录刚进入落地页的时间点
        val landingPageMs = System.currentTimeMillis() - recordStartWallMs

        log("正在寻找博主名称：$targetBloggerName ...", LogManager.Level.INFO)
        var foundBlogger = false
        val cleanTarget = targetBloggerName.replace(" ", "")
        
        for (i in 0..20) {
            var foundX = 0f
            var foundY = 0f
            var clickX = 0f
            var clickY = 0f
            var isFound = false
            var isOcr = false
            var matchedTextStr = ""

            // 1. 优先使用无障碍节点寻找博主名称 (速度快且 100% 精准)
            val nodes = NodeFinder.findByTextContains(targetBloggerName)
            if (nodes.isNotEmpty()) {
                val targetNode = nodes.first()
                val rect = android.graphics.Rect()
                targetNode.getBoundsInScreen(rect)
                foundX = rect.centerX().toFloat()
                foundY = rect.centerY().toFloat()
                clickX = foundX
                // Y坐标向上偏移屏幕高度的 15%，刚好点在这个帖子的封面大图正中心
                clickY = foundY - (sh * 0.15f)
                isFound = true
            } else {
                // 2. 节点未找到则使用 OCR 兜底 (带去空格强力容错)
                val frame = capturer.captureFrame()
                if (frame != null) {
                    val texts = OcrEngine.recognize(frame)
                    frame.recycle()
                    
                    // 打印出 OCR 识别到的所有文本片段，方便后续排查错误
                    val recognizedStrings = texts.joinToString(", ") { "[${it.text}]" }
                    log("OCR 扫描完毕，识别到以下文本: $recognizedStrings", LogManager.Level.INFO)
                    
                    // 去除所有空格进行比对，防止 OCR 将“女侠已退休”识别成“女 侠 已 退 休”导致漏判
                    val matchedText = texts.find { it.text.replace(" ", "").contains(cleanTarget, ignoreCase = true) }
                    if (matchedText != null) {
                        isOcr = true
                        matchedTextStr = matchedText.text
                        val targetBox = matchedText.boundingBox
                        foundX = targetBox.centerX() * 2f
                        foundY = targetBox.centerY() * 2f
                        clickX = foundX
                        clickY = foundY - (sh * 0.15f)
                        isFound = true
                    }
                }
            }

            if (isFound) {
                // 如果发现目标在屏幕最底部 20% 区域（可能被遮挡或在可视区边缘，无法看清全貌）
                if (foundY > sh * 0.8f) {
                    log("发现目标博主，但处于屏幕底部边缘 (Y:${foundY})，暂不点击，等待滑动调整...", LogManager.Level.INFO)
                    // 不进入 else 分支，放任程序执行后面的 engine.swipe，把它往上滑到屏幕中间
                } else {
                    if (isOcr) {
                        log("✅ OCR 成功识别到博主名称【${matchedTextStr}】，准备点击进入", LogManager.Level.SUCCESS)
                    } else {
                        log("✅ 无障碍节点成功识别到博主名称【$targetBloggerName】，准备点击进入", LogManager.Level.SUCCESS)
                    }
                    
                    // 动态设定裁剪起点：往前回溯 5 秒以保留最后两次滑动痕迹，不足 5 秒则从进入落地页开始
                    val foundTimeMs = System.currentTimeMillis() - recordStartWallMs
                    trimStartMs = maxOf(landingPageMs, foundTimeMs - 5000L)
                    
                    // 停顿 1 秒，让录屏明确记录到此时锁定目标的状态
                    engine.sleep(1000)
                    
                    engine.clickAt(clickX, clickY)
                    foundBlogger = true
                    break
                }
            }
            
            log("未找到博主或位置不佳，微小滑动页面...", LogManager.Level.INFO)
            // 缩小滑动距离 (原本 40% 屏幕高度可能一次性滑过了一整行卡片)
            // 延长滑动后的等待时间 (防止因惯性未停稳导致截图模糊，或网络延迟导致文字尚未渲染)
            engine.swipe(sw / 2f, sh * 0.75f, sw / 2f, sh * 0.55f, 1000L)
            engine.sleep(1500)
        }

        if (!foundBlogger) {
            log("❌ OCR 未识别到目标博主，任务失败", LogManager.Level.ERROR)
            return false
        }
        return true
    }

    private suspend fun handleAdPlaybackOrBrowse(engine: AutomationEngine, capturer: ScreenCapturer, sw: Int, sh: Int) {
        LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${taskId}|PROCESSING", LogManager.Level.INFO)
        
        // 自动探测当前广告类型：0 为视频，1 为图文
        val adType = autoDetectAdType(engine, capturer, sw, sh)
        
        if (adType == 0) {
            // 视频广告：等待用户配置的秒数
            log("开始等待广告视频播放 ${configuredAdDurationMs / 1000} 秒...", LogManager.Level.INFO)
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < configuredAdDurationMs && !engine.isCancelled) {
                engine.sleep(1000)
            }
        } else {
            // 图文广告：获取页码 -> 横滑 -> 找评论区
            log("进入图文广告，开始尝试横滑翻页...", LogManager.Level.INFO)
            
            var totalPages = 0
            
            // 1. 尝试用底层无障碍节点提取页码 (例如 1/18)
            val pageNodes = NodeFinder.findByTextContains("/")
            for (node in pageNodes) {
                val text = node.text?.toString() ?: ""
                if (text.matches(Regex("\\d+/\\d+"))) {
                    val parts = text.split("/")
                    if (parts.size == 2) {
                        totalPages = parts[1].toIntOrNull() ?: 0
                        log("✅ 通过无障碍节点提取到总页数: $totalPages ($text)", LogManager.Level.INFO)
                        break
                    }
                }
            }
            
            // 2. 如果节点失败，使用 OCR 提取页码兜底
            if (totalPages <= 0) {
                log("无障碍节点未找到页码，尝试使用 OCR 识别右上角...", LogManager.Level.WARN)
                val frame = capturer.captureFrame()
                if (frame != null) {
                    val texts = OcrEngine.recognize(frame)
                    for (tb in texts) {
                        val txt = tb.text.replace(" ", "")
                        if (txt.matches(Regex("\\d+/\\d+"))) {
                            val parts = txt.split("/")
                            if (parts.size == 2) {
                                totalPages = parts[1].toIntOrNull() ?: 0
                                log("✅ 通过 OCR 提取到总页数: $totalPages ($txt)", LogManager.Level.INFO)
                                break
                            }
                        }
                    }
                    frame.recycle()
                }
            }
            
            // 3. 执行横滑翻页
            if (totalPages > 1) {
                val swipeCount = totalPages - 1
                log("图片共 $totalPages 页，开始左滑 $swipeCount 次...", LogManager.Level.INFO)
                for (p in 0 until swipeCount) {
                    if (engine.isCancelled) break
                    // 上半部分左滑 (翻看图片)
                    engine.swipe(sw * 0.8f, sh * 0.3f, sw * 0.2f, sh * 0.3f, 500)
                    engine.sleep(1500)
                }
            } else if (totalPages == 1) {
                log("检测为单图文，无需左滑", LogManager.Level.INFO)
            } else {
                log("⚠️ 未识别到明确的页码标识(可能是单图文或获取失败)，继续执行下滑逻辑...", LogManager.Level.WARN)
            }
            
            // 4. 向上滑动寻找评论区
            log("横滑完毕，开始向下滑动寻找评论区...", LogManager.Level.INFO)
            var foundComment = false
            for (scrollCount in 0..15) {
                if (engine.isCancelled) break
                
                val frame = capturer.captureFrame()
                if (frame != null) {
                    val texts = OcrEngine.recognize(frame)
                    frame.recycle()
                    val hasComment = texts.any { it.text.contains("条评论") }
                    if (hasComment) {
                        foundComment = true
                        log("✅ 已到达评论区，停止滑动", LogManager.Level.SUCCESS)
                        break
                    }
                }
                // 向上滑 (页面向下滚动)
                engine.swipe(sw / 2f, sh * 0.7f, sw / 2f, sh * 0.4f, 1000)
                engine.sleep(1000)
            }
            
            if (!foundComment) {
                log("⚠️ 滚动多次未找到明显评论区标志，强制停止", LogManager.Level.WARN)
            }
            
            engine.sleep(2000) // 原地停留 2 秒
        }

        log("✅ 播放/浏览完毕，准备裁切视频", LogManager.Level.SUCCESS)
        trimEndMs = System.currentTimeMillis() - recordStartWallMs
    }

    /**
     * 三位一体动态判定广告类型 (节点 + OCR + 画面静默比对)
     * @return 0: 视频广告, 1: 图文广告
     */
    private suspend fun autoDetectAdType(engine: AutomationEngine, capturer: ScreenCapturer, sw: Int, sh: Int): Int {
        log("开始自动探测广告类型...", LogManager.Level.INFO)
        engine.sleep(2500) // 等待页面和图片加载完毕

        // 1. 无障碍节点特征：页码 -> 图文
        val pageNodes = NodeFinder.findByTextContains("/")
        for (node in pageNodes) {
            val text = node.text?.toString()?.replace(" ", "") ?: ""
            if (text.matches(Regex("\\d+/\\d+"))) {
                log("检测到无障碍节点页码: $text，判定为 [多图文广告]", LogManager.Level.INFO)
                return 1
            }
        }

        // 2. 无障碍节点特征：进度条/播放时间 -> 视频
        if (NodeFinder.findByClassName("SeekBar").isNotEmpty() || 
            NodeFinder.findByDescription("播放").isNotEmpty() || 
            NodeFinder.findByDescription("暂停").isNotEmpty()) {
            log("检测到视频相关节点(进度条/播放按钮)，判定为 [视频广告]", LogManager.Level.INFO)
            return 0
        }
        val timeNodes = NodeFinder.findByTextContains(":")
        for (node in timeNodes) {
            val text = node.text?.toString()?.replace(" ", "") ?: ""
            if (text.matches(Regex(".*\\d{1,2}:\\d{2}.*"))) {
                log("检测到视频时间节点: $text，判定为 [视频广告]", LogManager.Level.INFO)
                return 0
            }
        }

        // 3. 抓取第一帧进行 OCR 补充识别
        var isVideoFromOcr = false
        var isImageFromOcr = false
        val frame1 = capturer.captureFrame()
        if (frame1 != null) {
            val texts = OcrEngine.recognize(frame1)
            for (tb in texts) {
                val txt = tb.text.replace(" ", "")
                if (txt.matches(Regex("\\d+/\\d+"))) {
                    log("OCR识别到页码: $txt，判定为 [多图文广告]", LogManager.Level.INFO)
                    isImageFromOcr = true
                    break
                }
                if (Regex("\\d{1,2}:\\d{2}").containsMatchIn(txt)) {
                    log("OCR识别到时间戳: $txt，判定为 [视频广告]", LogManager.Level.INFO)
                    isVideoFromOcr = true
                    break
                }
            }
        }

        if (isImageFromOcr) { frame1?.recycle(); return 1 }
        if (isVideoFromOcr) { frame1?.recycle(); return 0 }

        // 4. 终极兜底：物理画面变动比对法 (解决单图文与无UI视频的区分)
        log("节点与OCR均未发现明显特征，采用物理画面变动比对法兜底...", LogManager.Level.WARN)
        engine.sleep(2000) // 让视频播放两秒
        val frame2 = capturer.captureFrame()

        if (frame1 != null && frame2 != null) {
            var diffCount = 0
            var totalCount = 0
            val checkWidth = sw / 2
            val checkHeight = sh / 3
            val startX = sw / 4
            val startY = sh / 3 // 截取屏幕中间偏下一点的核心内容区

            // 每隔 15 个像素采样一个点进行比对，提高效率
            val step = 15
            for (x in startX until (startX + checkWidth) step step) {
                for (y in startY until (startY + checkHeight) step step) {
                    val p1 = frame1.getPixel(x, y)
                    val p2 = frame2.getPixel(x, y)
                    // 允许轻微的视频压缩噪点误差
                    if (Math.abs(android.graphics.Color.red(p1) - android.graphics.Color.red(p2)) > 15 ||
                        Math.abs(android.graphics.Color.green(p1) - android.graphics.Color.green(p2)) > 15 ||
                        Math.abs(android.graphics.Color.blue(p1) - android.graphics.Color.blue(p2)) > 15) {
                        diffCount++
                    }
                    totalCount++
                }
            }
            frame1.recycle()
            frame2.recycle()

            val diffRatio = diffCount.toFloat() / totalCount
            log("画面像素变化率: $diffRatio ($diffCount / $totalCount)", LogManager.Level.INFO)
            
            if (diffRatio > 0.05f) {
                log("2秒内画面发生大面积变动，判定为 [视频广告]", LogManager.Level.INFO)
                return 0
            } else {
                log("2秒内画面基本静止，判定为 [单图文广告]", LogManager.Level.INFO)
                return 1
            }
        }
        
        frame1?.recycle()
        frame2?.recycle()

        // 如果获取截屏失败，默认按照视频处理
        log("物理比对失败，默认降级为 [视频广告]", LogManager.Level.WARN)
        return 0
    }

    private suspend fun cleanupAndTrimVideo(
        context: Context, capturer: ScreenCapturer, 
        avRecorder: AudioVideoRecorder?, sharedProjection: MediaProjection?, 
        tmpFile: File?
    ) {
        withContext(kotlinx.coroutines.NonCancellable) {
            capturer.stop()
            try { avRecorder?.stop() } catch (_: Exception) {}
            try { avRecorder?.release() } catch (_: Exception) {}
            try { sharedProjection?.stop() } catch (_: Exception) {}

            if (trimEndMs > 0 && trimStartMs > 0 && tmpFile != null && tmpFile.exists()) {
                withContext(Dispatchers.IO) {
                    val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) 
                        ?: File(context.filesDir, "Movies").also { if(!it.exists()) it.mkdirs() }
                    if (!outDir.exists()) outDir.mkdirs()

                    try {
                        val outName = "XiaohongshuQRAd_${targetBloggerName}_${System.currentTimeMillis()}.mp4"
                        val outFile = File(outDir, outName)
                        
                        log("正在裁剪片段: $outName (Range: ${trimStartMs} - ${trimEndMs}ms)", LogManager.Level.INFO)
                        
                        if (VideoTrimmer.trim(tmpFile.absolutePath, outFile.absolutePath, trimStartMs, trimEndMs)) {
                            log("🎬 裁剪成功: $outName", LogManager.Level.SUCCESS)
                            log("${LOG_PREFIX_VIDEO_RESULT}${taskId}|${outFile.absolutePath}", LogManager.Level.INFO)
                        }
                    } catch (te: Exception) {
                        log("❌ 裁剪片段失败: ${te.message}", LogManager.Level.ERROR)
                    }
                }
            }
            try { tmpFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun getContext(engine: AutomationEngine): Context? {
        return try {
            val field = AutomationEngine::class.java.getDeclaredField("context")
            field.isAccessible = true
            field.get(engine) as? Context
        } catch (_: Exception) { null }
    }
    
    private fun createTempFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return File(dir, "XhsQRImage_TEMP.mp4").also { it.delete() }
    }
}
