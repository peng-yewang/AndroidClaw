package com.androidclaw.app.task

import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.NodeFinder
import com.androidclaw.app.log.LogManager

/**
 * 抖音开屏广告验证任务
 *
 * 流程：
 * 1. 启动抖音 App
 * 2. 检测开屏广告
 * 3. 在倒计时最后1秒点击广告
 * 4. 等待京东落地页加载
 * 5. 点击 "加入购物车"
 * 6. 遍历商品规格图片
 * 7. 关闭购物车弹窗
 * 8. 向下滑动浏览全部落地页
 * 9. 返回广告点位
 * 10. 等待广告播放完毕
 */
class DouyinAdVerifyTask : TaskScript {

    override val name = "抖音开屏广告验证"
    override val description = "启动抖音 → 检测开屏广告 → 倒计时最后1秒点击 → 京东落地页验证"
    override var configuredAdDurationMs: Long = 0L

    companion object {
        // 抖音包名
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        // 京东包名
        private const val JD_PACKAGE = "com.jingdong.app.mall"

        // 开屏广告等待超时
        private const val SPLASH_AD_TIMEOUT_MS = 15_000L
        // 落地页加载超时
        private const val LANDING_PAGE_TIMEOUT_MS = 20_000L
        // 广告播放等待时间
        private const val AD_PLAY_WAIT_MS = 30_000L
    }

