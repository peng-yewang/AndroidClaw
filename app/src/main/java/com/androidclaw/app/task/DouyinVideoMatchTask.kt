package com.androidclaw.app.task

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.os.Environment
import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.MobileNetFingerprintManager
import com.androidclaw.app.engine.ScreenCapturer
import com.androidclaw.app.engine.VideoFingerprintManager
import com.androidclaw.app.engine.VideoTrimmer
import com.androidclaw.app.log.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 抖音视频开屏广告自动巡检与采集任务
 */
class DouyinVideoMatchTask : TaskScript {

    override val name = "抖音开屏视频匹配捕获"
    override val description = "循环关闭并打开抖音，利用视频匹配捕捉对应的开屏广告记录"
    override var configuredAdDurationMs: Long = 0L

    var targetVideoTasks: List<VideoTask> = emptyList()
    var recordResultCode: Int = 0
    var recordData: Intent? = null
    var algorithmType: Int = 0
    var enableRotationMatch: Boolean = false

    private fun log(message: String, level: LogManager.Level = LogManager.Level.INFO) {
        LogManager.log("[DyTask] $message", level)
    }

    companion object {
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        private const val SPLASH_MONITOR_MS = 12_000L // 每次打开后最多监控 12 秒
        private const val PRE_ROLL_MS = 2_000L
        private const val POST_ROLL_MS = 2_000L

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
        var recorderVd: android.hardware.display.VirtualDisplay? = null
        var recorder: MediaRecorder? = null
        val capturer = ScreenCapturer(context)
        var tmpFile: File? = null
        var recordStartWallMs = 0L

        val adIntervals = mutableListOf<AdInterval>()

        try {
            log("═══════════════════════════════════", LogManager.Level.INFO)
            log("开始执行: $name", LogManager.Level.SUCCESS)
            log("═══════════════════════════════════", LogManager.Level.INFO)

            if (targetVideoTasks.isEmpty()) {
                log("未提供匹配目标视频，任务中止", LogManager.Level.ERROR)
                return false
            }

            // ===== 准备匹配器 =====
            val fpManager = if (algorithmType == 0) VideoFingerprintManager() else null
            val aiManager = if (algorithmType == 1) MobileNetFingerprintManager(context) else null
            fpManager?.enableRotationMatch = enableRotationMatch
            aiManager?.enableRotationMatch = enableRotationMatch

            for (task in targetVideoTasks) {
                if (algorithmType == 0) {
                    fpManager?.extractFromUri(context, task.id, task.uri, 4, true)
                } else {
                    aiManager?.extractFromUri(context, task.id, task.uri, 1)
                }
            }

            if ((fpManager?.isLoaded != true) && (aiManager?.isLoaded != true)) {
                log("指纹初始化失败，任务终止。", LogManager.Level.ERROR)
                return false
            }

            // ===== 准备环境与录像 =====
            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            sharedProjection = pm.getMediaProjection(recordResultCode, recordData!!)
            
            capturer.initScreenMetrics()
            if (!capturer.startWithProjection(sharedProjection)) return false

            tmpFile = createTempFile(context)
            val sw = capturer.screenWidth
            val sh = capturer.screenHeight
            val sd = capturer.screenDensity
            val rw = if (sw % 2 == 0) sw else sw - 1
            val rh = if (sh % 2 == 0) sh else sh - 1

            recorder = createRecorder(context).apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(rw, rh)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(rw * rh * 2)
                setOutputFile(tmpFile.absolutePath)
                prepare()
            }
            recorderVd = sharedProjection.createVirtualDisplay("DyVerifier", rw, rh, sd, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null)
            recorder.start()
            recordStartWallMs = System.currentTimeMillis()

            // ===== 核心业务循环 =====
            for (targetTask in targetVideoTasks) {
                var isFound = false
                var attempts = 0
                log("🎬 正在重点攻坚目标任务: ${targetTask.name}", LogManager.Level.INFO)
                
                while (!engine.isCancelled && !isFound) { // 去除最大重试次数限制，直至匹配到目标视频
                    attempts++
                    log("👉 第 $attempts 次尝试唤起抖音...", LogManager.Level.INFO)
                    
                    // 1. 关闭抖音
                    killApp(engine, DOUYIN_PACKAGE)
                    engine.sleep(1000)
                    
                    // 2. 重新启动抖音
                    if (!engine.launchApp(DOUYIN_PACKAGE)) {
                        engine.sleep(2000)
                        continue
                    }
                    
                    // 3. 监控开屏并比对
                    val splashWaitStart = System.currentTimeMillis()
                    var matchFrames = 0
                    var localStartMs = 0L
                    var localEndMs = 0L

                    log("开始监控开屏广告 (超时: ${SPLASH_MONITOR_MS}ms)", LogManager.Level.INFO)
                    
                    while (System.currentTimeMillis() - splashWaitStart < SPLASH_MONITOR_MS && !engine.isCancelled) {
                        // 🟢 [优化点 2]：智能识别“无广告”状态。
                        // 如果检测到已经进入了抖音主页（例如看到“首页”或“推荐”按钮），且目前还没匹配到广告，说明本次启动无广告
                        val homeIndicators = com.androidclaw.app.engine.NodeFinder.findByTextContains("首页")
                        if (homeIndicators.isNotEmpty() && matchFrames == 0) {
                            log("检测到直接进入首页，未触发开屏广告。立即重启以节省时间...", LogManager.Level.WARN)
                            break // 跳出本轮监控，直接进入下一轮 kill & launch
                        }

                        val frame = capturer.captureFrame()
                        if (frame != null) {
                            val nowMs = System.currentTimeMillis() - recordStartWallMs
                            val matchedSet = if (algorithmType == 0) {
                                fpManager?.matchScreenshots(frame, emptySet()) 
                            } else {
                                aiManager?.matchScreenshots(frame, emptySet())
                            }

                            if (matchedSet?.contains(targetTask.id) == true) {
                                matchFrames++
                                if (localStartMs == 0L) localStartMs = nowMs
                                localEndMs = nowMs
                            }
                            frame.recycle()
                        }
                        
                        // 发现有连续多帧匹配，判定为验证成功
                        if (matchFrames >= 4) {
                            isFound = true
                            log("⭐ 成功命中目标广告！($matchFrames 帧确认)", LogManager.Level.SUCCESS)
                            log("${LOG_PREFIX_VIDEO_STATUS}${targetTask.id}|COMPLETED", LogManager.Level.INFO)
                            adIntervals.add(AdInterval(targetTask.id, localStartMs, localEndMs))
                            break
                        }
                        engine.sleep(500)
                    }

                    if (!isFound) {
                        log("未命中目标广告。准备销毁进入下一轮...", LogManager.Level.WARN)
                    }
                }
                
                if (!isFound) {
                    log("任务已取消或中止，跳过此视频: ${targetTask.name}", LogManager.Level.ERROR)
                    log("${LOG_PREFIX_VIDEO_STATUS}${targetTask.id}|FAILED", LogManager.Level.ERROR)
                }
            }
            
            return true

        } catch (e: Exception) {
            log("❌ 任务异常: ${e.message}", LogManager.Level.ERROR)
            return false
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                // 清理截屏录屏资源
                capturer.stop()
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                try { recorderVd?.release() } catch (_: Exception) {}
                recorderVd = null
                try { sharedProjection?.stop() } catch (_: Exception) {}
                sharedProjection = null

                // 统一裁剪
                if (adIntervals.isNotEmpty() && tmpFile != null && tmpFile.exists()) {
                    log("【扫尾】开始裁剪取得的广告片段...", LogManager.Level.INFO)
                    withContext(Dispatchers.IO) {
                        val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        if (outDir?.exists() == false) outDir.mkdirs()

                        for (interval in adIntervals) {
                            val taskInfo = targetVideoTasks.find { it.id == interval.videoId }
                            val trimStart = (interval.startMs - PRE_ROLL_MS).coerceAtLeast(0)
                            val trimEnd = interval.endMs + POST_ROLL_MS
                            val outName = "SplashAd_${taskInfo?.name?.substringBeforeLast('.') ?: "clip"}_${System.currentTimeMillis()}.mp4"
                            val outFile = File(outDir, outName)

                            if (VideoTrimmer.trim(tmpFile.absolutePath, outFile.absolutePath, trimStart, trimEnd)) {
                                log("🎬 裁剪成功，保存于: $outName", LogManager.Level.SUCCESS)
                                log("${LOG_PREFIX_VIDEO_RESULT}${interval.videoId}|${outFile.absolutePath}", LogManager.Level.INFO)
                            }
                        }
                    }
                }
                
                try { tmpFile?.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 🟢 [核心改进]：针对鸿蒙/非Root设备设计的混合强杀逻辑
     */
    private suspend fun killApp(engine: AutomationEngine, packageName: String) {
        try {
            log("正在尝试终结进程: $packageName", LogManager.Level.INFO)

            // [核心逻辑] 恢复原版直接启动并去掉判断，因为退回桌面会触发 Android 底层的 stopAppSwitches 保护机制，导致5秒内拦截一切后台启动！
            val svc = com.androidclaw.app.service.ClawAccessibilityService.instance
            
            // 1. 调起系统应用详情页
            val targetContext = svc ?: getContext(engine) ?: return
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(intent)
            
            // 2. 在详情页寻找应用状态并点击
            engine.sleep(1200) // 等待设置页面渲染
            val stopNodes = com.androidclaw.app.engine.NodeFinder.findByTextContains("强行停止")
                .plus(com.androidclaw.app.engine.NodeFinder.findByTextContains("结束运行"))
                .plus(com.androidclaw.app.engine.NodeFinder.findByTextContains("Force stop"))
            
            var needConfirm = false
            if (stopNodes.isNotEmpty()) {
                for (node in stopNodes) {
                    if (node.isEnabled && node.isClickable) {
                        engine.clickNode(node)
                        needConfirm = true
                        break
                    } else if (node.isEnabled && node.parent?.isClickable == true) {
                        engine.clickNode(node.parent)
                        needConfirm = true
                        break
                    }
                }
                
                if (needConfirm) {
                    log("主界面强停触发，等待二次确认弹窗...", LogManager.Level.INFO)
                    engine.sleep(1500) // 等待鸿蒙系统的二次确认对话框弹起
                    
                    // 3. 处理鸿蒙系统的二次确认对话框 (倒序优先点最上层UI)
                    val confirmNodes = com.androidclaw.app.engine.NodeFinder.findByTextContains("强行停止")
                        .plus(com.androidclaw.app.engine.NodeFinder.findByTextContains("确定"))
                        
                    for (node in confirmNodes.reversed()) {
                        if (engine.clickNode(node)) {
                            log("成功点击弹窗彻底终结应用", LogManager.Level.SUCCESS)
                            break
                        }
                    }
                    engine.sleep(800)
                } else {
                    log("检测到强停按钮已置灰，应用本就不在运行，直接跳过", LogManager.Level.INFO)
                }
            } else {
                log("未能在详情页识别到强杀按钮，执行原路返回", LogManager.Level.WARN)
            }
            
            // 💡 [鸿蒙终极退出策略]：鸿蒙会拦截全局返回事件、也会拦截处于背景期间的 startActivity！
            // 解法：直接在屏幕上抠出它应用信息页左上角的那个自带的“返回箭头”点击退回！
            val backArrow = com.androidclaw.app.engine.NodeFinder.findByDescription("返回").firstOrNull() 
                ?: com.androidclaw.app.engine.NodeFinder.findByDescription("向上导航").firstOrNull()
                ?: com.androidclaw.app.engine.NodeFinder.findByClassName("android.widget.ImageButton").firstOrNull() // 通常左上角第一个是返回

            if (backArrow != null) {
                engine.clickNode(backArrow)
                log("利用左上角实体返回键成功退出详情页", LogManager.Level.INFO)
            } else {
                // 如果实在没找到左上角的物理UI返回键，再试一次全局返回或者双击多任务还原
                svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                log("发送全局后退指令", LogManager.Level.INFO)
            }
            engine.sleep(1000)

        } catch (e: Exception) {
            log("强制关闭执行异常: ${e.message}", LogManager.Level.ERROR)
        }
    }

    private fun createTempFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (dir?.exists() == false) dir.mkdirs()
        return File(dir, "DyVerification_TEMP.mp4").also {
            if (it.exists()) it.delete()
        }
    }

    private fun createRecorder(context: Context): MediaRecorder {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun getContext(engine: AutomationEngine): Context? {
        return try {
            val field = AutomationEngine::class.java.getDeclaredField("context")
            field.isAccessible = true
            field.get(engine) as? Context
        } catch (_: Exception) { null }
    }
}
