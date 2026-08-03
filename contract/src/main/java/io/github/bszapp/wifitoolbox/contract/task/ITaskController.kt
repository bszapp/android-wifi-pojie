package io.github.bszapp.wifitoolbox.contract.task

import kotlinx.coroutines.flow.StateFlow

interface ITaskController {
    val state: StateFlow<TaskControllerState>

    suspend fun startTask(request: TaskStartRequest): Long

    fun stopTask(taskId: Long)

    fun trackTask(taskId: Long)

    fun clearLogs()
}
