# AndroidClaw - 智能视频广告识别与端侧监测系统

![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-blue.svg?logo=kotlin)
![Android](https://img.shields.io/badge/Android-10.0%2B-green.svg?logo=android)
![Algorithm](https://img.shields.io/badge/Algorithm-MobileNetV3%20%7C%20PHash-orange)
![Service](https://img.shields.io/badge/Service-Accessibility%20%7C%20MediaProjection-blueviolet)

AndroidClaw 是一款基于 Android 平台的高可用自动化全链路视频视频监测工具。它深度结合了 **端侧轻量级神经网络（MobileNetV3）** 与传统视觉感知哈希（pHash/dHash）组成的双轨识别架构，利用系统级 MediaProjection 采集管线与 AccessibilityService 无人值守交互能力，实现对不同端内横竖屏广告的精准捕捉命中、实时多播录制并完成本地极速裁剪重封的一站式全自动巡检留证导出。

## 🚀 核心特性

- **端侧 AI 推理协同 (MobileNetV3 + TFLite)**：利用深度学习模型直接在设备端提取每一帧的 1024 维高阶语义特征张量进行余弦相似度（Cosine Similarity）比对，强力抑制极端复杂的弹幕、全屏UI挂件等强噪干扰；并与原生 64 位 2D-DCT 感知算法相辅双打。
- **自适应强工程鲁棒性检测**：原生嵌入横屏 90° 自动姿态补偿、精细化去黑边裁切 (Crop Black Borders) 并引入连续命中时间序列密度核查过滤机制，有效消除跨机型碎片化展示比例变态拉长及各平台乱切屏的假概率。
- **高自由度重载矩阵巡游**：支持注入各短视频流（抖音 Feed、腾讯视频等）实现精准组件驱动及滑动播放；外挂 `Kill-and-Restart` 自保重连机制，实现极低开销挂机侦听。
- **零损耗毫秒级证据割接 (Evidence Trim)**：录制侧革新引入系统级环境声直击内录（REMOTE_SUBMIX）以及利用底层 MediaExtractor/MediaMuxer 数据重包裹脱离二次硬软件重封，直指首关键 I 帧完成毫秒出片取证闭环。

## 🛠️ 技术架构概览

- **双驱比对算力中台**：自研 `MobileNetFingerprintManager` 与 `VideoFingerprintManager` 引擎，引入 TensorFlow Lite 将耗时的特征抽取在协程池控制下以 1fps 平流输送且稳定限制在极小延迟之内。
- **低碳环保管线处理**：全链路摒开主线程堵塞隐患，全面通过 `Kotlin Coroutines` (Dispatchers.Default/IO) 实现对象复用池分拆资源与Bitmap管控抗OOM发热卡顿风险。
- **高解音画并发采编**：集成 `ImageReader` + `VirtualDisplay` 建立防抖捕获环境，不抢占多媒体中心渲染输出。

## 📋 快速开始指导

### 运行环境
- Android 10.0 (API 29) 或更高版本。
- 必须开启系统“无障碍服务（Accessibility Service）”获取交互驱动权限。
- 必须授权允许 App 进行“录屏”与“录音（内录）”。

### 操作验证流程
1. **策略加载**：点击主界面的“添加视频”按钮构建并映射分析验证片源特征库。
2. **侦查启动**：确认无障碍配置后进入对应宿主应用（如抖音/腾讯），启动监控监听拦截池任务。
3. **后台伺服运转**：系统进入指定队列将依据内置流程执行划动、点击、切页及视频扫描静默录象工作。
4. **验证出栈**：达到特征阀点系统后会立刻触发后验证截断流处理与出参弹层提醒，直接本端即回放。

## ⚖️ 声明与遵守协议

本工具模块主要涉及 App 环境下的业务链路验收测试与端内性能实验用途。仅为测试场景服务，切勿用于破坏或涉及反制绕开对方商业平台之系统安全逻辑使用，并在遵守对应的用户最终协议下配置。

---

*Copyright © 2026 AndroidClaw Project. All Rights Reserved.*
