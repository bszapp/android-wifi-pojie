@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.service

import android.net.wifi.ScanResult
import android.os.SystemClock
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
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
    private val onWifiStateChanged: (WifiState) -> Unit,
    private val onSavedWifiListChanged: (SavedWifiList) -> Unit,
    private val onInformationSourceStateChanged: (WifiInformationSourceState) -> Unit,
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

    @Volatile
    private var informationSourceState = WifiInformationSourceState(
        source = WifiInformationSource.SYSTEM,
        initializing = false,
    )

    fun getWifiState(): WifiState? = wifiState

    fun getSavedWifiList(): SavedWifiList? = savedWifiList

    fun getInformationSourceState(): WifiInformationSourceState = informationSourceState

    fun initialize() {
        execute {
            if (initialized) return@execute
            initialized = true
            Log.d(TAG, "初始化 Wi-Fi 数据")
            refreshWifiDataInternal()
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
            if (informationSourceState.source == WifiInformationSource.HYBRID) {
                Log.d(TAG, "混合模式收到 Wi-Fi 状态事件，仅刷新已保存网络")
                refreshSavedNetworksInternal()
            } else {
                Log.d(TAG, "检测到 Wi-Fi 状态可能变化，刷新 Service 数据")
                refreshWifiDataInternal()
            }
        }
    }

    fun setInformationSource(
        source: WifiInformationSource,
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) {
        execute {
            val current = informationSourceState
            if (current.source == source) return@execute

            val generation = ++informationSourceGeneration
            cancelScanInternal(publishChange = false)
            publishInformationSourceState(
                WifiInformationSourceState(source = source, initializing = true),
            )
            when (source) {
                WifiInformationSource.SYSTEM -> switchToSystemSource(generation)
                WifiInformationSource.HYBRID -> switchToHybridSource(
                    generation = generation,
                    rootfsPath = rootfsPath,
                    runtimePath = runtimePath,
                    terminalPath = terminalPath,
                )
            }
        }
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
        publishInformationSourceState(
            WifiInformationSourceState(source = source, initializing = false),
        )
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
            Log.d(TAG, "停止 Wi-Fi 控制器")
            cancelScanInternal(publishChange = false)
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
            if (!api.isWifiEnabledDirect()) {
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

    /**
     * 唯一允许读取扫描结果的入口。
     * 每次读取并发布扫描结果后，必须在同一串行任务中读取并发布 SavedWifiList。
     */
    private fun publishEnabledWifiDataAndSavedList(
        api: AndroidApi,
        isScanning: Boolean,
    ) {
        val scanResults = readScanResults(api)
        publishWifiState(
            WifiState.Data.Enabled(
                scanResults = scanResults,
                isScanning = isScanning,
            ),
        )
        Log.d(
            TAG,
            "已更新 WifiState：Enabled scan=${scanResults.size} scanning=$isScanning",
        )
        refreshSavedNetworksInternal()
    }

    private fun refreshSavedNetworksInternal() {
        try {
            val value = SavedWifiList(
                networks = requireAndroidApi().getSavedWifiListDirect(),
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
                "enabled" -> publishWifiState(
                    WifiState.Data.Enabled(
                        scanResults = parseHybridScanResults(state.getJSONArray("wifilist")),
                        isScanning = state.optBoolean("scanning", false),
                    ),
                )
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
                    ScanResult().apply {//TODO:报错啦：Call requires API level 30 (current min is 24): android.net.wifi.ScanResult()
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
        if (!api.isWifiEnabledDirect()) {
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
        api.getScanResultsDirect().filterIndexed { index, result ->
            val keep = !result.BSSID.isNullOrBlank()
            if (!keep) Log.w(TAG, "丢弃第 $index 项扫描结果：BSSID 为空")
            keep
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
        const val SCAN_START_CONFIRM_TIMEOUT_MS = 10_000L
    }
}
