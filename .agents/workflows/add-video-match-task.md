---
description: 创建带有视频匹配能力的各大平台自动化任务 (Video Match Supported Task)
---
# 工作流：添加新的视频匹配自动化刷流任务
// turbo-all

当要求在特定平台（如小红书、快手等）“创建支持视频匹配的自动化刷视频广告脚本”时，务必按照以下渐进式披露的模型进行结构化代码生成。

## 查阅阶段 (Context)
- AI Agent 切忌盲写比对算法。请直接查询 `.agents/AGENT_KNOWLEDGE.md` 中的「视频核心匹配算法 (Video Matching SDK)」章节，获取 `MobileNetFingerprintManager` 或 `VideoFingerprintManager` 以及 `ScreenCapturer` 的调用接口签名。

## 步骤 1: 构建框架 & 提取特征
- 新建类似于 `[PlatformName]VideoAdVerifyTask.kt`。
- 在 `execute` 方法的第一步，获取 `Context` 并提示用户传入需匹配的目标视频 `Uri`。
- 调用算法提取目标的指纹缓存。

## 步骤 2: 截屏基础建设
- 申请并启动 `ScreenCapturer`（必须配合 MediaProjection 进行）。

## 步骤 3: 核心巡检循环 (The Brush Loop)
编写主流程：
```kotlin
// 伪代码参考，切勿死抄
while (!engine.isCancelled && !isFound) {
    val frame = capturer.captureFrame()
    if (frame == null) { engine.sleep(500); continue }
    
    val matched = manager.matchScreenshots(frame, emptySet())
    frame.recycle() // 必须释放，防止 OOM！
    
    if (matched.isNotEmpty()) {
        // 匹配成功！
        isFound = true
        // 进入落地页点击逻辑...
        break
    } else {
        // 没有匹配，由 engine 触发一次向下滑动并等待 UI 稳定
        engine.scrollDown() 
        engine.sleep(2000) 
    }
}
```

## 步骤 4: 兜底清理与注销注册表
- 务必在 `finally` 块中调用 `capturer.stop()` 释放在后台拉起的截屏服务。
- 完成代码后，将该 Task 注册到 `TaskManager.kt` 中。
- 执行本地 Harness 构建脚本验证无语法错误。
