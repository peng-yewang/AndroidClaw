package com.androidclaw.app.task

import com.androidclaw.app.engine.AutomationEngine

/**
 * 任务脚本接口
 * 所有自动化任务都实现此接口
 */
interface TaskScript {
    /** 任务名称 */
    val name: String

    /** 任务描述 */
    val description: String

    /** 用户配置的广告总时长（毫秒，0代表测量模式） */
    var configuredAdDurationMs: Long

    /**
     * 执行任务
     * @param engine 自动化引擎
     * @return true=验证成功, false=验证失败
     */
    suspend fun execute(engine: AutomationEngine): Boolean
}
