package io.github.bszapp.wifitoolbox.contract.task

import kotlinx.coroutines.flow.StateFlow
import io.github.bszapp.wifitoolbox.contract.settings.AuditLogPanelConfig

interface ITaskController {
    val state: StateFlow<TaskControllerState>
    val auditHistory: StateFlow<List<AuditHistoryRecord>>
    val auditConfig: StateFlow<PskSecurityAuditConfig>
    val wpsAuditConfig: StateFlow<WpsSecurityAuditConfig>
    val logPanelConfig: StateFlow<AuditLogPanelConfig>
    fun configurePskSecurityAudit(config: PskSecurityAuditConfig)
    fun configureWpsSecurityAudit(config: WpsSecurityAuditConfig)
    fun configureLogPanel(config: AuditLogPanelConfig)

    /** 向唯一运行中的弱密码检测任务加入 taskCount 条内部测试记录，返回顶层任务 ID。 */
    suspend fun runPskSecurityAudit(input: PskSecurityAuditTaskInput, dictionary: java.io.File, taskCount: Int): Long
    suspend fun joinPskSecurityAuditPool(input: PskSecurityAuditTaskInput, taskCount: Int): Long

    /** 提交一批网络的PIN漏洞检测，返回任务 ID。 */
    suspend fun runWpsSecurityAudit(input: WpsSecurityAuditTaskInput): Long
    fun deleteAuditHistory(recordId: String)

    suspend fun startTask(request: TaskStartRequest): Long

    fun stopTask(taskId: Long)

    fun updateTask(taskId: Long, update: TaskUpdateRequest)

    fun trackTask(taskId: Long)

    fun clearLogs()

    /** 仅隐藏此任务日志面板中已经收到的日志，不清除原始日志。 */
    fun clearLogPanel(taskId: Long)
}
