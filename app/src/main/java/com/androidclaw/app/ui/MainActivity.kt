package com.androidclaw.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    // 视频/媒体选择启动器
    private val videoPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val fileName = getFileName(it) ?: "未知媒体"
            val task = com.androidclaw.app.task.VideoTask(uri = it, name = fileName)
            viewModel.addVideoTask(task)
        }
    }

    private val logListener: (TaskLog) -> Unit = { log ->
        val content = log.message
        if (content.startsWith(com.androidclaw.app.task.AdRecognitionTask.LOG_PREFIX_VIDEO_STATUS)) {
            val parts = content.removePrefix(com.androidclaw.app.task.AdRecognitionTask.LOG_PREFIX_VIDEO_STATUS).split("|")
            if (parts.size == 2) {
                val videoId = parts[0]
                val statusStr = parts[1]
                val status = try { com.androidclaw.app.task.VideoTask.Status.valueOf(statusStr) } catch (e: Exception) { null }
                status?.let {
                    runOnUiThread { viewModel.updateVideoTaskStatus(videoId, it) }
                }
            }
        } else if (content.startsWith(com.androidclaw.app.task.AdRecognitionTask.LOG_PREFIX_VIDEO_RESULT)) {
            val parts = content.removePrefix(com.androidclaw.app.task.AdRecognitionTask.LOG_PREFIX_VIDEO_RESULT).split("|")
            if (parts.size == 2) {
                val videoId = parts[0]
                val resultPath = parts[1]
                runOnUiThread { viewModel.updateVideoTaskResult(videoId, resultPath) }
            }
        }
        
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

        // 读取并恢复云端模式开关状态
        val prefs = getSharedPreferences("AndroidClawPrefs", android.content.Context.MODE_PRIVATE)
        val isCloudMode = prefs.getBoolean("key_cloud_mode", false)
        binding.switchCloudMode.isChecked = isCloudMode
        toggleCloudMode(isCloudMode)

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
        videoTaskAdapter = VideoTaskAdapter { task ->
            if (task.status == com.androidclaw.app.task.VideoTask.Status.COMPLETED && task.resultPath != null) {
                openVideo(task.resultPath!!)
            }
        }
        binding.rvVideoTasks.adapter = videoTaskAdapter
        
        // 滑动删除逻辑
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val task = videoTaskAdapter.currentList[position]
                
                // 弹出确认对话框
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认删除")
                    .setMessage("确定要删除任务 \"${task.name}\" 吗？")
                    .setPositiveButton("删除") { _, _ ->
                        viewModel.removeVideoTask(task.id)
                        Toast.makeText(this@MainActivity, "任务已删除", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        videoTaskAdapter.notifyItemChanged(position)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvVideoTasks)

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
            if (state == TaskManager.TaskState.COMPLETED) {
                Toast.makeText(this, "🎉 所有广告识别任务已全部完成！", Toast.LENGTH_LONG).show()
            }
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
            }
        }
    }

    private fun setupListeners() {
        // 云端模式切换开关
        binding.switchCloudMode.setOnCheckedChangeListener { _, isChecked ->
            // 保存状态
            getSharedPreferences("AndroidClawPrefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("key_cloud_mode", isChecked)
                .apply()
                
            toggleCloudMode(isChecked)
        }

        // 开启无障碍服务
        binding.btnEnableService.setOnClickListener {
            openAccessibilitySettings()
        }

        // 添加视频/图片
        binding.btnAddVideo.setOnClickListener {
            videoPickerLauncher.launch("*/*")
        }

        // 开始任务
        binding.btnStartTask.setOnClickListener {
            startNextVideoTask()
        }

        // 停止任务
        binding.btnStopTask.setOnClickListener {
            taskManager.cancelTask()
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

    private fun toggleCloudMode(isCloud: Boolean) {
        if (isCloud) {
            // 开启云端模式
            binding.cardTaskControl.visibility = android.view.View.GONE
            binding.cardVideoQueue.visibility = android.view.View.GONE
            binding.cardCloudStatus.visibility = android.view.View.VISIBLE
            LogManager.log("已切换为云端自动调度模式，开始监听 API", LogManager.Level.INFO)
            // TODO: 启动 API 轮询和调度器
        } else {
            // 恢复本地模式
            binding.cardTaskControl.visibility = android.view.View.VISIBLE
            binding.cardVideoQueue.visibility = android.view.View.VISIBLE
            binding.cardCloudStatus.visibility = android.view.View.GONE
            LogManager.log("已切换为本地测试模式", LogManager.Level.INFO)
            // TODO: 停止 API 轮询
        }
    }

    private fun startNextVideoTask() {
        if (taskManager.state == TaskManager.TaskState.RUNNING) return

        if (!ClawAccessibilityService.isServiceRunning()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        // 🟢 第一步：选择具体要执行的自动化任务
        val allTasks = taskManager.getRegisteredTasks()
        val taskNames = allTasks.map { it.name }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("第一步：选择验证任务")
            .setItems(taskNames) { _, index ->
                val selectedTask = allTasks[index]
                handleTaskSelection(selectedTask)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleTaskSelection(task: com.androidclaw.app.task.TaskScript) {
        if (task is com.androidclaw.app.task.XiaohongshuQRMatchTask) {
            val input = android.widget.EditText(this).apply {
                hint = "请输入目标博主名称 (如: 女侠已退休)"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("配置博主名称")
                .setView(input)
                .setPositiveButton("下一步") { _, _ ->
                    task.targetBloggerName = input.text.toString().trim()
                    
                    val durationInput = android.widget.EditText(this).apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        hint = "单位：秒 (例如: 30)"
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("配置录制时长")
                        .setView(durationInput)
                        .setPositiveButton("开始") { _, _ ->
                            val durationS = durationInput.text.toString().toLongOrNull() ?: 30L
                            task.configuredAdDurationMs = durationS * 1000L
                            
                            pendingTask = task
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_RECORD_AUDIO)
                            } else {
                                launchScreenCaptureRequest()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 判断是否为需要“媒体队列+算法匹配”类的重度任务
        if (task is com.androidclaw.app.task.AdRecognitionTask || 
            task is com.androidclaw.app.task.DouyinVideoMatchTask ||
            task is com.androidclaw.app.task.TencentVideoMatchTask ||
            task is com.androidclaw.app.task.DouyinFeedVideoMatchTask ||
            task is com.androidclaw.app.task.WechatChannelsAdMatchTask ||
            task is com.androidclaw.app.task.XiaohongshuAdMatchTask ||
            task is com.androidclaw.app.task.WechatMomentsAdMatchTask) {
            
            val waitingTasks = viewModel.getWaitingVideoTasks()
            if (waitingTasks.isEmpty()) {
                Toast.makeText(this, "请先添加目标媒体到队列中", Toast.LENGTH_LONG).show()
                return
            }
            
            // 🟢 第二步：进入算法选择流程
            showAlgorithmSelectionFlow(task, waitingTasks)
        } else {
            // 普通脚本类任务，无需录屏权限
            taskManager.executeTask(task)
        }
    }

    private fun showAlgorithmSelectionFlow(task: com.androidclaw.app.task.TaskScript, waitingTasks: List<com.androidclaw.app.task.VideoTask>) {
        val options = arrayOf("PHash (感知哈希 - 省电极速)", "MobileNetV3 (轻量AI - 语义特征)")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("第二步：选择识别算法方案")
            .setItems(options) { _, algorithmIndex ->
                // 🟢 第三步：确认是否开启旋转对轨补偿测试
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("第三步：开启横屏姿态旋转补偿？")
                    .setMessage("开启后，针对不规则比例广告会自动尝试二次旋转验核")
                    .setPositiveButton("开启") { _, _ ->
                        if (task is com.androidclaw.app.task.DouyinFeedVideoMatchTask || 
                            task is com.androidclaw.app.task.DouyinVideoMatchTask ||
                            task is com.androidclaw.app.task.WechatChannelsAdMatchTask) {
                            showDouyinAdConfigFlow(task, algorithmIndex, true, waitingTasks)
                        } else {
                            requestScreenCapture(task, algorithmIndex, true, waitingTasks)
                        }
                    }
                    .setNegativeButton("不开启") { _, _ ->
                        if (task is com.androidclaw.app.task.DouyinFeedVideoMatchTask || 
                            task is com.androidclaw.app.task.DouyinVideoMatchTask ||
                            task is com.androidclaw.app.task.WechatChannelsAdMatchTask) {
                            showDouyinAdConfigFlow(task, algorithmIndex, false, waitingTasks)
                        } else {
                            requestScreenCapture(task, algorithmIndex, false, waitingTasks)
                        }
                    }
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDouyinAdConfigFlow(task: com.androidclaw.app.task.TaskScript, algorithm: Int, enableRotation: Boolean, waitingTasks: List<com.androidclaw.app.task.VideoTask>) {
        // 第一步：输入时长
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "单位：秒 (例如: 30)"
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("参数配置：广告总时长")
            .setMessage("请输入广告视频的预计总时长（秒）：")
            .setView(input)
            .setPositiveButton("下一步") { _, _ ->
                val durationS = input.text.toString().toLongOrNull() ?: 30L
                val durationMs = durationS * 1000L
                
                // 第二步：选择跳转模式
                val modes = arrayOf("模式 A: 播放 15s 后跳转 (跳转回后再看完整)", "模式 B: 播放完毕后再跳转 (看完整后再跳落地页)")
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("参数配置：播放跳转模式")
                    .setItems(modes) { _, modeIndex ->
                        val isFull = (modeIndex == 1)
                        
                        // 第三步：选择加购/交互模式
                        val cartModes = arrayOf("不执行", "普通落地页加购 (XSL 模式)", "直播间小黄车点击 (主播模式)")
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("参数配置：落地页交互模式")
                            .setItems(cartModes) { _, cartIndex ->
                                applyConfigAndStart(task, durationMs, isFull, cartIndex, algorithm, enableRotation, waitingTasks)
                            }
                            .setCancelable(false)
                            .show()
                    }
                    .setCancelable(false)
                    .show()
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun applyConfigAndStart(task: com.androidclaw.app.task.TaskScript, durationMs: Long, isFull: Boolean, addCartMode: Int, algorithm: Int, enableRotation: Boolean, waitingTasks: List<com.androidclaw.app.task.VideoTask>) {
        task.configuredAdDurationMs = durationMs
        if (task is com.androidclaw.app.task.DouyinFeedVideoMatchTask) {
            task.playFullVideoBeforeJump = isFull
            task.addCartMode = addCartMode
        } else if (task is com.androidclaw.app.task.DouyinVideoMatchTask) {
            task.playFullVideoBeforeJump = isFull
            task.addCartMode = addCartMode
        } else if (task is com.androidclaw.app.task.WechatChannelsAdMatchTask) {
            task.playFullVideoBeforeJump = isFull
            task.addCartMode = addCartMode
        }
        requestScreenCapture(task, algorithm, enableRotation, waitingTasks)
    }

    private fun requestScreenCapture(task: com.androidclaw.app.task.TaskScript, algorithm: Int, enableRotation: Boolean, waitingTasks: List<com.androidclaw.app.task.VideoTask>) {
        // 注入参数
        when (task) {
            is com.androidclaw.app.task.AdRecognitionTask -> {
                task.targetVideoTasks = waitingTasks
                task.algorithmType = algorithm
                task.enableRotationMatch = enableRotation
            }
            is com.androidclaw.app.task.DouyinVideoMatchTask -> {
                task.targetVideoTasks = waitingTasks
                task.algorithmType = algorithm
                task.enableRotationMatch = enableRotation
            }
            is com.androidclaw.app.task.TencentVideoMatchTask -> {
                task.targetVideoTasks = waitingTasks
                task.algorithmType = algorithm
                task.enableRotationMatch = enableRotation
            }
            is com.androidclaw.app.task.DouyinFeedVideoMatchTask -> {
                task.targetVideoTasks = waitingTasks
                task.algorithmType = algorithm
                task.enableRotationMatch = enableRotation
            }
            is com.androidclaw.app.task.WechatChannelsAdMatchTask -> {
                task.targetVideoTasks = waitingTasks
                task.algorithmType = algorithm
                task.enableRotationMatch = enableRotation
            }
            is com.androidclaw.app.task.XiaohongshuAdMatchTask -> {
                task.targetVideoTasks = waitingTasks
                task.algorithmType = algorithm
                task.enableRotationMatch = enableRotation
            }
            is com.androidclaw.app.task.WechatMomentsAdMatchTask -> {
                task.targetVideoTasks = waitingTasks
                task.algorithmType = algorithm
                task.enableRotationMatch = enableRotation
            }
        }

        pendingTask = task

        // 先检查 RECORD_AUDIO 权限 (AudioPlaybackCapture 内部音频录制需要)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_CODE_RECORD_AUDIO
            )
            return
        }

        // 已有录音权限，直接申请录屏权限
        launchScreenCaptureRequest()
    }

    private fun launchScreenCaptureRequest() {
        val projectionManager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 录音权限已授予，继续申请录屏权限
                launchScreenCaptureRequest()
            } else {
                Toast.makeText(this, "未授予录音权限，录制的视频将没有声音", Toast.LENGTH_LONG).show()
                // 即使没有录音权限也允许继续（降级为仅视频录制）
                launchScreenCaptureRequest()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_RECORD_AUDIO = 1001
    }

    private fun handleAutomaticQueue(state: TaskManager.TaskState) {
        // 多任务并行化后，不再需要手动处理队列衔接
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

    private fun openVideo(path: String) {
        val file = java.io.File(path)
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在: $path", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开视频: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogManager.removeListener(logListener)
        taskManager.destroy()
    }
}
