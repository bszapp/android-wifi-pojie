package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest

internal interface ServiceTask {
    @Throws(InterruptedException::class)
    fun run(context: TaskContext)

    fun update(update: TaskUpdateRequest): Boolean = false
}

internal class TaskContext(
    private val taskId: Long,
    private val manager: TaskManager,
) {
    fun log(text: String) = manager.appendLog(taskId, text)

    fun updateProgress(progress: TaskProgress) = manager.updateProgress(taskId, progress)

    fun ensureRunning() {
        if (manager.isStopRequested(taskId) || Thread.currentThread().isInterrupted) {
            throw InterruptedException("任务已收到停止请求")
        }
    }
}
