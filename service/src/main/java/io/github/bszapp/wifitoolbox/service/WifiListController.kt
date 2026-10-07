@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.service

import android.net.wifi.ScanResult
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChannel
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiListDataSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeSwitch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.contract.wifilist.createScanResultCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
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
    private val hybridWifiScanner: HybridWifiScanner,
    private val terminalManager: TerminalManager,
    private val onWifiStateChanged: (WifiState) -> Unit,
    private val onSavedWifiListChanged: (SavedWifiList) -> Unit,
    private val onModeStateChanged: (WifiModeState) -> Unit,
    private val onMonitorRecordedBytesChanged: (Long, Long) -> Unit,
    private val onMonitorPcapExported: (requestId: String, path: String, fileName: String) -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "toolbox-service-wifi-state").apply { isDaemon = true }
    }
    private val interfaceModePollingExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "toolbox-service-interface-mode").apply { isDaemon = true }
    }
    private val interruptionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "toolbox-service-wifi-interrupt").apply { isDaemon = true }
    }

    @Volatile
    private var wifiState: WifiState? = null

    @Volatile
    private var savedWifiList: SavedWifiList? = null

    private var initialized = false
    @Volatile private var environmentConfigured = false
    @Volatile private var monitorCapturePlan: MonitorCapturePlan = MonitorCapturePlan.Stopped
    /** 仅保护服务内部异步网卡操作，不作为公开模式。 */
    private var monitorCaptureChangeActive = false
    private var monitorCaptureOperationId = 0L
    private var monitorRestoreCompletion: CompletableFuture<Unit>? = null
    private val monitorScanner = MonitorWifiScanner(terminalManager) { error ->
        val generation = modeGeneration
        execute {
            if (isCurrentMode(generation, WifiMode.MONITOR)) {
                failMonitorReception("跳频录制", error)
            }
        }
    }
    private var monitorScanActive = false
    private val diagnosticSubmitted = AtomicLong()
    private val diagnosticCompleted = AtomicLong()
    private val diagnosticQueueLoggedAt = AtomicLong()
    @Volatile private var diagnosticRunning = "idle"
    @Volatile private var diagnosticRunningAt = 0L
    private var diagnosticModeLoggedAt = 0L

    @Volatile
    private var stopped = false

    private var scanGeneration = 0L
    @Volatile
    private var modeGeneration = 0L
    private val interruptedModeOperation = AtomicLong(-1L)
    /** 仅供服务内部保护执行中的操作，不作为公开模式或 UI 状态。 */
    @Volatile
    private var modeOperationInProgress = false
    @Volatile private var informationSourceTransitionTerminalId: Long? = null
    private var interfaceModePollingFuture: ScheduledFuture<*>? = null

    private val monitorModeController = MonitorModeController(
        terminalManager = terminalManager,
        captureChannel = ::monitorCaptureChannel,
        savedNetworks = { savedWifiList?.networks ?: requireAndroidApi().getSavedWifiList() },
        onStatisticsChanged = { statistics ->
            execute("monitorStatistics") { publishMonitorStatistics(statistics) }
        },
        onRecordedBytesChanged = { sessionGeneration, recordedBytes ->
            execute("monitorRecordedBytes") { publishMonitorRecordedBytes(sessionGeneration, recordedBytes) }
        },
        onExportCompleted = { requestId, path, fileName ->
            execute { onMonitorPcapExported(requestId, path, fileName) }
        },
        onError = { operation, error ->
            execute {
                if (modeState.mode == WifiMode.MONITOR) {
                    reportError(operation, error)
                }
            }
        },
    )

    @Volatile
    private var modeState = WifiModeState(
        mode = WifiMode.NORMAL,

    )

    private val normalScanner: NormalWifiScanner = NormalWifiScanner(
        androidApi = ::requireAndroidApi,
        hybridScanner = hybridWifiScanner,
        parseUnderlyingResults = { parseHybridScanResults(it.getJSONArray("wifilist")) },
        onState = { state, runIfCurrent ->
            execute("normalScanState") {
                runIfCurrent {
                    if (modeState.mode == WifiMode.NORMAL) {
                        val source = when (state) {
                            is WifiState.System -> WifiListDataSource.SYSTEM
                            is WifiState.Underlying -> WifiListDataSource.UNDERLYING
                            is WifiState.Monitor -> error("普通扫描器不能发布监听扫描数据")
                        }
                        if (modeState.listDataSource != source) {
                            publishModeState(modeState.copy(listDataSource = source))
                        }
                        publishWifiState(state)
                    }
                }
            }
        },
        onSavedNetworksChanged = ::refreshSavedNetworks,
        onError = ::reportError,
    )

    /** 模式只控制扫描作用域是否启用；来源请求由扫描器自身保存。 */
    private fun syncNormalScanSelection() {
        normalScanner.setEnabled(
            initialized && !stopped && !modeOperationInProgress && modeState.mode == WifiMode.NORMAL &&
                modeState.modeSwitch?.isRunning != true
        )
    }

    fun beforeContainerDelete() = normalScanner.beforeContainerDelete()
    fun afterContainerOperation() = normalScanner.afterContainerOperation()

    fun getWifiState(): WifiState? = wifiState

    fun getSavedWifiList(): SavedWifiList? = savedWifiList

    fun getModeState(): WifiModeState = modeState

    fun isNormalModeTaskReady(): Boolean = environmentConfigured &&
        modeState.mode == WifiMode.NORMAL && !modeOperationInProgress

    fun initialize() {
        execute {
            if (initialized) return@execute
            initialized = true
            modeOperationInProgress = true
            try {
                publishDetectedMode(readInterfaceMode(WifiMode.NORMAL))
                if (modeState.mode == WifiMode.MONITOR) {
                    setInterfaceUp(false)
                    publishWifiState(WifiState.Monitor(emptyList(), false))
                    refreshSavedNetworksInternal()
                    if (environmentConfigured) initializeMonitorSession()
                } else {
                    refreshSavedNetworksInternal()
                }
            } catch (error: Throwable) {
                reportError("准备 Wi-Fi 数据", error)
            } finally {
                modeOperationInProgress = false
                syncNormalScanSelection()
                startInterfaceModePolling()
            }
        }
    }

    fun configureEnvironment() {
        execute {
            environmentConfigured = true
            if (modeState.mode == WifiMode.MONITOR && modeState.monitorStatistics == null) {
                runCatching { initializeMonitorSession() }
                    .onFailure { reportError("初始化 monitor 会话", it) }
            }
        }
    }

    private fun initializeMonitorSession() {
        val previousOperation = modeOperationInProgress
        modeOperationInProgress = true
        try {
            monitorCapturePlan = MonitorCapturePlan.Stopped
            monitorCaptureChangeActive = false
            monitorModeController.start()
            monitorScanner.initialize().get(SCAN_START_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            publishModeState(modeState.copy(
                monitorStatistics = monitorModeController.statisticsHeader(),
                availableChannels = monitorScanner.availableChannels(),
                capturing = false, hoppingCapture = false,
            ))
        } catch (error: Exception) {
            monitorScanner.stop()
            monitorModeController.stop()
            publishModeState(modeState.copy(monitorStatistics = null, availableChannels = emptyList(),
                capturing = false, clearingCapture = false, captureClearProgress = null))
            throw error
        } finally {
            modeOperationInProgress = previousOperation
        }
    }

    fun setWifiListDataSource(source: WifiListDataSource) {
        execute {
            runCatching {
                check(!modeOperationInProgress) { "网卡操作尚未完成" }
                check(modeState.mode == WifiMode.NORMAL) { "列表数据源仅用于普通模式" }
                normalScanner.select(source)
            }.onFailure { reportError("选择 Wi-Fi 扫描来源", it) }
        }
    }

    fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int, hopping: Boolean) {
        Log.d(TAG, "[MonitorDiagnostic] captureRequested enabled=$enabled frequencyMhz=$frequencyMhz hopping=$hopping")
        execute("setMonitorCapture") {
            val started = SystemClock.elapsedRealtime()
            Log.d(TAG, "[MonitorDiagnostic] captureBegin mode=${modeState.mode} scanning=$monitorScanActive clearing=${modeState.clearingCapture}")
            val generation = modeGeneration
            var accepted = false
            try {
                check(modeState.mode == WifiMode.MONITOR && !modeOperationInProgress) { "网卡操作尚未完成" }
                check((!enabled || (!monitorScanActive && !monitorCaptureChangeActive)) &&
                    !modeState.clearingCapture) { "请等待扫描或网卡操作或清理完成" }
                if (enabled) {
                    check(!monitorModeController.isCapturing()) { "抓取进程尚不能修改信道" }
                    val channel = modeState.availableChannels.firstOrNull { hopping || it.frequencyMhz == frequencyMhz }
                        ?: error("所选信道不可用")
                    monitorCapturePlan = if (hopping) MonitorCapturePlan.Hopping
                        else MonitorCapturePlan.Fixed(channel.frequencyMhz)
                } else {
                    monitorCapturePlan = MonitorCapturePlan.Stopped
                }
                Log.d(TAG, "[MonitorDiagnostic] capturePlanChanged generation=$generation plan=$monitorCapturePlan")
                val operationId = ++monitorCaptureOperationId
                monitorCaptureChangeActive = true
                accepted = true

                fun isCurrent() = isCurrentMode(generation, WifiMode.MONITOR) &&
                    !modeOperationInProgress && monitorCaptureOperationId == operationId
                fun finish() {
                    monitorCaptureChangeActive = false
                    publishModeState(modeState.copy(
                        capturing = monitorModeController.isCapturing(),
                        hoppingCapture = monitorCapturePlan == MonitorCapturePlan.Hopping &&
                            monitorModeController.isCapturing(),
                        monitorStatistics = monitorModeController.statisticsHeader(),
                    ))
                    Log.d(TAG, "[MonitorDiagnostic] captureEnd elapsedMs=${SystemClock.elapsedRealtime() - started}")
                }
                fun awaitCapture(request: CompletableFuture<Unit>, bounded: Boolean = true, next: () -> Unit) {
                    val timeout = if (bounded) executor.schedule({
                        if (isCurrent() && !request.isDone) {
                            request.completeExceptionally(TimeoutException("等待抓取进程确认超时"))
                        }
                    }, SCAN_START_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS) else null
                    request.whenComplete { _, error ->
                        execute("captureAck") {
                            timeout?.cancel(false)
                            if (!isCurrent()) return@execute
                            try {
                                if (error != null) throw unwrapCompletionFailure(error)
                                next()
                            } catch (failure: Throwable) {
                                monitorCaptureChangeActive = false
                                failMonitorReception("修改持续抓取状态", failure)
                            }
                        }
                    }
                }

                if (enabled) {
                    // 故障后回放历史文件可能很大；异步等待就绪，停止和模式切换仍可处理。
                    val ready = monitorModeController.ensureRunning()
                    awaitCapture(ready, bounded = false) {
                        setInterfaceUp(true)
                        awaitCapture(restoreMonitorReception(generation)) {
                            awaitCapture(monitorModeController.setCapture(true)) { finish() }
                        }
                    }
                } else {
                    // 收到 tcpdump 停止确认后，才恢复停止计划和关闭网卡接收。
                    awaitCapture(monitorModeController.setCapture(false)) {
                        if (monitorScanActive) finish()
                        else awaitCapture(restoreMonitorReception(generation)) { finish() }
                    }
                }
            } catch (error: Throwable) {
                if (accepted) failMonitorReception("修改持续抓取状态", error)
                else reportError("修改持续抓取状态", error)
            }
        }
    }

    /** 停止后的历史展示沿用已发布快照；网卡恢复始终只读取 monitorCapturePlan。 */
    private fun monitorCaptureChannel(): MonitorChannel? = when (val plan = monitorCapturePlan) {
        is MonitorCapturePlan.Fixed ->
            modeState.availableChannels.firstOrNull { it.frequencyMhz == plan.frequencyMhz }
        MonitorCapturePlan.Hopping -> null
        MonitorCapturePlan.Stopped -> modeState.monitorStatistics?.takeIf { it.frequencyMhz > 0 }?.let {
            MonitorChannel(it.channel, it.frequencyMhz)
        }
    }

    /** 扫描操作在采集结束后调用；在串行调度线程上读取此刻的录制计划。 */
    private fun restoreMonitorReception(
        generation: Long,
        onRestored: () -> Unit = {},
    ): CompletableFuture<Unit> {
        val completion = CompletableFuture<Unit>()
        if (stopped) {
            completion.completeExceptionally(IllegalStateException("Wi-Fi 服务已停止"))
        } else execute("monitorRestore") restore@{
            if (!isCurrentMode(generation, WifiMode.MONITOR) || modeOperationInProgress) {
                completion.completeExceptionally(IllegalStateException("monitor 模式已改变"))
                return@restore
            }
            val active = monitorRestoreCompletion
            if (active != null && !active.isDone) {
                active.whenComplete { _, error ->
                    execute("monitorRestoreJoined") {
                        if (error != null) completion.completeExceptionally(unwrapCompletionFailure(error))
                        else try { onRestored(); completion.complete(Unit) }
                        catch (failure: Throwable) { completion.completeExceptionally(failure) }
                    }
                }
                return@restore
            }
            monitorRestoreCompletion = completion
            try {
                // 扫描进程异常退出后只重新准备信道执行器，已有抓包数据保持不动。
                check(environmentConfigured) { "容器环境尚未配置" }
                val ready = monitorScanner.initialize()
                awaitMonitorCommand(generation, ready, completion, "初始化信道执行器") {
                    val channels = monitorScanner.availableChannels()
                    if (channels != modeState.availableChannels) {
                        publishModeState(modeState.copy(availableChannels = channels))
                    }
                    applyMonitorCapturePlan(generation, completion, onRestored)
                }
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    private fun applyMonitorCapturePlan(
        generation: Long,
        completion: CompletableFuture<Unit>,
        onRestored: () -> Unit,
    ) {
        if (completion.isDone) return
        if (!isCurrentMode(generation, WifiMode.MONITOR) || modeOperationInProgress) {
            completion.completeExceptionally(IllegalStateException("monitor 模式已改变"))
            return
        }
        val plan = monitorCapturePlan
        Log.d(TAG, "[MonitorDiagnostic] resumeBegin generation=$generation plan=$plan")
        val request = try {
            monitorScanner.resume(plan)
        } catch (error: Throwable) {
            completion.completeExceptionally(error)
            return
        }
        awaitMonitorCommand(generation, request, completion, "恢复") {
            if (monitorCapturePlan != plan) {
                // 等待 ACK 期间收到停止请求，旧确认不能结束本次恢复。
                Log.d(TAG, "[MonitorDiagnostic] resumePlanChanged previous=$plan latest=$monitorCapturePlan")
                applyMonitorCapturePlan(generation, completion, onRestored)
            } else {
                if (plan == MonitorCapturePlan.Stopped) setInterfaceUp(false)
                Log.d(TAG, "[MonitorDiagnostic] resumeEnd generation=$generation plan=$plan")
                onRestored()
                completion.complete(Unit)
            }
        }
    }

    /** 所有脚本确认均异步等待，服务调度线程可以继续处理停止和模式变化。 */
    private fun awaitMonitorCommand(
        generation: Long,
        request: CompletableFuture<Unit>,
        completion: CompletableFuture<Unit>,
        operation: String,
        onConfirmed: () -> Unit,
    ) {
        val timeout = executor.schedule({
            if (!completion.isDone && !request.isDone) {
                completion.completeExceptionally(TimeoutException("等待 monitor $operation 确认超时"))
            }
        }, SCAN_START_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        request.whenComplete { _, error ->
            execute("monitorRestoreAck") {
                timeout.cancel(false)
                if (completion.isDone) return@execute
                if (!isCurrentMode(generation, WifiMode.MONITOR) || modeOperationInProgress) {
                    completion.completeExceptionally(IllegalStateException("monitor 模式已改变"))
                } else if (error != null) {
                    completion.completeExceptionally(unwrapCompletionFailure(error))
                } else {
                    try {
                        onConfirmed()
                    } catch (failure: Throwable) {
                        completion.completeExceptionally(failure)
                    }
                }
            }
        }
    }

    private fun failMonitorReception(operation: String, error: Throwable) {
        monitorCapturePlan = MonitorCapturePlan.Stopped
        monitorCaptureChangeActive = false
        monitorCaptureOperationId++
        monitorModeController.abortProcess(error)
        monitorScanner.stop()
        runCatching { setInterfaceUp(false) }.onFailure { reportError("暂停 monitor 接收", it) }
        publishModeState(modeState.copy(
            hoppingCapture = false, capturing = monitorModeController.isCapturing(),
            monitorStatistics = monitorModeController.statisticsHeader(),
        ))
        reportError(operation, error)
    }

    fun clearMonitorCapture(handshakesOnly: Boolean) {
        Log.d(TAG, "[MonitorDiagnostic] clearRequested handshakesOnly=$handshakesOnly")
        execute("clearMonitorCapture") {
            Log.d(TAG, "[MonitorDiagnostic] clearBegin mode=${modeState.mode} scanning=$monitorScanActive capturing=${modeState.capturing}")
            runCatching {
                check(modeState.mode == WifiMode.MONITOR && !monitorScanActive &&
                    !monitorCaptureChangeActive && !modeOperationInProgress)
                monitorModeController.clearCapture(handshakesOnly)
            }.onFailure { reportError("清理抓取数据", it) }
            Log.d(TAG, "[MonitorDiagnostic] clearRequestEnd handshakesOnly=$handshakesOnly")
        }
    }

    fun interruptMonitorClear(operationId: Long) {
        // 中断直接释放脚本资源，不排在 Wi-Fi 调度线程的等待操作后面。
        if (!monitorModeController.interruptClear(operationId)) return
        execute("interruptMonitorClear") {
            ++modeGeneration
            monitorCapturePlan = MonitorCapturePlan.Stopped
            monitorCaptureChangeActive = false
            monitorCaptureOperationId++
            monitorScanner.stop()
            monitorRestoreCompletion = null
            if (modeState.mode == WifiMode.MONITOR) {
                runCatching { setInterfaceUp(false) }.onFailure { reportError("中断清理后停止接收", it) }
                publishMonitorStatistics(monitorModeController.statisticsHeader())
            }
        }
    }

    fun interruptModeSwitch(operationId: Long) {
        val progress = modeState.modeSwitch ?: return
        if (!progress.isRunning || progress.operationId != operationId) return
        interruptedModeOperation.set(operationId)
        interruptionExecutor.execute interruption@{
            if (modeState.modeSwitch?.operationId != operationId) return@interruption
            Log.i(TAG, "强制中断网卡模式切换，operationId=$operationId")
            val error = IOException("用户强制中断网卡模式切换")
            runCatching { stopModeTransition() }
            runCatching { monitorModeController.abortProcess(error) }
            runCatching { monitorScanner.stop() }
            runCatching { normalScanner.awaitInactive() }
            execute("interruptModeSwitch") cleanup@{
                if (modeState.modeSwitch?.operationId != operationId) return@cleanup
                ++modeGeneration
                normalScanner.awaitInactive()
                // 收回请求中断与工作线程真正退出之间可能创建的资源。
                stopModeTransition()
                monitorModeController.abortProcess(error)
                monitorScanner.stop()
                monitorRestoreCompletion = null
                monitorCapturePlan = MonitorCapturePlan.Stopped
                monitorCaptureChangeActive = false
                monitorCaptureOperationId++
                monitorScanActive = false
                publishDetectedMode(readInterfaceMode())
                modeOperationInProgress = false
                if (modeState.mode == WifiMode.MONITOR) {
                    runCatching { setInterfaceUp(false) }.onFailure { reportError("中断模式切换后停止接收", it) }
                    publishModeState(modeState.copy(capturing = false, hoppingCapture = false, clearingCapture = false,
                        captureClearProgress = monitorModeController.captureClearProgress()))
                }
                finishModeSwitch()
            }
        }
    }

    /** monitor 接口可没有 carrier；接收开关只由 IFF_UP 决定。 */
    private fun readInterfaceUp(): Boolean {
        val flags = File("/sys/class/net/wlan0/flags").readText().trim()
            .removePrefix("0x").toLong(16)
        return (flags and OsConstants.IFF_UP.toLong()) != 0L
    }

    private fun setInterfaceUp(up: Boolean) {
        // 此处的 DOWN 请求均用于 monitor 省电；模式切换脚本的 DOWN 操作独立保留。
        if (!up && !MONITOR_POWER_SAVING_ENABLED) return
        val observedUp = readInterfaceUp()
        if (observedUp == up) {
            Log.d(TAG, "[MonitorDiagnostic] interfaceChangeSkipped requestedUp=$up observedUp=$observedUp " +
                "scanning=$monitorScanActive capturing=${modeState.capturing}")
            return
        }
        val started = SystemClock.elapsedRealtime()
        Log.d(TAG, "[MonitorDiagnostic] interfaceChangeBegin up=$up observedUp=$observedUp scanning=$monitorScanActive capturing=${modeState.capturing}")
        val process = ProcessBuilder("ip", "link", "set", "wlan0", if (up) "up" else "down")
            .redirectErrorStream(true).start()
        if (!process.waitForCompat(3_000L, TimeUnit.MILLISECONDS)) {
            process.destroyForciblyCompat()
            throw IOException("修改 wlan0 接收状态超时：up=$up")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        val afterUp = readInterfaceUp()
        Log.d(TAG, "[MonitorDiagnostic] interfaceChangeEnd up=$up observedUp=$afterUp exitCode=$code elapsedMs=${SystemClock.elapsedRealtime() - started}")
        check(code == 0) { "修改 wlan0 接收状态失败：$output" }
        check(afterUp == up) { "wlan0 接收状态未变为请求的状态：up=$up" }
    }

    private fun startMonitorScanSynchronously() {
        Log.d(TAG, "[MonitorDiagnostic] scanRequested modeGeneration=$modeGeneration capturing=${modeState.capturing}")
        val requestedGeneration = modeGeneration
        val requestedScanId = AtomicLong(-1L)
        val accepted = CompletableFuture<Unit>()
        execute {
            if (accepted.isDone) return@execute
            var ownsScan = false
            try {
                check(isCurrentMode(requestedGeneration, WifiMode.MONITOR) && !modeOperationInProgress) { "monitor 模式已改变" }
                check(!monitorScanActive && !monitorCaptureChangeActive && !modeState.clearingCapture) { "monitor 操作正在进行" }
                check(environmentConfigured) { "容器环境尚未配置" }
                val generation = modeGeneration
                val scanId = ++scanGeneration
                requestedScanId.set(scanId)
                monitorScanActive = true
                ownsScan = true
                Log.d(TAG, "[MonitorDiagnostic] scanBegin modeGeneration=$generation capturing=${modeState.capturing}")
                setInterfaceUp(true)
                val current = wifiState as? WifiState.Monitor
                publishWifiState(WifiState.Monitor(current?.scanResults.orEmpty(), true))
                monitorScanner.start(onMessage = { message ->
                    execute {
                        if (isCurrentMode(generation, WifiMode.MONITOR) && scanGeneration == scanId) {
                            val state = message.optJSONObject("state") ?: return@execute
                            if (state.optString("type") == "error") reportError("monitor 扫描", IllegalStateException(state.optString("message")))
                            else {
                                val incoming = parseHybridScanResults(state.getJSONArray("wifilist"))
                                Log.d(TAG, "[MonitorDiagnostic] scanUpdate modeGeneration=$generation networks=${incoming.size}")
                                publishMonitorScanResults(incoming, true)
                                refreshSavedNetworksInternal()
                            }
                        }
                    }
                }, restore = {
                    restoreMonitorReception(generation) {
                        monitorScanActive = false
                        (wifiState as? WifiState.Monitor)?.let { publishWifiState(it.copy(isScanning = false)) }
                    }
                }, onFinished = { code, restoreError ->
                    execute {
                        if (isCurrentMode(generation, WifiMode.MONITOR) && scanGeneration == scanId) {
                            if (restoreError != null) {
                                failMonitorReception("恢复 monitor 接收", unwrapCompletionFailure(restoreError))
                                monitorScanActive = false
                                (wifiState as? WifiState.Monitor)?.let { publishWifiState(it.copy(isScanning = false)) }
                            }
                            Log.d(TAG, "[MonitorDiagnostic] scanEnd modeGeneration=$generation exitCode=$code capturing=${modeState.capturing}")
                            if (code != 0) reportError("monitor 扫描", IllegalStateException("扫描脚本退出码 $code"))
                        }
                    }
                }).whenComplete { _, error ->
                    if (error == null) accepted.complete(Unit) else accepted.completeExceptionally(error)
                }
            } catch (error: Throwable) {
                if (ownsScan) {
                    monitorScanActive = false
                    if (!monitorModeController.isCapturing()) runCatching { setInterfaceUp(false) }
                }
                accepted.completeExceptionally(error)
            }
        }
        try { accepted.get(SCAN_START_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        catch (error: ExecutionException) { throw (error.cause as? Exception ?: RuntimeException(error.cause)) }
        catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            accepted.completeExceptionally(error)
            execute {
                if (isCurrentMode(requestedGeneration, WifiMode.MONITOR) &&
                    requestedScanId.get() >= 0 && requestedScanId.get() == scanGeneration) {
                    failMonitorReception("等待 monitor 扫描启动", error)
                }
            }
            throw IllegalStateException("等待 monitor 扫描启动失败", error)
        }
    }

    fun refreshSavedNetworks() {
        execute {
            Log.d(TAG, "刷新已保存 Wi-Fi 列表")
            refreshSavedNetworksInternal()
        }
    }

    private fun publishMonitorScanResults(incoming: List<ScanResult>, scanning: Boolean) {
        publishWifiState(WifiState.Monitor(incoming, scanning))
    }

    /** 请求只转交给当前活动作用域，monitor 保留独立的扫描调度。 */
    @Throws(Exception::class)
    fun startScan() {
        check(!stopped) { "Wi-Fi 服务已停止" }
        check(!modeOperationInProgress) { "网卡操作尚未完成" }
        if (modeState.mode == WifiMode.MONITOR) startMonitorScanSynchronously()
        else normalScanner.startScan()
    }

    fun setMode(
        mode: WifiMode,
    ) {
        require(mode == WifiMode.NORMAL) { "监听模式通过命令编辑入口进入" }
        execute {
            val current = modeState
            if (current.mode == mode && !modeOperationInProgress) return@execute
            stopInterfaceModePolling()
            modeOperationInProgress = true
            val generation = ++modeGeneration
            publishModeState(modeState.copy(modeSwitch = WifiModeSwitch(mode, true, generation)))
            try {
                normalScanner.awaitInactive()
                stopModeTransition()
                val detected = readInterfaceMode(current.mode)
                publishDetectedMode(detected)
                if (detected == WifiMode.MONITOR) {
                    monitorCapturePlan = MonitorCapturePlan.Stopped
                    monitorCaptureChangeActive = false
                    monitorScanner.stop()
                    monitorScanActive = false
                    monitorModeController.stop()
                    publishModeState(modeState.copy(capturing = false, hoppingCapture = false, clearingCapture = false))
                    runMonitorExitScript(generation, mode)
                } else {
                    continueModeSwitch(generation, mode)
                }
            } catch (error: Throwable) {
                finishModeFailure(generation, "切换网卡模式", error)
            }
        }
    }

    fun enterMonitorMode(
        command: String,
    ) {
        execute {
            if (modeState.mode == WifiMode.MONITOR) return@execute
            stopInterfaceModePolling()
            modeOperationInProgress = true
            val generation = ++modeGeneration
            publishModeState(modeState.copy(modeSwitch = WifiModeSwitch(WifiMode.MONITOR, true, generation)))
            try {
                normalScanner.awaitInactive()
                stopModeTransition()
                monitorScanner.stop()
                monitorScanActive = false
                monitorCapturePlan = MonitorCapturePlan.Stopped
                monitorCaptureChangeActive = false
                monitorModeController.stop()
                runChrootTerminalScript(command) { exitCode ->
                    if (generation != modeGeneration || interruptedModeOperation.get() == generation) return@runChrootTerminalScript
                    try {
                        val detected = readInterfaceMode()
                        check(detected == WifiMode.MONITOR) { MONITOR_MODE_VERIFICATION_ERROR }
                        setInterfaceUp(false)
                        if (interruptedModeOperation.get() == generation) return@runChrootTerminalScript
                        initializeMonitorSession()
                        if (interruptedModeOperation.get() == generation) return@runChrootTerminalScript
                        publishInitializedMonitorMode()
                        modeOperationInProgress = false
                        finishModeSwitch()
                        Log.i(TAG, "监听模式已启动，进入脚本退出码=$exitCode")
                    } catch (error: Throwable) {
                        finishModeFailure(generation, "进入监听模式", error)
                    }
                }
            } catch (error: Throwable) {
                finishModeFailure(generation, "启动监听模式进入终端", error)
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
                    modeState.mode == WifiMode.MONITOR &&
                        !modeOperationInProgress,
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
                    modeState.mode == WifiMode.MONITOR &&
                        !modeOperationInProgress,
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
                    modeState.mode == WifiMode.MONITOR &&
                        !modeOperationInProgress,
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
        targetSource: WifiMode,
    ) {
        try {
            runChrootTerminalScript(MONITOR_EXIT_COMMAND) { exitCode ->
                if (generation != modeGeneration || interruptedModeOperation.get() == generation) return@runChrootTerminalScript
                try {
                    if (exitCode != 0) {
                        Log.w(TAG, "监听模式退出脚本退出码=$exitCode，继续开启 Wi-Fi 并确认实际网卡模式")
                    }
                    continueModeSwitch(generation, targetSource, waitForNormalMode = true)
                } catch (error: Throwable) {
                    finishModeFailure(generation, "退出监听模式", error)
                }
            }
        } catch (error: Throwable) {
            finishModeFailure(generation, "启动监听模式退出终端", error)
        }
    }

    private fun continueModeSwitch(
        generation: Long,
        mode: WifiMode,
        waitForNormalMode: Boolean = false,
    ) {
        if (generation != modeGeneration || interruptedModeOperation.get() == generation) return
        check(mode == WifiMode.NORMAL) { "监听模式使用专用入口" }
        requireAndroidApi().setWifiEnabled(true)

        fun completeSwitch() = switchToSystemSource(generation)

        if (!waitForNormalMode) {
            completeSwitch()
            return
        }
        // 系统 Wi-Fi 恢复是异步的；等待期间保留切换状态，不阻塞停止/中断请求。
        val deadline = SystemClock.elapsedRealtime() + SCAN_START_CONFIRM_TIMEOUT_MS
        fun confirmNormalMode() {
            if (stopped || generation != modeGeneration || interruptedModeOperation.get() == generation) return
            try {
                val observed = runCatching { WirelessInterfaceNetlink.readMode() }
                val detected = observed.getOrNull()
                if (detected == WifiMode.NORMAL) {
                    publishDetectedMode(detected)
                    completeSwitch()
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    throw IOException("等待退出监听模式超时：" +
                        if (detected == null) "无法读取 wlan0 模式" else "wlan0 仍处于监听模式",
                        observed.exceptionOrNull())
                } else {
                    Log.d(TAG, "等待系统恢复普通模式，实际模式=$detected")
                    executor.schedule({ confirmNormalMode() }, INTERFACE_MODE_REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                }
            } catch (error: Throwable) {
                finishModeFailure(generation, "退出监听模式", error)
            }
        }
        confirmNormalMode()
    }

    private fun runChrootTerminalScript(
        command: String,
        onExit: (exitCode: Int) -> Unit,
    ) {
        val terminalId = terminalManager.createChrootTerminal(
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
        if (interruptedModeOperation.get() == modeGeneration) {
            stopModeTransition()
            return
        }
        terminalManager.writeInput(terminalId, command.trimEnd() + "\nexit")
    }

    private fun stopModeTransition() {
        val terminalId = informationSourceTransitionTerminalId ?: return
        informationSourceTransitionTerminalId = null
        terminalManager.stopTerminal(terminalId, reason = "切换 Wi-Fi 信息源")
    }

    fun getMonitorChanges(sessionGeneration: Long, afterRevision: Long) =
        monitorModeController.changesPage(sessionGeneration, afterRevision)

    fun getMonitorCommunications(session: Long, bssid: String, mac: String, from: Long) =
        monitorModeController.communicationPage(session, bssid, mac, from)

    fun getMonitorCommunicationDetail(session: Long, bssid: String, mac: String, id: String, cursor: Long) =
        monitorModeController.communicationDetail(session, bssid, mac, id, cursor)

    private fun publishMonitorStatistics(statistics: MonitorModeStatistics) {
        val current = modeState
        if (current.mode != WifiMode.MONITOR || modeOperationInProgress ||
            !monitorModeController.isCurrentSnapshot(statistics.sessionGeneration)) return
        if (!monitorModeController.isCapturing() && !monitorCaptureChangeActive &&
            monitorCapturePlan != MonitorCapturePlan.Stopped) {
            monitorCapturePlan = MonitorCapturePlan.Stopped
            if (!monitorScanActive) {
                val generation = modeGeneration
                monitorCaptureChangeActive = true
                restoreMonitorReception(generation) {
                    monitorCaptureChangeActive = false
                }.whenComplete { _, error ->
                    if (error != null) execute {
                        if (isCurrentMode(generation, WifiMode.MONITOR)) {
                            failMonitorReception("停止 monitor 接收", unwrapCompletionFailure(error))
                        }
                    }
                }
            }
        }
        if (!monitorScanActive && !monitorCaptureChangeActive && !monitorModeController.isCapturing()) {
            runCatching { setInterfaceUp(false) }.onFailure { reportError("暂停 monitor 接收", it) }
        }
        val channel = monitorCaptureChannel()
        publishModeState(
            current.copy(
                capturing = monitorModeController.isCapturing(),
                hoppingCapture = monitorCapturePlan == MonitorCapturePlan.Hopping && monitorModeController.isCapturing(),
                clearingCapture = monitorModeController.isClearing(),
                captureClearProgress = monitorModeController.captureClearProgress(),
                monitorStatistics = statistics.copy(
                    channel = channel?.channel ?: 0,
                    frequencyMhz = channel?.frequencyMhz ?: 0,
                ),
            ),
        )
    }

    private fun publishMonitorRecordedBytes(sessionGeneration: Long, recordedBytes: Long) {
        val current = modeState
        val statistics = current.monitorStatistics
        if (
            current.mode != WifiMode.MONITOR ||
            modeOperationInProgress ||
            statistics == null ||
            statistics.sessionGeneration != sessionGeneration ||
            statistics.recordedBytes == recordedBytes
        ) {
            return
        }
        modeState = current.copy(
            monitorStatistics = statistics.copy(recordedBytes = recordedBytes),
        )
        if (!stopped) onMonitorRecordedBytesChanged(sessionGeneration, recordedBytes)
    }

    private fun switchToSystemSource(generation: Long) {
        if (!isCurrentMode(generation, WifiMode.NORMAL)) return
        modeOperationInProgress = false
        normalScanner.select(WifiListDataSource.SYSTEM)
        refreshSavedNetworksInternal()
        finishModeSwitch()
    }

    private fun finishModeFailure(generation: Long, operation: String, error: Throwable) {
        if (generation != modeGeneration || interruptedModeOperation.get() == generation) return
        publishDetectedMode(readInterfaceMode())
        modeOperationInProgress = false
        refreshSavedNetworksInternal()
        finishModeSwitch()
        reportError(operation, error)
    }

    private fun finishModeSwitch() {
        val progress = modeState.modeSwitch
        if (progress?.isRunning == true) {
            publishModeState(modeState.copy(modeSwitch = progress.copy(isRunning = false)))
        }
        startInterfaceModePolling(immediate = true)
    }

    /** 用户请求进入 monitor 时，等监听扫描与统计会话初始化成功后再发布新模式。 */
    private fun publishInitializedMonitorMode() {
        val previous = modeState
        if (previous.mode == WifiMode.MONITOR) return
        monitorCapturePlan = MonitorCapturePlan.Stopped
        monitorCaptureChangeActive = false
        publishModeState(previous.copy(mode = WifiMode.MONITOR))
        publishWifiState(WifiState.Monitor(emptyList(), false))
    }

    private fun readInterfaceMode(fallback: WifiMode = modeState.mode): WifiMode =
        runCatching { WirelessInterfaceNetlink.readMode() }
            .onFailure { Log.w(TAG, "读取网卡类型失败，保留已确认的模式", it) }.getOrDefault(fallback)

    /** 只发布读取到的类型。抓包数据在确认离开 monitor 后清理。 */
    private fun publishDetectedMode(detected: WifiMode) {
        val previous = modeState
        if (previous.mode == detected) return
        monitorCapturePlan = MonitorCapturePlan.Stopped
        monitorCaptureChangeActive = false
        if (detected == WifiMode.NORMAL) {
            monitorScanner.stop()
            monitorScanActive = false
            monitorModeController.stop()
        }
        publishModeState(WifiModeState(mode = detected,
            modeSwitch = previous.modeSwitch,
            listDataSource = previous.listDataSource))
        if (detected == WifiMode.MONITOR) publishWifiState(WifiState.Monitor(emptyList(), false))
    }

    private fun isCurrentMode(
        generation: Long,
        mode: WifiMode,
    ): Boolean =
        generation == modeGeneration &&
            interruptedModeOperation.get() != generation &&
            modeState.mode == mode

    private fun publishModeState(next: WifiModeState) {
        val previous = modeState
        modeState = next
        syncNormalScanSelection()
        val now = SystemClock.elapsedRealtime()
        if (previous.mode != next.mode || previous.capturing != next.capturing ||
            previous.clearingCapture != next.clearingCapture || previous.hoppingCapture != next.hoppingCapture ||
            previous.monitorStatistics?.sessionGeneration != next.monitorStatistics?.sessionGeneration ||
            (next.mode == WifiMode.MONITOR && now - diagnosticModeLoggedAt >= 1000L)) {
            diagnosticModeLoggedAt = now
            val header = next.monitorStatistics
            Log.d(TAG, "[MonitorDiagnostic] modePublish mode=${next.mode} capturing=${next.capturing} " +
                "clearing=${next.clearingCapture} clearStage=${next.captureClearProgress?.stage} " +
                "hopping=${next.hoppingCapture} scanning=$monitorScanActive epoch=${header?.sessionGeneration} " +
                "revision=${header?.revision} bytes=${header?.recordedBytes} nonHandshakeBytes=${header?.nonHandshakeBytes}")
        }
        if (!stopped) onModeStateChanged(next)
    }

    private fun unwrapCompletionFailure(error: Throwable): Throwable =
        if (error is java.util.concurrent.CompletionException && error.cause != null) {
            error.cause!!
        } else {
            error
        }

    fun stop() {
        if (stopped) return
        val completion = CompletableFuture<Unit>()
        executor.execute {
            try {
                if (!stopped) {
                    normalScanner.close()
                    stopModeTransition()
                    monitorCapturePlan = MonitorCapturePlan.Stopped
                    monitorCaptureChangeActive = false
                    monitorScanner.stop()
                    monitorModeController.close()
                    interfaceModePollingFuture?.cancel(true)
                    interfaceModePollingFuture = null
                    interfaceModePollingExecutor.shutdownNow()
                    interruptionExecutor.shutdownNow()
                    stopped = true
                    executor.shutdown()
                }
                completion.complete(Unit)
            } catch (error: Throwable) { completion.completeExceptionally(error) }
        }
        try { completion.get() }
        catch (error: ExecutionException) { throw (error.cause as? Exception ?: RuntimeException(error.cause)) }
    }

    /** 独立读取线程，避免扫描或容器脚本等待阻塞每秒的网卡类型检测。 */
    private fun startInterfaceModePolling(immediate: Boolean = false) {
        if (stopped || interfaceModePollingFuture != null) return
        interfaceModePollingFuture = interfaceModePollingExecutor.scheduleAtFixedRate(
            {
                if (!stopped && !modeOperationInProgress) {
                    val generation = modeGeneration
                    val detected = runCatching { WirelessInterfaceNetlink.readMode() }
                        .onFailure { Log.w(TAG, "定时读取 wlan0 类型失败，保留上次模式", it) }
                        .getOrNull()
                    if (detected != null) execute {
                        if (generation == modeGeneration && !modeOperationInProgress && detected != modeState.mode) {
                            handleExternalInterfaceModeChange(detected)
                        }
                    }
                }
            },
            if (immediate) 0L else INTERFACE_MODE_REFRESH_INTERVAL_MS,
            INTERFACE_MODE_REFRESH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopInterfaceModePolling() {
        interfaceModePollingFuture?.cancel(false)
        interfaceModePollingFuture = null
    }

    private fun handleExternalInterfaceModeChange(detected: WifiMode) {
        val previous = modeState.mode
        val generation = ++modeGeneration
        modeOperationInProgress = true
        try {
            publishDetectedMode(detected)
            if (detected == WifiMode.MONITOR) normalScanner.awaitInactive()
            if (previous == WifiMode.MONITOR) {
                // publishDetectedMode 已停止录制、跳频和统计进程，并清理离开模式后的数据。
                reportError("监听模式意外改变", IllegalStateException(
                    "wlan0 已从监听模式变为${detected.displayName}，已结束录制。",
                ))
            }
            if (detected == WifiMode.NORMAL) {
                switchToSystemSource(generation)
            } else {
                setInterfaceUp(false)
                refreshSavedNetworksInternal()
                if (environmentConfigured) initializeMonitorSession()
            }
        } catch (error: Throwable) {
            reportError("同步网卡模式变化", error)
        } finally {
            modeOperationInProgress = false
            syncNormalScanSelection()
        }
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

    private fun reportError(operation: String, error: Throwable) {
        Log.e(TAG, "$operation 失败：${error.message}", error)
        if (!stopped) onError(operation, error)
    }

    private fun publishWifiState(next: WifiState) {
        wifiState = next
        if (!stopped) onWifiStateChanged(next)
    }

    private fun publishSavedWifiList(next: SavedWifiList) {
        savedWifiList = next
        monitorModeController.savedNetworksChanged()
        if (!stopped) onSavedWifiListChanged(next)
    }

    private fun requireAndroidApi(): AndroidApi =
        androidApiProvider() ?: throw IllegalStateException("AndroidApi 尚未初始化")

    private fun execute(diagnosticLabel: String = "stateUpdate", block: () -> Unit) {
        if (stopped) return
        val queuedAt = SystemClock.elapsedRealtime()
        diagnosticSubmitted.incrementAndGet()
        val previousLog = diagnosticQueueLoggedAt.get()
        if (modeState.mode == WifiMode.MONITOR && queuedAt - previousLog >= 1000L &&
            diagnosticQueueLoggedAt.compareAndSet(previousLog, queuedAt)) {
            Log.d(TAG, "[MonitorDiagnostic] controllerQueue submitted=${diagnosticSubmitted.get()} " +
                "completed=${diagnosticCompleted.get()} running=$diagnosticRunning " +
                "runningAgeMs=${if (diagnosticRunning == "idle") -1L else queuedAt - diagnosticRunningAt}")
        }
        try {
            executor.execute {
                val started = SystemClock.elapsedRealtime()
                diagnosticRunning = diagnosticLabel
                diagnosticRunningAt = started
                if (modeState.mode == WifiMode.MONITOR && started - queuedAt >= 1000L) Log.d(TAG,
                    "[MonitorDiagnostic] controllerQueueDelayed operation=$diagnosticLabel waitMs=${started - queuedAt}")
                try {
                    if (!stopped) block()
                } finally {
                    diagnosticCompleted.incrementAndGet()
                    diagnosticRunning = "idle"
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (modeState.mode == WifiMode.MONITOR && elapsed >= 1000L) Log.d(TAG,
                        "[MonitorDiagnostic] controllerOperationSlow operation=$diagnosticLabel elapsedMs=$elapsed")
                }
            }
        } catch (_: RejectedExecutionException) {
            diagnosticCompleted.incrementAndGet()
            // stop() 与异步回调竞争时允许静默丢弃已失效任务。
        }
    }

    private companion object {
        const val TAG = "ServiceWifiListController"
        // 仅通过修改源码启用 monitor 空闲时的 wlan0 DOWN 省电功能。
        const val MONITOR_POWER_SAVING_ENABLED = false
        const val INTERFACE_MODE_REFRESH_INTERVAL_MS = 1_000L
        const val SCAN_START_CONFIRM_TIMEOUT_MS = 10_000L
        const val MONITOR_MODE_VERIFICATION_ERROR =
            "脚本执行完毕但系统没能进入监听模式。"
        const val MONITOR_EXIT_COMMAND = """if [ -w /sys/module/wlan/parameters/con_mode ] && { [ "$(cat /sys/module/wlan/parameters/con_mode)" = "4" ] || [ ! -e /sys/class/net/wlan0 ]; }; then
    stop wpa_supplicant
    stop vendor.wifi_hal_legacy
    stop wificond
    monitor_phy=$(basename "$(readlink /sys/class/net/wlan0/phy80211)")
    if [ ! -d "/sys/class/ieee80211/${'$'}monitor_phy" ]; then
        set -- /sys/class/ieee80211/*
        if [ "${'$'}#" -eq 1 ] && [ -d "${'$'}1" ]; then monitor_phy=$(basename "${'$'}1"); fi
    fi
    for interface in wlan0 wlan1 p2p0; do
        if ip link show "${'$'}interface" >/dev/null 2>&1; then
            ip link set "${'$'}interface" down || echo "Warning: failed to bring ${'$'}interface down; continuing Wi-Fi recovery" >&2
        fi
    done
    reset_result=0
    printf '0\n' > /sys/module/wlan/parameters/con_mode || reset_result=${'$'}?
    if [ ! -e /sys/class/net/wlan0 ] && [ -d "/sys/class/ieee80211/${'$'}monitor_phy" ]; then
        echo "Driver reset result=${'$'}reset_result; rebuilding wlan0 on ${'$'}monitor_phy"
        iw phy "${'$'}monitor_phy" interface add wlan0 type managed || echo "Warning: failed to rebuild wlan0; continuing Wi-Fi recovery" >&2
    fi
    if [ ! -e /sys/class/net/wlan0 ]; then
        echo "Driver reset did not recreate wlan0" >&2
    fi
else
    ip link set wlan0 down || echo "Warning: failed to bring wlan0 down; continuing Wi-Fi recovery" >&2
fi
iw dev wlan0 set type managed || echo "Warning: failed to set wlan0 managed; continuing Wi-Fi recovery" >&2
ip link set wlan0 up || echo "Warning: failed to bring wlan0 up; continuing Wi-Fi recovery" >&2
setprop ctl.restart wificond
setprop ctl.restart vendor.wifi_hal_legacy
start wificond
start vendor.wifi_hal_legacy
svc wifi enable"""
    }
}
