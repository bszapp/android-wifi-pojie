package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest

internal interface ServiceTask {
    @Throws(InterruptedException::class)
    fun run(context: TaskContext)

    fun update(update: TaskUpdateRequest): Boolean = false
}

/** 单次连接供服务调度使用；不改变通用任务 FINISHED 的公开契约。 */
internal enum class ConnectionAttemptResult { SUCCESS, FAILED, TIMEOUT, REJECTED, ERROR }
internal class ConnectionCleanupException(cause: Throwable) : Exception(cause)

internal class TaskContext(
    private val taskId: Long,
    private val manager: TaskManager,
    private val progressReceiver: ((TaskProgress) -> Unit)? = null,
    private val checkAttempt: (() -> Unit)? = null,
    private val logPrefix: String = "",
    val auditConfiguration: (() -> io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditConfig)? = null,
) {
    fun recordCapturedNetwork(network: io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetwork) =
        manager.recordCapturedNetwork(taskId, network)

    fun log(text: String) = manager.appendLog(taskId, logPrefix + text)

    /** 每次尝试的精简结果或异常，不受详细日志开关过滤。 */
    fun logAuditResult(text: String) = manager.appendLog(taskId, text, compactAuditEntry = true)

    @Volatile var attemptError: String? = null
        private set

    fun recordAttemptError(text: String) { attemptError = text }

    fun updateProgress(progress: TaskProgress) {
        if (progressReceiver != null) progressReceiver.invoke(progress)
        else manager.updateProgress(taskId, progress)
    }

    fun forAttempt(ssid: String, onProgress: (TaskProgress) -> Unit, check: () -> Unit,
        configuration: () -> io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditConfig) =
        TaskContext(taskId, manager, onProgress, check, "[$ssid] ", configuration)

    fun publishAuditNetworks(config: io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditConfig,
        networks: List<io.github.bszapp.wifitoolbox.contract.task.AuditRecord>,
        windows: List<String> = emptyList()) =
        manager.updateAuditNetworks(taskId, networks) { revision, active ->
            TaskProgress.PskSecurityAudit(revision, active, config, windows)
        }

    fun publishAuditNetworks(config: io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditConfig,
        networks: List<io.github.bszapp.wifitoolbox.contract.task.AuditRecord>) =
        manager.updateAuditNetworks(taskId, networks) { revision, active ->
            TaskProgress.WpsSecurityAudit(revision, active, config)
        }

    fun ensureRunning() {
        if (manager.isStopRequested(taskId) || Thread.currentThread().isInterrupted) {
            throw InterruptedException("任务已收到停止请求")
        }
        checkAttempt?.invoke()
    }
}
