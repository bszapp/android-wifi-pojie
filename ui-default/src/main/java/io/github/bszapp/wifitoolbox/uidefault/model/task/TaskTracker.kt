package io.github.bszapp.wifitoolbox.uidefault.model.task

import io.github.bszapp.wifitoolbox.contract.task.ITaskController
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 每个 ViewModel 独立持有；只跟踪真实任务，不保存配置表单或弹窗状态。 */
class TaskTracker(
    private val controller: ITaskController,
    scope: CoroutineScope,
) {
    private val trackedTaskId = MutableStateFlow<Long?>(null)

    val currentTask: StateFlow<TaskSnapshot?> = controller.state
        .map { it.currentTask }
        .stateIn(scope, SharingStarted.Eagerly, controller.state.value.currentTask)

    val trackedTask: StateFlow<TrackedTaskState?> = combine(
        controller.state,
        trackedTaskId,
    ) { state, taskId ->
        taskId?.let(state.tasks::get)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun startTask(request: TaskStartRequest): Long {
        val taskId = controller.startTask(request)
        controller.trackTask(taskId)
        trackedTaskId.value = taskId
        return taskId
    }

    fun track(taskId: Long) {
        controller.trackTask(taskId)
        trackedTaskId.value = taskId
    }

    fun stopTrackedTask() {
        trackedTaskId.value?.let(controller::stopTask)
    }

    fun updateTrackedTask(update: TaskUpdateRequest) {
        trackedTaskId.value?.let { controller.updateTask(it, update) }
    }

    fun clearTracking() {
        trackedTaskId.value = null
    }
}
