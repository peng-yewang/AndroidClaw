package com.androidclaw.app.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志条目数据类
 */
data class TaskLog(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogManager.Level = LogManager.Level.INFO
) {
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun getFormattedTime(): String = dateFormat.format(Date(timestamp))

    override fun toString(): String = "[${getFormattedTime()}] [${level.name}] $message"
}
