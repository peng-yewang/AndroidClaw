---
description: 创建新的应用自动化验证任务 (Create a new Ad Verify Task)
---
# 工作流：添加新的自动化脚本任务
// turbo-all

当要求为新的 App（如快手、B站等）添加自动化验证任务时，必须严格遵守以下步骤执行：

## 步骤 1: 扫描并记录当前任务模板
- 使用 `view_file` 工具读取 `app/src/main/java/com/androidclaw/app/task/DouyinAdVerifyTask.kt` 和 `TaskScript.kt`。
- 分析现有的日志标准格式（`LogManager.log()`）、超时控制（`engine.sleep()` 及循环检测）、配置属性等。

## 步骤 2: 生成新的 Task 类
- 在 `app/src/main/java/com/androidclaw/app/task/` 下创建一个新的 `[AppName]AdVerifyTask.kt`。
- 必须实现 `TaskScript` 接口。
- 代码结构必须划分为明确的私有挂起步骤函数（例如 `step1_launchApp`, `step2_detectAd` 等）。
- 所有等待 UI 条件的地方，**绝不允许**使用无限等待，必须配套 `engine.isCancelled` 的检查以及超时机制。
- 节点查找必须使用现有的 `NodeFinder` 封装能力。

## 步骤 3: 注册此任务
- 打开 `app/src/main/java/com/androidclaw/app/task/TaskManager.kt`（或相应的任务注册表）。
- 将新创建的任务类实例化并添加到可用任务列表中，以便 UI 界面可以加载。

## 步骤 4: 编译检查 (Feedback Loop)
- 使用 `run_command` 运行以下命令，确保新代码没有语法或通过性错误：
  ```
  ./gradlew assembleDebug --quiet
  ```
- 如果编译失败，读取错误输出并自动修正，直到构建通过。

## 步骤 5: 汇报与总结
向用户汇报新任务类创建完成，并请用户提供该 App 具体开屏或广告页面的 UI Tree 快照（或连接手机运行一次并观测），以便我们在随后的步骤中精确设定 `NodeFinder` 查找关键字。
