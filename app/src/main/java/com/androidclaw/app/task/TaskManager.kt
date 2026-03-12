package com.androidclaw.app.task

import android.content.Context
import android.content.Intent
import com.androidclaw.app.engine.AutomationEngine
import com.androidclaw.app.engine.ScreenRecorder
import com.androidclaw.app.log.LogManager
import com.androidclaw.app.service.ClawForegroundService
import kotlinx.coroutines.*

/**
 * 任务管理器
 * 管理任务的注册、执行、取消
 */
class TaskManager(private val context: Context) {

    enum class TaskState {
        IDLE, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private val engine = AutomationEngine(context)
    private val screenRecorder = ScreenRecorder(context)
    private var currentJob: Job? = null
    private var currentTask: TaskScript? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 任务状态回调
    var onStateChanged: ((TaskState, String) -> Unit)? = null

    // 当前状态
    var state: TaskState = TaskState.IDLE
        private set

    // 已注册的任务脚本
    private val registeredTasks = mutableListOf<TaskScript>()

    init {
        // 注册默认任务
        registerTask(DouyinAdVerifyTask())
        registerTask(TencentVideoAdVerifyTask())
        registerTask(AdRecognitionTask())
    }

    /**
     * 注册任务脚本
     */
    fun registerTask(task: TaskScript) {
        registeredTasks.add(task)
    }

    /**
     * 获取所有已注册的任务
     */
    fun getRegisteredTasks(): List<TaskScript> = registeredTasks.toList()

    /**
     * 执行指定任务
     */
    fun executeTask(task: TaskScript, recordResultCode: Int? = null, recordData: Intent? = null) {
        if (state == TaskState.RUNNING) {
            LogManager.log("已有任务在运行中", LogManager.Level.WARN)
            return
        }

        if (!engine.isReady()) {
            LogManager.log("自动化引擎未就绪（无障碍服务未开启）", LogManager.Level.ERROR)
            updateState(TaskState.FAILED, "无障碍服务未开启")
            return
        }

        engine.reset()
        currentTask = task
        updateState(TaskState.RUNNING, "正在执行: ${task.name}")

        // 启动前台服务
        val hasVideo = recordResultCode != null
        ClawForegroundService.start(context, hasVideo)

        // 广告识别任务自行管理录屏，传递权限数据
        val isAdRecognition = task is AdRecognitionTask
        if (isAdRecognition && recordResultCode != null && recordData != null) {
            (task as AdRecognitionTask).recordResultCode = recordResultCode
            task.recordData = recordData
        }

        currentJob = scope.launch {
            try {
                // 等待服务启动并注册前台通知
                delay(1000)
                
                // 开始录屏 (广告识别任务自行管理，其他任务由 TaskManager 管理)
                if (!isAdRecognition && recordResultCode != null && recordData != null) {
                    screenRecorder.startRecording(recordResultCode, recordData)
                }

                val success = task.execute(engine)

                if (engine.isCancelled) {
                    updateState(TaskState.CANCELLED, "任务已取消")
                } else if (success) {
                    updateState(TaskState.COMPLETED, "验证成功: ${task.name}")
                } else {
                    updateState(TaskState.FAILED, "验证失败: ${task.name}")
                }
            } catch (e: CancellationException) {
                updateState(TaskState.CANCELLED, "任务已取消")
            } catch (e: Exception) {
                LogManager.log("任务异常: ${e.message}", LogManager.Level.ERROR)
                updateState(TaskState.FAILED, "任务异常: ${e.message}")
            } finally {
                // 停止录屏 (广告识别任务已自行清理)
                if (!isAdRecognition) {
                    screenRecorder.stopRecording()
                }
                // 停止前台服务
                ClawForegroundService.stop(context)
            }
        }
    }

    /**
     * 执行默认任务（抖音广告验证）
     */
    fun executeDefaultTask() {
        val defaultTask = registeredTasks.firstOrNull()
        if (defaultTask != null) {
            executeTask(defaultTask)
        } else {
            LogManager.log("没有可执行的任务", LogManager.Level.ERROR)
        }
    }

    /**
     * 取消当前任务
     */
    fun cancelTask() {
        engine.cancel()
        currentJob?.cancel()
        currentJob = null
        currentTask = null
        screenRecorder.stopRecording()
        updateState(TaskState.CANCELLED, "任务已取消")
        ClawForegroundService.stop(context)
    }

    private fun updateState(newState: TaskState, message: String) {
        state = newState
        LogManager.log("[$newState] $message", LogManager.Level.INFO)
        onStateChanged?.invoke(newState, message)
    }

    /**
     * 清理资源
     */
    fun destroy() {
        cancelTask()
        scope.cancel()
    }
}
