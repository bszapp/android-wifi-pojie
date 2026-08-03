package io.github.bszapp.wifitoolbox.contract.task

data class TaskLogEntry(
    val taskId: Long,
    val taskLineId: Long,
    val globalLineId: Long,
    val timestampMillis: Long,
    val text: String,
)

data class TaskLogBatch(
    val scopeTaskId: Long,
    val generation: Long,
    val oldestAvailableId: Long,
    val latestId: Long,
    val lineCount: Int,
    val entries: List<TaskLogEntry>,
)

data class TaskLogState(
    val scopeTaskId: Long,
    val generation: Long = 0L,
    val oldestAvailableId: Long = 1L,
    val latestId: Long = 0L,
    val lineCount: Int = 0,
    val entries: List<TaskLogEntry> = emptyList(),
)

data class TrackedTaskState(
    val snapshot: TaskSnapshot,
    val logs: TaskLogState,
)

data class TaskControllerState(
    val currentTaskId: Long? = null,
    val tasks: Map<Long, TrackedTaskState> = emptyMap(),
    val globalLogs: TaskLogState = TaskLogState(scopeTaskId = 0L),
) {
    val currentTask: TaskSnapshot?
        get() = currentTaskId?.let { tasks[it]?.snapshot }
}
