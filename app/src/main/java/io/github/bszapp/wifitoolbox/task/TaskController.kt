package io.github.bszapp.wifitoolbox.task

import android.os.DeadObjectException
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.PagedDataTransport
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetworkPage
import io.github.bszapp.wifitoolbox.contract.task.ITaskController
import io.github.bszapp.wifitoolbox.contract.task.TaskControllerState
import io.github.bszapp.wifitoolbox.contract.task.TaskLogBatch
import io.github.bszapp.wifitoolbox.contract.task.TaskLogEntry
import io.github.bszapp.wifitoolbox.contract.task.TaskLogState
import io.github.bszapp.wifitoolbox.contract.task.TaskLogTransport
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.ITaskManagerCallback
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import io.github.bszapp.wifitoolbox.contract.task.*
import java.io.File

class TaskController(
    private val scope: CoroutineScope,
    historyDirectory: File,
    private val reportError: (
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String?,
    ) -> Unit,
) : ITaskController {
    private val auditStore = SecurityAuditHistoryStore(historyDirectory)
    override val auditHistory = auditStore.state
    private val _auditConfig = MutableStateFlow(SecurityAuditConfig())
    override val auditConfig = _auditConfig.asStateFlow()
    private val _wpsAuditConfig = MutableStateFlow(WpsSecurityAuditConfig())
    override val wpsAuditConfig = _wpsAuditConfig.asStateFlow()
    override fun configureSecurityAudit(config: SecurityAuditConfig) {
        _auditConfig.value = config
        val running = state.value.currentTaskId?.let(state.value.tasks::get)
        if (running?.snapshot?.request?.payload is TaskRequestPayload.SecurityAudit) {
            updateTask(running.snapshot.taskId, TaskUpdateRequest(TaskUpdatePayload.SecurityAuditConfigure(config)))
        }
    }

    override fun configureWpsSecurityAudit(config: WpsSecurityAuditConfig) {
        _wpsAuditConfig.value = config
        val running = state.value.currentTaskId?.let(state.value.tasks::get)
        if (running?.snapshot?.request?.payload is TaskRequestPayload.WpsSecurityAudit) {
            updateTask(
                running.snapshot.taskId,
                TaskUpdateRequest(TaskUpdatePayload.WpsSecurityAuditConfigure(config)),
            )
        }
    }
    override fun auditDictionary(recordId: String) = auditStore.dictionary(recordId)
    init { scope.launch(Dispatchers.IO) { auditStore.load() } }
    private val connectionLock = Any()
    private val _state = MutableStateFlow(TaskControllerState())
    override val state: StateFlow<TaskControllerState> = _state.asStateFlow()
    private var activeBinding: Binding? = null

    fun connect(service: IMainService) {
        lateinit var binding: Binding
        val callback = object : ITaskManagerCallback.Stub() {
            override fun onTaskManagerChanged(
                currentTaskId: Long,
                changedTaskId: Long,
            ) {
                if (!isCurrent(binding)) return
                synchronized(binding.pendingLock) {
                    binding.currentTaskId = currentTaskId.takeIf { it > 0L }
                    if (changedTaskId > 0L) binding.pendingTaskIds += changedTaskId
                }
                binding.signal.trySend(Unit)
            }

            override fun onTaskLogRangeChanged(
                taskId: Long,
                generation: Long,
                oldestAvailableId: Long,
                latestId: Long,
                lineCount: Int,
            ) {
                if (!isCurrent(binding)) return
                synchronized(binding.pendingLock) { binding.pendingLogIds += taskId }
                binding.signal.trySend(Unit)
            }

            override fun onGlobalTaskLogRangeChanged(
                generation: Long,
                oldestAvailableId: Long,
                latestId: Long,
                lineCount: Int,
            ) {
                if (isCurrent(binding)) {
                    synchronized(binding.pendingLock) { binding.globalLogsDirty = true }
                    binding.signal.trySend(Unit)
                }
            }
        }
        binding = Binding(service, callback)
        val previous = synchronized(connectionLock) {
            activeBinding.also { activeBinding = binding }
        }
        release(previous)
        _state.value = TaskControllerState()

        binding.job = scope.launch(Dispatchers.IO) {
            try {
                synchronized(binding.registrationLock) {
                    if (!isCurrent(binding)) return@launch
                    service.registerTaskManagerCallback(callback)
                    binding.registered = true
                }
                synchronized(binding.pendingLock) {
                    binding.currentTaskId = service.getCurrentTaskId().takeIf { it > 0L }
                    binding.currentTaskId?.let { binding.pendingTaskIds += it; binding.pendingLogIds += it }
                    binding.globalLogsDirty = true
                }
                var offset = 0
                while (isCurrent(binding)) {
                    val ids = service.getSecurityAuditTaskIds(offset)
                    if (ids.isEmpty()) break
                    synchronized(binding.pendingLock) { binding.pendingTaskIds.addAll(ids.toList()) }
                    offset += ids.size
                }
                reconcile(binding)
                while (isActive && isCurrent(binding)) {
                    binding.signal.receive()
                    reconcile(binding)
                }
            } catch (error: Throwable) {
                if (isActive && isCurrent(binding) && error !is DeadObjectException) {
                    Log.w(TAG, "同步任务状态失败：${error.message}", error)
                }
            }
        }
    }

    fun disconnect() {
        val previous = synchronized(connectionLock) {
            activeBinding.also { activeBinding = null }
        }
        release(previous)
        _state.value = TaskControllerState()
    }

    override suspend fun startTask(request: TaskStartRequest): Long {
        val binding = synchronized(connectionLock) { activeBinding }
            ?: throw IllegalStateException("service 未连接")
        return withContext(Dispatchers.IO) {
            try {
                check(isCurrent(binding) && binding.service.asBinder().isBinderAlive) {
                    "service 未连接"
                }
                binding.service.startTask(request).also { taskId ->
                    val snapshot = binding.service.getTaskSnapshot(taskId)
                    updateState(binding) { current ->
                        current.copy(
                            tasks = current.tasks + (
                                taskId to TrackedTaskState(
                                    snapshot = snapshot,
                                    logs = current.tasks[taskId]?.logs
                                        ?: TaskLogState(scopeTaskId = taskId),
                                    capturedNetworks = current.tasks[taskId]?.capturedNetworks.orEmpty(),
                                    auditNetworks = current.tasks[taskId]?.auditNetworks.orEmpty(),
                                )
                            ),
                        )
                    }
                    synchronized(binding.pendingLock) { binding.pendingTaskIds += taskId; binding.pendingLogIds += taskId }
                    binding.signal.trySend(Unit)
                }
            } catch (error: Throwable) {
                if (isCurrent(binding)) {
                    reportError("App.TaskController", "启动服务任务", error, null)
                }
                throw error
            }
        }
    }

    override suspend fun runSecurityAudit(input: SecurityAuditTaskInput, dictionary: File): Long = withContext(Dispatchers.IO) {
        val binding = synchronized(connectionLock) { activeBinding } ?: error("服务未连接")
        try {
            check(isCurrent(binding)) { "服务未连接" }
            val request = TaskStartRequest(TaskRequestPayload.SecurityAudit(input))
            require(PagedDataTransport.bytes(request).size <= 64 * 1024) { "安全测试请求元数据过大，请减少字典选择" }
            auditStore.saveDictionary(input.network.dictionaryId, dictionary)
            val id = SecurityAuditDictionaryTransport.open(dictionary).use { fd ->
                binding.service.runSecurityAudit(request, fd)
            }
            val snapshot = binding.service.getTaskSnapshot(id)
            val currentTaskId = synchronized(binding.pendingLock) {
                binding.service.getCurrentTaskId().takeIf { it > 0L }.also { binding.currentTaskId = it }
            }
            updateState(binding) { current ->
                current.copy(currentTaskId = currentTaskId, tasks = current.tasks +
                    (id to (current.tasks[id]?.copy(snapshot = snapshot) ?: TrackedTaskState(snapshot, TaskLogState(scopeTaskId = id)))))
            }
            synchronized(binding.pendingLock) { binding.pendingTaskIds += id; binding.pendingLogIds += id }
            binding.signal.trySend(Unit)
            id
        } catch (error: Throwable) {
            if (isCurrent(binding)) reportError("App.TaskController", "运行网络安全测试", error, null)
            throw error
        }
    }

    override suspend fun runWpsSecurityAudit(input: WpsSecurityAuditTaskInput): Long =
        withContext(Dispatchers.IO) {
            val binding = synchronized(connectionLock) { activeBinding } ?: error("服务未连接")
            try {
                check(isCurrent(binding)) { "服务未连接" }
                require(input.targets.isNotEmpty()) { "至少选择一个网络" }
                val request = TaskStartRequest.wpsSecurityAudit(input)
                require(PagedDataTransport.bytes(request).size <= 64 * 1024) { "WPS 安全测试请求元数据过大，请减少网络选择" }
                val id = binding.service.startTask(request)
                val snapshot = binding.service.getTaskSnapshot(id)
                val currentTaskId = synchronized(binding.pendingLock) {
                    binding.service.getCurrentTaskId().takeIf { it > 0L }.also { binding.currentTaskId = it }
                }
                updateState(binding) { current ->
                    current.copy(
                        currentTaskId = currentTaskId,
                        tasks = current.tasks + (
                            id to (current.tasks[id]?.copy(snapshot = snapshot)
                                ?: TrackedTaskState(snapshot, TaskLogState(scopeTaskId = id)))
                            ),
                    )
                }
                synchronized(binding.pendingLock) { binding.pendingTaskIds += id; binding.pendingLogIds += id }
                binding.signal.trySend(Unit)
                id
            } catch (error: Throwable) {
                if (isCurrent(binding)) reportError("App.TaskController", "运行 WPS 安全测试", error, null)
                throw error
            }
        }

    override fun deleteAuditHistory(recordId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { auditStore.delete(recordId) }.onFailure {
                reportError("App.TaskController", "删除安全测试历史", it, null)
            }
        }
    }

    override fun stopTask(taskId: Long) {
        val binding = synchronized(connectionLock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (!isCurrent(binding) || !binding.service.asBinder().isBinderAlive) return@launch
            runCatching {
                check(binding.service.stopTask(taskId)) { "任务 $taskId 已经结束" }
            }.onFailure { error ->
                if (isCurrent(binding)) {
                    reportError("App.TaskController", "停止服务任务", error, null)
                }
            }
        }
    }

    override fun updateTask(taskId: Long, update: TaskUpdateRequest) {
        val binding = synchronized(connectionLock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (!isCurrent(binding) || !binding.service.asBinder().isBinderAlive) return@launch
            runCatching {
                check(binding.service.updateTask(taskId, update)) { "任务 $taskId 已经结束" }
            }.onFailure { error ->
                if (isCurrent(binding)) {
                    reportError("App.TaskController", "更新服务任务", error, null)
                }
            }
        }
    }

    override fun trackTask(taskId: Long) {
        val binding = synchronized(connectionLock) { activeBinding } ?: return
        synchronized(binding.pendingLock) {
            binding.pendingTaskIds += taskId
            binding.pendingLogIds += taskId
        }
        binding.signal.trySend(Unit)
    }

    override fun clearLogs() {
        val binding = synchronized(connectionLock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (isCurrent(binding) && binding.service.asBinder().isBinderAlive) {
                runCatching { binding.service.clearTaskLogs() }
                    .onFailure { error ->
                        if (isCurrent(binding)) {
                            Log.w(TAG, "清空任务日志失败：${error.message}", error)
                        }
                    }
            }
        }
    }

    private fun reconcile(binding: Binding) {
        if (!isCurrent(binding)) return
        val service = binding.service
        val pending = synchronized(binding.pendingLock) {
            Triple(binding.pendingTaskIds.toSet(), binding.pendingLogIds.toSet(), binding.globalLogsDirty).also {
                binding.pendingTaskIds.clear()
                binding.pendingLogIds.clear()
                binding.globalLogsDirty = false
            }
        }
        val requestedIds = pending.first
        requestedIds.sorted().forEach { taskId ->
            if (!isCurrent(binding)) return
            runCatching {
                val snapshot = service.getTaskSnapshot(taskId)
                if (!isCurrent(binding)) return
                updateState(binding) { current ->
                    val existingLogs = current.tasks[taskId]?.logs
                        ?: TaskLogState(scopeTaskId = taskId)
                    current.copy(
                        tasks = current.tasks + (
                            taskId to TrackedTaskState(snapshot, existingLogs, current.tasks[taskId]?.capturedNetworks.orEmpty(),
                                current.tasks[taskId]?.auditNetworks.orEmpty())
                        ),
                    )
                }
                syncCapturedNetworks(binding, taskId)
                syncAuditNetworks(binding, taskId)
            }.onFailure { error ->
                if (error !is DeadObjectException && error !is IOException && isCurrent(binding)) {
                    Log.d(TAG, "任务 $taskId 对账失败：${error.message}")
                }
            }
        }
        pending.second.forEach { taskId ->
            if (taskId in _state.value.tasks) syncLogs(binding, taskId)
        }
        if (pending.third) syncLogs(binding, GLOBAL_SCOPE_TASK_ID)
        if (!isCurrent(binding)) return
        updateState(binding) {
            it.copy(
                currentTaskId = synchronized(binding.pendingLock) { binding.currentTaskId },
            )
        }
    }

    private fun syncCapturedNetworks(binding: Binding, taskId: Long) {
        val task = _state.value.tasks[taskId] ?: return
        val count = when (val progress = task.snapshot.progress) {
            is TaskProgress.WpsPbc -> progress.networkCount
            is TaskProgress.WpsPin -> progress.networkCount
            else -> return
        }
        val networks = ArrayList(task.capturedNetworks)
        while (isCurrent(binding) && networks.size < count) {
            val page = PagedDataTransport.decode(
                binding.service.getWpsCapturedNetworks(taskId, networks.size),
                WpsCapturedNetworkPage::class.java,
            )
            require(page.fromIndex == networks.size && page.networks.isNotEmpty()) { "捕获结果分页不连续" }
            networks.addAll(page.networks)
        }
        if (!isCurrent(binding)) return
        updateState(binding) { current ->
            val old = current.tasks[taskId] ?: return@updateState current
            current.copy(tasks = current.tasks + (taskId to old.copy(capturedNetworks = networks.toList())))
        }
    }

    private fun syncAuditNetworks(binding: Binding, taskId: Long) {
        val progress = _state.value.tasks[taskId]?.snapshot?.progress
        val revisionOf: (TaskProgress) -> Long? = {
            when (it) {
                is TaskProgress.SecurityAudit -> it.revision
                is TaskProgress.WpsSecurityAudit -> it.revision
                else -> null
            }
        }
        val revision = progress?.let(revisionOf) ?: return
        if (binding.auditRevisions[taskId] == revision) return
        repeat(3) {
            val records = ArrayList<AuditRecord>()
            var pageRevision: Long? = null
            var changed = false
            var total: Int
            do {
                if (!isCurrent(binding)) return
                val page = PagedDataTransport.decode(
                    binding.service.getSecurityAuditNetworks(taskId, records.size),
                    AuditRecordPage::class.java,
                )
                if (pageRevision != null && pageRevision != page.revision) { changed = true; break }
                pageRevision = page.revision
                total = page.totalCount
                require(page.fromIndex == records.size) { "安全测试分页不连续" }
                require(page.records.isNotEmpty() || total == records.size) { "安全测试分页为空" }
                records.addAll(page.records)
            } while (records.size < total)
            if (!changed) {
                if (!isCurrent(binding)) return
                updateState(binding) { state ->
                    val old = state.tasks[taskId] ?: return@updateState state
                    state.copy(tasks = state.tasks + (taskId to old.copy(auditNetworks = records.toList())))
                }
                auditStore.merge(records)
                binding.auditRevisions[taskId] = pageRevision ?: 0
                return
            }
        }
        synchronized(binding.pendingLock) { binding.pendingTaskIds += taskId }
        binding.signal.trySend(Unit)
    }

    private fun syncLogs(binding: Binding, scopeTaskId: Long) {
        if (!isCurrent(binding)) return
        var range = readRange(binding.service, scopeTaskId)
        val existing = if (scopeTaskId == GLOBAL_SCOPE_TASK_ID) {
            _state.value.globalLogs.entries
        } else _state.value.tasks[scopeTaskId]?.logs?.entries.orEmpty()
        val local = ArrayList(existing.filter { entryId(it, scopeTaskId) in range.oldestAvailableId..range.latestId })
        if (local.isNotEmpty() && entryId(local.first(), scopeTaskId) != range.oldestAvailableId) local.clear()

        var fromId = local.lastOrNull()?.let { entryId(it, scopeTaskId) + 1L }
            ?: range.oldestAvailableId
        while (isCurrent(binding) && fromId <= range.latestId) {
            val toId = min(fromId + FETCH_SIZE - 1L, range.latestId)
            val batch = TaskLogTransport.decode(
                if (scopeTaskId == GLOBAL_SCOPE_TASK_ID) {
                    binding.service.getGlobalTaskLogs(fromId, toId)
                } else {
                    binding.service.getTaskLogs(scopeTaskId, fromId, toId)
                },
            )
            range = batch.toRange()
            if (local.firstOrNull()?.let { entryId(it, scopeTaskId) < range.oldestAvailableId } == true) local.clear()
            val fetched = batch.entries.filter {
                entryId(it, scopeTaskId) >= range.oldestAvailableId
            }
            if (fetched.isEmpty()) break
            val expected = local.lastOrNull()?.let { entryId(it, scopeTaskId) + 1L }
                ?: range.oldestAvailableId
            if (entryId(fetched.first(), scopeTaskId) != expected) {
                local.clear()
                fromId = range.oldestAvailableId
                continue
            }
            local.addAll(fetched)
            fromId = entryId(fetched.last(), scopeTaskId) + 1L
        }
        applyLogState(binding, scopeTaskId, range, local.toList())
    }

    private fun applyLogState(
        binding: Binding,
        scopeTaskId: Long,
        range: Range,
        entries: List<TaskLogEntry>,
    ) {
        if (!isCurrent(binding)) return
        val logState = TaskLogState(
            scopeTaskId = scopeTaskId,
            generation = range.generation,
            oldestAvailableId = range.oldestAvailableId,
            latestId = range.latestId,
            lineCount = range.lineCount,
            entries = entries,
        )
        updateState(binding) { current ->
            if (scopeTaskId == GLOBAL_SCOPE_TASK_ID) {
                current.copy(globalLogs = logState)
            } else {
                val task = current.tasks[scopeTaskId] ?: return@updateState current
                current.copy(
                    tasks = current.tasks + (
                        scopeTaskId to task.copy(logs = logState)
                    ),
                )
            }
        }
    }

    private fun readRange(service: IMainService, scopeTaskId: Long): Range {
        val values = if (scopeTaskId == GLOBAL_SCOPE_TASK_ID) {
            service.getGlobalTaskLogRange()
        } else {
            service.getTaskLogRange(scopeTaskId)
        }
        require(values.size == RANGE_VALUE_COUNT) { "任务日志范围字段数非法：${values.size}" }
        return Range(
            generation = values[0],
            oldestAvailableId = values[1],
            latestId = values[2],
            lineCount = values[3].also { count ->
                require(count in 0L..Int.MAX_VALUE.toLong()) { "任务日志行数非法：$count" }
            }.toInt(),
        )
    }

    private fun TaskLogBatch.toRange() = Range(
        generation = generation,
        oldestAvailableId = oldestAvailableId,
        latestId = latestId,
        lineCount = lineCount,
    )

    private fun entryId(entry: TaskLogEntry, scopeTaskId: Long): Long =
        if (scopeTaskId == GLOBAL_SCOPE_TASK_ID) entry.globalLineId else entry.taskLineId

    private inline fun updateState(binding: Binding, transform: (TaskControllerState) -> TaskControllerState) {
        synchronized(connectionLock) {
            if (activeBinding !== binding) return
            _state.update(transform)
        }
    }

    private fun isCurrent(binding: Binding): Boolean = synchronized(connectionLock) {
        activeBinding === binding && binding.service.asBinder().isBinderAlive
    }

    private fun release(binding: Binding?) {
        if (binding == null) return
        binding.signal.close()
        binding.job?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(binding.registrationLock) {
                if (binding.registered && binding.service.asBinder().isBinderAlive) {
                    runCatching {
                        binding.service.unregisterTaskManagerCallback(binding.callback)
                    }
                }
                binding.registered = false
            }
        }
    }

    private class Binding(
        val service: IMainService,
        val callback: ITaskManagerCallback,
        val signal: Channel<Unit> = Channel(Channel.CONFLATED),
        val pendingLock: Any = Any(),
        val registrationLock: Any = Any(),
        val pendingTaskIds: MutableSet<Long> = mutableSetOf(),
        val pendingLogIds: MutableSet<Long> = mutableSetOf(),
        val auditRevisions: MutableMap<Long, Long> = mutableMapOf(),
        var globalLogsDirty: Boolean = false,
        var currentTaskId: Long? = null,
        @Volatile var registered: Boolean = false,
        @Volatile var job: Job? = null,
    )

    private data class Range(
        val generation: Long,
        val oldestAvailableId: Long,
        val latestId: Long,
        val lineCount: Int,
    )

    private companion object {
        const val TAG = "TaskController"
        const val GLOBAL_SCOPE_TASK_ID = 0L
        const val FETCH_SIZE = 500L
        const val RANGE_VALUE_COUNT = 4
    }
}
