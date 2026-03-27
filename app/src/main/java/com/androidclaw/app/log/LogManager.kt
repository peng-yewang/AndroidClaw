package com.androidclaw.app.log

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 日志管理器
 * 记录自动化操作日志，支持 UI 回调和文件导出
 */
object LogManager {

    private const val TAG = "LogManager"

    enum class Level {
        INFO, WARN, ERROR, SUCCESS
    }

    private val logs = CopyOnWriteArrayList<TaskLog>()
    private val listeners = CopyOnWriteArrayList<(TaskLog) -> Unit>()

    /**
     * 添加日志并输出到控制台
     */
    fun log(message: String, level: Level = Level.INFO) {
        val entry = TaskLog(message = message, level = level)
        logs.add(entry)

        // 根据级别输出到不同级别的 Logcat，避免由默认的 Debug 级别被 AS 过滤
        val logContent = entry.toString()
        when (level) {
            Level.INFO -> Log.i(TAG, logContent)
            Level.WARN -> Log.w(TAG, logContent)
            Level.ERROR -> Log.e(TAG, logContent)
            Level.SUCCESS -> Log.i(TAG, "🟢 [SUCCESS] $logContent")
        }

        // 同时输出到标准控制台 (System.out)，方便在 Android Studio 的 Run 窗口查看
        println("$TAG: $logContent")

        // 通知所有监听者
        listeners.forEach { listener ->
            try {
                listener(entry)
            } catch (e: Exception) {
                Log.e(TAG, "日志回调异常: ${e.message}")
            }
        }
    }

    /**
     * 注册日志监听器（用于 UI 更新）
     */
    fun addListener(listener: (TaskLog) -> Unit) {
        listeners.add(listener)
    }

    /**
     * 移除日志监听器
     */
    fun removeListener(listener: (TaskLog) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 获取所有日志
     */
    fun getAllLogs(): List<TaskLog> = logs.toList()

    /**
     * 清除所有日志
     */
    fun clear() {
        logs.clear()
    }

    /**
     * 导出日志到文件
     */
    fun exportToFile(): String? {
        return try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "androidclaw_${dateFormat.format(Date())}.log"
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "AndroidClaw"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)

            FileWriter(file).use { writer ->
                logs.forEach { log ->
                    writer.appendLine(log.toString())
                }
            }

            LogManager.log("日志已导出: ${file.absolutePath}", Level.SUCCESS)
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "导出日志失败: ${e.message}")
            LogManager.log("导出日志失败: ${e.message}", Level.ERROR)
            null
        }
    }
}
