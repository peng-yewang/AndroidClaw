package com.androidclaw.app.task

import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.NodeFinder
import com.androidclaw.app.log.LogManager

class TencentVideoAdVerifyTask : TaskScript {

    override val name = "腾讯视频开屏广告验证"
    override val description = "启动腾讯视频 → 检测开屏广告 → 倒计时最后1秒点击 → 京东落地页验证"
    override var configuredAdDurationMs: Long = 0L

    companion object {
        private const val TENCENT_VIDEO_PACKAGE = "com.tencent.qqlive"
        private const val SPLASH_AD_TIMEOUT_MS = 15_000L
        private const val LANDING_PAGE_TIMEOUT_MS = 20_000L
        private const val AD_PLAY_WAIT_MS = 30_000L
    }

    override suspend fun execute(engine: AutomationEngine): Boolean {
        try {
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)
            LogManager.log("开始执行: $name", LogManager.Level.SUCCESS)
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)

            if (!step1_launchApp(engine)) return false
            if (!step2_detectAndClickSplashAd(engine)) return false
            if (!step3_operateLandingPage(engine)) return false
            if (!step4_browseLandingPage(engine)) return false
            if (!step5_returnAndWait(engine)) return false

            LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
            LogManager.log("✅ 腾讯视频广告验证完成!", LogManager.Level.SUCCESS)
            LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
            return true

        } catch (e: Exception) {
            LogManager.log("❌ 任务执行异常: ${e.message}", LogManager.Level.ERROR)
            return false
        }
    }

    private suspend fun step1_launchApp(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤1/5】启动腾讯视频 App", LogManager.Level.INFO)

        if (!engine.launchApp(TENCENT_VIDEO_PACKAGE)) {
            LogManager.log("启动腾讯视频失败", LogManager.Level.ERROR)
            return false
        }

        val appStarted = engine.waitForApp(TENCENT_VIDEO_PACKAGE, 10_000)
        if (!appStarted) {
            LogManager.log("等待腾讯视频启动超时", LogManager.Level.ERROR)
            return false
        }

        LogManager.log("腾讯视频已启动", LogManager.Level.SUCCESS)
        engine.sleep(500)
        return true
    }

    private suspend fun step2_detectAndClickSplashAd(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤2/5】检测开屏广告", LogManager.Level.INFO)

        var adDetected = false
        val startTime = System.currentTimeMillis()
        var adDetectTime = 0L

        while (System.currentTimeMillis() - startTime < SPLASH_AD_TIMEOUT_MS && !engine.isCancelled) {
            val skipNodes = NodeFinder.findByTextContains("跳过")
            val adNodes = NodeFinder.findByTextContains("广告")
            val adDescNodes = NodeFinder.findByDescription("广告")

            if (skipNodes.isNotEmpty() || adNodes.isNotEmpty() || adDescNodes.isNotEmpty()) {
                adDetected = true
                adDetectTime = System.currentTimeMillis()
                LogManager.log("检测到开屏广告", LogManager.Level.SUCCESS)
                break
            }
            engine.sleep(200)
        }

        if (!adDetected) {
            LogManager.log("未检测到开屏广告（也许无广告）", LogManager.Level.WARN)
            return true
        }

        if (configuredAdDurationMs <= 0L) {
            // 测量模式
            LogManager.log("【学习测量模式】只看不点，正在计算广告自然死亡时长...", LogManager.Level.INFO)
            while (System.currentTimeMillis() - adDetectTime < 20_000 && !engine.isCancelled) {
                val allNodes = com.androidclaw.app.engine.NodeFinder.findAll()
                val hasAdOrSkip = allNodes.any { 
                    val txt = (it.text ?: it.contentDescription ?: "").toString()
                    txt.contains("跳过") || txt.contains("广告")
                }
                
                if (!hasAdOrSkip) {
                    val measuredTime = System.currentTimeMillis() - adDetectTime
                    val secStr = (measuredTime / 100 / 10.0).toString()
                    LogManager.log("💡【测量完成】广告实际存活时间为 \$secStr 秒。", LogManager.Level.SUCCESS)
                    LogManager.log("测试终止！请重新执行任务，并在弹窗里直接填入 \$secStr 即可完美卡点点击！", LogManager.Level.INFO)
                    break 
                }
                engine.sleep(200)
            }
            LogManager.log("测量完毕，终止后续流程。", LogManager.Level.WARN)
            return false 
        } else {
            // 定时点击模式
            val clickTime = configuredAdDurationMs - 1000L
            LogManager.log("【强制倒数模式】总放映：\${configuredAdDurationMs / 1000.0}秒，将在最后1秒时盲点出击！", LogManager.Level.INFO)

            while (System.currentTimeMillis() - adDetectTime < configuredAdDurationMs + 3000 && !engine.isCancelled) {
                val allNodes = com.androidclaw.app.engine.NodeFinder.findAll()
                val hasAdOrSkip = allNodes.any { 
                    val txt = (it.text ?: it.contentDescription ?: "").toString()
                    txt.contains("跳过") || txt.contains("广告")
                }

                if (!hasAdOrSkip) {
                    LogManager.log("没来得及点，广告就强制消失了！", LogManager.Level.WARN)
                    break 
                }

                val elapsed = System.currentTimeMillis() - adDetectTime
                if (elapsed >= clickTime) {
                    LogManager.log("💡 抵达目标时刻 (\${elapsed}ms / \${configuredAdDurationMs}ms)，立即点击！", LogManager.Level.SUCCESS)
                    val (screenWidth, screenHeight) = engine.getScreenSize()
                    engine.clickAt(screenWidth / 2f, screenHeight * 0.4f)
                    engine.sleep(1000)
                    break
                }

                engine.sleep(100)
            }
        }

        engine.sleep(2000)
        return true
    }

    private suspend fun step3_operateLandingPage(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤3/5】落地页操作 - 购物车流程", LogManager.Level.INFO)

        engine.sleep(3000)

        LogManager.log("查找并点击 \"加入购物车\"", LogManager.Level.INFO)
        val cartButtonTexts = listOf("加入购物车", "加购", "添加到购物车", "Add to Cart")
        var cartClicked = false

        for (text in cartButtonTexts) {
            if (engine.isCancelled) return false
            val found = engine.clickTextContains(text, timeoutMs = 5_000)
            if (found) {
                LogManager.log("成功点击: \"$text\"", LogManager.Level.SUCCESS)
                cartClicked = true
                engine.sleep(2000)
                break
            }
        }

        if (!cartClicked) {
            LogManager.log("未找到加入购物车按钮，尝试在页面底部查找", LogManager.Level.WARN)
            val (sw, sh) = engine.getScreenSize()
            engine.clickAt(sw * 0.75f, sh - 100f)
            engine.sleep(2000)
        }

        LogManager.log("遍历商品规格图片", LogManager.Level.INFO)
        engine.sleep(1500)

        val specNodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val specKeywords = listOf("颜色", "尺寸", "规格", "款式", "版本", "型号", "容量")
        for (keyword in specKeywords) {
            val found = NodeFinder.findByTextContains(keyword)
            specNodes.addAll(found)
        }

        val imageNodes = NodeFinder.findByClassName("ImageView").filter { node ->
            val parent = node.parent
            parent != null && parent.isClickable
        }

        LogManager.log("找到 ${specNodes.size} 个规格文本项, ${imageNodes.size} 个图片规格项", LogManager.Level.INFO)

        val allSpecNodes = (specNodes + imageNodes).take(10)
        for ((index, node) in allSpecNodes.withIndex()) {
            if (engine.isCancelled) return false
            LogManager.log("点击规格项 ${index + 1}/${allSpecNodes.size}", LogManager.Level.INFO)
            engine.clickNode(node)
            engine.sleep(800)
        }

        LogManager.log("关闭购物车弹窗", LogManager.Level.INFO)
        engine.sleep(500)

        val closeTexts = listOf("关闭", "×", "✕", "X")
        var closed = false
        for (text in closeTexts) {
            val closeNodes = NodeFinder.findByText(text)
            if (closeNodes.isNotEmpty()) {
                engine.clickNode(closeNodes.first())
                closed = true
                LogManager.log("点击关闭按钮: \"$text\"", LogManager.Level.SUCCESS)
                break
            }
        }

        if (!closed) {
            val closeDescNodes = NodeFinder.findByDescription("关闭")
            if (closeDescNodes.isNotEmpty()) {
                engine.clickNode(closeDescNodes.first())
                closed = true
                LogManager.log("通过描述关闭弹窗", LogManager.Level.SUCCESS)
            }
        }

        if (!closed) {
            engine.goBack()
            LogManager.log("通过返回键关闭弹窗", LogManager.Level.INFO)
        }

        engine.sleep(1000)
        return true
    }

    private suspend fun step4_browseLandingPage(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤4/5】滑动浏览落地页内容", LogManager.Level.INFO)
        engine.sleep(1000)

        val maxScrolls = 15
        for (i in 1..maxScrolls) {
            if (engine.isCancelled) return false

            LogManager.log("向下滑动 $i/$maxScrolls", LogManager.Level.INFO)
            engine.scrollDown(600)
            engine.sleep(1200)

            val bottomTexts = listOf("已经到底了", "没有更多了", "到底了", "已无更多")
            for (btm in bottomTexts) {
                val found = NodeFinder.findByTextContains(btm)
                if (found.isNotEmpty()) {
                    LogManager.log("已到达页面底部", LogManager.Level.SUCCESS)
                    return true
                }
            }
        }

        LogManager.log("落地页浏览完成", LogManager.Level.SUCCESS)
        return true
    }

    private suspend fun step5_returnAndWait(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤5/5】返回广告点位，等待广告播放完毕", LogManager.Level.INFO)

        engine.goBack()
        engine.sleep(1500)

        var backAttempts = 0
        val maxBackAttempts = 5
        while (backAttempts < maxBackAttempts && !engine.isCancelled) {
            val currentPkg = engine.getCurrentPackage()
            if (currentPkg == TENCENT_VIDEO_PACKAGE) {
                LogManager.log("已返回腾讯视频", LogManager.Level.SUCCESS)
                break
            }
            LogManager.log("当前应用: $currentPkg, 继续返回...", LogManager.Level.INFO)
            engine.goBack()
            engine.sleep(1500)
            backAttempts++
        }

        LogManager.log("等待广告播放完毕...", LogManager.Level.INFO)

        val waitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - waitStart < AD_PLAY_WAIT_MS && !engine.isCancelled) {
            val homeIndicators = listOf("首页", "推荐", "电视剧", "电影", "频道")
            for (indicator in homeIndicators) {
                val found = NodeFinder.findByTextContains(indicator)
                if (found.isNotEmpty()) {
                    LogManager.log("广告已结束，进入腾讯视频主页: \$indicator", LogManager.Level.SUCCESS)
                    return true
                }
            }

            val adNodes = NodeFinder.findByTextContains("广告")
            if (adNodes.isNotEmpty()) {
                LogManager.log("广告仍在播放，继续等待...", LogManager.Level.INFO)
            }

            engine.sleep(2000)
        }

        LogManager.log("广告等待超时，继续执行", LogManager.Level.WARN)
        return true
    }
}
