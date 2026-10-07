@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.service

import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.SystemScanData
import io.github.bszapp.wifitoolbox.contract.wifilist.UnderlyingScanData
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiListDataSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

/**
 * 普通模式扫描资源的唯一拥有者。
 *
 * selection 是生命周期输入。每次替换先取消并完整释放旧作用域，再建立新作用域。
 * 系统开关读取只存在于 System 作用域；终端/FIFO 只存在于 Underlying 作用域。
 * 结果和回调通道属于单次作用域，不能跨来源复用。
 */
internal class NormalWifiScanner(
    private val androidApi: () -> AndroidApi,
    private val hybridScanner: HybridWifiScanner,
    private val parseUnderlyingResults: (JSONObject) -> List<ScanResult>,
    private val onState: (WifiState, (() -> Unit) -> Unit) -> Unit,
    private val onSavedNetworksChanged: () -> Unit,
    private val onError: (String, Throwable) -> Unit,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selection = MutableStateFlow(Selection(0, null))
    private val active = MutableStateFlow<Session?>(null)
    private var requestedSource = WifiListDataSource.SYSTEM
    private var enabled = false
    private var containerBlocked = false
    private var closed = false
    @Volatile private var cleanupFailed = false
    private val cleanupFailure = MutableStateFlow<Throwable?>(null)
    private var systemSnapshot: SystemScanData? = null
    private var underlyingSnapshot: UnderlyingScanData? = null

    private val lifecycle = scope.launch {
        try {
            selection.collectLatest { selected ->
                check(!cleanupFailed) { "旧扫描器资源释放失败，停止创建新作用域" }
                val source = selected.source ?: return@collectLatest
                val session = synchronized(lock) {
                    if (closed || selection.value !== selected) null
                    else Session(selected).also { active.value = it }
                } ?: return@collectLatest
                Log.d("NormalWifiScanner", "创建扫描作用域：source=$source id=${selected.id}")
                try {
                    coroutineScope {
                        try {
                            when (source) {
                                WifiListDataSource.SYSTEM -> runSystem(session)
                                WifiListDataSource.UNDERLYING -> runUnderlying(session)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            if (cleanupFailed) throw error
                            session.ready = false
                            session.pending?.completeExceptionally(error)
                            if (source == WifiListDataSource.UNDERLYING) {
                                val fallback = synchronized(lock) {
                                    if (!session.isCurrent()) false else {
                                        requestedSource = WifiListDataSource.SYSTEM
                                        updateSelectionLocked()
                                        true
                                    }
                                }
                                if (fallback) onError("运行${source.displayName}扫描器", error)
                            } else {
                                session.publishStoppedSnapshot()
                                if (session.isCurrent()) onError("运行${source.displayName}扫描器", error)
                                awaitCancellation()
                            }
                        }
                    }
                } finally {
                    session.ready = false
                    session.events.cancel()
                    session.pending?.completeExceptionally(IOException("扫描作用域已结束"))
                    // runSystem/runUnderlying 的 finally 已完成真实资源释放。
                    if (!cleanupFailed) {
                        Log.d("NormalWifiScanner", "扫描资源已释放：source=$source id=${selected.id}")
                        active.value = null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // 清理失败终止调配，绝不启动下一个来源并留下旧资源。
            synchronized(lock) { cleanupFailed = true }
            cleanupFailure.value = error
            onError("释放扫描器资源", error)
        }
    }

    /** 调用方只声明当前模式要求的来源，不在这里编排启动和停止命令。 */
    fun select(source: WifiListDataSource) = synchronized(lock) {
        if (closed) return@synchronized
        requestedSource = source
        updateSelectionLocked()
    }

    /** 模式生命周期只控制是否启用，不使用最后发布的来源覆盖正在处理的请求。 */
    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        enabled = value
        updateSelectionLocked()
    }

    private fun updateSelectionLocked() {
        val desired = requestedSource.takeIf {
            enabled && !(containerBlocked && it == WifiListDataSource.UNDERLYING)
        }
        if (selection.value.source == desired) return
        // 切换期间保留已发布的数据，新来源准备完成后再发布结果。
        selection.value = Selection(selection.value.id + 1, desired)
    }

    /** 用于网卡操作之前的释放屏障；不会提前把“已取消”当作“已退出”。 */
    fun awaitInactive() {
        check(selection.value.source == null) { "扫描来源仍处于活动选择状态" }
        runBlocking {
            combine(active, cleanupFailure) { session, failure ->
                if (failure != null) throw IOException("旧扫描器资源未能释放", failure)
                session == null
            }.first { it }
        }
        check(!cleanupFailed) { "旧扫描器资源未能释放" }
    }

    fun beforeContainerDelete() {
        val mustWait = synchronized(lock) {
            containerBlocked = true
            updateSelectionLocked()
            requestedSource == WifiListDataSource.UNDERLYING ||
                active.value?.selection?.source == WifiListDataSource.UNDERLYING
        }
        if (mustWait) runBlocking {
            combine(active, cleanupFailure) { session, failure ->
                if (failure != null) throw IOException("底层扫描器资源未能释放", failure)
                session?.selection?.source != WifiListDataSource.UNDERLYING
            }.first { it }
        }
    }

    fun afterContainerOperation() = synchronized(lock) {
        containerBlocked = false
        if (!closed) updateSelectionLocked()
    }

    fun startScan() {
        val confirmation = CompletableFuture<Unit>()
        synchronized(lock) {
            val session = active.value
            check(session != null && session.isCurrent() && session.ready) { "所选扫描器尚未就绪" }
            check(session.events.trySend(Event.Scan(confirmation)).isSuccess) { "扫描器已经结束" }
        }
        try {
            confirmation.get(10, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: RuntimeException(error.cause))
        } catch (error: Exception) {
            confirmation.completeExceptionally(error)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
    }

    fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                enabled = false
                selection.value = Selection(selection.value.id + 1, null)
            }
        }
        awaitInactive()
        runBlocking { lifecycle.cancel(); lifecycle.join() }
        scope.cancel()
    }

    private suspend fun runSystem(session: Session) = coroutineScope {
        val api = androidApi()
        val scanner = WifiScannerClient(api.callerPackage)
        var request: WifiScannerClient.ScanRequest? = null
        var requestId = 0L
        var acceptedAt: Long? = null
        var resultsReceived = false
        var primaryStatus: Int? = null
        var snapshot = systemSnapshot.let {
            if (it is SystemScanData.Enabled) it.copy(isScanning = false) else it
        }
        val monitor = eventMonitor(session)
        fun publish(next: SystemScanData?) {
            session.checkCurrent()
            snapshot = next
            systemSnapshot = next
            if (session.ready) session.publish(WifiState.System(next))
        }
        fun readEnabled(scanning: Boolean) {
            val previous = (snapshot as? SystemScanData.Enabled)?.connection
            val results = api.getScanResults().filter { !it.BSSID.isNullOrBlank() }
            val connection = readConnection(session, primaryStatus, previous)
            publish(SystemScanData.Enabled(results, scanning, connection))
            session.notifySavedNetworks()
        }
        fun finishRequest() {
            request?.let(scanner::stopScan)
            request = null
            acceptedAt = null
            session.pending = null
            requestId++
        }
        fun refresh() {
            session.checkCurrent()
            if (!api.isWifiEnabled()) {
                session.pending?.completeExceptionally(IOException("系统 Wi-Fi 已关闭"))
                finishRequest()
                publish(SystemScanData.Disabled)
                session.notifySavedNetworks()
            } else readEnabled(acceptedAt != null)
        }
        val ticker = launch {
            while (true) { delay(250); session.send(Event.Tick) }
        }
        var nextSwitchRead = SystemClock.elapsedRealtime() + 1_000
        try {
            monitor.start()
            val initialData = try { refresh(); snapshot }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (session.isCurrent()) onError("读取系统 Wi-Fi 数据", error)
                null
            }
            session.ready = true
            initialData?.let {
                session.publish(WifiState.System(it))
                session.notifySavedNetworks()
            }
            for (event in session.events) {
                session.checkCurrent()
                try {
                    when (event) {
                        is Event.Scan -> {
                            if (event.confirmation.isDone) continue
                            if (request != null || session.pending != null) {
                                event.confirmation.completeExceptionally(IOException("已有扫描请求正在运行")); continue
                            }
                            if (snapshot !is SystemScanData.Enabled) {
                                event.confirmation.completeExceptionally(IOException("系统 Wi-Fi 未开启")); continue
                            }
                            session.pending = event.confirmation
                            resultsReceived = false
                            val id = ++requestId
                            try {
                                request = scanner.startScan(Executor { it.run() }, object : WifiScannerClient.ScanListener {
                                    override fun onSuccess() { session.send(Event.Accepted(id)) }
                                    override fun onFailure(reason: Int, description: String) {
                                        session.send(Event.Rejected(id, IOException("系统扫描被拒绝：$reason $description")))
                                    }
                                    override fun onResults() { session.send(Event.Results(id)) }
                                })
                            } catch (error: Throwable) {
                                session.pending?.completeExceptionally(error)
                                session.pending = null
                                throw error
                            }
                        }
                        is Event.Accepted -> if (event.id == requestId) {
                            if (session.pending?.isDone != false) { finishRequest(); continue }
                            acceptedAt = SystemClock.elapsedRealtime()
                            val current = snapshot as? SystemScanData.Enabled ?: error("系统 Wi-Fi 已关闭")
                            publish(current.copy(isScanning = true))
                            session.pending?.complete(Unit)
                        }
                        is Event.Results -> if (event.id == requestId) resultsReceived = true
                        is Event.Rejected -> if (event.id == requestId) {
                            session.pending?.completeExceptionally(event.error)
                            finishRequest()
                            (snapshot as? SystemScanData.Enabled)?.let { publish(it.copy(isScanning = false)) }
                            throw event.error
                        }
                        is Event.Network -> {
                            if (event.role == 1) {
                                primaryStatus = event.status
                                (snapshot as? SystemScanData.Enabled)?.let {
                                    publish(it.copy(connection = readConnection(session, primaryStatus, it.connection)))
                                }
                            }
                        }
                        Event.RefreshSwitch -> refresh()
                        Event.Tick -> {
                            val now = SystemClock.elapsedRealtime()
                            if (now >= nextSwitchRead) {
                                nextSwitchRead = now + 1_000
                                session.checkCurrent()
                                val enabled = api.isWifiEnabled()
                                if (snapshot == null || enabled != (snapshot is SystemScanData.Enabled)) refresh()
                            }
                            if (acceptedAt != null) {
                                readEnabled(true)
                                if (resultsReceived && now - acceptedAt!! >= 3_000) {
                                    finishRequest()
                                    (snapshot as? SystemScanData.Enabled)?.let { publish(it.copy(isScanning = false)) }
                                }
                            } else if (session.pending?.isCompletedExceptionally == true) finishRequest()
                        }
                        else -> Unit
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (session.isCurrent()) onError("系统 Wi-Fi 扫描", error) }
            }
        } finally {
            session.ready = false
            withContext(NonCancellable) {
                ticker.cancel(); ticker.join()
                var failure: Throwable? = null
                runCatching { monitor.stop() }.onFailure { failure = it }
                runCatching { finishRequest() }.onFailure { error ->
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
                failure?.let { error ->
                    markCleanupFailed(error)
                    throw error
                }
            }
        }
    }

    private suspend fun runUnderlying(session: Session) = coroutineScope {
        var snapshot = underlyingSnapshot?.copy(isScanning = false)
        var primaryStatus: Int? = null
        val monitor = eventMonitor(session)
        fun publish(next: UnderlyingScanData?) {
            session.checkCurrent()
            snapshot = next
            underlyingSnapshot = next
            session.publish(WifiState.Underlying(next))
        }
        hybridScanner.setUnexpectedExitHandler { code -> session.send(Event.Exited(code)) }
        try {
            try { withTimeout(20_000) { hybridScanner.start().awaitCompletion() } }
            catch (error: TimeoutCancellationException) { throw IOException("等待底层扫描终端就绪超时", error) }
            session.checkCurrent()
            monitor.start()
            //TODO:这里需要给scan.py添加仅读取功能，我不希望首次扫描。
            try { withTimeout(10_000) {
                hybridScanner.readPreviousResults { session.send(Event.Payload(it)) }.awaitCompletion()
            } } catch (error: TimeoutCancellationException) { throw IOException("读取底层已有扫描结果超时", error) }
            session.ready = true
            for (event in session.events) {
                session.checkCurrent()
                when (event) {
                    is Event.Scan -> {
                        if (event.confirmation.isDone) continue
                        if (session.pending != null) {
                            event.confirmation.completeExceptionally(IOException("已有底层扫描正在运行")); continue
                        }
                        session.pending = event.confirmation
                        try {
                            hybridScanner.runWifiScan(
                                onMessage = { session.send(Event.Payload(it)) },
                                onFinished = { session.send(Event.Finished(it)) },
                            ).whenComplete { _, failure ->
                                if (!session.isCurrent()) return@whenComplete
                                if (failure == null) event.confirmation.complete(Unit)
                                else {
                                    event.confirmation.completeExceptionally(failure)
                                    session.send(Event.Failed(failure))
                                }
                            }
                        } catch (error: Throwable) {
                            session.pending = null
                            event.confirmation.completeExceptionally(error)
                            throw error
                        }
                    }
                    is Event.Payload -> {
                        if (event.value.optString("action") != "update_wifi_state") continue
                        val data = event.value.getJSONObject("state")
                        if (data.getString("type") == "enabled") {
                            publish(UnderlyingScanData(
                                parseUnderlyingResults(data), data.optBoolean("scanning"),
                                readConnection(session, primaryStatus, snapshot?.connection),
                            ))
                            session.notifySavedNetworks()
                        } else {
                            throw IOException(data.optString("message", "扫描失败"))
                        }
                    }
                    is Event.Finished -> {
                        if (event.code != 0) throw IOException("扫描脚本退出码 ${event.code}")
                        session.pending?.completeExceptionally(IOException("扫描未确认即退出：${event.code}"))
                        session.pending = null
                        snapshot?.let { publish(it.copy(isScanning = false)) }
                    }
                    is Event.Failed -> throw event.error
                    is Event.Exited -> throw IOException("底层扫描终端异常退出，退出码 ${event.code}")
                    is Event.Network -> if (event.role == 1) {
                        primaryStatus = event.status
                        snapshot?.let { publish(it.copy(connection = readConnection(session, primaryStatus, it.connection))) }
                    }
                    else -> Unit
                }
            }
        } finally {
            session.ready = false
            withContext(NonCancellable) {
                var failure: Throwable? = null
                runCatching { monitor.stop() }.onFailure { failure = it }
                hybridScanner.setUnexpectedExitHandler(null)
                runCatching { hybridScanner.stop() }.onFailure { error ->
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
                failure?.let { error ->
                    markCleanupFailed(error)
                    throw error
                }
            }
        }
    }

    private fun eventMonitor(session: Session) = ServiceWifiBroadcastLogger(
        // 仅系统作用域会响应开关唤醒；底层作用域只接收既有的连接信息变化。
        onWifiStateChanged = { if (session.selection.source == WifiListDataSource.SYSTEM) session.send(Event.RefreshSwitch) },
        onWifiNetworkStateChanged = { role, status -> session.send(Event.Network(role, status)) },
        onError = { operation, error -> if (session.isCurrent()) onError(operation, error) },
    )

    private fun markCleanupFailed(error: Throwable) {
        cleanupFailed = true
        cleanupFailure.value = error
    }

    private fun readConnection(session: Session, status: Int?, previous: WifiInfo?): WifiInfo? {
        session.checkCurrent()
        if (status != null && status != 6) return null
        return try {
            androidApi().getConnectionInfo().takeIf {
                it.networkId >= 0 && it.supplicantState == SupplicantState.COMPLETED &&
                    !it.ssid.isNullOrBlank() && it.ssid != WifiManager.UNKNOWN_SSID &&
                    !it.bssid.isNullOrBlank() && it.bssid != "02:00:00:00:00:00"
            }
        } catch (error: Throwable) {
            if (session.isCurrent()) onError("读取当前 Wi-Fi 连接", error)
            previous
        }
    }

    private data class Selection(val id: Long, val source: WifiListDataSource?)

    private inner class Session(val selection: Selection) {
        @Volatile var ready = false
        private var hasPublishedState = false
        var pending: CompletableFuture<Unit>? = null
        val events = Channel<Event>(Channel.UNLIMITED, onUndeliveredElement = {
            if (it is Event.Scan) it.confirmation.completeExceptionally(IOException("扫描作用域已结束"))
        })
        fun isCurrent(): Boolean = synchronized(lock) {
            !closed && this@NormalWifiScanner.selection.value === selection && active.value === this
        }
        fun checkCurrent() { if (!isCurrent()) throw CancellationException("扫描作用域已替换") }
        fun send(event: Event) { if (isCurrent()) events.trySend(event) }
        fun publish(state: WifiState) = synchronized(lock) {
            checkCurrent()
            hasPublishedState = true
            onState(state, ::runIfCurrent)
        }
        private fun runIfCurrent(block: () -> Unit): Unit = synchronized(lock) {
            if (isCurrent()) block()
        }
        fun notifySavedNetworks() {
            checkCurrent()
            if (ready) onSavedNetworksChanged()
        }
        fun publishStoppedSnapshot() {
            if (!hasPublishedState || !isCurrent()) return
            when (selection.source) {
                WifiListDataSource.SYSTEM -> {
                    systemSnapshot = systemSnapshot.let { if (it is SystemScanData.Enabled) it.copy(isScanning = false) else it }
                    publish(WifiState.System(systemSnapshot))
                }
                WifiListDataSource.UNDERLYING -> {
                    underlyingSnapshot = underlyingSnapshot?.copy(isScanning = false)
                    publish(WifiState.Underlying(underlyingSnapshot))
                }
                null -> Unit
            }
        }
    }

    private sealed interface Event {
        data class Scan(val confirmation: CompletableFuture<Unit>) : Event
        data class Accepted(val id: Long) : Event
        data class Results(val id: Long) : Event
        data class Rejected(val id: Long, val error: Throwable) : Event
        data class Network(val role: Int, val status: Int) : Event
        data class Payload(val value: JSONObject) : Event
        data class Finished(val code: Int) : Event
        data class Exited(val code: Int) : Event
        data class Failed(val error: Throwable) : Event
        data object Tick : Event
        data object RefreshSwitch : Event
    }
}

private suspend fun CompletableFuture<Unit>.awaitCompletion() = suspendCancellableCoroutine<Unit> { continuation ->
    whenComplete { _, error ->
        if (error == null) continuation.resume(Unit)
        else continuation.resumeWithException(error)
    }
}
