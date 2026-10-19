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
    val oldestAvailableId: Long = 0L,
    val latestId: Long = -1L,
    val lineCount: Int = 0,
    val entries: List<TaskLogEntry> = emptyList(),
)

data class TrackedTaskState(
    val snapshot: TaskSnapshot,
    val logs: TaskLogState,
    val capturedNetworks: List<WpsCapturedNetwork> = emptyList(),
    val auditNetworks: List<AuditRecord> = emptyList(),
    /** 日志面板的显示起点，仅用于本地展示，不裁剪任务日志。 */
    val logPanelMinIndex: Long = 0L,
)

data class TaskControllerState(
    val currentTaskId: Long? = null,
    val tasks: Map<Long, TrackedTaskState> = emptyMap(),
    val globalLogs: TaskLogState = TaskLogState(scopeTaskId = 0L),
) {
    val currentTask: TaskSnapshot?
        get() = currentTaskId?.let { tasks[it]?.snapshot }
}
