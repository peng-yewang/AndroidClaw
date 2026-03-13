package com.androidclaw.app.task

import android.net.Uri

/**
 * 视频识别任务模型
 */
data class VideoTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    var status: Status = Status.WAITING,
    var resultPath: String? = null
) {
    enum class Status {
        WAITING,    // 等待中
        PROCESSING, // 处理中
        COMPLETED,  // 已完成
        FAILED      // 失败
    }
}
