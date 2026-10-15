package io.github.bszapp.wifitoolbox.contract.task

import kotlinx.coroutines.flow.StateFlow

interface ITaskController {
    val state: StateFlow<TaskControllerState>
    val auditHistory: StateFlow<List<AuditHistoryRecord>>
    val auditConfig: StateFlow<SecurityAuditConfig>
    val wpsAuditConfig: StateFlow<WpsSecurityAuditConfig>
    fun configureSecurityAudit(config: SecurityAuditConfig)
    fun configureWpsSecurityAudit(config: WpsSecurityAuditConfig)

    /** 由服务选择追加到当前批次或创建新批次，返回实际任务 ID。 */
    suspend fun runSecurityAudit(input: SecurityAuditTaskInput, dictionary: java.io.File): Long
    suspend fun joinSecurityAuditPool(input: SecurityAuditTaskInput): Long

    /** 提交一批网络的 WPS 安全测试，返回任务 ID。 */
    suspend fun runWpsSecurityAudit(input: WpsSecurityAuditTaskInput): Long
    fun deleteAuditHistory(recordId: String)

    suspend fun startTask(request: TaskStartRequest): Long

    fun stopTask(taskId: Long)

    fun updateTask(taskId: Long, update: TaskUpdateRequest)

    fun trackTask(taskId: Long)

    fun clearLogs()
}
