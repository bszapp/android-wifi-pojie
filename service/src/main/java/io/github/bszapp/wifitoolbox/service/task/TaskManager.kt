package io.github.bszapp.wifitoolbox.service.task

import android.os.RemoteCallbackList
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTarget
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskLogBatch
import io.github.bszapp.wifitoolbox.contract.task.TaskLogEntry
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdatePayload
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.HybridTaskEnvironment
import io.github.bszapp.wifitoolbox.service.ITaskManagerCallback
import io.github.bszapp.wifitoolbox.service.TerminalManager
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogAnalyzer
import io.github.bszapp.wifitoolbox.service.wifilog.decodeHexSsid
import java.util.ArrayDeque
import java.util.concurrent.Executors

internal class TaskManager(
    private val androidApiProvider: () -> AndroidApi?,
    private val wifiLogAnalyzer: WifiLogAnalyzer,
    private val terminalManager: TerminalManager,
    private val hybridTaskEnvironmentProvider: () -> HybridTaskEnvironment?,
    private val onSavedWifiNetworksChanged: () -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val records = linkedMapOf<Long, TaskRecord>()
    private val globalLogs = ArrayDeque<TaskLogEntry>()
    private val callbacks = RemoteCallbackList<ITaskManagerCallback>()
    private val taskExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "service-task-runner").apply { isDaemon = true }
    }
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "service-task-callback").apply { isDaemon = true }
    }

    private var nextTaskId = 1L
    private var nextGlobalLogId = 1L
    private var globalLogGeneration = 0L
    private var currentTaskId: Long? = null
    private var closed = false

    fun start(request: TaskStartRequest): Long {
        validate(request)
        val record = synchronized(lock) {
            check(!closed) { "任务管理器已关闭" }
            check(currentTaskId == null) { "已有任务正在运行：taskId=$currentTaskId" }
            check(nextTaskId < Long.MAX_VALUE) { "任务 ID 已耗尽" }
            val taskId = nextTaskId++
            TaskRecord(
                snapshot = TaskSnapshot(
                    taskId = taskId,
                    request = request,
                    state = TaskExecutionState.RUNNING,
                    progress = null,
                ),
            ).also {
                records[taskId] = it
                currentTaskId = taskId
            }
        }
        broadcastManagerChanged(record.snapshot)
        appendLog(record.snapshot.taskId, "任务已创建：type=${request.payload.javaClass.simpleName}")
        taskExecutor.execute { execute(record.snapshot.taskId) }
        return record.snapshot.taskId
    }

    fun stop(taskId: Long): Boolean {
        val update = synchronized(lock) {
            val record = records[taskId]
            if (currentTaskId != taskId || record?.snapshot?.state != TaskExecutionState.RUNNING) {
                return false
            }
            record.stopRequested = true
            record.runnerThread
        }
        appendLog(taskId, "收到停止任务指令")
        update?.interrupt()
        return true
    }

    fun update(taskId: Long, update: TaskUpdateRequest): Boolean {
        val task = synchronized(lock) {
            val record = records[taskId]
            if (currentTaskId != taskId || record?.snapshot?.state != TaskExecutionState.RUNNING) {
                return false
            }
            validateUpdate(record.snapshot.request, update)
            record.activeTask ?: run {
                record.pendingUpdates.addLast(update)
                return true
            }
        }
        return task.update(update)
    }

    fun stopHybridTaskIfRunning() {
        val taskId = synchronized(lock) {
            currentTaskId?.takeIf { id ->
                records[id]?.snapshot?.request?.payload is TaskRequestPayload.WpsPbc
            }
        }
        taskId?.let(::stop)
    }

    fun currentTaskId(): Long = synchronized(lock) { currentTaskId ?: NO_TASK_ID }

    fun snapshot(taskId: Long): TaskSnapshot = synchronized(lock) {
        records[taskId]?.snapshot ?: throw IllegalArgumentException("任务 $taskId 不存在")
    }

    fun taskLogRange(taskId: Long): TaskLogRangeSnapshot = synchronized(lock) {
        records[taskId]?.rangeLocked() ?: throw IllegalArgumentException("任务 $taskId 不存在")
    }

    fun globalLogRange(): TaskLogRangeSnapshot = synchronized(lock) {
        globalRangeLocked()
    }

    fun taskLogs(taskId: Long, fromIdInclusive: Long, toIdInclusive: Long): TaskLogBatch =
        synchronized(lock) {
            val record = records[taskId] ?: throw IllegalArgumentException("任务 $taskId 不存在")
            val range = record.rangeLocked()
            TaskLogBatch(
                scopeTaskId = taskId,
                generation = range.generation,
                oldestAvailableId = range.oldestAvailableId,
                latestId = range.latestId,
                lineCount = range.lineCount,
                entries = record.logs.filter {
                    it.taskLineId in fromIdInclusive..toIdInclusive
                },
            )
        }

    fun globalLogs(fromIdInclusive: Long, toIdInclusive: Long): TaskLogBatch =
        synchronized(lock) {
            val range = globalRangeLocked()
            TaskLogBatch(
                scopeTaskId = NO_TASK_ID,
                generation = range.generation,
                oldestAvailableId = range.oldestAvailableId,
                latestId = range.latestId,
                lineCount = range.lineCount,
                entries = globalLogs.filter {
                    it.globalLineId in fromIdInclusive..toIdInclusive
                },
            )
        }

    fun clearLogs() {
        val ranges = synchronized(lock) {
            records.values.map { record ->
                record.logs.clear()
                record.logGeneration++
                record.rangeLocked()
            }.also {
                globalLogs.clear()
                globalLogGeneration++
            }
        }
        ranges.forEach(::broadcastTaskLogRange)
        broadcastGlobalLogRange(globalLogRange())
    }

    fun registerCallback(callback: ITaskManagerCallback) {
        callbacks.register(callback)
        val state = synchronized(lock) {
            ManagerCallbackState(
                currentTaskId = currentTaskId ?: NO_TASK_ID,
                changedSnapshot = currentTaskId?.let { records[it]?.snapshot },
                globalRange = globalRangeLocked(),
                currentRange = currentTaskId?.let { records[it]?.rangeLocked() },
            )
        }
        pushManagerChanged(callback, state.changedSnapshot, state.currentTaskId)
        state.currentRange?.let { pushTaskLogRange(callback, it) }
        pushGlobalLogRange(callback, state.globalRange)
    }

    fun unregisterCallback(callback: ITaskManagerCallback) {
        callbacks.unregister(callback)
    }

    internal fun appendLog(taskId: Long, text: String) {
        val ranges = synchronized(lock) {
            val record = records[taskId] ?: return
            val entry = TaskLogEntry(
                taskId = taskId,
                taskLineId = record.nextLogId++,
                globalLineId = nextGlobalLogId++,
                timestampMillis = System.currentTimeMillis(),
                text = text,
            )
            record.logs.addLast(entry)
            globalLogs.addLast(entry)
            while (record.logs.size > MAX_LOG_LINES) record.logs.removeFirst()
            while (globalLogs.size > MAX_LOG_LINES) globalLogs.removeFirst()
            record.logGeneration++
            globalLogGeneration++
            record.rangeLocked() to globalRangeLocked()
        }
        broadcastTaskLogRange(ranges.first)
        broadcastGlobalLogRange(ranges.second)
    }

    internal fun updateProgress(
        taskId: Long,
        progress: TaskProgress,
    ) {
        val snapshot = synchronized(lock) {
            val record = records[taskId] ?: return
            if (record.snapshot.state != TaskExecutionState.RUNNING) return
            record.snapshot = record.snapshot.copy(
                progress = progress,
            )
            record.snapshot
        }
        broadcastManagerChanged(snapshot)
    }

    internal fun isStopRequested(taskId: Long): Boolean = synchronized(lock) {
        records[taskId]?.stopRequested != false
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            currentTaskId
        }
        if (active != null) stop(active)
        taskExecutor.shutdownNow()
        callbackExecutor.shutdown()
        callbacks.kill()
    }

    private fun execute(taskId: Long) {
        synchronized(lock) { records[taskId]?.runnerThread = Thread.currentThread() }
        val request = snapshot(taskId).request
        try {
            val androidApi = androidApiProvider()
                ?: throw IllegalStateException("AndroidApi 尚未初始化")
            val task = when (val payload = request.payload) {
                is TaskRequestPayload.ConnectWifi -> {
                    val connectRequest = payload.request
                    val expectedSsid = when (val target = connectRequest.input.target) {
                        is ConnectWifiTarget.SavedNetwork -> androidApi.getSavedWifiList()
                            .firstOrNull { it.networkId == target.networkId }
                            ?.SSID
                            ?.removeSurrounding("\"")
                            ?.let(::decodeHexSsid)
                            ?: throw IllegalArgumentException(
                                "找不到 networkId=${target.networkId} 的已保存 Wi-Fi 配置",
                            )
                        is ConnectWifiTarget.TemporaryNetwork -> target.ssid
                    }
                    ConnectWifiTask(
                        request = connectRequest,
                        expectedSsid = expectedSsid,
                        androidApi = androidApi,
                        wifiLogAnalyzer = wifiLogAnalyzer,
                    )
                }
                is TaskRequestPayload.WpsPbc -> WpsPbcTask(
                    input = payload.input,
                    environment = hybridTaskEnvironmentProvider()
                        ?: throw IllegalStateException("WPS-PBC 任务只能在已就绪的混合扫描模式运行"),
                    terminalManager = terminalManager,
                    androidApi = androidApi,
                    onSavedWifiNetworksChanged = onSavedWifiNetworksChanged,
                    onError = onError,
                )
            }
            val pendingUpdates = synchronized(lock) {
                val record = records[taskId] ?: return
                record.activeTask = task
                buildList {
                    while (record.pendingUpdates.isNotEmpty()) {
                        add(record.pendingUpdates.removeFirst())
                    }
                }
            }
            pendingUpdates.forEach { update ->
                check(task.update(update)) { "任务不支持更新 ${update.payload.javaClass.simpleName}" }
            }
            task.run(TaskContext(taskId, this))
        } catch (_: InterruptedException) {
            appendLog(taskId, "任务已停止")
            Thread.interrupted()
        } catch (error: Throwable) {
            appendLog(
                taskId,
                "任务失败：${error.message ?: error.javaClass.name}",
            )
        } finally {
            synchronized(lock) { records[taskId]?.runnerThread = null }
            finish(taskId)
        }
    }

    private fun finish(taskId: Long) {
        val snapshot = synchronized(lock) {
            val record = records[taskId] ?: return
            if (record.snapshot.state == TaskExecutionState.FINISHED) return
            record.snapshot = record.snapshot.copy(
                state = TaskExecutionState.FINISHED,
            )
            if (currentTaskId == taskId) currentTaskId = null
            record.snapshot
        }
        broadcastManagerChanged(snapshot)
    }

    private fun validate(request: TaskStartRequest) {
        when (val payload = request.payload) {
            is TaskRequestPayload.ConnectWifi -> {
                val connect = payload.request
                when (val target = connect.input.target) {
                    is ConnectWifiTarget.SavedNetwork -> {
                        require(target.networkId >= 0) { "networkId 必须大于等于 0" }
                    }
                    is ConnectWifiTarget.TemporaryNetwork -> {
                        require(target.ssid.isNotBlank()) { "临时连接的 SSID 不能为空" }
                    }
                }
                if (connect.input.type == ConnectWifiTaskType.USE_SAVED_NETWORK) {
                    require(connect.input.target is ConnectWifiTarget.SavedNetwork) {
                        "使用已保存的网络连接必须提供 networkId"
                    }
                }
                require(connect.config.timeoutMillis > 0L) { "任务超时时间必须大于 0" }
                connect.config.failureFlags.handshakeAttemptsExceeded?.let {
                    require(it.maxHandshakeAttempts > 0) { "最大握手次数必须大于 0" }
                }
                connect.config.failureFlags.handshakeTimeout?.let {
                    require(it.handshakeStepTimeoutMillis > 0L) { "握手超时时间必须大于 0" }
                }
            }
            is TaskRequestPayload.WpsPbc -> {
                payload.input.targetMac?.let { mac ->
                    require(MAC_ADDRESS.matches(mac)) { "目标设备 MAC 格式非法：$mac" }
                }
                check(hybridTaskEnvironmentProvider() != null) {
                    "WPS-PBC 任务只能在已就绪的混合扫描模式运行"
                }
            }
        }
    }

    private fun validateUpdate(request: TaskStartRequest, update: TaskUpdateRequest) {
        require(request.payload is TaskRequestPayload.WpsPbc) { "当前任务不支持运行时更新" }
        when (update.payload) {
            is TaskUpdatePayload.WpsPbcContinuousCapture,
            is TaskUpdatePayload.WpsPbcAutoSaveToDevice,
            -> Unit
        }
    }

    private fun TaskRecord.rangeLocked(): TaskLogRangeSnapshot = TaskLogRangeSnapshot(
        taskId = snapshot.taskId,
        generation = logGeneration,
        oldestAvailableId = logs.firstOrNull()?.taskLineId ?: nextLogId,
        latestId = logs.lastOrNull()?.taskLineId ?: nextLogId - 1L,
        lineCount = logs.size,
    )

    private fun globalRangeLocked(): TaskLogRangeSnapshot = TaskLogRangeSnapshot(
        taskId = NO_TASK_ID,
        generation = globalLogGeneration,
        oldestAvailableId = globalLogs.firstOrNull()?.globalLineId ?: nextGlobalLogId,
        latestId = globalLogs.lastOrNull()?.globalLineId ?: nextGlobalLogId - 1L,
        lineCount = globalLogs.size,
    )

    private fun broadcastManagerChanged(snapshot: TaskSnapshot) {
        val current = synchronized(lock) { currentTaskId ?: NO_TASK_ID }
        callbackExecutor.execute {
            forEachCallback { callback ->
                pushManagerChanged(
                    callback,
                    snapshot,
                    current,
                )
            }
        }
    }

    private fun pushManagerChanged(
        callback: ITaskManagerCallback,
        snapshot: TaskSnapshot?,
        currentTaskId: Long,
    ) {
        runCatching {
            callback.onTaskManagerChanged(
                currentTaskId,
                snapshot?.taskId ?: NO_TASK_ID,
            )
        }.onFailure { callbacks.unregister(callback) }
    }

    private fun broadcastTaskLogRange(range: TaskLogRangeSnapshot) {
        callbackExecutor.execute {
            forEachCallback { callback -> pushTaskLogRange(callback, range) }
        }
    }

    private fun pushTaskLogRange(
        callback: ITaskManagerCallback,
        range: TaskLogRangeSnapshot,
    ) {
        runCatching {
            callback.onTaskLogRangeChanged(
                range.taskId,
                range.generation,
                range.oldestAvailableId,
                range.latestId,
                range.lineCount,
            )
        }.onFailure { callbacks.unregister(callback) }
    }

    private fun broadcastGlobalLogRange(range: TaskLogRangeSnapshot) {
        callbackExecutor.execute {
            forEachCallback { callback -> pushGlobalLogRange(callback, range) }
        }
    }

    private fun pushGlobalLogRange(
        callback: ITaskManagerCallback,
        range: TaskLogRangeSnapshot,
    ) {
        runCatching {
            callback.onGlobalTaskLogRangeChanged(
                range.generation,
                range.oldestAvailableId,
                range.latestId,
                range.lineCount,
            )
        }.onFailure { callbacks.unregister(callback) }
    }

    private inline fun forEachCallback(block: (ITaskManagerCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) block(callbacks.getBroadcastItem(index))
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private data class TaskRecord(
        var snapshot: TaskSnapshot,
        val logs: ArrayDeque<TaskLogEntry> = ArrayDeque(),
        var nextLogId: Long = 1L,
        var logGeneration: Long = 0L,
        var runnerThread: Thread? = null,
        var stopRequested: Boolean = false,
        var activeTask: ServiceTask? = null,
        val pendingUpdates: ArrayDeque<TaskUpdateRequest> = ArrayDeque(),
    )

    private data class ManagerCallbackState(
        val currentTaskId: Long,
        val changedSnapshot: TaskSnapshot?,
        val globalRange: TaskLogRangeSnapshot,
        val currentRange: TaskLogRangeSnapshot?,
    )

    companion object {
        const val NO_TASK_ID = 0L
        const val MAX_LOG_LINES = 50_000
        private val MAC_ADDRESS = Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    }
}

internal data class TaskLogRangeSnapshot(
    val taskId: Long,
    val generation: Long,
    val oldestAvailableId: Long,
    val latestId: Long,
    val lineCount: Int,
)