    override suspend fun execute(engine: AutomationEngine): Boolean {
        try {
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)
            LogManager.log("开始执行: $name", LogManager.Level.SUCCESS)
            LogManager.log("═══════════════════════════════════", LogManager.Level.INFO)

            // ===== 步骤1: 启动抖音 =====
            if (!step1_launchDouyin(engine)) return false

            // ===== 步骤2: 检测并点击开屏广告 =====
            if (!step2_detectAndClickSplashAd(engine)) return false

            // ===== 步骤3: 京东落地页操作 =====
            if (!step3_operateLandingPage(engine)) return false

            // ===== 步骤4: 浏览落地页 =====
            if (!step4_browseLandingPage(engine)) return false

            // ===== 步骤5: 返回并等待广告播放完毕 =====
            if (!step5_returnAndWait(engine)) return false

            LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
            LogManager.log("✅ 广告验证完成!", LogManager.Level.SUCCESS)
            LogManager.log("═══════════════════════════════════", LogManager.Level.SUCCESS)
            return true

        } catch (e: Exception) {
            LogManager.log("❌ 任务执行异常: ${e.message}", LogManager.Level.ERROR)
            return false
        }
    }

    /**
     * 步骤1: 启动抖音
     */
    private suspend fun step1_launchDouyin(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤1/5】启动抖音 App", LogManager.Level.INFO)

        // 启动抖音
        if (!engine.launchApp(DOUYIN_PACKAGE)) {
            LogManager.log("启动抖音失败", LogManager.Level.ERROR)
            return false
        }

        // 等待抖音进入前台
        val appStarted = engine.waitForApp(DOUYIN_PACKAGE, 10_000)
        if (!appStarted) {
            LogManager.log("等待抖音启动超时", LogManager.Level.ERROR)
            return false
        }

        LogManager.log("抖音已启动", LogManager.Level.SUCCESS)
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

    /**
     * 步骤3: 在落地页进行购物车操作
     */
    private suspend fun step3_operateLandingPage(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤3/5】落地页操作 - 购物车流程", LogManager.Level.INFO)

        // 等待落地页加载完成
        engine.sleep(3000) // 给页面加载留时间

        // --- 3.1 点击"加入购物车" ---
        LogManager.log("查找并点击 \"加入购物车\" 按钮", LogManager.Level.INFO)

        // 尝试多种关键词匹配
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
            // 尝试在底部点击（京东通常在底部有固定的按钮栏）
            val (sw, sh) = engine.getScreenSize()
            engine.clickAt(sw * 0.75f, sh - 100f)
            engine.sleep(2000)
        }

        // --- 3.2 遍历商品规格图片 ---
        LogManager.log("遍历商品规格图片", LogManager.Level.INFO)
        engine.sleep(1500)

        // 查找规格选项（通常是图片或文本按钮）
        val specNodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()

        // 查找常见的规格关键词
        val specKeywords = listOf("颜色", "尺寸", "规格", "款式", "版本", "型号", "容量")
        for (keyword in specKeywords) {
            val found = NodeFinder.findByTextContains(keyword)
            specNodes.addAll(found)
        }

        // 也查找带图片的规格按钮（通常是 ImageView 在某个容器里）
        val imageNodes = NodeFinder.findByClassName("ImageView")
            .filter { node ->
                val parent = node.parent
                parent != null && parent.isClickable
            }

        LogManager.log("找到 ${specNodes.size} 个规格文本项, ${imageNodes.size} 个图片规格项", LogManager.Level.INFO)

        // 点击每个规格选项
        val allSpecNodes = (specNodes + imageNodes).take(10) // 限制最多10个避免无限循环
        for ((index, node) in allSpecNodes.withIndex()) {
            if (engine.isCancelled) return false
            LogManager.log("点击规格项 ${index + 1}/${allSpecNodes.size}", LogManager.Level.INFO)
            engine.clickNode(node)
            engine.sleep(800)
        }

        // --- 3.3 关闭购物车弹窗 ---
        LogManager.log("关闭购物车弹窗", LogManager.Level.INFO)
        engine.sleep(500)

        // 尝试找到关闭按钮
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

        // 也尝试通过 contentDescription 查找关闭按钮
        if (!closed) {
            val closeDescNodes = NodeFinder.findByDescription("关闭")
            if (closeDescNodes.isNotEmpty()) {
                engine.clickNode(closeDescNodes.first())
                closed = true
                LogManager.log("通过描述关闭弹窗", LogManager.Level.SUCCESS)
            }
        }

        // 如果还没关闭，按返回键
        if (!closed) {
            engine.goBack()
            LogManager.log("通过返回键关闭弹窗", LogManager.Level.INFO)
        }

        engine.sleep(1000)
        return true
    }

    /**
     * 步骤4: 向下滑动浏览全部落地页内容
     */
    private suspend fun step4_browseLandingPage(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤4/5】滑动浏览落地页内容", LogManager.Level.INFO)

        engine.sleep(1000)

        // 连续向下滑动浏览
        val maxScrolls = 15 // 最多滑15次
        for (i in 1..maxScrolls) {
            if (engine.isCancelled) return false

            LogManager.log("向下滑动 $i/$maxScrolls", LogManager.Level.INFO)
            engine.scrollDown(600)
            engine.sleep(1200) // 等待内容加载

            // 检查是否到达页面底部（查找常见的底部标志）
            val bottomTexts = listOf("已经到底了", "没有更多了", "到底了", "已无更多")
            for (btm in bottomTexts) {
                val found = NodeFinder.findByTextContains(btm)
                if (found.isNotEmpty()) {
                    LogManager.log("已到达页面底部", LogManager.Level.SUCCESS)
                    return true
                }
            }
        }

        LogManager.log("落地页浏览完成（已滑动 $maxScrolls 次）", LogManager.Level.SUCCESS)
        return true
    }

    /**
     * 步骤5: 返回广告点位并等待广告播放完毕
     */
    private suspend fun step5_returnAndWait(engine: AutomationEngine): Boolean {
        LogManager.log("【步骤5/5】返回广告点位，等待广告播放完毕", LogManager.Level.INFO)

        // 按返回键回到广告/抖音
        engine.goBack()
        engine.sleep(1500)

        // 如果还在京东/WebView中，继续按返回键
        var backAttempts = 0
        val maxBackAttempts = 5
        while (backAttempts < maxBackAttempts && !engine.isCancelled) {
            val currentPkg = engine.getCurrentPackage()
            if (currentPkg == DOUYIN_PACKAGE) {
                LogManager.log("已返回抖音", LogManager.Level.SUCCESS)
                break
            }
            LogManager.log("当前应用: $currentPkg, 继续返回...", LogManager.Level.INFO)
            engine.goBack()
            engine.sleep(1500)
            backAttempts++
        }

        // 等待广告播放完毕
        LogManager.log("等待广告播放完毕...", LogManager.Level.INFO)

        // 检测广告是否还在播放
        val waitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - waitStart < AD_PLAY_WAIT_MS && !engine.isCancelled) {
            // 检查是否已经进入抖音主界面（首页标志）
            val homeIndicators = listOf("首页", "推荐", "关注")
            for (indicator in homeIndicators) {
                val found = NodeFinder.findByTextContains(indicator)
                if (found.isNotEmpty()) {
                    LogManager.log("广告已结束，进入抖音主页", LogManager.Level.SUCCESS)
                    return true
                }
            }

            // 检查是否还有广告标记
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
