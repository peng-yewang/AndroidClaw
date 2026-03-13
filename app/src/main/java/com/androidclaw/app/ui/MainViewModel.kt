package com.androidclaw.app.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.androidclaw.app.task.TaskManager

/**
 * 主界面 ViewModel
 */
class MainViewModel : ViewModel() {

    private val _serviceEnabled = MutableLiveData(false)
    val serviceEnabled: LiveData<Boolean> = _serviceEnabled

    private val _taskState = MutableLiveData(TaskManager.TaskState.IDLE)
    val taskState: LiveData<TaskManager.TaskState> = _taskState

    private val _taskMessage = MutableLiveData("等待启动")
    val taskMessage: LiveData<String> = _taskMessage

    private val _videoTasks = MutableLiveData<List<com.androidclaw.app.task.VideoTask>>(emptyList())
    val videoTasks: LiveData<List<com.androidclaw.app.task.VideoTask>> = _videoTasks

    private val _currentVideoTaskId = MutableLiveData<String?>(null)
    val currentVideoTaskId: LiveData<String?> = _currentVideoTaskId

    fun updateServiceStatus(enabled: Boolean) {
        _serviceEnabled.postValue(enabled)
    }

    fun updateTaskState(state: TaskManager.TaskState, message: String) {
        _taskState.postValue(state)
        _taskMessage.postValue(message)
    }

    fun addVideoTask(task: com.androidclaw.app.task.VideoTask) {
        val current = _videoTasks.value?.toMutableList() ?: mutableListOf()
        current.add(task)
        _videoTasks.postValue(current)
    }

    fun removeVideoTask(taskId: String) {
        val current = _videoTasks.value?.filter { it.id != taskId } ?: emptyList()
        _videoTasks.postValue(current)
    }

    fun setCurrentVideoTask(taskId: String?) {
        _currentVideoTaskId.postValue(taskId)
    }

    fun updateVideoTaskStatus(id: String, status: com.androidclaw.app.task.VideoTask.Status) {
        val list = _videoTasks.value?.toMutableList() ?: return
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = list[index].copy(status = status)
            _videoTasks.postValue(list)
        }
    }

    fun updateVideoTaskResult(id: String, resultPath: String) {
        val list = _videoTasks.value?.toMutableList() ?: return
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list[index] = list[index].copy(status = com.androidclaw.app.task.VideoTask.Status.COMPLETED, resultPath = resultPath)
            _videoTasks.postValue(list)
        }
    }

    fun getWaitingVideoTasks(): List<com.androidclaw.app.task.VideoTask> {
        return _videoTasks.value?.filter { it.status == com.androidclaw.app.task.VideoTask.Status.WAITING } ?: emptyList()
    }

    fun getNextWaitingTask(): com.androidclaw.app.task.VideoTask? {
        return _videoTasks.value?.firstOrNull { it.status == com.androidclaw.app.task.VideoTask.Status.WAITING }
    }
}
