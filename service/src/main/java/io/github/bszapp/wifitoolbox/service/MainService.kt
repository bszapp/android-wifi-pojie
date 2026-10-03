package io.github.bszapp.wifitoolbox.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse
import io.github.bszapp.wifitoolbox.contract.container.ContainerEnvironment
import io.github.bszapp.wifitoolbox.contract.container.ContainerOperationRequest
import io.github.bszapp.wifitoolbox.contract.container.ContainerState
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogTransport
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogTransport
import io.github.bszapp.wifitoolbox.contract.task.TaskLogTransport
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.service.task.TaskManager
import io.github.bszapp.wifitoolbox.service.container.ContainerSystemManager
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogAnalyzer

@Keep
open class MainService(
    private val serviceContext: Context? = null,
) : IMainService.Stub() {

    private val serviceLogRecorder = ServiceLogRecorders.service
    private val systemWifiLogRecorder = ServiceLogRecorders.systemWifi

    init {
        ServiceLogRecorders.start()
    }

    constructor(startupInfo: StartupInfo) : this(serviceContext = null) {
        Log.d(TAG, "使用启动参数初始化服务")
        initializeFromStartupInfo(startupInfo)
    }

    private val initializer = ServiceInitializer(serviceContext)

    private val wifiLogAnalyzer = WifiLogAnalyzer(systemWifiLogRecorder)

    private val communication = ServiceCommunication(
        startupInfoProvider = { initializer.requireStartupInfo() },
        serviceBinderProvider = { this.asBinder() },
    )

    init {
        serviceLogRecorder.setOnVisibleRangeChanged(
            communication::broadcastServiceLogRangeChanged,
        )
        systemWifiLogRecorder.setOnVisibleRangeChanged(
            communication::broadcastSystemWifiLogRangeChanged,
        )
    }

    private val terminalManager = TerminalManager(
        onAliveTerminalsChanged = communication::broadcastAliveTerminalsChanged,
        onTerminalLogRangeChanged = communication::broadcastTerminalLogRangeChanged,
    )

    private val hybridWifiScanner = HybridWifiScanner(
        terminalManager = terminalManager,
    )

    private val containerSystemManager = ContainerSystemManager(
        trustedUid = { initializer.requireStartupInfo().trustedUid },
        beforeDelete = hybridWifiScanner::stop,
        onError = { operation, error ->
            communication.broadcastServiceError(
                source = "Service.ContainerSystemManager",
                operation = operation,
                error = error,
            )
        },
    )

    private val wifiListController: WifiListController = WifiListController(
        androidApiProvider = { initializer.androidApi },
        hybridWifiScanner = hybridWifiScanner,
        terminalManager = terminalManager,
        onWifiStateChanged = communication::broadcastWifiState,
        onSavedWifiListChanged = communication::broadcastSavedWifiList,
        onModeStateChanged = { state ->
            communication.broadcastWifiModeState(state)
            serviceNotifications.refresh()
        },
        onMonitorRecordedBytesChanged = communication::broadcastMonitorRecordedBytes,
        onMonitorPcapExported = communication::broadcastMonitorPcapExported,
        onError = { operation, error ->
            communication.broadcastServiceError(
                source = "Service.WifiListController",
                operation = operation,
                error = error,
            )
        },
    )

    private val taskManager: TaskManager = TaskManager(
        androidApiProvider = { initializer.androidApi },
        wifiLogAnalyzer = wifiLogAnalyzer,
        terminalManager = terminalManager,
        hybridTaskEnvironmentProvider = wifiListController::getHybridTaskEnvironment,
        networkCardTaskEnvironmentProvider = wifiListController::getNetworkCardTaskEnvironment,
        onSavedWifiNetworksChanged = wifiListController::refreshSavedNetworks,
        onError = { operation, error ->
            communication.broadcastServiceError(
                source = "Service.WpsPbcTask",
                operation = operation,
                error = error,
            )
        },
    )

    private val serviceNotifications: ServiceNotificationManager = ServiceNotificationManager(
        hashcatTaskCountProvider = { hashcatManager.activeCount() },
        modeProvider = { wifiListController.getModeState().mode },
        taskProvider = {
            taskManager.currentTaskId().takeIf { it != TaskManager.NO_TASK_ID }
                ?.let(taskManager::snapshot)
        },
        appUserIdProvider = { initializer.requireStartupInfo().trustedUid / 100_000 },
        onError = { operation, error ->
            communication.broadcastServiceError(
                source = "Service.Notification",
                operation = operation,
                error = error,
            )
        },
    )

    private val hashcatManager: io.github.bszapp.wifitoolbox.service.hashcat.HashcatManager = io.github.bszapp.wifitoolbox.service.hashcat.HashcatManager(
        onError = { operation, error -> communication.broadcastServiceError("Service.Hashcat", operation, error) },
        onActivityChanged = { serviceNotifications.refresh() },
    )

    private val notificationTaskCallback = object : ITaskManagerCallback.Stub() {
        override fun onTaskManagerChanged(currentTaskId: Long, changedTaskId: Long) {
            serviceNotifications.refresh()
        }

        override fun onTaskLogRangeChanged(
            taskId: Long, generation: Long, oldestAvailableId: Long, latestId: Long, lineCount: Int,
        ) = Unit

        override fun onGlobalTaskLogRangeChanged(
            generation: Long, oldestAvailableId: Long, latestId: Long, lineCount: Int,
        ) = Unit
    }

    init {
        taskManager.registerCallback(notificationTaskCallback)
    }

    private val wifiEventMonitor = ServiceWifiBroadcastLogger(
        onWifiStateChanged = wifiListController::onWifiStateMayHaveChanged,
        onWifiNetworkStateChanged = wifiListController::onWifiNetworkStateChanged,
        onError = { operation, error ->
            communication.broadcastServiceError(
                source = "Service.WifiEventMonitor",
                operation = operation,
                error = error,
            )
        },
    )

    override fun initializeStartupInfo(startupInfo: StartupInfo) {
        communication.enforceStartupInitializer(startupInfo)
        Log.d(TAG, "应用补充启动参数")
        initializeFromStartupInfo(startupInfo)
    }

    private fun initializeFromStartupInfo(startupInfo: StartupInfo): StartupInfo {
        val completed = initializer.initialize(startupInfo)
        serviceNotifications.start()
        wifiEventMonitor.start()
        wifiListController.initialize()
        communication.startBinderPublisher()
        return completed
    }

    override fun connect(): Boolean = communication.connect()

    override fun isAlive(): Boolean = communication.isAlive()

    override fun getStartupInfo(): StartupInfo = communication.callFromApp {
        initializer.requireStartupInfo()
    }

    override fun configureContainerSystem(environment: ContainerEnvironment) = communication.callFromApp {
        containerSystemManager.configure(environment)
    }

    override fun getContainerState(): ContainerState = communication.callFromApp { containerSystemManager.state() }

    override fun executeContainerOperation(
        request: ContainerOperationRequest,
        archive: ParcelFileDescriptor?,
    ): Boolean = communication.callFromApp {
        try { containerSystemManager.start(request, archive) }
        finally { archive?.close() }
    }

    override fun registerContainerSystemCallback(cb: IContainerSystemCallback) = communication.callFromApp {
        containerSystemManager.register(cb)
    }

    override fun unregisterContainerSystemCallback(cb: IContainerSystemCallback) = communication.callFromApp {
        containerSystemManager.unregister(cb)
    }

    override fun executeAndroidApi(request: AndroidApiRequest): AndroidApiResponse =
        communication.callFromApp {
            runCatching {
                initializer.androidApi
                    ?.execute(request)
                    ?: AndroidApiResponse.failure(
                        IllegalStateException("AndroidApi 尚未初始化"),
                    )
            }.getOrElse(AndroidApiResponse::failure)
        }

    override fun getServiceLogRange(): LongArray = communication.callFromApp {
        serviceLogRecorder.visibleRange().let { longArrayOf(it.first, it.second) }
    }

    override fun getSystemWifiLogRange(): LongArray = communication.callFromApp {
        systemWifiLogRecorder.visibleRange().let { longArrayOf(it.first, it.second) }
    }

    override fun getServiceLogs(
        fromIdInclusive: Long,
        toIdInclusive: Long,
    ): ParcelFileDescriptor = communication.callFromApp {
        require(fromIdInclusive >= 0L) { "日志起始 ID 必须大于等于 0" }
        require(toIdInclusive >= fromIdInclusive) { "日志结束 ID 不能小于起始 ID" }
        ServiceLogTransport.encode(
            serviceLogRecorder.getRange(fromIdInclusive, toIdInclusive),
        )
    }

    override fun clearServiceLogs() = communication.callFromApp {
        serviceLogRecorder.clear()
    }

    override fun getSystemWifiLogs(
        fromIdInclusive: Long,
        toIdInclusive: Long,
    ): ParcelFileDescriptor = communication.callFromApp {
        require(fromIdInclusive >= 0L) { "系统wifi日志起始 ID 必须大于等于 0" }
        require(toIdInclusive >= fromIdInclusive) { "系统wifi日志结束 ID 不能小于起始 ID" }
        ServiceLogTransport.encode(
            systemWifiLogRecorder.getRange(fromIdInclusive, toIdInclusive),
        )
    }

    override fun clearSystemWifiLogs() = communication.callFromApp {
        systemWifiLogRecorder.clear()
    }

    override fun registerServiceLogCallback(cb: IServiceLogCallback) {
        communication.registerServiceLogCallback(cb)
        serviceLogRecorder.visibleRange().let { range ->
            communication.pushServiceLogRangeChanged(cb, range.first, range.second)
        }
        systemWifiLogRecorder.visibleRange().let { range ->
            communication.pushSystemWifiLogRangeChanged(cb, range.first, range.second)
        }
    }

    override fun unregisterServiceLogCallback(cb: IServiceLogCallback) {
        communication.unregisterServiceLogCallback(cb)
    }

    override fun refreshSavedWifiNetworks() = communication.callFromApp {
        Log.d(TAG, "应用请求刷新已保存 Wi-Fi 列表")
        wifiListController.refreshSavedNetworks()
    }

    override fun saveWifiNetwork(ssid: String, password: String): Int =
        communication.callFromApp {
            try {
                initializer.androidApi
                    ?.saveWifiNetwork(ssid, password)
                    ?: throw IllegalStateException("AndroidApi 尚未初始化")
            } finally {
                wifiListController.refreshSavedNetworks()
            }
        }

    override fun startWifiScan(): Boolean = communication.callFromApp {
        Log.d(TAG, "应用同步请求启动 Wi-Fi 扫描")
        try {
            wifiListController.startScan()
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Wi-Fi 扫描没有开始：${error.message}", error)
            throw IllegalStateException(
                buildString {
                    append(error.message ?: error.javaClass.name)
                    append("\n\nService 扫描启动调用栈：\n")
                    append(error.stackTraceToString())
                },
                error,
            )
        }
    }

    override fun setWifiMode(
        source: Int,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) = communication.callFromApp {
        val resolvedSource = WifiMode.fromWireValue(source)
        wifiListController.setMode(
            mode = resolvedSource,
            rootfsPath = rootfsPath,
            runtimePath = runtimePath,
            terminalPath = terminalPath,
        )
    }

    override fun configureWifiEnvironment(rootfsPath: String, runtimePath: String, terminalPath: String) = communication.callFromApp {
        wifiListController.configureEnvironment(rootfsPath, runtimePath, terminalPath)
    }

    override fun setHybridScanEnabled(enabled: Boolean) = communication.callFromApp {
        wifiListController.setHybridScanEnabled(enabled)
    }

    override fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int, hopping: Boolean) = communication.callFromApp {
        wifiListController.setMonitorCapture(enabled, frequencyMhz, hopping)
    }

    override fun clearMonitorCapture(handshakesOnly: Boolean) = communication.callFromApp {
        wifiListController.clearMonitorCapture(handshakesOnly)
    }

    override fun enterMonitorMode(
        command: String,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) = communication.callFromApp {
        wifiListController.enterMonitorMode(
            command = command,
            rootfsPath = rootfsPath,
            runtimePath = runtimePath,
            terminalPath = terminalPath,
        )
    }

    override fun getMonitorChanges(sessionGeneration: Long, afterRevision: Long): ParcelFileDescriptor =
        communication.callFromApp {
            io.github.bszapp.wifitoolbox.contract.PagedDataTransport.encode(
                wifiListController.getMonitorChanges(sessionGeneration, afterRevision),
            )
        }

    override fun exportMonitorPcap(
        requestId: String,
        mode: String,
        bssid: String,
        deviceMac: String,
        subtypeIds: Array<out String>,
    ) = communication.callFromApp {
        wifiListController.exportMonitorPcap(
            requestId = requestId,
            mode = mode,
            bssid = bssid,
            deviceMac = deviceMac,
            subtypeIds = Array(subtypeIds.size) { subtypeIds[it] },
        )
    }

    override fun releaseMonitorPcapExport(path: String) = communication.callFromApp {
        wifiListController.releaseMonitorPcapExport(path)
    }

    override fun exportMonitorHandshakePcap(
        requestId: String,
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ) = communication.callFromApp {
        wifiListController.exportMonitorHandshakePcap(
            requestId = requestId,
            bssid = bssid,
            deviceMac = deviceMac,
            handshakeId = handshakeId,
        )
    }

    override fun exportMonitorDisconnectionPcap(
        requestId: String,
        bssid: String,
        deviceMac: String,
        disconnectionId: String,
    ) = communication.callFromApp {
        wifiListController.exportMonitorDisconnectionPcap(
            requestId = requestId,
            bssid = bssid,
            deviceMac = deviceMac,
            disconnectionId = disconnectionId,
        )
    }

    override fun stopHybridScanner() = communication.callFromApp {
        hybridWifiScanner.stop()
    }

    override fun getAliveTerminalIds(): LongArray = communication.callFromApp {
        terminalManager.aliveSnapshot().terminalIds
    }

    override fun getAliveTerminalGeneration(): Long = communication.callFromApp {
        terminalManager.aliveSnapshot().generation
    }

    override fun getTerminalLogRange(terminalId: Long): LongArray = communication.callFromApp {
        terminalManager.getLogRange(terminalId).let { range ->
            longArrayOf(
                range.generation,
                range.oldestAvailableId,
                range.latestId,
                range.lineCount.toLong(),
            )
        }
    }

    override fun getTerminalInputPrompt(terminalId: Long): String? = communication.callFromApp {
        terminalManager.getInputPrompt(terminalId)
    }

    override fun getTerminalLogs(
        terminalId: Long,
        fromIdInclusive: Long,
        toIdInclusive: Long,
    ): ParcelFileDescriptor = communication.callFromApp {
        require(fromIdInclusive >= 0L) { "终端日志起始 ID 必须大于等于 0" }
        require(toIdInclusive >= fromIdInclusive) { "终端日志结束 ID 不能小于起始 ID" }
        TerminalLogTransport.encode(
            terminalManager.getLogs(terminalId, fromIdInclusive, toIdInclusive),
        )
    }

    override fun createServiceTerminal(
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) = communication.callFromApp {
        terminalManager.createChrootTerminal(rootfsPath, runtimePath, terminalPath)
        Unit
    }

    override fun clearTerminalLogs(terminalId: Long) = communication.callFromApp {
        terminalManager.clearLogs(terminalId)
    }

    override fun sendTerminalInput(
        terminalId: Long,
        text: String,
    ) = communication.callFromApp {
        terminalManager.writeInput(terminalId, text)
    }

    override fun stopTerminal(terminalId: Long) = communication.callFromApp {
        terminalManager.stopTerminal(terminalId)
    }

    override fun registerTerminalManagerCallback(cb: ITerminalManagerCallback) {
        communication.registerTerminalManagerCallback(cb)
        communication.pushAliveTerminalsChanged(cb, terminalManager.aliveSnapshot())
        terminalManager.terminalSnapshots().forEach { range ->
            communication.pushTerminalLogRangeChanged(cb, range)
        }
    }

    override fun unregisterTerminalManagerCallback(cb: ITerminalManagerCallback) {
        communication.unregisterTerminalManagerCallback(cb)
    }

    override fun startTask(request: TaskStartRequest): Long = communication.callFromApp {
        taskManager.start(request)
    }

    override fun stopTask(taskId: Long): Boolean = communication.callFromApp {
        taskManager.stop(taskId)
    }

    override fun updateTask(taskId: Long, update: TaskUpdateRequest): Boolean =
        communication.callFromApp {
            taskManager.update(taskId, update)
        }

    override fun getCurrentTaskId(): Long = communication.callFromApp {
        taskManager.currentTaskId()
    }

    override fun getTaskSnapshot(taskId: Long): TaskSnapshot = communication.callFromApp {
        taskManager.snapshot(taskId)
    }

    override fun getWpsCapturedNetworks(taskId: Long, fromIndex: Int): ParcelFileDescriptor =
        communication.callFromApp {
            io.github.bszapp.wifitoolbox.contract.PagedDataTransport.encode(
                taskManager.capturedNetworkPage(taskId, fromIndex),
            )
        }

    override fun getTaskLogRange(taskId: Long): LongArray = communication.callFromApp {
        taskManager.taskLogRange(taskId).let { range ->
            longArrayOf(
                range.generation,
                range.oldestAvailableId,
                range.latestId,
                range.lineCount.toLong(),
            )
        }
    }

    override fun getTaskLogs(
        taskId: Long,
        fromIdInclusive: Long,
        toIdInclusive: Long,
    ): ParcelFileDescriptor = communication.callFromApp {
        require(fromIdInclusive >= 0L) { "任务日志起始 ID 必须大于等于 0" }
        require(toIdInclusive >= fromIdInclusive) { "任务日志结束 ID 不能小于起始 ID" }
        TaskLogTransport.encode(
            taskManager.taskLogs(taskId, fromIdInclusive, toIdInclusive),
        )
    }

    override fun getGlobalTaskLogRange(): LongArray = communication.callFromApp {
        taskManager.globalLogRange().let { range ->
            longArrayOf(
                range.generation,
                range.oldestAvailableId,
                range.latestId,
                range.lineCount.toLong(),
            )
        }
    }

    override fun getGlobalTaskLogs(
        fromIdInclusive: Long,
        toIdInclusive: Long,
    ): ParcelFileDescriptor = communication.callFromApp {
        require(fromIdInclusive >= 0L) { "全局任务日志起始 ID 必须大于等于 0" }
        require(toIdInclusive >= fromIdInclusive) { "全局任务日志结束 ID 不能小于起始 ID" }
        TaskLogTransport.encode(
            taskManager.globalLogs(fromIdInclusive, toIdInclusive),
        )
    }

    override fun clearTaskLogs() = communication.callFromApp {
        taskManager.clearLogs()
    }

    override fun registerTaskManagerCallback(cb: ITaskManagerCallback) =
        communication.callFromApp {
            taskManager.registerCallback(cb)
        }

    override fun unregisterTaskManagerCallback(cb: ITaskManagerCallback) =
        communication.callFromApp {
            taskManager.unregisterCallback(cb)
        }

    override fun getWifiStateChunk(
        cb: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
    ): ParcelFileDescriptor = communication.getWifiStateChunk(cb, generation, chunkIndex)

    override fun getSavedWifiListChunk(
        cb: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
    ): ParcelFileDescriptor = communication.getSavedWifiListChunk(cb, generation, chunkIndex)

    override fun getWifiModeStateChunk(
        cb: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
    ): ParcelFileDescriptor = communication.getWifiModeStateChunk(
        cb,
        generation,
        chunkIndex,
    )

    override fun acknowledgeWifiState(cb: IMainServiceCallback, generation: Long) {
        communication.acknowledgeWifiState(cb, generation)
    }

    override fun acknowledgeSavedWifiList(cb: IMainServiceCallback, generation: Long) {
        communication.acknowledgeSavedWifiList(cb, generation)
    }

    override fun acknowledgeWifiModeState(
        cb: IMainServiceCallback,
        generation: Long,
    ) {
        communication.acknowledgeWifiModeState(cb, generation)
    }

    override fun startHashcat(request: io.github.bszapp.wifitoolbox.contract.hashcat.HashcatLaunch, inputs: ParcelFileDescriptor) = communication.callFromApp {
        inputs.use { hashcatManager.start(request.id, it, false, request.revision, request.createdAt, request.memoryLimitMiB) }
    }
    override fun resumeHashcat(request: io.github.bszapp.wifitoolbox.contract.hashcat.HashcatLaunch, inputs: ParcelFileDescriptor) = communication.callFromApp {
        inputs.use { hashcatManager.start(request.id, it, true, request.revision, request.createdAt, request.memoryLimitMiB) }
    }
    override fun pauseHashcat(taskId: String): Boolean = communication.callFromApp { hashcatManager.pause(taskId) }
    override fun prepareHashcatShutdown(): Boolean = communication.callFromApp { hashcatManager.prepareShutdown() }
    override fun getHashcatKernelStatus(programHash: String): ParcelFileDescriptor = communication.callFromApp {
        val snapshot = hashcatManager.kernelStatus(programHash)
        io.github.bszapp.wifitoolbox.contract.hashcat.HashcatFiles.pipe { out ->
            out.writer(Charsets.UTF_8).use { it.write(snapshot.toJson().toString()) }
        }
    }
    override fun compileHashcatKernels(programHash: String, program: ParcelFileDescriptor) = communication.callFromApp {
        program.use { hashcatManager.compileKernels(programHash, it) }
    }
    override fun getHashcatTask(taskId: String): ParcelFileDescriptor = communication.callFromApp {
        val snapshot = hashcatManager.snapshot(taskId)
        io.github.bszapp.wifitoolbox.contract.hashcat.HashcatFiles.pipe { out ->
            out.writer(Charsets.UTF_8).use { it.write(snapshot.toJson().toString()) }
        }
    }
    override fun getHashcatTaskIds(offset: Int): Array<String> = communication.callFromApp { hashcatManager.ids(offset) }
    override fun getHashcatMemory(): ParcelFileDescriptor = communication.callFromApp {
        val memory = hashcatManager.memory()
        io.github.bszapp.wifitoolbox.contract.hashcat.HashcatFiles.pipe { out ->
            out.writer(Charsets.UTF_8).use { it.write(memory.toJson().toString()) }
        }
    }
    override fun registerHashcatCallback(cb: IHashcatCallback) = communication.callFromApp { hashcatManager.register(cb) }
    override fun unregisterHashcatCallback(cb: IHashcatCallback) = communication.callFromApp { hashcatManager.unregister(cb) }

    override fun shutdown() = communication.callFromApp {
        check(hashcatManager.prepareShutdown()) { "Hashcat 任务备份尚未完成，服务保持运行" }
        Log.d(TAG, "收到 shutdown，服务退出")
        wifiEventMonitor.stop()
        wifiListController.stop()
        hybridWifiScanner.close()
        taskManager.unregisterCallback(notificationTaskCallback)
        serviceNotifications.close()
        taskManager.close()
        containerSystemManager.close()
        initializer.close()
        terminalManager.close()
        wifiLogAnalyzer.close()
        serviceLogRecorder.setOnVisibleRangeChanged(null)
        systemWifiLogRecorder.setOnVisibleRangeChanged(null)
        serviceLogRecorder.stop()
        systemWifiLogRecorder.stop()
        Process.killProcess(Process.myPid())
    }

    override fun registerCallback(cb: IMainServiceCallback) {
        communication.registerCallback(cb)
        // 重连时分别发送当前两种原始数据；不存在的数据等首次初始化后再推送。
        wifiListController.getWifiState()?.let { communication.pushWifiState(cb, it) }
        wifiListController.getSavedWifiList()?.let { communication.pushSavedWifiList(cb, it) }
        communication.pushWifiModeState(
            cb,
            wifiListController.getModeState(),
        )
    }

    override fun unregisterCallback(cb: IMainServiceCallback) {
        communication.unregisterCallback(cb)
    }

    companion object {
        private const val TAG = "ToolboxMainService"
    }
}
