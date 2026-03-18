# AndroidClaw - 智能视频广告识别与监测系统

![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-blue.svg?logo=kotlin)
![Android](https://img.shields.io/badge/Android-10.0%2B-green.svg?logo=android)
![Algorithm](https://img.shields.io/badge/Algorithm-PHash%20%7C%202D--DCT-orange)
![Service](https://img.shields.io/badge/Service-Accessibility%20%7C%20MediaProjection-blueviolet)

AndroidClaw 是一款基于 Android 平台的端侧自动化视频广告监测工具。它利用 MediaProjection 屏幕采集技术与感知哈希（Perceptual Hashing）算法，实现对移动端 App 广告投放的实时精准侦听、匹配标记以及自动化片段导出。

## 🚀 核心特性

- **多目标并发侦听**：支持上传多个目标视频，系统并行运行多维指纹匹配算法，同时监控不同广告的投放情况。
- **高精度 PHash 匹配**：采用 64 位感知哈希（PHash）与 2D-DCT 余弦变换算法，支持在端侧进行毫秒级响应，有效抗击弹幕、小浮窗、偏色及画面微拉伸干扰。
- **动态黑边裁切**：内置自动选区提取算子，计算前自动剔除状态栏、底部虚拟键及四周黑框，保证小窗或非全屏播放下内容依然精准配对。
- **全自动任务闭环**：
    - **自动识别**：精准捕捉广告起止时间。
    - **自动裁剪**：任务完成后自动执行无损视频剪辑（Evidence Trim），保留真实的投放视频证据。
    - **自动终止**：队列任务全部达成后自动释放系统资源，确保低能耗静默运行。
- **工业级稳定性增强**：录入端内置空闲帧（全黑/全灰转场帧）过滤逻辑，配合“持续时长+命中密度”时间序列双重验证，极大降低假阳碰撞。
- **交互式任务管理**：提供完整的 UI 控制台，支持手势删除任务、实时日志审计、结果视频在本端直接回放预览。

## 🛠️ 技术架构

- **感知哈希端侧工程化**：自研 `VideoFingerprintManager`，采用双重离散余弦变换分离式算法（Separable 2D-DCT）降维运算，保障 1ms 级极速响应。
- **媒体处理管线**：
    - 使用 `MediaProjection` 与 `ImageReader` 实现非侵入性屏幕捕获。
    - 结合 `VirtualDisplay` 与 `MediaRecorder` 实现多链路音视频同步录制证据库。
    - 集成 `MediaExtractor`/`MediaMuxer` 方案实现毫秒级帧精度的视频裁剪。
- **端侧资源管理**：基于 Kotlin 协程（Coroutines）调度异步分析链，充分利用多核性能，保证捕获与识别在低峰耗能下不丢帧。

## 📋 快速开始

### 运行环境
- Android 10.0 (API 29) 或更高版本。
- 需开启“无障碍服务（Accessibility Service）”以支持系统自动化响应调度。
- 需授权“屏幕录制”系统权限。

### 操作步骤
1. **添加目标**：点击主界面的“添加视频”按钮，上传需要监测的广告原片。
2. **启动服务**：确认无障碍服务开启后，点击“开始任务”。
3. **实时匹配**：在目标 App（如抖音、腾讯视频等）中进行日常操作。当目标广告出现时，系统会自动后台抓拍、录制并标记。
4. **查看结果**：任务完成后，在列表项中直接点击“查看结果”即可回放捕获到的广告证据片段。

## ⚖️ 声明与协议

本工具仅用于 App 广告投放验证、能耗监测及正当的自动化脚本测试场景。请在遵守目标软件服务协议及当地法律法规的前提下进行部署。

---

*Copyright © 2026 AndroidClaw Project. All Rights Reserved.*
