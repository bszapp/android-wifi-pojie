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
import io.github.bszapp.wifitoolbox.service.ITaskManagerCallback
import io.github.bszapp.wifitoolbox.service.TerminalManager
import io.github.bszapp.wifitoolbox.service.WirelessInterfaceNetlink
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogAnalyzer
import io.github.bszapp.wifitoolbox.service.wifilog.decodeHexSsid
import io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetwork
import io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetworkPage
import io.github.bszapp.wifitoolbox.contract.PagedDataTransport
import io.github.bszapp.wifitoolbox.contract.log.sliceLogRange
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.os.ParcelFileDescriptor
import android.net.wifi.ScanResult
import io.github.bszapp.wifitoolbox.contract.wifilist.supportsWps
import io.github.bszapp.wifitoolbox.contract.task.*

internal class TaskManager(
    private val androidApiProvider: () -> AndroidApi?,
    private val wifiLogAnalyzer: WifiLogAnalyzer,
    private val terminalManager: TerminalManager,
    private val normalModeTaskReadyProvider: () -> Boolean,
    private val scanResultsProvider: () -> List<ScanResult>,
    private val usbMonitorTaskReadyProvider: () -> Boolean,
    private val acquireUsbMonitor: () -> AutoCloseable,
    private val onSavedWifiNetworksChanged: () -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val records = linkedMapOf<Long, TaskRecord>()
    private val globalLogs = ArrayList<TaskLogEntry>()
    private val callbacks = RemoteCallbackList<ITaskManagerCallback>()
    private val taskExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "service-task-runner").apply { isDaemon = true }
    }
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "service-task-callback").apply { isDaemon = true }
    }

    private val changedTasks = linkedSetOf<Long>()
    private val changedLogRanges = linkedSetOf<Long>()
    private var globalRangeDirty = false
    private var notificationScheduled = false

    private var nextTaskId = 1L
    private var nextGlobalLogId = 0L
    private var globalLogGeneration = 0L
    private var currentTaskId: Long? = null
    private var closed = false

    fun start(request: TaskStartRequest): Long {
        require(request.payload !is TaskRequestPayload.SecurityAudit) { "安全测试必须通过字典流接口启动" }
        val wps = request.payload as? TaskRequestPayload.WpsSecurityAudit
        if (wps != null) {
            validate(request)
            return synchronized(lock) {
                val running = currentTaskId?.let(records::get)
                if (running == null) createTask(request)
                else {
                    val current = running.snapshot.request.payload as? TaskRequestPayload.WpsSecurityAudit
                        ?: error("已有其他任务正在运行")
                    if (running.activeTask == null) {
                        val pending = current.input.targets + running.pendingUpdates.flatMap {
                            (it.payload as? TaskUpdatePayload.WpsSecurityAuditAdd)?.targets.orEmpty()
                        }
                        val keys = pending.map { wpsAuditTargetKey(it.ssid, it.mac) }.toSet()
                        check(wps.input.targets.none { wpsAuditTargetKey(it.ssid, it.mac) in keys }) { "目标网络已在运行队列中" }
                    }
                    val update = TaskUpdateRequest(TaskUpdatePayload.WpsSecurityAuditAdd(wps.input.targets))
                    val accepted = running.activeTask?.update(update) ?: run { running.pendingUpdates.addLast(update); true }
                    check(accepted) { "目标网络已在队列中，或本批任务已结束" }
                    running.snapshot.taskId
                }
            }
        }
        return createTask(request)
    }

    private fun createTask(request: TaskStartRequest): Long {
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

    /** 单条内部记录沿用原有事务。 */
    fun runSecurityAudit(request: TaskStartRequest, fd: ParcelFileDescriptor): Long =
        runSecurityAuditRecords(request, fd, 1)

    /** 一次读取字典，在同一个顶层任务内加入 taskCount 条测试记录。 */
    fun runSecurityAuditRecords(request: TaskStartRequest, fd: ParcelFileDescriptor, taskCount: Int): Long {
        require(taskCount in 1..8) { "测试记录数量应为 1～8 条" }
        val input = (request.payload as? TaskRequestPayload.SecurityAudit)?.input
            ?: error("安全测试请求类型不正确")
        validateAudit(input)
        require(input.network.sharedWithRecordId == null) { "共享候选池不能重复提交字典" }
        require(PagedDataTransport.bytes(request).size <= 64 * 1024) { "安全测试请求元数据过大" }
        val passwords = SecurityAuditDictionaryTransport.read(fd)
        require(passwords.isNotEmpty()) { "字典为空" }
        val (record, created) = synchronized(lock) {
            check(!closed) { "任务管理器已关闭" }
            val current = currentTaskId?.let(records::get)
            check(current == null || current.snapshot.request.payload is TaskRequestPayload.SecurityAudit &&
                current.snapshot.state == TaskExecutionState.RUNNING && !current.stopRequested) {
                "已有其他任务正在运行或安全测试正在结束"
            }
            val isNew = current == null
            val dispatcher = current?.auditDispatcher ?: run {
                check(isNew) { "安全测试调度器不可用" }
                SecurityAuditDispatcher(input.config,
                    SecurityAuditConnectWorker(androidApiProvider() ?: error("AndroidApi 尚未初始化"), wifiLogAnalyzer, terminalManager),
                    ::managedInterfaces)
            }
            check(dispatcher.isAccepting()) { "安全测试正在结束" }
            val parent = current ?: run {
                check(nextTaskId < Long.MAX_VALUE) { "任务 ID 已耗尽" }
                val id = nextTaskId++
                TaskRecord(TaskSnapshot(id, request, TaskExecutionState.RUNNING, null),
                    auditDispatcher = dispatcher).also {
                    records[id] = it
                    currentTaskId = id
                }
            }
            if (dispatcher.currentConfig() != input.config) check(dispatcher.configure(input.config)) { "调度器已结束" }
            if (!dispatcher.submitGroup(input.network, passwords, taskCount, TaskContext(parent.snapshot.taskId, this))) {
                if (isNew) {
                    records.remove(parent.snapshot.taskId)
                    currentTaskId = null
                    nextTaskId--
                }
                error("调度器已结束")
            }
            if (isNew) taskExecutor.execute { executeAudit(dispatcher, parent.snapshot.taskId) }
            parent to isNew
        }
        if (created) broadcastManagerChanged(record.snapshot)
        appendLog(record.snapshot.taskId, if (created) "安全测试任务已创建：加入 $taskCount 条测试记录"
            else "安全测试已加入 $taskCount 条测试记录")
        return record.snapshot.taskId
    }

    /** 单条内部记录沿用原有事务。 */
    fun joinSecurityAuditPool(request: TaskStartRequest): Long =
        joinSecurityAuditRecords(request, 1)

    /** 一次将 taskCount 条内部记录接入同一个候选池。 */
    fun joinSecurityAuditRecords(request: TaskStartRequest, taskCount: Int): Long {
        require(taskCount in 1..8) { "测试记录数量应为 1～8 条" }
        val input = (request.payload as? TaskRequestPayload.SecurityAudit)?.input
            ?: error("共享候选池请求类型不正确")
        validateAudit(input)
        val sourceId = input.network.sharedWithRecordId ?: error("未指定共享来源记录")
        require(PagedDataTransport.bytes(request).size <= 64 * 1024) { "共享候选池请求元数据过大" }
        val taskId = synchronized(lock) {
            check(!closed) { "任务管理器已关闭" }
            val parent = currentTaskId?.let(records::get)?.takeIf {
                it.snapshot.request.payload is TaskRequestPayload.SecurityAudit &&
                    it.snapshot.state == TaskExecutionState.RUNNING && !it.stopRequested
            } ?: error("没有运行中的安全测试")
            val dispatcher = parent.auditDispatcher?.takeIf { it.isAccepting() }
                ?: error("没有可共享的运行中安全测试")
            val source = parent.auditNetworks.asSequence().filterIsInstance<AuditRecord.Dictionary>()
                .firstOrNull { it.id == sourceId && it.active }
                ?: error("共享来源记录已结束")
            require(source.input.dictionaryId == input.network.dictionaryId) { "共享字典标识与来源记录不一致" }
            if (!dispatcher.joinGroup(input.network, taskCount, TaskContext(parent.snapshot.taskId, this))) {
                error("共享候选池已停止接受新任务")
            }
            parent.snapshot.taskId
        }
        appendLog(taskId, "安全测试已加入 $taskCount 条共享候选池测试记录")
        return taskId
    }

    private fun executeAudit(dispatcher: SecurityAuditDispatcher, taskId: Long) {
        try {
            dispatcher.run()
        } catch (_: InterruptedException) {
            Thread.interrupted()
        } catch (error: Throwable) {
            appendLog(taskId, "调度器异常：${error.message ?: error.javaClass.name}")
        } finally {
            finish(taskId)
        }
    }

    fun auditTaskIds(offset: Int): LongArray = synchronized(lock) {
        require(offset >= 0)
        records.values.asSequence().filter {
            it.snapshot.request.payload is TaskRequestPayload.SecurityAudit ||
                it.snapshot.request.payload is TaskRequestPayload.WpsSecurityAudit
        }
            .drop(offset).take(100).map { it.snapshot.taskId }.toList().toLongArray()
    }

    fun auditNetworkPage(taskId: Long, fromIndex: Int): AuditRecordPage = synchronized(lock) {
        val record = records[taskId] ?: error("任务 $taskId 不存在")
        val networks = record.auditNetworks
        require(fromIndex in 0..networks.size)
        var end = minOf(fromIndex + 100, networks.size)
        while (true) {
            val page = AuditRecordPage(record.auditRevision, fromIndex, networks.size, networks.subList(fromIndex, end).toList())
            if (PagedDataTransport.bytes(page).size <= PagedDataTransport.MAX_PAGE_BYTES) return@synchronized page
            check(end > fromIndex + 1) { "单条安全测试记录超过传输预算" }
            end--
        }
        @Suppress("UNREACHABLE_CODE") error("不可达")
    }

    internal fun updateAuditNetworks(
        taskId: Long,
        networks: List<AuditRecord>,
        progressOf: (revision: Long, activeCount: Int) -> TaskProgress,
    ) {
        synchronized(lock) {
            val record = records[taskId] ?: return
            val active = networks.count { it.active }
            if (record.auditNetworks == networks && record.snapshot.progress == progressOf(record.auditRevision, active)) return
            record.auditNetworks = networks
            record.auditRevision++
            updateProgress(taskId, progressOf(record.auditRevision, networks.count { it.active }))
        }
    }

    fun stop(taskId: Long): Boolean {
        val target = synchronized(lock) {
            val record = records[taskId]
            if (record?.snapshot?.state != TaskExecutionState.RUNNING) {
                return false
            }
            if (record.auditDispatcher != null && !record.auditDispatcher.stopAll()) return false
            record.stopRequested = true
            record.auditDispatcher to record.runnerThread
        }
        appendLog(taskId, "收到停止任务指令")
        if (target.first == null) target.second?.interrupt()
        return true
    }

    fun update(taskId: Long, update: TaskUpdateRequest): Boolean {
        val task = synchronized(lock) {
            val record = records[taskId]
            if (record?.snapshot?.state != TaskExecutionState.RUNNING) {
                return false
            }
            validateUpdate(record.snapshot.request, update)
            record.auditDispatcher?.let { dispatcher ->
                return when (val payload = update.payload) {
                    is TaskUpdatePayload.SecurityAuditConfigure -> dispatcher.configure(payload.config)
                    is TaskUpdatePayload.SecurityAuditStopRecord ->
                        record.auditNetworks.any { it.id == payload.recordId && it.active } &&
                            dispatcher.stopRecord(payload.recordId)
                    is TaskUpdatePayload.SecurityAuditRemove ->
                        record.auditNetworks.any { it is AuditRecord.Dictionary && it.ssid == payload.ssid && it.active } &&
                            dispatcher.stopNetwork(payload.ssid)
                    else -> error("安全测试不支持此更新")
                }
            }
            if (currentTaskId != taskId) return false
            record.activeTask ?: run {
                record.pendingUpdates.addLast(update)
                return true
            }
        }
        return task.update(update)
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
                entries = record.logs.sliceLogRange(range.oldestAvailableId, fromIdInclusive, toIdInclusive, 500),
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
                entries = globalLogs.sliceLogRange(range.oldestAvailableId, fromIdInclusive, toIdInclusive, 500),
            )
        }

    internal fun recordCapturedNetwork(taskId: Long, network: WpsCapturedNetwork) {
        synchronized(lock) {
            val record = records[taskId] ?: error("任务 $taskId 不存在")
            record.capturedNetworks.add(network)
        }
    }

    fun capturedNetworkPage(taskId: Long, fromIndex: Int): WpsCapturedNetworkPage =
        synchronized(lock) {
            val networks = records[taskId]?.capturedNetworks ?: error("任务 $taskId 不存在")
            require(fromIndex in 0..networks.size) { "捕获结果起始下标非法" }
            var end = minOf(fromIndex + 50, networks.size)
            while (true) {
                val page = WpsCapturedNetworkPage(fromIndex, networks.size, networks.subList(fromIndex, end).toList())
                if (PagedDataTransport.bytes(page).size <= PagedDataTransport.MAX_PAGE_BYTES) return@synchronized page
                check(end > fromIndex + 1) { "单条捕获结果超出传输预算" }
                end--
            }
            @Suppress("UNREACHABLE_CODE")
            error("不可达")
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
            record.logs.add(entry)
            globalLogs.add(entry)
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

    internal fun auditNotificationText(): String? = synchronized(lock) {
        val auditTask = currentTaskId?.let(records::get)?.takeIf { it.auditDispatcher != null &&
            it.snapshot.state == TaskExecutionState.RUNNING }
        if (auditTask != null) {
            val active = auditTask.auditNetworks.filterIsInstance<AuditRecord.Dictionary>()
                .filter { it.active }
            val windows = (auditTask.snapshot.progress as? TaskProgress.SecurityAudit)?.windows.orEmpty()
            if (windows.isNotEmpty()) {
                val busy = active.mapNotNull { entry -> entry.window?.let { it to entry } }.toMap()
                return@synchronized "正在进行密码测试\n" + windows.joinToString("\n") { window ->
                    val entry = busy[window]
                    if (entry == null) "$window> IDLE" else "$window> ${entry.ssid},${entry.attemptPassword ?: "?"}"
                }
            }
            val connecting = active.firstOrNull { it.state == SecurityAuditNetworkState.CONNECTING }
            return@synchronized if (connecting != null) "网络安全测试：${connecting.ssid} · ${connecting.message}"
                else "网络安全测试：${active.size} 个任务等待窗口或冷却"
        }
        val record = currentTaskId?.let(records::get) ?: return@synchronized null
        val isDictionary = record.snapshot.request.payload is TaskRequestPayload.SecurityAudit
        if (!isDictionary && record.snapshot.request.payload !is TaskRequestPayload.WpsSecurityAudit) {
            return@synchronized null
        }
        val active = record.auditNetworks.filter { it.active }
        // 多线程：按窗口逐行列出每张网卡此刻在做什么，空闲窗口也占一行。
        val windows = (record.snapshot.progress as? TaskProgress.SecurityAudit)?.windows.orEmpty()
        if (windows.isNotEmpty()) {
            val busy = active.filterIsInstance<AuditRecord.Dictionary>()
                .mapNotNull { entry -> entry.window?.let { it to entry } }.toMap()
            val body = windows.joinToString("\n") { window ->
                val job = busy[window]
                if (job == null) "$window> IDLE"
                else "$window> ${job.ssid},${job.attemptPassword ?: "?"}"
            }
            return "正在进行密码测试\n$body"
        }
        val current = active.firstOrNull { it.state == SecurityAuditNetworkState.CONNECTING }
        if (!isDictionary) {
            val label = if (current != null) "${current.ssid} · ${current.message}" else "等待冷却"
            return "WPS 安全测试：$label"
        }
        val estimates = active.filterIsInstance<AuditRecord.Dictionary>()
            .groupBy { it.ssid to (it.groupId ?: it.id) }.values.map { members ->
                val latest = members.maxBy { it.updatedAt }
                latest.groupAverageAttemptMillis?.times((latest.total -
                    members.maxOf { it.poolCompleted ?: it.completed }).coerceAtLeast(0))
            }
        val remaining = if (estimates.any { it == null } || estimates.isEmpty()) "剩余时间计算中"
            else "预计剩余 ${estimates.filterNotNull().sum() / 1000} 秒"
        if (current != null) "网络安全测试：${current.ssid} · ${current.message}；$remaining"
        else if (active.isNotEmpty()) "网络安全测试：等待冷却；$remaining"
        else "网络安全测试：准备中"
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            records.values.filter { it.snapshot.state == TaskExecutionState.RUNNING }
                .map { it.snapshot.taskId }
        }
        active.forEach(::stop)
        taskExecutor.shutdownNow()
        check(taskExecutor.awaitTermination(20, TimeUnit.SECONDS)) {
            "任务清理尚未完成，不能退出服务"
        }
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
                is TaskRequestPayload.SecurityAudit -> error("安全测试任务由共享窗口调度器执行")
                TaskRequestPayload.UsbMonitor -> UsbMonitorTask(terminalManager, acquireUsbMonitor)
                is TaskRequestPayload.ConnectWifi -> {
                    createConnectionTask(payload.request, androidApi)
                }
                is TaskRequestPayload.WpsPbc -> WpsPbcTask(
                    input = payload.input,
                    terminalManager = terminalManager,
                    androidApi = androidApi,
                    onSavedWifiNetworksChanged = onSavedWifiNetworksChanged,
                    onError = onError,
                )
                is TaskRequestPayload.WpsSecurityAudit -> WpsSecurityAuditTask(
                    input = payload.input,
                    terminals = terminalManager,
                    androidApi = androidApi,
                    scanResults = scanResultsProvider,
                    onSavedWifiNetworksChanged = onSavedWifiNetworksChanged,
                    onError = onError,
                )
                is TaskRequestPayload.WpsPin -> {
                    val target = scanResultsProvider().filter {
                        it.SSID == payload.input.ssid && it.supportsWps()
                    }.maxByOrNull { if (it.level == 0) Int.MIN_VALUE else it.level }
                        ?: error("当前扫描结果没有同名且带 WPS 广播的接入点")
                    WpsPinTask(payload.input, target.BSSID, terminalManager, androidApi,
                        onSavedWifiNetworksChanged, onError)
                }
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
            if (request.payload is TaskRequestPayload.UsbMonitor) onError("电脑控制", error)
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
            record.activeTask = null
            record.pendingUpdates.clear()
            record.snapshot
        }
        broadcastManagerChanged(snapshot)
    }

    private fun validate(request: TaskStartRequest) {
        when (val payload = request.payload) {
            is TaskRequestPayload.SecurityAudit -> validateAudit(payload.input)
            is TaskRequestPayload.WpsSecurityAudit -> validateWpsAudit(payload.input)
            TaskRequestPayload.UsbMonitor -> {
                require(android.os.Process.myUid() == 0) { "电脑控制需要 Root 工作模式" }
                check(usbMonitorTaskReadyProvider()) { "电脑控制只能在监听模式运行" }
                require(terminalManager.isContainerSystemInstalled()) { "容器系统尚未安装" }
                require(terminalManager.hasRootfsFile("wlantool/usb_monitor.py")) {
                    "容器缺少 usb_monitor.py，请更新容器系统"
                }
            }
            is TaskRequestPayload.ConnectWifi -> {
                val connect = payload.request
                when (val target = connect.input.target) {
                    is ConnectWifiTarget.SavedNetwork -> {
                        require(target.networkId >= 0) { "networkId 必须大于等于 0" }
                    }
                    is ConnectWifiTarget.TemporaryNetwork -> {
                        require(target.ssid.isNotBlank()) { "临时连接的 SSID 不能为空" }
                    }
                    is ConnectWifiTarget.NetworkCard -> {
                        validateNetworkCardEnvironment()
                        require(target.ssid.toByteArray(Charsets.UTF_8).size in 1..32) { "SSID 长度应为 1～32 字节" }
                        require(target.passwords.isNotEmpty() && target.passwords.all {
                            it.toByteArray(Charsets.UTF_8).size in 8..63
                        }) { "每行 WPA2 密码需为 8～63 字节" }
                        target.mac?.let { mac ->
                            require(MAC_ADDRESS.matches(mac)) { "MAC 格式非法" }
                            require((mac.substringBefore(':').toInt(16) and 1) == 0 &&
                                mac != "00:00:00:00:00:00") { "MAC 必须为有效单播地址" }
                        }
                    }
                }
                if (connect.input.type == ConnectWifiTaskType.USE_SAVED_NETWORK) {
                    require(connect.input.target is ConnectWifiTarget.SavedNetwork) {
                        "使用已保存的网络连接必须提供 networkId"
                    }
                }
                validateConnectionConfig(connect.config)
            }
            is TaskRequestPayload.WpsPbc -> {
                payload.input.targetMac?.let { mac ->
                    require(MAC_ADDRESS.matches(mac)) { "目标设备 MAC 格式非法：$mac" }
                }
                check(normalModeTaskReadyProvider()) {
                    "WPS-PBC 任务只能在普通模式且容器环境已配置时运行"
                }
            }
            is TaskRequestPayload.WpsPin -> {
                require(android.os.Process.myUid() == 0) { "WPS PIN 需要 Root 工作模式" }
                check(normalModeTaskReadyProvider()) { "WPS PIN 只能在普通模式运行" }
                require(payload.input.ssid.toByteArray(Charsets.UTF_8).size in 1..32) { "SSID 长度应为 1～32 字节" }
                require(Regex("(?:[0-9]{4}|[0-9]{8})").matches(payload.input.pin)) { "PIN 应为 4 位或 8 位数字" }
                payload.input.localMac?.trim()?.takeIf { it.isNotEmpty() }?.let { mac ->
                    require(MAC_ADDRESS.matches(mac)) { "本机 MAC 格式非法" }
                    require((mac.substringBefore(':').toInt(16) and 1) == 0 && mac != "00:00:00:00:00:00") {
                        "本机 MAC 必须为有效单播地址"
                    }
                }
                require(terminalManager.isContainerSystemInstalled()) { "容器系统尚未安装" }
                require(terminalManager.hasRootfsFile("wlantool/wps.py")) { "容器缺少 wps.py，请更新容器系统" }
            }
        }
    }

    private fun validateUpdate(request: TaskStartRequest, update: TaskUpdateRequest) {
        if (request.payload is TaskRequestPayload.SecurityAudit) {
            when (val payload = update.payload) {
                is TaskUpdatePayload.SecurityAuditConfigure -> validateAuditConfig(payload.config)
                is TaskUpdatePayload.SecurityAuditStopRecord -> java.util.UUID.fromString(payload.recordId)
                is TaskUpdatePayload.SecurityAuditRemove -> {
                    require(payload.mac == null) { "字典安全测试按 SSID 停止" }
                    require(payload.ssid.toByteArray(Charsets.UTF_8).size in 1..32) { "SSID 长度应为 1～32 字节" }
                }
                else -> error("安全测试不支持此更新")
            }
            return
        }
        if (request.payload is TaskRequestPayload.WpsSecurityAudit) {
            when (val payload = update.payload) {
                is TaskUpdatePayload.WpsSecurityAuditConfigure -> validateWpsAuditConfig(payload.config)
                is TaskUpdatePayload.SecurityAuditRemove -> validateWpsTarget(WpsSecurityAuditTarget(payload.ssid, payload.mac))
                is TaskUpdatePayload.WpsSecurityAuditAdd -> validateWpsAudit(WpsSecurityAuditTaskInput(
                    (request.payload as TaskRequestPayload.WpsSecurityAudit).input.config, payload.targets))
                else -> error("WPS 安全测试不支持此更新")
            }
            return
        }
        require(request.payload is TaskRequestPayload.WpsPbc) { "当前任务不支持运行时更新" }
        when (update.payload) {
            is TaskUpdatePayload.WpsPbcContinuousCapture,
            is TaskUpdatePayload.WpsPbcAutoSaveToDevice,
            is TaskUpdatePayload.WpsPbcUseIncompleteProtocol,
            is TaskUpdatePayload.WpsPbcIgnoreRepeatedDevices,
            -> Unit
            else -> error("WPS-PBC 不支持此更新")
        }
    }

    private fun validateWpsAudit(input: WpsSecurityAuditTaskInput) {
        require(input.targets.isNotEmpty()) { "至少选择一个网络" }
        val seen = mutableSetOf<String>()
        input.targets.forEach { target ->
            validateWpsTarget(target)
            require(seen.add(wpsAuditTargetKey(target.ssid, target.mac))) { "同一目标只能出现一次" }
        }
        validateWpsAuditConfig(input.config)
    }

    private fun validateWpsTarget(target: WpsSecurityAuditTarget) {
            require(target.ssid.toByteArray(Charsets.UTF_8).size in 0..32) { "SSID 长度不能超过32字节" }
            require(target.ssid.isNotEmpty() || target.mac != null) { "隐藏网络必须先选择具体接入点" }
            target.mac?.let { mac ->
                require(MAC_ADDRESS.matches(mac)) { "目标设备 MAC 格式非法：$mac" }
                require((mac.substringBefore(':').toInt(16) and 1) == 0 && mac != "00:00:00:00:00:00") {
                    "目标设备 MAC 必须为有效单播地址"
                }
            }
    }

    private fun validateWpsAuditConfig(config: WpsSecurityAuditConfig) {
        require(android.os.Process.myUid() == 0) { "WPS 安全测试需要 Root 工作模式" }
        check(normalModeTaskReadyProvider()) { "WPS 安全测试只能在普通模式且容器环境已配置时运行" }
        require(config.retryCount in 0..6 && config.cooldownBaseMillis in 0..8000) { "重试/冷却配置非法" }
        require(config.macChangeThreshold in 1..5) { "异常时更换的连续重试次数应为 1～5" }
        require(config.maxAttemptsPerNetwork >= 0) { "单网络尝试上限不能为负" }
        require(terminalManager.isContainerSystemInstalled()) { "容器系统尚未安装" }
        require(terminalManager.hasRootfsFile("wlantool/wps.py")) { "容器缺少 wps.py，请更新容器系统" }
        require(terminalManager.hasRootfsFile("bin/pixiewps")) { "容器缺少 pixiewps，请更新容器系统" }
    }

    private fun validateAudit(input: SecurityAuditTaskInput) {
        require(input.network.ssid.toByteArray(Charsets.UTF_8).size in 1..32) { "SSID 长度应为 1～32 字节" }
        java.util.UUID.fromString(input.network.dictionaryId)
        input.network.sharedWithRecordId?.let(java.util.UUID::fromString)
        require(input.network.dictionaryNames.isNotEmpty()) { "未选择字典" }
        require(input.network.startIndex >= 0) { "字典起始位置非法" }
        validateAuditConfig(input.config)
    }

    private fun validateAuditConfig(config: SecurityAuditConfig) {
        check(normalModeTaskReadyProvider()) { "安全测试只能在普通网卡模式运行" }
        require(config.retryCount in 0..6 && config.cooldownBaseMillis in 0..8000) { "重试/冷却配置非法" }
        require(config.macChangeThreshold in 1..5) { "异常时更换的连续重试次数应为 1～5" }
        // 多线程要多开脚本各占一张网卡，只有网卡方式具备这个条件。
        require(!config.multithreaded || config.connectionMethod == SecurityAuditConnectionMethod.NETWORK_CARD) {
            "多线程运行仅在测试连通性（网卡）方式下可用"
        }
        validateConnectionConfig(config.connection)
        if (config.connectionMethod == SecurityAuditConnectionMethod.NETWORK_CARD) validateNetworkCardEnvironment()
    }

    /** 可作为普通 Wi-Fi 客户端的网卡；多线程模式下由任务当作可分配的窗口。 */
    private fun managedInterfaces(): List<String> =
        runCatching { WirelessInterfaceNetlink.managedInterfaceNames() }.getOrElse { emptyList() }

    private fun validateConnectionConfig(config: ConnectWifiTaskConfig) {
        require(config.timeoutMillis > 0L) { "任务超时时间必须大于 0" }
        config.failureFlags.handshakeAttemptsExceeded?.let {
            require(it.maxHandshakeAttempts > 0) { "最大握手次数必须大于 0" }
        }
        config.failureFlags.handshakeTimeout?.let {
            require(it.handshakeStepTimeoutMillis > 0L) { "握手超时时间必须大于 0" }
        }
    }

    private fun validateNetworkCardEnvironment() {
        require(android.os.Process.myUid() == 0) { "网卡测试需要 Root 工作模式" }
        check(normalModeTaskReadyProvider()) { "网卡测试只能在普通模式且容器环境已配置时运行" }
        require(terminalManager.isContainerSystemInstalled()) { "容器系统尚未安装" }
        require(terminalManager.hasRootfsFile("wlantool/managed_connect.py")) {
            "容器缺少 managed_connect.py，请更新容器系统"
        }
        // 脚本靠 --interface 指定网卡；旧容器不认识这个参数，直接开跑会以参数错误退出。
        require(terminalManager.rootfsFileContains("wlantool/managed_connect.py", "'--interface'")) {
            "容器系统版本过旧，managed_connect.py 尚不支持指定网卡，请更新容器系统"
        }
    }

    private fun createConnectionTask(request: io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskRequest,
        androidApi: AndroidApi): ServiceTask {
        val card = request.input.target as? ConnectWifiTarget.NetworkCard
        if (card != null) return NetworkCardConnectTask(card, request.config, terminalManager,
            card.name ?: androidApi.getDeviceName())
        val ssid = when (val target = request.input.target) {
            is ConnectWifiTarget.SavedNetwork -> androidApi.getSavedWifiList().firstOrNull { it.networkId == target.networkId }
                ?.SSID?.removeSurrounding("\"")?.let(::decodeHexSsid)
                ?: error("找不到 networkId=${target.networkId} 的已保存 Wi-Fi 配置")
            is ConnectWifiTarget.TemporaryNetwork -> target.ssid
            is ConnectWifiTarget.NetworkCard -> error("不可达")
        }
        return ConnectWifiTask(request, ssid, androidApi, wifiLogAnalyzer)
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
        synchronized(lock) { changedTasks.add(snapshot.taskId) }
        scheduleNotifications()
    }

    private fun scheduleNotifications() {
        synchronized(lock) {
            if (closed || notificationScheduled) return
            notificationScheduled = true
        }
        callbackExecutor.execute {
            try {
                while (true) {
                    val update = synchronized(lock) {
                        if (changedTasks.isEmpty() && changedLogRanges.isEmpty() && !globalRangeDirty) return@execute
                        Notification(
                            currentTaskId ?: NO_TASK_ID,
                            changedTasks.mapNotNull { records[it]?.snapshot },
                            changedLogRanges.mapNotNull { records[it]?.rangeLocked() },
                            if (globalRangeDirty) globalRangeLocked() else null,
                        ).also { changedTasks.clear(); changedLogRanges.clear(); globalRangeDirty = false }
                    }
                    forEachCallback { cb ->
                        update.snapshots.forEach { pushManagerChanged(cb, it, update.currentTaskId) }
                        update.ranges.forEach { pushTaskLogRange(cb, it) }
                        update.globalRange?.let { pushGlobalLogRange(cb, it) }
                    }
                }
            } finally {
                val pending = synchronized(lock) {
                    notificationScheduled = false
                    changedTasks.isNotEmpty() || changedLogRanges.isNotEmpty() || globalRangeDirty
                }
                if (pending) scheduleNotifications()
            }
        }
    }

    private data class Notification(
        val currentTaskId: Long,
        val snapshots: List<TaskSnapshot>,
        val ranges: List<TaskLogRangeSnapshot>,
        val globalRange: TaskLogRangeSnapshot?,
    )

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
        synchronized(lock) { changedLogRanges.add(range.taskId) }
        scheduleNotifications()
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
        synchronized(lock) { globalRangeDirty = true }
        scheduleNotifications()
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
        val logs: ArrayList<TaskLogEntry> = ArrayList(),
        val capturedNetworks: ArrayList<WpsCapturedNetwork> = ArrayList(),
        val auditDispatcher: SecurityAuditDispatcher? = null,
        var auditNetworks: List<AuditRecord> = emptyList(),
        var auditRevision: Long = 0,
        var nextLogId: Long = 0L,
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
