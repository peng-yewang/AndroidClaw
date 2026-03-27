---
description: "自动化抓取系统日志流（供Agent分析时调用）"
---
# 工作流：自动日志提取与分析 (Debug & Fetch Logs)
// turbo-all

当需要验证应用是否如期执行了某项任务，或者排除线上 Bug 时，Agent 可以按照本流水线获取高信噪比的反馈回路：

## 步骤 1: 执行 ADB 日志提取与过滤
- 运行过滤脚本提取最干净的引擎层日志，将 `logcat` 过滤特定 `LogManager` 及 `[Task]` 相关的内容。
- 调用环境内的 PowerShell 工具或 `run_command` 执行如下指令（示例）：
  ```powershell
  adb logcat -d > output_temp.log
  Select-String -Path output_temp.log -Pattern "\b(Claw|AndroidClaw|TaskLog)\b" -Context 0,2 | Select-Object -Last 150
  ```
  *(这样可以只回捞最新的跟应用逻辑有关的 150 条核心执行痕迹)*。

## 步骤 2: 定位 Error 和 Exception
- 根据输出的内容，提取 `Level.ERROR` 后方的异常追踪。

## 步骤 3: 从知识库中进行交叉比对
- 用 `grep_search` 获取代码中对应的类，结合 `.agents/AGENT_KNOWLEDGE.md` 中的已知陷阱检查是否属于典型的死循环、UI 延迟不够、或节点寻找路径变更问题。

## 步骤 4: 提出修补建议并重新编译验证
- 修补相关模块后，必须主动执行：`./gradlew assembleDebug` 以完成代码正确性审查。
