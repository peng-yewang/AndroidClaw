# AndroidClaw - Agent 结构化知识库 (Harness Knowledge Base)

这是本项目的 AI Agent 专属工作记忆库。每次接受新的开发或调试任务前，Agent 应优先回顾此结构化原则。

## 1. 核心架构认知 (Core Architecture)
- **`AutomationEngine` (自动化引擎)**:
  - 职责：提供所有与设备环境交互的方法（点击、滑动、返回、截屏、判断是否终止）。
  - **重要原则**：所有基于引擎的操作必须检查生命周期，特别是在 `while` 循环内部，**绝对不能缺少 `!engine.isCancelled` 的前置判断**。
  - **延迟原则**：所有带有轮询性质的查找，必须配合 `engine.sleep(200~1000)` 防止卡死主线程服务。
- **`NodeFinder` (节点嗅探)**:
  - 职责：通过辅助功能服务树（AccessibilityService）查找节点。
  - **特点**：返回的是一系列的虚拟节点列表。需注意动态页面的文字可能会刷新，尽量多使用关键字包含匹配（`findByTextContains`），如果文本失效需回退到 `findByDescription` (内容描述)，最后才是坐标点击。
- **`LogManager` (日志系统)**:
  - 项目全局监控依赖于应用内列表呈现。Agent 打印日志必须使用：`LogManager.log("消息", LogManager.Level.INFO/SUCCESS/WARN/ERROR)`。

## 2. Agent 常见踩坑与工程经验 (Anti-Patterns & Known Issues)
1. **死循环陷阱**：遇到“找不到加购按钮”时，不要死等，须设定最长存活时间（如 `timeoutMs`），并在外部计数终止循环。
2. **异步 UI 延迟陷阱**：点击后页面发生跃迁时，UI 刷新可能需要 1~3 秒。不要点击后瞬间去查下一个页面的节点，**必须使用 `engine.sleep(1500)` 起步的缓冲期，或者设计一个长达 15 秒的轮询查找**。
3. **坐标盲点的隐患**：当节点文本无法识别，而采用固定比例坐标时（如 `engine.clickAt(sw/2, sh*0.4f)`），需在上方写明注释并提示风险，优先寻找特征更明显的参考物。

## 3. 标准任务 SOP (Standard Tasks)
- **验证类任务**：(例如 `DouyinAdVerifyTask`)
  - 所有的子任务流程应切分为私有挂起函数（如 `step1_launch...`, `step2_findAd...`），在主 `execute(engine)` 方法里逐个调用并立即返回失败结果，以此保持 `execute` 的原子性和清晰。
  - 开屏广告测量倒计时与强制倒计时需妥善处理等待逻辑。

## 4. 视频核心匹配算法 (Video Matching SDK - 渐进式披露)
本项目具有强大的图像/视频测帧识别底座。在进行“各大平台自动刷页面找目标广告视频”的任务时，不需要再写复杂的图像比对，只需直接拼装以下能力组件：
* **`ScreenCapturer` (屏幕捕获器)**
  * `capturer.initScreenMetrics()` / `capturer.startWithProjection(pm)` / `val frame = capturer.captureFrame() // 返回Bitmap`
* **`VideoFingerprintManager` (PHash 基础指纹算法)**
  * 初始化：`val fpManager = VideoFingerprintManager()` (可设 `enableRotationMatch = true`)
  * 提取特征并加载：`fpManager.extractFromUri(context, videoId, uri, frameInterval=4, isAsset=true)`
  * 匹配侦测：`val matchedList = fpManager.matchScreenshots(frameBitmap, ignoreList)`
* **`MobileNetFingerprintManager` (AI MobileNetV3 深度学习匹配算法)**
  * 用法与 `VideoFingerprintManager` 相同，准确率更高。初始化需传入 context。

## 5. 调试与反馈 (Agent Feedback Loop)
- 在处理运行 Bug 时，Agent 优先使用 `scripts/harness_feedback_loop.ps1` 抓取应用过滤后的特定标签日志，避免被数千行系统硬件日志淹没。
