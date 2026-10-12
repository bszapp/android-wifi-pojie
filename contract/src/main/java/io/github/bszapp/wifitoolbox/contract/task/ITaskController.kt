package io.github.bszapp.wifitoolbox.contract.task

import kotlinx.coroutines.flow.StateFlow

interface ITaskController {
    val state: StateFlow<TaskControllerState>
    val auditHistory: StateFlow<List<SecurityAuditNetworkRecord>>
    val auditConfig: StateFlow<SecurityAuditConfig>
    fun configureSecurityAudit(config: SecurityAuditConfig)
    fun auditDictionary(recordId: String): java.io.File?

    /** 由服务选择追加到当前批次或创建新批次，返回实际任务 ID。 */
    suspend fun runSecurityAudit(input: SecurityAuditTaskInput, dictionary: java.io.File): Long
    fun deleteAuditHistory(recordId: String)

    suspend fun startTask(request: TaskStartRequest): Long

    fun stopTask(taskId: Long)

    fun updateTask(taskId: Long, update: TaskUpdateRequest)

    fun trackTask(taskId: Long)

    fun clearLogs()
}
