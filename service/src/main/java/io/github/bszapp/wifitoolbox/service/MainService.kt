package io.github.bszapp.wifitoolbox.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogTransport
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogTransport
import io.github.bszapp.wifitoolbox.contract.task.TaskLogTransport
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSource
import io.github.bszapp.wifitoolbox.service.task.TaskManager
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

    private val containerTerminalController = ContainerTerminalController(
        terminalManager = terminalManager,
        publishEvent = communication::broadcastContainerTerminalEvent,
    )

    private val wifiListController = WifiListController(
        androidApiProvider = { initializer.androidApi },
        containerTerminalController = containerTerminalController,
        terminalManager = terminalManager,
        onWifiStateChanged = communication::broadcastWifiState,
        onSavedWifiListChanged = communication::broadcastSavedWifiList,
        onInformationSourceStateChanged = communication::broadcastWifiInformationSourceState,
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

    private val taskManager = TaskManager(
        androidApiProvider = { initializer.androidApi },
        wifiLogAnalyzer = wifiLogAnalyzer,
        terminalManager = terminalManager,
        hybridTaskEnvironmentProvider = wifiListController::getHybridTaskEnvironment,
        onSavedWifiNetworksChanged = wifiListController::refreshSavedNetworks,
        onError = { operation, error ->
            communication.broadcastServiceError(
                source = "Service.WpsPbcTask",
                operation = operation,
                error = error,
            )
        },
    )

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

    override fun getLatestServiceLogId(): Long = communication.callFromApp {
        serviceLogRecorder.latestId()
    }

    override fun getServiceLogs(
        fromIdInclusive: Long,
        toIdInclusive: Long,
    ): ParcelFileDescriptor = communication.callFromApp {
        require(fromIdInclusive >= 1L) { "日志起始 ID 必须大于等于 1" }
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
        require(fromIdInclusive >= 1L) { "系统wifi日志起始 ID 必须大于等于 1" }
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

    override fun setWifiInformationSource(
        source: Int,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) = communication.callFromApp {
        val resolvedSource = WifiInformationSource.fromWireValue(source)
        wifiListController.setInformationSource(
            source = resolvedSource,
            rootfsPath = rootfsPath,
            runtimePath = runtimePath,
            terminalPath = terminalPath,
        )
    }

    override fun enterMonitorMode(
        command: String,
        targetChannel: Int,
        targetFrequencyMhz: Int,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) = communication.callFromApp {
        wifiListController.enterMonitorMode(
            command = command,
            targetChannel = targetChannel,
            targetFrequencyMhz = targetFrequencyMhz,
            rootfsPath = rootfsPath,
            runtimePath = runtimePath,
            terminalPath = terminalPath,
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

    override fun startContainerTerminal(
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) = communication.callFromApp {
        containerTerminalController.start(rootfsPath, runtimePath, terminalPath)
        Unit
    }

    override fun stopContainerTerminal() = communication.callFromApp {
        containerTerminalController.stop()
    }

    override fun runContainerWifiScan() = communication.callFromApp {
        containerTerminalController.runWifiScan()
        Unit
    }

    override fun registerContainerTerminalCallback(cb: IContainerTerminalCallback) {
        communication.registerContainerTerminalCallback(cb)
        communication.pushContainerTerminalEvent(cb, containerTerminalController.snapshotJson())
    }

    override fun unregisterContainerTerminalCallback(cb: IContainerTerminalCallback) {
        communication.unregisterContainerTerminalCallback(cb)
    }

    override fun getAliveTerminalIds(): LongArray = communication.callFromApp {
        terminalManager.aliveSnapshot().terminalIds
    }

    override fun getAliveTerminalGeneration(): Long = communication.callFromApp {
        terminalManager.aliveSnapshot().generation
    }

    override fun getTerminalLogCount(terminalId: Long): Int = communication.callFromApp {
        terminalManager.getLogCount(terminalId)
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
        require(fromIdInclusive >= 1L) { "终端日志起始 ID 必须大于等于 1" }
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
        require(fromIdInclusive >= 1L) { "任务日志起始 ID 必须大于等于 1" }
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
        require(fromIdInclusive >= 1L) { "全局任务日志起始 ID 必须大于等于 1" }
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

    override fun getWifiInformationSourceStateChunk(
        cb: IMainServiceCallback,
        generation: Long,
        chunkIndex: Int,
    ): ParcelFileDescriptor = communication.getWifiInformationSourceStateChunk(
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

    override fun acknowledgeWifiInformationSourceState(
        cb: IMainServiceCallback,
        generation: Long,
    ) {
        communication.acknowledgeWifiInformationSourceState(cb, generation)
    }

    override fun shutdown() = communication.callFromApp {
        Log.d(TAG, "收到 shutdown，服务退出")
        wifiEventMonitor.stop()
        wifiListController.stop()
        containerTerminalController.close()
        taskManager.close()
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
        communication.pushWifiInformationSourceState(
            cb,
            wifiListController.getInformationSourceState(),
        )
    }

    override fun unregisterCallback(cb: IMainServiceCallback) {
        communication.unregisterCallback(cb)
    }

    companion object {
        private const val TAG = "ToolboxMainService"
    }
}
