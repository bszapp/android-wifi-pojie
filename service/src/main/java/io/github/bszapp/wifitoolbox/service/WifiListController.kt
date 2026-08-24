@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.service

import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSourceState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service 进程中的 Wi-Fi 唯一数据源和扫描任务拥有者。
 *
 * [WifiState] 与 [SavedWifiList] 是两种同级、独立的数据。Service 分别维护并原样发送；
 * App 不读取系统 Wi-Fi API，也不重新组装数据结构。
 */
internal class WifiListController(
    private val androidApiProvider: () -> AndroidApi?,
    private val containerTerminalController: ContainerTerminalController,
    private val terminalManager: TerminalManager,
    private val onWifiStateChanged: (WifiState) -> Unit,
    private val onSavedWifiListChanged: (SavedWifiList) -> Unit,
    private val onInformationSourceStateChanged: (WifiInformationSourceState) -> Unit,
    private val onMonitorRecordedBytesChanged: (Long) -> Unit,
    private val onMonitorPcapExported: (requestId: String, path: String, fileName: String) -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "toolbox-service-wifi-state").apply { isDaemon = true }
    }

    /** 扫描请求、确认回调和扫描会话由本控制器直接拥有，不经过 AndroidApi。 */
    private val wifiScannerClient by lazy {
        WifiScannerClient(requireAndroidApi().callerPackage)
    }

    @Volatile
    private var wifiState: WifiState? = null

    @Volatile
    private var savedWifiList: SavedWifiList? = null

    private var initialized = false

    @Volatile
    private var stopped = false

    private var scanGeneration = 0L
    private var informationSourceGeneration = 0L
    private var pendingScanRequest: PendingScanRequest? = null
    private var scanSession: ScanSession? = null
    private var primaryNetworkStatus: Int? = null
    private var informationSourceTransitionTerminalId: Long? = null
    private var systemWifiEnabledPollingFuture: ScheduledFuture<*>? = null
    private var lastSystemWifiEnabled: Boolean? = null

    @Volatile
    private var hybridTaskEnvironment: HybridTaskEnvironment? = null

    private val monitorModeController = MonitorModeController(
        terminalManager = terminalManager,
        onStatisticsChanged = { statistics ->
            execute { publishMonitorStatistics(statistics) }
        },
        onRecordedBytesChanged = { recordedBytes ->
            execute { publishMonitorRecordedBytes(recordedBytes) }
        },
        onExportCompleted = { requestId, path, fileName ->
            execute { onMonitorPcapExported(requestId, path, fileName) }
        },
        onError = { operation, error ->
            execute {
                if (informationSourceState.source == WifiInformationSource.MONITOR) {
                    reportError(operation, error)
                }
            }
        },
    )

    @Volatile
    private var informationSourceState = WifiInformationSourceState(
        source = WifiInformationSource.SYSTEM,
        initializing = false,
    )

    fun getWifiState(): WifiState? = wifiState

    fun getSavedWifiList(): SavedWifiList? = savedWifiList

    fun getInformationSourceState(): WifiInformationSourceState = informationSourceState

    fun getHybridTaskEnvironment(): HybridTaskEnvironment? {
        val source = informationSourceState
        return hybridTaskEnvironment?.takeIf {
            source.source == WifiInformationSource.HYBRID && !source.initializing
        }
    }

    fun initialize() {
        execute {
            if (initialized) return@execute
            initialized = true
            Log.d(TAG, "初始化 Wi-Fi 数据")
            refreshWifiDataInternal()
            startSystemWifiEnabledPolling()
        }
    }

    fun refreshSavedNetworks() {
        execute {
            Log.d(TAG, "刷新已保存 Wi-Fi 列表")
            refreshSavedNetworksInternal()
        }
    }

    /**
     * 同步提交扫描请求。
     *
     * 当前调用线程会等待 WifiScanner.ScanListener.onSuccess/onFailure。只有 onSuccess 已经
     * 建立正式扫描会话、发布 isScanning=true 并启动 250ms 更新任务后，本方法才正常返回。
     * 后续结果等待和数据更新仍由 Service 的 executor 异步执行。
     */
    @Throws(Exception::class)
    fun startScan() {
        if (stopped) throw IllegalStateException("Wi-Fi 服务已停止")

        val sourceState = informationSourceState
        check(!sourceState.initializing) { "Wi-Fi 信息源正在初始化" }
        check(sourceState.source != WifiInformationSource.MONITOR) {
            "监听模式不支持 Wi-Fi 扫描"
        }
        if (sourceState.source == WifiInformationSource.HYBRID) {
            startHybridScanSynchronously()
            return
        }

        val confirmation = CompletableFuture<Unit>()
        try {
            executor.execute {
                if (stopped) {
                    confirmation.completeExceptionally(
                        IllegalStateException("Wi-Fi 服务已停止"),
                    )
                } else {
                    submitScanRequest(confirmation)
                }
            }
        } catch (error: RejectedExecutionException) {
            throw IllegalStateException("Wi-Fi 扫描执行器不可用", error)
        }

        try {
            confirmation.get(SCAN_START_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            val failure = IllegalStateException(
                "等待 WifiScanner 确认扫描请求超时",
                error,
            )
            confirmation.completeExceptionally(failure)
            execute { cancelTimedOutPendingRequest(confirmation) }
            throw failure
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            val failure = IllegalStateException("等待扫描请求确认时线程被中断", error)
            confirmation.completeExceptionally(failure)
            execute { cancelTimedOutPendingRequest(confirmation) }
            throw failure
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            throw when (cause) {
                is Exception -> cause
                else -> RuntimeException(cause)
            }
        }
    }

    /** Service 检测到 Wi-Fi 开关可能变化时自行刷新数据，不接受 App 的状态刷新命令。 */
    fun onWifiStateMayHaveChanged() {
        execute {
            if (informationSourceState.source != WifiInformationSource.SYSTEM) {
                Log.d(TAG, "非系统模式收到 Wi-Fi 状态事件，仅刷新已保存网络")
                refreshSavedNetworksInternal()
            } else {
                Log.d(TAG, "检测到 Wi-Fi 状态可能变化，刷新 Service 数据")
                refreshWifiDataInternal()
            }
        }
    }

    fun onWifiNetworkStateChanged(clientRole: Int, status: Int) {
        execute {
            if (clientRole != WIFI_ROLE_CLIENT_PRIMARY) return@execute
            if (informationSourceState.source == WifiInformationSource.MONITOR) return@execute
            primaryNetworkStatus = status
            val current = wifiState as? WifiState.Data.Enabled ?: return@execute
            val connection = readCurrentConnection(
                api = requireAndroidApi(),
                previous = current.connection,
                operation = "响应 Wi-Fi 网络状态变化",
            )
            publishWifiState(current.copy(connection = connection))
        }
    }

    fun setInformationSource(
        source: WifiInformationSource,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) {
        require(source != WifiInformationSource.MONITOR) {
            "监听模式只能从 Wi-Fi 项目的更多菜单进入"
        }
        execute {
            val current = informationSourceState
            if (current.source == source) return@execute

            val generation = ++informationSourceGeneration
            hybridTaskEnvironment = null
            stopSystemWifiEnabledPolling()
            cancelScanInternal(publishChange = false)
            stopInformationSourceTransition()
            publishInformationSourceState(
                WifiInformationSourceState(source = source, initializing = true),
            )
            if (current.source == WifiInformationSource.MONITOR) {
                monitorModeController.stop()
                runMonitorExitScript(
                    generation = generation,
                    targetSource = source,
                    rootfsPath = rootfsPath,
                    runtimePath = runtimePath,
                    terminalPath = terminalPath,
                )
            } else {
                continueInformationSourceSwitch(
                    generation = generation,
                    source = source,
                    rootfsPath = rootfsPath,
                    runtimePath = runtimePath,
                    terminalPath = terminalPath,
                )
            }
        }
    }

    fun enterMonitorMode(
        command: String,
        targetChannel: Int,
        targetFrequencyMhz: Int,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) {
        require(targetChannel > 0) { "监听模式目标信道必须大于 0" }
        require(targetFrequencyMhz > 0) { "监听模式目标频率必须大于 0" }
        execute {
            val generation = ++informationSourceGeneration
            hybridTaskEnvironment = null
            stopSystemWifiEnabledPolling()
            cancelScanInternal(publishChange = false)
            stopInformationSourceTransition()
            monitorModeController.stop()
            containerTerminalController.stop()
            publishInformationSourceState(
                WifiInformationSourceState(
                    source = WifiInformationSource.MONITOR,
                    initializing = true,
                ),
            )

            val resolvedCommand = command.replace(CHANNEL_PLACEHOLDER, targetChannel.toString())
            try {
                runHostTerminalScript(resolvedCommand) { exitCode ->
                    if (!isCurrentInformationSource(generation, WifiInformationSource.MONITOR)) {
                        return@runHostTerminalScript
                    }
                    try {
                        verifyWlan0MonitorMode()
                        monitorModeController.start(
                            rootfsPath = rootfsPath,
                            runtimePath = runtimePath,
                            terminalPath = terminalPath,
                            targetChannel = targetChannel,
                            targetFrequencyMhz = targetFrequencyMhz,
                        )
                        publishInformationSourceState(
                            WifiInformationSourceState(
                                source = WifiInformationSource.MONITOR,
                                initializing = false,
                            ),
                        )
                        Log.i(
                            TAG,
                            "监听模式已启动，信道=$targetChannel，" +
                                "频率=${targetFrequencyMhz}MHz，进入脚本退出码=$exitCode",
                        )
                    } catch (error: Throwable) {
                        monitorModeController.stop()
                        finishInformationSourceFailure(
                            generation = generation,
                            source = WifiInformationSource.MONITOR,
                            operation = "进入监听模式",
                            error = error,
                        )
                    }
                }
            } catch (error: Throwable) {
                finishInformationSourceFailure(
                    generation = generation,
                    source = WifiInformationSource.MONITOR,
                    operation = "启动监听模式进入终端",
                    error = error,
                )
            }
        }
    }

    fun exportMonitorPcap(
        requestId: String,
        mode: String,
        bssid: String,
        deviceMac: String,
        subtypeIds: Array<String>,
    ) {
        require(requestId.isNotBlank()) { "监听模式导出请求 ID 不能为空" }
        require(mode == "all" || mode == "filtered") { "未知监听模式导出类型: $mode" }
        if (mode == "filtered") {
            require(bssid.isNotBlank() && deviceMac.isNotBlank()) {
                "局部导出必须指定接入点和设备 MAC"
            }
            require(subtypeIds.isNotEmpty()) { "局部导出至少选择一种帧子类型" }
        }
        execute {
            try {
                check(
                    informationSourceState.source == WifiInformationSource.MONITOR &&
                        !informationSourceState.initializing,
                ) { "监听模式尚未运行" }
                monitorModeController.exportPcap(
                    requestId = requestId,
                    mode = mode,
                    bssid = bssid,
                    deviceMac = deviceMac,
                    subtypeIds = subtypeIds,
                )
            } catch (error: Throwable) {
                reportError("导出监听模式 PCAP", error)
            }
        }
    }

    fun releaseMonitorPcapExport(path: String) {
        execute { monitorModeController.releaseExport(path) }
    }

    fun exportMonitorHandshakePcap(
        requestId: String,
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ) {
        require(requestId.isNotBlank()) { "握手包导出请求 ID 不能为空" }
        require(bssid.isNotBlank() && deviceMac.isNotBlank()) {
            "握手包导出必须指定接入点和设备 MAC"
        }
        require(handshakeId.isNotBlank()) { "握手记录 ID 不能为空" }
        execute {
            try {
                check(
                    informationSourceState.source == WifiInformationSource.MONITOR &&
                        !informationSourceState.initializing,
                ) { "监听模式尚未运行" }
                monitorModeController.exportHandshakePcap(
                    requestId = requestId,
                    bssid = bssid,
                    deviceMac = deviceMac,
                    handshakeId = handshakeId,
                )
            } catch (error: Throwable) {
                reportError("导出握手包 PCAP", error)
            }
        }
    }

    fun exportMonitorDisconnectionPcap(
        requestId: String,
        bssid: String,
        deviceMac: String,
        disconnectionId: String,
    ) {
        require(requestId.isNotBlank()) { "断开事件导出请求 ID 不能为空" }
        require(bssid.isNotBlank() && deviceMac.isNotBlank()) {
            "断开事件导出必须指定接入点和设备 MAC"
        }
        require(disconnectionId.isNotBlank()) { "断开事件记录 ID 不能为空" }
        execute {
            try {
                check(
                    informationSourceState.source == WifiInformationSource.MONITOR &&
                        !informationSourceState.initializing,
                ) { "监听模式尚未运行" }
                monitorModeController.exportDisconnectionPcap(
                    requestId = requestId,
                    bssid = bssid,
                    deviceMac = deviceMac,
                    disconnectionId = disconnectionId,
                )
            } catch (error: Throwable) {
                reportError("导出断开事件 PCAP", error)
            }
        }
    }

    private fun runMonitorExitScript(
        generation: Long,
        targetSource: WifiInformationSource,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) {
        try {
            runHostTerminalScript(MONITOR_EXIT_COMMAND) { exitCode ->
                if (!isCurrentInformationSource(generation, targetSource)) {
                    return@runHostTerminalScript
                }
                if (exitCode != 0) {
                    finishInformationSourceFailure(
                        generation = generation,
                        source = targetSource,
                        operation = "退出监听模式",
                        error = IllegalStateException("监听模式退出脚本执行失败，退出码 $exitCode"),
                    )
                    return@runHostTerminalScript
                }
                continueInformationSourceSwitch(
                    generation = generation,
                    source = targetSource,
                    rootfsPath = rootfsPath,
                    runtimePath = runtimePath,
                    terminalPath = terminalPath,
                )
            }
        } catch (error: Throwable) {
            finishInformationSourceFailure(
                generation = generation,
                source = targetSource,
                operation = "启动监听模式退出终端",
                error = error,
            )
        }
    }

    private fun continueInformationSourceSwitch(
        generation: Long,
        source: WifiInformationSource,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) {
        when (source) {
            WifiInformationSource.SYSTEM -> switchToSystemSource(generation)
            WifiInformationSource.HYBRID -> switchToHybridSource(
                generation = generation,
                rootfsPath = rootfsPath,
                runtimePath = runtimePath,
                terminalPath = terminalPath,
            )
            WifiInformationSource.MONITOR -> error("监听模式使用专用入口")
        }
    }

    private fun runHostTerminalScript(
        command: String,
        onExit: (exitCode: Int) -> Unit,
    ) {
        val terminalId = terminalManager.createTerminal(
            command = listOf("/system/bin/sh"),
            onExit = { exitedTerminalId, exitCode ->
                execute {
                    if (informationSourceTransitionTerminalId == exitedTerminalId) {
                        informationSourceTransitionTerminalId = null
                    }
                    onExit(exitCode)
                }
            },
        )
        informationSourceTransitionTerminalId = terminalId
        terminalManager.writeInput(terminalId, command.trimEnd() + "\nexit")
    }

    private fun stopInformationSourceTransition() {
        val terminalId = informationSourceTransitionTerminalId ?: return
        informationSourceTransitionTerminalId = null
        terminalManager.stopTerminal(terminalId, reason = "切换 Wi-Fi 信息源")
    }

    private fun verifyWlan0MonitorMode() {
        val verified = runCatching {
            val process = ProcessBuilder("iw", "dev")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor() == 0 && outputContainsWlan0MonitorMode(output)
        }.getOrDefault(false)
        if (!verified) {
            throw IllegalStateException(MONITOR_MODE_VERIFICATION_ERROR)
        }
    }

    private fun outputContainsWlan0MonitorMode(output: String): Boolean {
        var currentInterface: String? = null
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Interface ")) {
                currentInterface = trimmed.removePrefix("Interface ").trim()
            } else if (currentInterface == "wlan0" && trimmed == "type monitor") {
                return true
            }
        }
        return false
    }

    private fun publishMonitorStatistics(statistics: MonitorModeStatistics) {
        val current = informationSourceState
        if (current.source != WifiInformationSource.MONITOR || current.initializing) return
        val currentRecordedBytes = current.monitorStatistics?.recordedBytes ?: 0L
        publishInformationSourceState(
            current.copy(
                monitorStatistics = statistics.copy(
                    recordedBytes = maxOf(currentRecordedBytes, statistics.recordedBytes),
                ),
            ),
        )
    }

    private fun publishMonitorRecordedBytes(recordedBytes: Long) {
        val current = informationSourceState
        val statistics = current.monitorStatistics
        if (
            current.source != WifiInformationSource.MONITOR ||
            current.initializing ||
            statistics == null ||
            statistics.recordedBytes == recordedBytes
        ) {
            return
        }
        informationSourceState = current.copy(
            monitorStatistics = statistics.copy(recordedBytes = recordedBytes),
        )
        if (!stopped) onMonitorRecordedBytesChanged(recordedBytes)
    }

    private fun switchToSystemSource(generation: Long) {
        try {
            containerTerminalController.stop()
            if (!isCurrentInformationSource(generation, WifiInformationSource.SYSTEM)) return
            refreshWifiDataInternal()
            publishInformationSourceState(
                WifiInformationSourceState(
                    source = WifiInformationSource.SYSTEM,
                    initializing = false,
                ),
            )
            startSystemWifiEnabledPolling()
            Log.i(TAG, "Wi-Fi 信息源已切换为系统模式")
        } catch (error: Throwable) {
            finishInformationSourceFailure(
                generation = generation,
                source = WifiInformationSource.SYSTEM,
                operation = "切换到系统模式",
                error = error,
            )
        }
    }

    private fun switchToHybridSource(
        generation: Long,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) {
        val startup = try {
            containerTerminalController.start(rootfsPath, runtimePath, terminalPath)
        } catch (error: Throwable) {
            finishInformationSourceFailure(
                generation = generation,
                source = WifiInformationSource.HYBRID,
                operation = "初始化混合模式终端",
                error = error,
            )
            return
        }
        startup.whenComplete { _, failure ->
            execute {
                if (!isCurrentInformationSource(generation, WifiInformationSource.HYBRID)) {
                    return@execute
                }
                if (failure != null) {
                    finishInformationSourceFailure(
                        generation = generation,
                        source = WifiInformationSource.HYBRID,
                        operation = "初始化混合模式终端",
                        error = unwrapCompletionFailure(failure),
                    )
                    return@execute
                }

                hybridTaskEnvironment = HybridTaskEnvironment(
                    rootfsPath = rootfsPath,
                    runtimePath = runtimePath,
                    terminalPath = terminalPath,
                )

                publishInformationSourceState(
                    WifiInformationSourceState(
                        source = WifiInformationSource.HYBRID,
                        initializing = false,
                    ),
                )
                Log.i(TAG, "Wi-Fi 信息源已切换为混合模式，启动首次扫描")
                //TODO:这里需要给scan.py添加仅读取功能，我不希望首次扫描。
                val confirmation = try {
                    beginHybridScan()
                } catch (error: Throwable) {
                    reportError("执行混合模式首次扫描", error)
                    return@execute
                }
                confirmation.whenComplete { _, scanFailure ->
                    if (scanFailure != null) {
                        execute {
                            if (isCurrentInformationSource(
                                    generation,
                                    WifiInformationSource.HYBRID,
                                )
                            ) {
                                reportError(
                                    "执行混合模式首次扫描",
                                    unwrapCompletionFailure(scanFailure),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishInformationSourceFailure(
        generation: Long,
        source: WifiInformationSource,
        operation: String,
        error: Throwable,
    ) {
        if (!isCurrentInformationSource(generation, source)) return
        if (source == WifiInformationSource.HYBRID) hybridTaskEnvironment = null
        publishInformationSourceState(
            WifiInformationSourceState(source = source, initializing = false),
        )
        if (source == WifiInformationSource.SYSTEM) startSystemWifiEnabledPolling()
        publishWifiStateError(error)
        refreshSavedNetworksInternal()
        reportError(operation, error)
    }

    private fun isCurrentInformationSource(
        generation: Long,
        source: WifiInformationSource,
    ): Boolean =
        generation == informationSourceGeneration &&
            informationSourceState.source == source

    private fun publishInformationSourceState(next: WifiInformationSourceState) {
        informationSourceState = next
        if (!stopped) onInformationSourceStateChanged(next)
    }

    private fun unwrapCompletionFailure(error: Throwable): Throwable =
        if (error is java.util.concurrent.CompletionException && error.cause != null) {
            error.cause!!
        } else {
            error
        }

    fun stop() {
        executor.execute {
            if (stopped) return@execute
            stopped = true
            hybridTaskEnvironment = null
            Log.d(TAG, "停止 Wi-Fi 控制器")
            cancelScanInternal(publishChange = false)
            stopInformationSourceTransition()
            monitorModeController.close()
            stopSystemWifiEnabledPolling()
            executor.shutdown()
        }
    }

    /**
     * 刷新完整 Wi-Fi 数据。
     *
     * 读取扫描结果时必须在同一任务中读取并发布 [SavedWifiList]。需要仅读取已保存网络时，
     * 调用 [refreshSavedNetworksInternal]。
     */
    private fun refreshWifiDataInternal() {
        try {
            val api = requireAndroidApi()
            if (!api.isWifiEnabled()) {
                cancelScanInternal(publishChange = false)
                publishWifiState(WifiState.Data.Disabled)
                Log.d(TAG, "已更新 WifiState：Disabled")
            } else {
                publishEnabledWifiDataAndSavedList(
                    api = api,
                    isScanning = scanSession != null,
                )
                return
            }
        } catch (error: Throwable) {
            reportError("刷新 Wi-Fi 数据", error)
            publishWifiStateError(error)
        }

        // Disabled/Error 路径仍属于完整刷新，同步维护独立的 SavedWifiList。
        refreshSavedNetworksInternal()
    }

    private fun startSystemWifiEnabledPolling() {
        stopSystemWifiEnabledPolling()
        if (informationSourceState.source != WifiInformationSource.SYSTEM || stopped) return
        lastSystemWifiEnabled = when (wifiState) {
            is WifiState.Data.Enabled -> true
            is WifiState.Data.Disabled -> false
            else -> null
        }
        systemWifiEnabledPollingFuture = executor.scheduleAtFixedRate(
            ::refreshSystemWifiEnabledState,
            SYSTEM_WIFI_ENABLED_REFRESH_INTERVAL_MS,
            SYSTEM_WIFI_ENABLED_REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopSystemWifiEnabledPolling() {
        systemWifiEnabledPollingFuture?.cancel(false)
        systemWifiEnabledPollingFuture = null
        lastSystemWifiEnabled = null
    }

    private fun refreshSystemWifiEnabledState() {
        if (
            stopped ||
            informationSourceState.source != WifiInformationSource.SYSTEM ||
            informationSourceState.initializing
        ) return
        try {
            val enabled = requireAndroidApi().isWifiEnabled()
            val previousEnabled = lastSystemWifiEnabled
            lastSystemWifiEnabled = enabled
            if (previousEnabled == null || previousEnabled == enabled) return
            if (enabled) {
                publishWifiState(
                    WifiState.Data.Enabled(
                        scanResults = emptyList(),
                        isScanning = false,
                        connection = null,
                    ),
                )
            } else {
                cancelScanInternal(publishChange = false)
                publishWifiState(WifiState.Data.Disabled)
            }
        } catch (error: Throwable) {
            reportError("刷新系统模式 Wi-Fi 开关状态", error)
        }
    }

    /**
     * 唯一允许读取扫描结果的入口。
     * 每次读取并发布扫描结果后，必须在同一串行任务中读取并发布 SavedWifiList。
     */
    private fun publishEnabledWifiDataAndSavedList(
        api: AndroidApi,
        isScanning: Boolean,
    ) {
        val scanResults = readScanResults(api)
        val previousConnection = (wifiState as? WifiState.Data.Enabled)?.connection
        val connection = readCurrentConnection(
            api = api,
            previous = previousConnection,
            operation = "刷新当前 Wi-Fi 连接信息",
        )
        publishWifiState(
            WifiState.Data.Enabled(
                scanResults = scanResults,
                isScanning = isScanning,
                connection = connection,
            ),
        )
        Log.d(
            TAG,
            "已更新 WifiState：Enabled scan=${scanResults.size} scanning=$isScanning " +
                "connected=${connection != null}",
        )
        refreshSavedNetworksInternal()
    }

    private fun refreshSavedNetworksInternal() {
        try {
            val value = SavedWifiList(
                networks = requireAndroidApi().getSavedWifiList(),
            )
            publishSavedWifiList(value)
            Log.d(TAG, "已更新 SavedWifiList：count=${value.networks.size}")
        } catch (error: Throwable) {
            // SavedWifiList 与 WifiState 无关；读取失败时保留最后一份列表，不篡改 WifiState。
            reportError("刷新已保存 Wi-Fi 列表", error)
        }
    }

    private fun startHybridScanSynchronously() {
        val confirmation = CompletableFuture<Unit>()
        try {
            executor.execute {
                try {
                    check(
                        informationSourceState.source == WifiInformationSource.HYBRID &&
                            !informationSourceState.initializing,
                    ) { "混合模式扫描终端尚未就绪" }
                    beginHybridScan().whenComplete { _, failure ->
                        if (failure == null) {
                            confirmation.complete(Unit)
                        } else {
                            confirmation.completeExceptionally(
                                unwrapCompletionFailure(failure),
                            )
                        }
                    }
                } catch (error: Throwable) {
                    confirmation.completeExceptionally(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            throw IllegalStateException("Wi-Fi 扫描执行器不可用", error)
        }

        try {
            confirmation.get(SCAN_START_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            throw IllegalStateException("等待混合模式扫描请求确认超时", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("等待混合模式扫描请求确认时线程被中断", error)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            throw when (cause) {
                is Exception -> cause
                else -> RuntimeException(cause)
            }
        }
    }

    private fun beginHybridScan(): CompletableFuture<Unit> {
        val generation = informationSourceGeneration
        return containerTerminalController.runWifiScan(
            onMessage = { payload ->
                execute {
                    if (isCurrentInformationSource(generation, WifiInformationSource.HYBRID)) {
                        handleHybridScanMessage(payload)
                    }
                }
            },
            onFinished = { exitCode ->
                execute { finishHybridScan(generation, exitCode) }
            },
        )
    }

    private fun handleHybridScanMessage(payload: JSONObject) {
        if (payload.optString("action") != "update_wifi_state") return
        try {
            val state = payload.getJSONObject("state")
            when (state.getString("type")) {
                "enabled" -> {
                    val api = requireAndroidApi()
                    val previousConnection =
                        (wifiState as? WifiState.Data.Enabled)?.connection
                    publishWifiState(
                        WifiState.Data.Enabled(
                            scanResults = parseHybridScanResults(state.getJSONArray("wifilist")),
                            isScanning = state.optBoolean("scanning", false),
                            connection = readCurrentConnection(
                                api = api,
                                previous = previousConnection,
                                operation = "刷新混合模式当前 Wi-Fi 连接信息",
                            ),
                        ),
                    )
                }
                "error" -> publishWifiStateError(
                    IllegalStateException(
                        state.optString("message", "混合模式扫描发生未知错误"),
                    ),
                )
                else -> throw IllegalArgumentException(
                    "未知混合模式 Wi-Fi 状态: ${state.optString("type")}",
                )
            }
            refreshSavedNetworksInternal()
        } catch (error: Throwable) {
            publishWifiStateError(error)
            refreshSavedNetworksInternal()
            reportError("解析混合模式 Wi-Fi 状态", error)
        }
    }

    private fun finishHybridScan(generation: Long, exitCode: Int) {
        if (!isCurrentInformationSource(generation, WifiInformationSource.HYBRID)) return
        val current = wifiState as? WifiState.Data.Enabled
        if (current?.isScanning == true) {
            publishWifiState(current.copy(isScanning = false))
        }
        Log.d(TAG, "混合模式扫描进程结束：exitCode=$exitCode")
    }

    private fun parseHybridScanResults(values: JSONArray): List<ScanResult> =
        buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val bssid = value.getString("BSSID")
                require(bssid.isNotBlank()) { "第 $index 项混合扫描结果缺少 BSSID" }
                add(
                    createScanResultCompat().apply {//TODO:报错啦：Call requires API level 30 (current min is 24): android.net.wifi.ScanResult()
                        SSID = value.optString("SSID", "")
                        BSSID = bssid
                        capabilities = value.optString("capabilities", "")
                        level = value.optInt("level", -100)
                        frequency = value.optInt("frequency", 0)
                        timestamp = value.optLong("timestamp", 0L)
                        channelWidth = value.optInt("channelWidth", ScanResult.CHANNEL_WIDTH_20MHZ)
                        centerFreq0 = value.optInt("centerFreq0", frequency)
                        centerFreq1 = value.optInt("centerFreq1", 0)
                    },
                )
            }
        }

    private fun createScanResultCompat(): ScanResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return ScanResult()
        }

        return runCatching {
            ScanResult::class.java.getDeclaredConstructor().newInstance()
        }.getOrElse { error ->
            throw IllegalStateException("当前系统无法创建兼容的 ScanResult", error)
        }
    }

    private fun submitScanRequest(confirmation: CompletableFuture<Unit>) {
        if (pendingScanRequest != null || scanSession != null) {
            confirmation.completeExceptionally(
                IllegalStateException("已有 Wi-Fi 扫描请求或扫描任务正在运行"),
            )
            return
        }

        if (wifiState !is WifiState.Data.Enabled) {
            confirmation.completeExceptionally(
                IllegalStateException("Wi-Fi 当前未处于 Enabled 状态"),
            )
            return
        }

        try {
            requireAndroidApi()
        } catch (error: Throwable) {
            confirmation.completeExceptionally(error)
            return
        }

        val generation = ++scanGeneration
        val pending = PendingScanRequest(
            generation = generation,
            confirmation = confirmation,
        )
        pendingScanRequest = pending

        val listener = object : WifiScannerClient.ScanListener {
            override fun onSuccess() {
                execute { handleScanRequestAccepted(generation) }
            }

            override fun onFailure(reason: Int, description: String) {
                execute {
                    handleScanRequestRejected(
                        generation = generation,
                        reason = reason,
                        description = description,
                    )
                }
            }

            override fun onResults() {
                execute { handleScanResults(generation) }
            }
        }

        try {
            Log.d(TAG, "向 WifiScanner 提交扫描请求：generation=$generation")
            pending.request = wifiScannerClient.startScan(
                callbackExecutor = executor,
                callback = listener,
            )
        } catch (error: Throwable) {
            if (pendingScanRequest === pending) pendingScanRequest = null
            confirmation.completeExceptionally(error)
            Log.e(TAG, "提交 WifiScanner 扫描请求失败：${error.message}", error)
        }
    }

    private fun handleScanRequestAccepted(generation: Long) {
        val pending = pendingScanRequest ?: return
        if (pending.generation != generation) return

        val request = pending.request ?: run {
            val error = IllegalStateException("WifiScanner 已确认请求，但请求句柄尚未建立")
            pendingScanRequest = null
            pending.confirmation.completeExceptionally(error)
            return
        }

        // Binder 调用已经超时或被中断时，不再建立后台扫描任务。
        if (pending.confirmation.isDone) {
            pendingScanRequest = null
            stopScannerRequestQuietly(request)
            return
        }

        val enabledState = wifiState as? WifiState.Data.Enabled
        if (enabledState == null) {
            pendingScanRequest = null
            stopScannerRequestQuietly(request)
            pending.confirmation.completeExceptionally(
                IllegalStateException("WifiScanner 接受请求时 Wi-Fi 已不再是 Enabled"),
            )
            return
        }

        val session = ScanSession(
            generation = generation,
            startedAt = SystemClock.elapsedRealtime(),
            request = request,
            resultsReceived = pending.resultsReceived,
        )
        pendingScanRequest = null
        scanSession = session

        publishWifiState(enabledState.copy(isScanning = true))
        session.pollingFuture = executor.scheduleAtFixedRate(
            { runScanTick(generation) },
            0L,
            SCAN_REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )

        Log.d(TAG, "WifiScanner 已接受扫描请求：generation=$generation")
        pending.confirmation.complete(Unit)
        finishScanIfReady(session)
    }

    private fun handleScanRequestRejected(
        generation: Long,
        reason: Int,
        description: String,
    ) {
        val message = buildString {
            append("WifiScanner 拒绝扫描请求")
            append("（reason=")
            append(reason)
            append('）')
            if (description.isNotBlank()) {
                append(": ")
                append(description)
            }
        }

        val pending = pendingScanRequest
        if (pending != null && pending.generation == generation) {
            pendingScanRequest = null
            pending.request?.let(::stopScannerRequestQuietly)
            pending.confirmation.completeExceptionally(IllegalStateException(message))
            Log.w(TAG, message)
            return
        }

        val session = scanSession
        if (session != null && session.generation == generation) {
            Log.w(TAG, "$message；终止已开始的扫描任务")
            cancelScanInternal(publishChange = true)
            reportScanFailure(IllegalStateException(message))
        }
    }

    private fun handleScanResults(generation: Long) {
        val pending = pendingScanRequest
        if (pending != null && pending.generation == generation) {
            pending.resultsReceived = true
            return
        }

        val session = scanSession ?: return
        if (session.generation != generation) return

        if (!session.resultsReceived) {
            session.resultsReceived = true
            Log.d(
                TAG,
                "WifiScanner 返回扫描结果：generation=$generation " +
                    "elapsed=${SystemClock.elapsedRealtime() - session.startedAt}ms",
            )
        }
        finishScanIfReady(session)
    }

    private fun cancelTimedOutPendingRequest(confirmation: CompletableFuture<Unit>) {
        val pending = pendingScanRequest ?: return
        if (pending.confirmation !== confirmation) return

        pendingScanRequest = null
        pending.request?.let(::stopScannerRequestQuietly)
        Log.w(TAG, "取消未在规定时间内确认的 WifiScanner 请求：generation=${pending.generation}")
    }

    private fun runScanTick(generation: Long) {
        val session = scanSession ?: return
        if (session.generation != generation) return

        try {
            if (!updateDataDuringScan()) return
            session.lastRefreshFailed = false
        } catch (error: Throwable) {
            Log.e(TAG, "扫描期间更新数据失败：${error.message}", error)
            if (!session.lastRefreshFailed) reportScanFailure(error)
            session.lastRefreshFailed = true
        }

        finishScanIfReady(session)
    }

    /** 扫描期间每 250ms 更新 WifiState 的扫描列表，并刷新 SavedWifiList。 */
    private fun updateDataDuringScan(): Boolean {
        val api = requireAndroidApi()
        if (!api.isWifiEnabled()) {
            refreshWifiDataInternal()
            return false
        }

        publishEnabledWifiDataAndSavedList(
            api = api,
            isScanning = true,
        )
        return true
    }

    private fun finishScanIfReady(session: ScanSession) {
        if (scanSession !== session) return
        val elapsed = SystemClock.elapsedRealtime() - session.startedAt
        if (!session.resultsReceived || elapsed < MIN_SCAN_DURATION_MS) return

        // 先发布最终扫描列表，再把 isScanning 改为 false。
        try {
            if (!updateDataDuringScan()) return
        } catch (error: Throwable) {
            Log.e(TAG, "扫描结束瞬间更新数据失败：${error.message}", error)
            reportScanFailure(error)
        }

        session.pollingFuture?.cancel(false)
        scanSession = null
        val current = wifiState as? WifiState.Data.Enabled
        if (current != null) publishWifiState(current.copy(isScanning = false))
        Log.d(TAG, "Wi-Fi 扫描任务完成：generation=${session.generation} elapsed=${elapsed}ms")
    }

    private fun cancelScanInternal(publishChange: Boolean) {
        scanGeneration++

        val pending = pendingScanRequest
        pendingScanRequest = null
        pending?.request?.let(::stopScannerRequestQuietly)
        pending?.confirmation?.completeExceptionally(
            IllegalStateException("Wi-Fi 扫描请求已取消"),
        )

        val session = scanSession
        scanSession = null
        session?.pollingFuture?.cancel(false)
        session?.request?.let(::stopScannerRequestQuietly)

        if (publishChange) {
            val current = wifiState as? WifiState.Data.Enabled
            if (current?.isScanning == true) {
                publishWifiState(current.copy(isScanning = false))
            }
        }

        if (pending != null || session != null) {
            Log.d(TAG, "已终止 Wi-Fi 扫描请求和扫描任务")
        }
    }

    private fun stopScannerRequestQuietly(request: WifiScannerClient.ScanRequest) {
        runCatching { wifiScannerClient.stopScan(request) }
            .onFailure { Log.w(TAG, "停止 WifiScanner 请求失败：${it.message}") }
    }

    private fun readScanResults(api: AndroidApi): List<ScanResult> =
        api.getScanResults().filterIndexed { index, result ->
            val keep = !result.BSSID.isNullOrBlank()
            if (!keep) Log.w(TAG, "丢弃第 $index 项扫描结果：BSSID 为空")
            keep
        }

    private fun readCurrentConnection(
        api: AndroidApi,
        previous: WifiInfo?,
        operation: String,
    ): WifiInfo? {
        val status = primaryNetworkStatus
        if (status != null && status != WIFI_NETWORK_STATUS_CONNECTED) return null

        return try {
            api.getConnectionInfo().takeIf(::isUsableConnectedWifiInfo)
        } catch (error: Throwable) {
            reportError(operation, error)
            previous
        }
    }

    private fun isUsableConnectedWifiInfo(info: WifiInfo): Boolean {
        val ssid = info.ssid
        val bssid = info.bssid
        return info.networkId >= 0 &&
            info.supplicantState == SupplicantState.COMPLETED &&
            !ssid.isNullOrBlank() &&
            ssid != WifiManager.UNKNOWN_SSID &&
            !bssid.isNullOrBlank() &&
            !bssid.equals(DEFAULT_MAC_ADDRESS, ignoreCase = true)
    }

    private fun publishWifiStateError(error: Throwable) {
        val exception = error as? Exception ?: RuntimeException(error)
        publishWifiState(WifiState.Error(exception))
    }

    private fun reportScanFailure(error: Throwable) {
        reportError("执行 Wi-Fi 扫描任务", error)
    }

    private fun reportError(operation: String, error: Throwable) {
        Log.e(TAG, "$operation 失败：${error.message}", error)
        if (!stopped) onError(operation, error)
    }

    private fun publishWifiState(next: WifiState) {
        wifiState = next
        if (informationSourceState.source == WifiInformationSource.SYSTEM) {
            when (next) {
                is WifiState.Data.Enabled -> lastSystemWifiEnabled = true
                is WifiState.Data.Disabled -> lastSystemWifiEnabled = false
                is WifiState.Error -> Unit
            }
        }
        if (!stopped) onWifiStateChanged(next)
    }

    private fun publishSavedWifiList(next: SavedWifiList) {
        savedWifiList = next
        if (!stopped) onSavedWifiListChanged(next)
    }

    private fun requireAndroidApi(): AndroidApi =
        androidApiProvider() ?: throw IllegalStateException("AndroidApi 尚未初始化")

    private fun execute(block: () -> Unit) {
        if (stopped) return
        try {
            executor.execute {
                if (!stopped) block()
            }
        } catch (_: RejectedExecutionException) {
            // stop() 与异步回调竞争时允许静默丢弃已失效任务。
        }
    }

    private class PendingScanRequest(
        val generation: Long,
        val confirmation: CompletableFuture<Unit>,
        var request: WifiScannerClient.ScanRequest? = null,
        var resultsReceived: Boolean = false,
    )

    private class ScanSession(
        val generation: Long,
        val startedAt: Long,
        val request: WifiScannerClient.ScanRequest,
        var resultsReceived: Boolean = false,
        var lastRefreshFailed: Boolean = false,
        var pollingFuture: ScheduledFuture<*>? = null,
    )

    private companion object {
        const val TAG = "ServiceWifiListController"
        const val MIN_SCAN_DURATION_MS = 3_000L
        const val SCAN_REFRESH_INTERVAL_MS = 250L
        const val SYSTEM_WIFI_ENABLED_REFRESH_INTERVAL_MS = 1_000L
        const val SCAN_START_CONFIRM_TIMEOUT_MS = 10_000L
        const val WIFI_ROLE_CLIENT_PRIMARY = 1
        const val WIFI_NETWORK_STATUS_CONNECTED = 6
        const val DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00"
        const val CHANNEL_PLACEHOLDER = "【信道】"
        const val MONITOR_MODE_VERIFICATION_ERROR =
            "脚本执行完毕但系统没能进入监听模式。"
        const val MONITOR_EXIT_COMMAND = """setprop ctl.restart wificond
setprop ctl.restart vendor.wifi_hal_legacy
start wificond
start vendor.wifi_hal_legacy
svc wifi enable"""
    }
}

internal data class HybridTaskEnvironment(
    val rootfsPath: String,
    val runtimePath: String,
    val terminalPath: String,
)
