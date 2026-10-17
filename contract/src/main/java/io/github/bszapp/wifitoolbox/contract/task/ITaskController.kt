package io.github.bszapp.wifitoolbox.contract.task

import kotlinx.coroutines.flow.StateFlow

interface ITaskController {
    val state: StateFlow<TaskControllerState>
    val auditHistory: StateFlow<List<AuditHistoryRecord>>
    val auditConfig: StateFlow<SecurityAuditConfig>
    val wpsAuditConfig: StateFlow<WpsSecurityAuditConfig>
    fun configureSecurityAudit(config: SecurityAuditConfig)
    fun configureWpsSecurityAudit(config: WpsSecurityAuditConfig)

    /** 向唯一运行中的安全测试任务加入 taskCount 条内部测试记录，返回顶层任务 ID。 */
    suspend fun runSecurityAudit(input: SecurityAuditTaskInput, dictionary: java.io.File, taskCount: Int): Long
    suspend fun joinSecurityAuditPool(input: SecurityAuditTaskInput, taskCount: Int): Long

    /** 提交一批网络的 WPS 安全测试，返回任务 ID。 */
    suspend fun runWpsSecurityAudit(input: WpsSecurityAuditTaskInput): Long
    fun deleteAuditHistory(recordId: String)

    suspend fun startTask(request: TaskStartRequest): Long

    fun stopTask(taskId: Long)

    fun updateTask(taskId: Long, update: TaskUpdateRequest)

    fun trackTask(taskId: Long)

    fun clearLogs()
}
