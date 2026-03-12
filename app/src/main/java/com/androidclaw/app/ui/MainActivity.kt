package com.androidclaw.app.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.androidclaw.app.R
import com.androidclaw.app.databinding.ActivityMainBinding
import com.androidclaw.app.log.LogManager
import com.androidclaw.app.log.TaskLog
import com.androidclaw.app.service.ClawAccessibilityService
import com.androidclaw.app.task.TaskManager

/**
 * 主界面 - 任务控制面板
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var taskManager: TaskManager
    private lateinit var logAdapter: LogAdapter
    private lateinit var videoTaskAdapter: VideoTaskAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val serviceCheckRunnable = object : Runnable {
        override fun run() {
            checkServiceStatus()
            handler.postDelayed(this, 2000) // 每2秒检查一次
        }
    }

    private var pendingTask: com.androidclaw.app.task.TaskScript? = null

    // 录屏权限启动器
    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            pendingTask?.let {
                taskManager.executeTask(it, result.resultCode, result.data)
                pendingTask = null
            }
        } else {
            Toast.makeText(this, "未授予录屏权限，任务已取消", Toast.LENGTH_SHORT).show()
            pendingTask = null
            viewModel.setCurrentVideoTask(null)
        }
    }

    // 视频选择启动器
    private val videoPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val fileName = getFileName(it) ?: "未知视频.mp4"
            val task = com.androidclaw.app.task.VideoTask(uri = it, name = fileName)
            viewModel.addVideoTask(task)
        }
    }

    private val logListener: (TaskLog) -> Unit = { log ->
        runOnUiThread {
            logAdapter.addLog(log)
            binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        taskManager = TaskManager(applicationContext)

        setupUI()
        setupObservers()
        setupListeners()

        // 注册日志监听
        LogManager.addListener(logListener)

        LogManager.log("AndroidClaw 已启动", LogManager.Level.SUCCESS)
    }

    private fun setupUI() {
        // 日志列表
        logAdapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter

        // 视频任务队列
        videoTaskAdapter = VideoTaskAdapter()
        binding.rvVideoTasks.adapter = videoTaskAdapter

        // 加载已有日志
        LogManager.getAllLogs().forEach { logAdapter.addLog(it) }

        // 设置状态圆点为圆形
        makeCircle(binding.viewStatusDot)
        makeCircle(binding.viewTaskStatusDot)
    }

    private fun makeCircle(view: android.view.View) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(R.color.error))
        }
        view.background = drawable
    }

    private fun setupObservers() {
        viewModel.serviceEnabled.observe(this) { enabled ->
            updateServiceUI(enabled)
        }

        viewModel.taskState.observe(this) { state ->
            updateTaskUI(state)
            handleAutomaticQueue(state)
        }

        viewModel.taskMessage.observe(this) { message ->
            binding.tvTaskStatus.text = message
        }

        viewModel.videoTasks.observe(this) { tasks ->
            videoTaskAdapter.submitList(tasks)
            binding.tvQueueEmptyHint.visibility = if (tasks.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        // 任务状态回调
        taskManager.onStateChanged = { state, message ->
            runOnUiThread {
                viewModel.updateTaskState(state, message)
                
                // 更新当前视频任务状态
                viewModel.currentVideoTaskId.value?.let { id ->
                    val videoStatus = when (state) {
                        TaskManager.TaskState.RUNNING -> com.androidclaw.app.task.VideoTask.Status.PROCESSING
                        TaskManager.TaskState.COMPLETED -> com.androidclaw.app.task.VideoTask.Status.COMPLETED
                        TaskManager.TaskState.FAILED -> com.androidclaw.app.task.VideoTask.Status.FAILED
                        TaskManager.TaskState.CANCELLED -> com.androidclaw.app.task.VideoTask.Status.WAITING
                        else -> null
                    }
                    videoStatus?.let { viewModel.updateVideoTaskStatus(id, it) }
                    
                    if (state != TaskManager.TaskState.RUNNING) {
                        viewModel.setCurrentVideoTask(null)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        // 开启无障碍服务
        binding.btnEnableService.setOnClickListener {
            openAccessibilitySettings()
        }

        // 添加视频
        binding.btnAddVideo.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }

        // 开始任务
        binding.btnStartTask.setOnClickListener {
            startNextVideoTask()
        }

        // 停止任务
        binding.btnStopTask.setOnClickListener {
            taskManager.cancelTask()
            viewModel.setCurrentVideoTask(null)
        }

        // 清除日志
        binding.btnClearLog.setOnClickListener {
            logAdapter.clear()
            LogManager.clear()
        }

        // 导出日志
        binding.btnExportLog.setOnClickListener {
            val path = LogManager.exportToFile()
            if (path != null) {
                Toast.makeText(this, "日志已导出到: $path", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "日志导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startNextVideoTask() {
        if (taskManager.state == TaskManager.TaskState.RUNNING) return

        if (!ClawAccessibilityService.isServiceRunning()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        val nextTask = viewModel.getNextWaitingTask()
        if (nextTask == null) {
            Toast.makeText(this, "队列中没有等待的任务", Toast.LENGTH_SHORT).show()
            return
        }

        val adTask = com.androidclaw.app.task.AdRecognitionTask()
        adTask.targetVideoUri = nextTask.uri
        
        pendingTask = adTask
        viewModel.setCurrentVideoTask(nextTask.id)
        
        val projectionManager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun handleAutomaticQueue(state: TaskManager.TaskState) {
        // 如果任务完成或失败，且之前正在处理视频任务，则自动开始下一个
        if (state == TaskManager.TaskState.COMPLETED || state == TaskManager.TaskState.FAILED) {
            handler.postDelayed({
                startNextVideoTask()
            }, 1500) // 延迟一点点，让 UI 有个反馈
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name ?: uri.path?.substringAfterLast('/')
    }

    private fun updateServiceUI(enabled: Boolean) {
        val dotDrawable = binding.viewStatusDot.background as? GradientDrawable
        if (enabled) {
            binding.tvServiceStatus.text = getString(R.string.service_enabled)
            dotDrawable?.setColor(getColor(R.color.success))
            binding.btnEnableService.text = "已开启"
            binding.btnEnableService.isEnabled = false
        } else {
            binding.tvServiceStatus.text = getString(R.string.service_disabled)
            dotDrawable?.setColor(getColor(R.color.error))
            binding.btnEnableService.text = "开启"
            binding.btnEnableService.isEnabled = true
        }
    }

    private fun updateTaskUI(state: TaskManager.TaskState) {
        val taskDotDrawable = binding.viewTaskStatusDot.background as? GradientDrawable

        when (state) {
            TaskManager.TaskState.IDLE -> {
                binding.btnStartTask.isEnabled = true
                binding.btnStopTask.isEnabled = false
                taskDotDrawable?.setColor(getColor(R.color.text_secondary))
            }
            TaskManager.TaskState.RUNNING -> {
                binding.btnStartTask.isEnabled = false
                binding.btnStopTask.isEnabled = true
                taskDotDrawable?.setColor(getColor(R.color.running))
            }
            TaskManager.TaskState.COMPLETED -> {
                binding.btnStartTask.isEnabled = true
                binding.btnStopTask.isEnabled = false
                taskDotDrawable?.setColor(getColor(R.color.success))
            }
            TaskManager.TaskState.FAILED -> {
                binding.btnStartTask.isEnabled = true
                binding.btnStopTask.isEnabled = false
                taskDotDrawable?.setColor(getColor(R.color.error))
            }
            TaskManager.TaskState.CANCELLED -> {
                binding.btnStartTask.isEnabled = true
                binding.btnStopTask.isEnabled = false
                taskDotDrawable?.setColor(getColor(R.color.warning))
            }
        }
    }

    private fun checkServiceStatus() {
        val enabled = ClawAccessibilityService.isServiceRunning()
        viewModel.updateServiceStatus(enabled)
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "请在列表中找到 AndroidClaw 并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(serviceCheckRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(serviceCheckRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.removeListener(logListener)
        taskManager.destroy()
    }
}
