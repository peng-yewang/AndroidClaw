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
     * 添加日志
     */
    fun log(message: String, level: Level = Level.INFO) {
        val entry = TaskLog(message = message, level = level)
        logs.add(entry)
        Log.d(TAG, entry.toString())

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
