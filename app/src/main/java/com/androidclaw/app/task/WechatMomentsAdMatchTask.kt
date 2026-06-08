package com.androidclaw.app.task

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Environment
import com.androidclaw.app.engine.AudioVideoRecorder
import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.MobileNetFingerprintManager
import com.androidclaw.app.engine.ScreenCapturer
import com.androidclaw.app.engine.VideoFingerprintManager
import com.androidclaw.app.engine.VideoTrimmer
import com.androidclaw.app.engine.OcrEngine
import com.androidclaw.app.engine.OcrTextBlock
import com.androidclaw.app.log.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 微信朋友圈图片广告自动巡检与采集任务 (重构为基于纯 OCR 与图像截取的视觉方案)
 */
class WechatMomentsAdMatchTask : TaskScript {

    override val name = "微信朋友圈图片广告匹配捕获"
    override val description = "在微信朋友圈时光轴中寻找目标广告图片，并进行二阶段全屏精细化匹配"
    override var configuredAdDurationMs: Long = 0L
    var targetVideoTasks: List<VideoTask> = emptyList()
    var recordResultCode: Int = 0
    var recordData: Intent? = null
    var algorithmType: Int = 0
    var enableRotationMatch: Boolean = false

    private var recordStartWallMs = 0L

    private fun log(message: String, level: LogManager.Level = LogManager.Level.INFO) {
        LogManager.log("[WxMoments] $message", level)
    }

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val MONITOR_TIMEOUT_MS = 240_000L // 朋友圈巡检最长 4 分钟
        const val LOG_PREFIX_VIDEO_STATUS = "VIDEO_STATUS|"
        const val LOG_PREFIX_VIDEO_RESULT = "VIDEO_RESULT|"
    }

    private data class AdInterval(val videoId: String, val startMs: Long, val endMs: Long)

    override suspend fun execute(engine: AutomationEngine): Boolean {
        val context = getContext(engine) ?: run {
            log("无法获取 Context", LogManager.Level.ERROR)
            return false
        }

        var sharedProjection: android.media.projection.MediaProjection? = null
        var avRecorder: AudioVideoRecorder? = null
        val capturer = ScreenCapturer(context)
        var tmpFile: File? = null
        val adIntervals = mutableListOf<AdInterval>()

        try {
            log("🚀 启动微信朋友圈广告视觉 OCR 匹配循环", LogManager.Level.SUCCESS)

            if (targetVideoTasks.isEmpty()) {
                log("未提供匹配目标，任务中止", LogManager.Level.ERROR)
                return false
            }
            
            // 1. 初始化指纹加速引擎并提取图片指纹
            val fpManager = if (algorithmType == 0) VideoFingerprintManager() else null
            val aiManager = if (algorithmType == 1) MobileNetFingerprintManager(context) else null
            fpManager?.enableRotationMatch = enableRotationMatch
            aiManager?.enableRotationMatch = enableRotationMatch

            val targetOcrTexts = mutableMapOf<String, List<String>>()

            for (task in targetVideoTasks) {
                try {
                    val inputStream = context.contentResolver.openInputStream(task.uri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        // 提取目标图片的 OCR 文本，用于极速粗筛
                        val texts = OcrEngine.recognize(bitmap).map { it.text }
                        val validTexts = texts.filter { it.length >= 4 && !it.contains("广告") && !it.contains("详情") }
                        if (validTexts.isNotEmpty()) {
                            targetOcrTexts[task.id] = validTexts
                            log("已提取目标 [${task.name}] 的特征文本: $validTexts", LogManager.Level.INFO)
                        }

                        if (algorithmType == 0) {
                            val hash = fpManager?.computePHash(bitmap)
                            if (hash != null && hash != 0L) {
                                fpManager.addFingerprint(task.id, listOf(hash))
                            }
                        } else {
                            val vector = aiManager?.extractSingleFrame(bitmap)
                            if (vector != null) {
                                aiManager.addFingerprint(task.id, listOf(vector))
                            }
                        }
                        bitmap.recycle()
                    } else {
                        // 如果 Bitmap 解码失败，说明很可能是视频文件，使用视频提取逻辑作为降级
                        if (algorithmType == 0) fpManager?.extractFromUri(context, task.id, task.uri, 4, true)
                        else aiManager?.extractFromUri(context, task.id, task.uri, 1)
                    }
                } catch (e: Exception) {
                    log("解析目标媒体指纹失败: ${task.name}, ${e.message}", LogManager.Level.ERROR)
                }
            }

            if ((fpManager?.isLoaded != true) && (aiManager?.isLoaded != true)) {
                log("指纹初始化失败，任务中止。", LogManager.Level.ERROR)
                return false
            }

            // 2. 准备环境与录像
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (recordData == null) {
                log("录屏权限数据(recordData)为空！请重新授权", LogManager.Level.ERROR)
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

            // 音视频同步录像
            avRecorder = AudioVideoRecorder(context).also {
                it.prepare(sharedProjection!!, rw, rh, sd, tmpFile.absolutePath)
                it.start()
            }
            recordStartWallMs = System.currentTimeMillis()

            // 3. 业务主循环
            val finishedTasks = mutableSetOf<String>()
            var currentCalendarStartMs = 0L
            
            if (!navigateToMoments(engine, capturer)) {
                log("导航至朋友圈失败，任务中止", LogManager.Level.ERROR)
                return false
            }

            val startTimeLimit = System.currentTimeMillis()
            var scrollCount = 0
            // 追踪上一帧是否出现过"广告"标签，用于检测滚动过度
            var prevFrameHadAdLabel = false
            var microScrollRetries = 0  // 微调滑动重试计数，防止死循环
            var consecutiveAdMismatchCount = 0  // 连续广告未命中计数，用于跳过非目标广告

            while (!engine.isCancelled && finishedTasks.size < targetVideoTasks.size && (System.currentTimeMillis() - startTimeLimit < MONITOR_TIMEOUT_MS)) {
                log("👀 正在基于截屏视觉识别朋友圈 (当前滚动次数: $scrollCount)", LogManager.Level.INFO)
                
                var clickedAndMatched = false
                var currentFrameHasAdLabel = false
                val frame = capturer.captureFrame()
                if (frame != null) {
                    // 检查是否到达朋友圈底部
                    val texts = OcrEngine.recognize(frame)
                    
                    // 【调试日志】输出当前屏幕所有的 OCR 识别结果
                    val currentScreenTexts = texts.joinToString(" ") { it.text }
                    log("🔍 当前屏幕 OCR 识别文本: $currentScreenTexts", LogManager.Level.INFO)
                    
                    // 标记当前帧是否存在"广告"标签（用于后续滚动过度检测）
                    currentFrameHasAdLabel = texts.any { it.text.contains("广告") }
                    
                    if (texts.any { it.text.contains("只展示最近") || it.text.contains("暂无更多") }) {
                        log("🏁 已到达微信朋友圈底部，未找到更多匹配内容，结束巡检", LogManager.Level.SUCCESS)
                        frame.recycle()
                        break
                    }

                    // 极速粗筛：如果当前屏幕文本包含目标的特征文本，优先命中
                    var fastTextMatchedId: String? = null
                    for ((taskId, features) in targetOcrTexts) {
                        if (finishedTasks.contains(taskId)) continue
                        var matchCount = 0
                        for (feature in features) {
                            if (currentScreenTexts.contains(feature)) matchCount++
                        }
                        if (matchCount >= 1 && features.isNotEmpty()) {
                            log("⚡ OCR 极速粗筛命中特征文本！目标 ID: $taskId", LogManager.Level.SUCCESS)
                            fastTextMatchedId = taskId
                            break
                        }
                    }

                    // 提取候选图片区域 (注意：frame 宽高是物理屏幕的 1/2)
                    val cardBoundsList = getCardImageBoundsWithOcr(frame, texts, fastTextMatchedId != null)
                    
                    // 【增强逻辑 v3】位置微调：以"广告"标签（头像行代理）作为定位锚点
                    // 原则：当头像/广告标签处于屏幕上方可见位置 (5%-20%)，卡片内容一定是完整的
                    val isCutOffAtBottom = cardBoundsList.any { it.bottom >= frame.height - 20 }
                    val isTextAtBottomWithNoImage = fastTextMatchedId != null && cardBoundsList.isEmpty()
                    
                    val adBlock = texts.find { it.text.contains("广告") }
                    val adCenterY = adBlock?.boundingBox?.centerY()
                    
                    // —— 底部不完整检测：广告标签在屏幕下半部 ——
                    val adLabelInLowerHalf = adCenterY != null && adCenterY > frame.height * 0.45
                    val adCardsIncomplete = cardBoundsList.isEmpty() || isCutOffAtBottom
                    val needsScrollUp = isTextAtBottomWithNoImage || 
                        (adLabelInLowerHalf && adCardsIncomplete)
                    
                    // —— 顶部遮挡检测：文本特征命中但头像/广告标签不可见或贴近顶边 ——
                    val adHeaderOccludedAtTop = if (fastTextMatchedId != null && adBlock != null) {
                        // 广告标签的顶边贴近屏幕顶部 (< 5%)，说明头像可能被截断
                        adBlock.boundingBox.top < frame.height * 0.05
                    } else if (fastTextMatchedId != null && adBlock == null) {
                        // 文本特征命中但屏幕上没有"广告"标签，头像行可能已滚到屏幕上方外
                        true
                    } else false
                    
                    if (needsScrollUp && microScrollRetries < 3 && consecutiveAdMismatchCount < 2) {
                        microScrollRetries++
                        val adY = adCenterY ?: (frame.height - 50)
                        // 目标：将广告标签/头像移动到屏幕顶部可见位置 (约10%)，留最大空间给卡片图片
                        val targetY = (frame.height * 0.10f).toInt()
                        val scrollDistance = ((adY - targetY) * 2).coerceIn(300, 1400)
                        
                        log("⬆️ 广告需上滑重定位 (adY=$adY, 屏高=${frame.height}, 卡片数=${cardBoundsList.size}, 重试=$microScrollRetries/3)，微调上滑 ${scrollDistance}px...", LogManager.Level.INFO)
                        val screenCenterX = 540f
                        val startY = 1500f
                        val endY = startY - scrollDistance
                        // duration=2000ms 低速滑动，消除惯性
                        engine.swipe(screenCenterX, startY, screenCenterX, endY, 2000)
                        engine.sleep(1500)
                        frame.recycle()
                        continue
                    }
                    
                    if (adHeaderOccludedAtTop && microScrollRetries < 3 && consecutiveAdMismatchCount < 2) {
                        microScrollRetries++
                        // 头像在顶部被截断，需要向下滑动将头像拉回屏幕内
                        val pullDownDistance = if (adBlock != null) {
                            // 广告标签可见但贴顶，小幅下拉
                            ((frame.height * 0.15f - adBlock.boundingBox.top) * 2).toInt().coerceIn(200, 600)
                        } else {
                            // 广告标签完全不可见，较大幅度下拉
                            500
                        }
                        log("⬇️ 广告头像在顶部被遮挡，向下微调 ${pullDownDistance}px 将头像拉回可视区...", LogManager.Level.INFO)
                        // duration=2000ms 低速滑动，消除惯性
                        engine.swipe(540f, 800f, 540f, 800f + pullDownDistance, 2000)
                        engine.sleep(1500)
                        frame.recycle()
                        continue
                    }
                    
                    // 本轮未触发任何微调，重置重试计数
                    if (!needsScrollUp && !adHeaderOccludedAtTop) microScrollRetries = 0
                    
                    log("推算出 ${cardBoundsList.size} 个疑似图片卡片区域", LogManager.Level.INFO)
                    
                    for (bounds in cardBoundsList) {
                        val croppedCard = cropRegion(frame, bounds)
                        if (croppedCard != null) {
                            var matchedVideoId: String? = fastTextMatchedId

                            if (matchedVideoId == null) {
                                // ---- [DEBUG] 无论是否匹配成功，先保存每一次参与比对的候选切图供验证 ----
                                try {
                                    val debugDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                                    val debugFile = File(debugDir, "WxMoments_MatchCandidate_${System.currentTimeMillis()}.png")
                                    val fos = java.io.FileOutputStream(debugFile)
                                    croppedCard.compress(Bitmap.CompressFormat.PNG, 100, fos)
                                    fos.close()
                                    log("💾 [DEBUG] 准备进行图像相似度比对，已将当前候选截图保存至: ${debugFile.absolutePath}", LogManager.Level.INFO)
                                } catch (e: Exception) {
                                    log("⚠️ [DEBUG] 候选截图保存失败: ${e.message}", LogManager.Level.WARN)
                                }
                                // ------------------------------------------------
                                
                                // 一阶段：图像粗筛比对
                                if (algorithmType == 0) {
                                    fpManager?.matchThreshold = 16
                                } else {
                                    aiManager?.cosineMatchThreshold = 0.65f
                                }
                                
                                val matches = if (algorithmType == 0) {
                                    fpManager?.matchScreenshots(croppedCard, finishedTasks) ?: emptyList()
                                } else {
                                    aiManager?.matchScreenshots(croppedCard, finishedTasks) ?: emptyList()
                                }
                                if (matches.isNotEmpty()) {
                                    matchedVideoId = matches.first()
                                }
                            }
                            
                            if (matchedVideoId != null) {
                                log("🎯 粗筛确认目标卡片 $matchedVideoId (测试模式：保存截图后将终止)", LogManager.Level.SUCCESS)
                                
                                // ---- [DEBUG] 保存初筛成功的局部截图供验证 ----
                                try {
                                    val debugDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                                    val debugFile = File(debugDir, "WxMoments_RoughHit_${System.currentTimeMillis()}.png")
                                    val fos = java.io.FileOutputStream(debugFile)
                                    croppedCard.compress(Bitmap.CompressFormat.PNG, 100, fos)
                                    fos.close()
                                    log("💾 [DEBUG] 已保存初筛成功的局部截图至: ${debugFile.absolutePath}", LogManager.Level.INFO)
                                } catch (e: Exception) {
                                    log("⚠️ [DEBUG] 局部截图保存失败: ${e.message}", LogManager.Level.WARN)
                                }
                                // ------------------------------------------------
                                
                                croppedCard.recycle()
                                
                                /* ==================== 调试阶段暂时注释二阶段与后续交互 ====================
                                // 点击进入全屏预览 (注意：坐标需乘以 2 换算为物理屏幕坐标)
                                engine.clickAt(bounds.centerX() * 2f, bounds.centerY() * 2f)
                                engine.sleep(3000) // 等待大图完全加载
                                
                                // 二阶段：精筛比对
                                log("🔎 开始二阶段精筛比对...", LogManager.Level.INFO)
                                if (algorithmType == 0) {
                                    fpManager?.matchThreshold = 10
                                } else {
                                    aiManager?.cosineMatchThreshold = 0.80f
                                }
                                
                                var fineMatchSuccess = false
                                val fineStartTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - fineStartTime < 5000 && !engine.isCancelled) {
                                    val detailFrame = capturer.captureFrame()
                                    if (detailFrame != null) {
                                        val fineMatches = if (algorithmType == 0) {
                                            fpManager?.matchScreenshots(detailFrame, finishedTasks) ?: emptyList()
                                        } else {
                                            aiManager?.matchScreenshots(detailFrame, finishedTasks) ?: emptyList()
                                        }
                                        detailFrame.recycle()
                                        
                                        if (fineMatches.contains(matchedVideoId)) {
                                            fineMatchSuccess = true
                                            break
                                        }
                                    }
                                    engine.sleep(500)
                                }
                                
                                if (fineMatchSuccess) {
                                    log("⭐ 二阶段精筛匹配成功！", LogManager.Level.SUCCESS)
                                    finishedTasks.add(matchedVideoId)
                                    LogManager.log("${LOG_PREFIX_VIDEO_STATUS}${matchedVideoId}|PROCESSING", LogManager.Level.INFO)
                                    
                                    // 时间标记：日历对齐
                                    log("正在切换至系统日历 (并标记时间戳)...", LogManager.Level.INFO)
                                    if (!engine.launchApp("com.android.calendar")) {
                                        if (!engine.launchApp("com.huawei.calendar")) {
                                            engine.launchApp("com.hihonor.calendar")
                                        }
                                    }
                                    engine.sleep(2000)
                                    currentCalendarStartMs = System.currentTimeMillis() - recordStartWallMs
                                    engine.sleep(4000)
                                    
                                    // 回桌面 -> 重回微信 (大图全屏)
                                    log("返回微信朋友圈...", LogManager.Level.INFO)
                                    engine.goHome()
                                    engine.sleep(2000)
                                    engine.launchApp(WECHAT_PACKAGE)
                                    engine.sleep(4000)
                                    
                                    // 微信大图下方的广告通常有“查看详情”等标签
                                    jumpToLandingPageAndScroll(engine, capturer)
                                    
                                    // 返回大图预览，然后退出全屏
                                    log("返回朋友圈并清理会话...", LogManager.Level.INFO)
                                    engine.launchApp(WECHAT_PACKAGE)
                                    engine.sleep(3000)
                                    // 退出大图预览
                                    engine.goBack()
                                    engine.sleep(2000)
                                    
                                    val finalEndRel = System.currentTimeMillis() - recordStartWallMs
                                    adIntervals.add(AdInterval(matchedVideoId, currentCalendarStartMs, finalEndRel))
                                    
                                    cleanBackgroundTasks(engine)
                                    clickedAndMatched = true
                                    break
                                } else {
                                    log("⚠️ 精筛未命中，退出全屏预览", LogManager.Level.WARN)
                                    engine.goBack()
                                    engine.sleep(2000)
                                }
                                ========================================================================= */
                                return true // 调试模式下保存完图片直接结束任务
                            } else {
                                croppedCard.recycle()
                            }
                        }
                    }
                    frame.recycle()
                }
                
                // 追踪连续广告未命中次数：当屏幕上有广告但匹配失败时累加，广告消失时重置
                if (currentFrameHasAdLabel && !clickedAndMatched) {
                    consecutiveAdMismatchCount++
                } else if (!currentFrameHasAdLabel) {
                    consecutiveAdMismatchCount = 0
                }
                // 连续 2+ 次未命中，说明这是非目标广告，不再特殊处理
                val adIsNonTarget = consecutiveAdMismatchCount >= 2

                if (clickedAndMatched) {
                    scrollCount = 0
                    consecutiveAdMismatchCount = 0
                    engine.sleep(3000)
                } else {
                    // 检测是否发生了"滚动过度"：上一帧有广告标签，这一帧消失了
                    // 但如果该广告已确认为非目标，则跳过回退，直接继续前进
                    if (prevFrameHadAdLabel && !currentFrameHasAdLabel && !adIsNonTarget && finishedTasks.size < targetVideoTasks.size) {
                        log("🔄 检测到滚动过度！上一帧有广告标签但当前帧已消失，回退滑动...", LogManager.Level.WARN)
                        // 向上回退：duration=2000ms 低速滑动消除惯性
                        engine.swipe(540f, 800f, 540f, 1600f, 2000)
                        engine.sleep(1500)
                        prevFrameHadAdLabel = false
                        microScrollRetries = 0
                    } else {
                        if (adIsNonTarget && currentFrameHasAdLabel) {
                            log("⏩ 该广告已连续 ${consecutiveAdMismatchCount} 次未命中，确认为非目标广告，跳过并正常滚动", LogManager.Level.INFO)
                        } else {
                            log("⬇️ 未匹配到，滑动朋友圈时光轴", LogManager.Level.INFO)
                        }
                        // 所有滚动统一使用 swipe + 长 duration 消除惯性
                        val screenSize = engine.getScreenSize()
                        val sx = screenSize.first / 2f
                        if (currentFrameHasAdLabel && !adIsNonTarget) {
                            log("📏 检测到屏幕上有广告标签，使用小步滚动以避免跳过", LogManager.Level.INFO)
                            // 小步滚动：滑动屏幕高度的 25%，duration=2000ms 无惯性
                            engine.swipe(sx, screenSize.second * 0.60f, sx, screenSize.second * 0.35f, 2000)
                            engine.sleep(2000)
                        } else {
                            // 正常滚动（或非目标广告跳过）：滑动屏幕高度的 40%，duration=2000ms 无惯性
                            engine.swipe(sx, screenSize.second * 0.70f, sx, screenSize.second * 0.30f, 2000)
                            engine.sleep(3000)
                        }
                        scrollCount++
                        prevFrameHadAdLabel = currentFrameHasAdLabel
                    }
                }
            }
            return finishedTasks.isNotEmpty()
        } catch (e: Exception) {
            log("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
            return false
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                capturer.stop()
                try { avRecorder?.stop() } catch (_: Exception) {}
                try { avRecorder?.release() } catch (_: Exception) {}
                avRecorder = null
                try { sharedProjection?.stop() } catch (_: Exception) {}
                sharedProjection = null

                if (adIntervals.isNotEmpty() && tmpFile != null && tmpFile.exists()) {
                    withContext(Dispatchers.IO) {
                        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) 
                            ?: File(context.filesDir, "Movies").also { if(!it.exists()) it.mkdirs() }
                        if (!outDir.exists()) outDir.mkdirs()

                        for (interval in adIntervals) {
                            try {
                                val taskInfo = targetVideoTasks.find { it.id == interval.videoId }
                                val outName = "WxMomentsImageAd_${taskInfo?.name?.substringBeforeLast('.') ?: "clip"}_${System.currentTimeMillis()}.mp4"
                                val outFile = File(outDir, outName)
                                log("正在裁剪片段: $outName (Range: ${interval.startMs} - ${interval.endMs}ms)", LogManager.Level.INFO)
                                
                                if (VideoTrimmer.trim(tmpFile.absolutePath, outFile.absolutePath, interval.startMs, interval.endMs)) {
                                    log("🎬 裁剪成功: $outName", LogManager.Level.SUCCESS)
                                    log("${LOG_PREFIX_VIDEO_RESULT}${interval.videoId}|${outFile.absolutePath}", LogManager.Level.INFO)
                                }
                            } catch (te: Exception) {
                                log("❌ 裁剪片段失败: ${te.message}", LogManager.Level.ERROR)
                            }
                        }
                    }
                }
                try { tmpFile?.delete() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun navigateToMoments(engine: AutomationEngine, capturer: ScreenCapturer): Boolean {
        log("正在开启微信...", LogManager.Level.INFO)
        if (!engine.launchApp(WECHAT_PACKAGE)) {
            log("启动微信失败", LogManager.Level.ERROR)
            return false
        }
        engine.sleep(4000)

        // 寻找并点击“发现”
        var frame = capturer.captureFrame()
        var clicked = false
        if (frame != null) {
            val texts = OcrEngine.recognize(frame)
            val discover = texts.find { it.text.contains("发现") }
            if (discover != null) {
                engine.clickAt(discover.boundingBox.centerX() * 2f, discover.boundingBox.centerY() * 2f)
                clicked = true
                log("视觉定位到『发现』，执行点击", LogManager.Level.INFO)
            }
            frame.recycle()
        }
        
        if (!clicked) {
            log("OCR未找到『发现』标签，尝试使用物理坐标 (845, 2649) 作为备用...", LogManager.Level.WARN)
            engine.clickAt(845f, 2649f)
        }
        engine.sleep(2000)

        // 寻找并点击“朋友圈”
        frame = capturer.captureFrame()
        clicked = false
        if (frame != null) {
            val texts = OcrEngine.recognize(frame)
            val moments = texts.find { it.text.contains("朋友圈") }
            if (moments != null) {
                engine.clickAt(moments.boundingBox.centerX() * 2f, moments.boundingBox.centerY() * 2f)
                clicked = true
                log("视觉定位到『朋友圈』，执行点击", LogManager.Level.INFO)
            }
            frame.recycle()
        }
        
        if (!clicked) {
            log("OCR未找到『朋友圈』入口，尝试点击物理坐标 (738, 396)...", LogManager.Level.WARN)
            engine.clickAt(738f, 396f)
        }
        engine.sleep(6000)
        return true
    }

    private fun getCardImageBoundsWithOcr(frame: Bitmap, texts: List<OcrTextBlock>, hasFastTextMatch: Boolean = false): List<android.graphics.Rect> {
        val boundsList = mutableListOf<android.graphics.Rect>()
        if (texts.isEmpty()) return emptyList()

        val sw = frame.width
        val sh = frame.height
        
        // 1. 严格模式：寻找带有 "广告" 标签的文本块
        val adBlocks = texts.filter { it.text.contains("广告") }
        
        for (adBlock in adBlocks) {
            val adTop = adBlock.boundingBox.top
            
            val textsBelow = texts.filter { it.boundingBox.top > adTop - (sh * 0.02f) }
                                  .sortedBy { it.boundingBox.top }
            if (textsBelow.isEmpty()) continue

            // =========================================================================
            // 【多重候选打分机制 (Multi-Candidate Scoring)】
            // 微信朋友圈广告排版复杂（如：图片内带文字、完全无正文、底部带小程序卡片等）。
            // 靠单一算法很难每次都完美切出图片。因此我们采用“多重策略”生成多个不同的疑似截图区域，
            // 一起送给 AI 进行比对，只要任意一个候选框命中，即视为成功。
            // =========================================================================

            // --- 步骤 1：寻找“配图的物理天花板（mainTextBottom）” ---
            // 默认情况：应对“无正文广告”（例如：浪琴表广告，图片直接紧贴着头像）。
            // 此时配图的起始位置就是“广告”标签的底边。
            var mainTextBottom = adBlock.boundingBox.bottom
            
            // 判断是否存在真实正文：如果第一段文字紧紧贴着头像下方，说明这就是正文（如：99元套餐广告）
            if (textsBelow[0].boundingBox.top - adBlock.boundingBox.bottom < sh * 0.08f) {
                mainTextBottom = textsBelow[0].boundingBox.bottom
                // 遍历合并多行正文
                for (i in 0 until textsBelow.size - 1) {
                    // 行间距较小（小于 4% 屏幕高度）说明是同一段落的连续正文
                    if (textsBelow[i+1].boundingBox.top - textsBelow[i].boundingBox.bottom < sh * 0.04f) {
                        mainTextBottom = textsBelow[i+1].boundingBox.bottom
                    } else {
                        // 遇到大间隙，说明正文彻底结束，下面开始是配图区了
                        break 
                    }
                }
            }

            // --- 步骤 2：寻找“配图的物理地板（footerTop）” ---
            // 应对“图片内含文字”的干扰（如：户型图里写着“139平米”）。
            // 无论中间图片有多复杂，一条朋友圈的最底部一定跟着时间戳（X小时前、昨天）。
            val footerBlock = textsBelow.find { 
                it.text.contains("小时前") || it.text.contains("分钟前") || 
                it.text.contains("昨天") || it.text.contains("天前") 
            }

            if (footerBlock != null) {
                val footerTop = footerBlock.boundingBox.top
                if (footerTop > mainTextBottom) {
                    // 【策略 A：时间戳倒推法则】
                    // 原理：暴力截取“正文天花板”到“时间戳地板”之间的所有内容。
                    // 优势：绝对不会漏掉任何配图，哪怕里面有无数张图或者带了小程序卡片。对于 AI 向量提取来说，底部多出一点卡片基本不影响相似度达标。
                    boundsList.add(android.graphics.Rect((sw * 0.05f).toInt(), mainTextBottom, (sw * 0.98f).toInt(), footerTop - 10))
                    log("📍 命中区域推算 [策略A]：基于时间戳倒推，提取正文到时间戳的完整区块", LogManager.Level.INFO)

                    // 【策略 B：九宫格黄金比例法则】
                    // 原理：微信朋友圈的 9宫格 / 4宫格 排版，长宽比永远是一个完美的正方形。
                    // 且因为左侧要留出头像空间，图片区宽度永远固定在屏幕宽度的 80% 左右。
                    // 优势：如果目标广告是个纯净的九宫格，此策略能完美避开底下的小程序卡片，精准切出一个正方形。
                    val gridHeight = (sw * 0.80f).toInt()
                    // 校验：算出来的正方形底部不能越界到时间戳下面去
                    if (mainTextBottom + gridHeight < footerTop + (sh * 0.08f)) {
                        boundsList.add(android.graphics.Rect((sw * 0.05f).toInt(), mainTextBottom, (sw * 0.98f).toInt(), mainTextBottom + gridHeight))
                        log("📍 命中区域推算 [策略B]：基于九宫格固定比例，提取完美正方形区域", LogManager.Level.INFO)
                    }
                }
            }
            
            // 【策略 C】：原有的空白间隙法则（作为保留候选）
            for (i in 0 until textsBelow.size) {
                val gapTop = textsBelow[i].boundingBox.bottom
                val rawGapBottom = if (i + 1 < textsBelow.size) textsBelow[i+1].boundingBox.top else sh
                val gapBottom = if (rawGapBottom == sh) sh else Math.max(gapTop, rawGapBottom - (sh * 0.015f).toInt())
                
                val gapHeight = gapBottom - gapTop
                
                if (gapHeight > sh * 0.1f && gapTop > sh * 0.05f) {
                    boundsList.add(android.graphics.Rect((sw * 0.05f).toInt(), gapTop, (sw * 0.98f).toInt(), Math.min(gapBottom, sh)))
                    log("📍 命中区域推算 [策略C]：匹配到大段空白间隙提取区域", LogManager.Level.INFO)
                    break 
                }
            }
        }
        
        // 2. 兜底方案：如果“广告”二字没识别出来，但极速粗筛（特征文本）明确命中了！
        // 增加条件：只有在确实没找到“广告”字样时，才允许走兜底逻辑，防止乱切上一条朋友圈的图片
        if (boundsList.isEmpty() && hasFastTextMatch && adBlocks.isEmpty()) {
            val sortedBlocks = texts.sortedBy { it.boundingBox.top }
            for (i in 0 until sortedBlocks.size) {
                val gapTop = sortedBlocks[i].boundingBox.bottom
                val rawGapBottom = if (i + 1 < sortedBlocks.size) sortedBlocks[i+1].boundingBox.top else sh
                val gapBottom = if (rawGapBottom == sh) sh else Math.max(gapTop, rawGapBottom - (sh * 0.015f).toInt())
                
                if (gapBottom - gapTop > sh * 0.12f && gapTop > sh * 0.05f) {
                    boundsList.add(android.graphics.Rect((sw * 0.05f).toInt(), gapTop, (sw * 0.98f).toInt(), Math.min(gapBottom, sh)))
                    log("📍 命中区域推算 [兜底逻辑]：文本特征命中，按文本间隙提取图片区域", LogManager.Level.INFO)
                }
            }
            if (boundsList.isEmpty()) {
                boundsList.add(android.graphics.Rect((sw * 0.05f).toInt(), (sh * 0.15f).toInt(), (sw * 0.95f).toInt(), (sh * 0.85f).toInt()))
                log("📍 命中区域推算 [终极兜底]：无明显间隙，强行截取屏幕中央大块区域", LogManager.Level.WARN)
            }
        }
        
        return boundsList
    }

    private fun cropRegion(bitmap: Bitmap, bounds: android.graphics.Rect): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val left = Math.max(0, bounds.left)
        val top = Math.max(0, bounds.top)
        val right = Math.min(w, bounds.right)
        val bottom = Math.min(h, bounds.bottom)

        val cropW = right - left
        val cropH = bottom - top

        if (cropW <= 10 || cropH <= 10) return null

        return try {
            Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun jumpToLandingPageAndScroll(engine: AutomationEngine, capturer: ScreenCapturer): Long {
        val size = engine.getScreenSize()
        
        // 查找"查看详情"
        val frame = capturer.captureFrame()
        var clicked = false
        if (frame != null) {
            val texts = OcrEngine.recognize(frame)
            val target = texts.find { it.text.contains("查看详情") || it.text.contains("了解更多") || it.text.contains("立即购买") }
            if (target != null) {
                log("视觉定位到落地页入口[${target.text}]，执行点击", LogManager.Level.INFO)
                // 同样坐标需乘 2 换算为物理坐标
                engine.clickAt(target.boundingBox.centerX() * 2f, target.boundingBox.centerY() * 2f)
                clicked = true
            }
            frame.recycle()
        }

        if (!clicked) {
            log("OCR未找到详情标签，盲点大图底部区域", LogManager.Level.WARN)
            engine.clickAt(size.first / 2f, size.second * 0.92f)
        }
        engine.sleep(10000)

        // 动态滑动至触底
        var lastPageFingerprint = ""
        var scrollAttempts = 0

        log("落地页已进入，开始动态触底探测...", LogManager.Level.INFO)
        while (scrollAttempts < 15) {
            engine.scrollDown(400)
            engine.sleep(1500)
            
            // 使用底部文本和位置生成签名
            val chkFrame = capturer.captureFrame()
            val fingerprint = if (chkFrame != null) {
                val texts = OcrEngine.recognize(chkFrame)
                chkFrame.recycle()
                texts.takeLast(10).joinToString("|") { "${it.text}-${it.boundingBox.top}" }
            } else ""
            
            if (fingerprint == lastPageFingerprint && lastPageFingerprint.isNotEmpty() && scrollAttempts >= 8) {
                log("检测到页面内容不再变化，确认已到达最底部", LogManager.Level.SUCCESS)
                break
            }
            lastPageFingerprint = fingerprint
            scrollAttempts++
        }
        
        return System.currentTimeMillis() - recordStartWallMs
    }

    private suspend fun cleanBackgroundTasks(engine: AutomationEngine) {
        engine.showRecents()
        engine.sleep(2000)
        engine.clickAt(675f, 2524f) // 预估的一加/华为等底部清理坐标
        engine.sleep(2000)
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
        return File(dir, "WxMomentsImage_TEMP.mp4").also { it.delete() }
    }

}
