@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.wifilist

import android.content.Context
import android.net.Uri
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorPcapExportResult
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestOutcome
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestResult
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSourceState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiParcelTransport
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.IMainServiceCallback
import io.github.bszapp.wifitoolbox.tools.AndroidApiClient
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 侧只保存 Service 数据的只读镜像并转发用户命令。
 *
 * 所有错误都交给 ToolboxApp 的统一错误广播源；本控制器不再拥有独立扫描错误流。
 */
class WifiListController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val reportError: (
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String?,
    ) -> Unit,
) : IWifiListController {

    private val _state = MutableStateFlow<WifiState?>(null)
    override val state: StateFlow<WifiState?> = _state.asStateFlow()

    private val _savedWifiList = MutableStateFlow<SavedWifiList?>(null)
    override val savedWifiList: StateFlow<SavedWifiList?> = _savedWifiList.asStateFlow()

    private val _informationSourceState = MutableStateFlow<WifiInformationSourceState?>(null)
    override val informationSourceState: StateFlow<WifiInformationSourceState?> =
        _informationSourceState.asStateFlow()

    private val _monitorPcapExports = MutableSharedFlow<MonitorPcapExportResult>(
        extraBufferCapacity = 8,
    )
    override val monitorPcapExports: SharedFlow<MonitorPcapExportResult> =
        _monitorPcapExports.asSharedFlow()

    private val _monitorHandshakeTestResults = MutableSharedFlow<MonitorHandshakeTestResult>(
        extraBufferCapacity = 8,
    )
    override val monitorHandshakeTestResults: SharedFlow<MonitorHandshakeTestResult> =
        _monitorHandshakeTestResults.asSharedFlow()

    @Volatile
    private var registeredService: IMainService? = null

    @Volatile
    private var registeredBinder: IBinder? = null

    @Volatile
    private var registeredCallback: IMainServiceCallback? = null

    @Volatile
    private var androidApiClient: AndroidApiClient? = null

    @Volatile
    private var connectionGeneration = 0L

    private val connectionLock = Any()

    private data class DetachedConnection(
        val service: IMainService,
        val binder: IBinder,
        val callback: IMainServiceCallback,
    )

    private data class ConnectionPlan(
        val detached: DetachedConnection?,
        val generation: Long,
        val callback: IMainServiceCallback,
    )

    private data class ConnectionLease(
        val generation: Long,
        val service: IMainService,
        val binder: IBinder,
        val client: AndroidApiClient,
    )

    /**
     * 接收 ProcessLauncher 已确认的当前连接。
     *
     * 本方法只建立 App 侧 callback 订阅；注册结果不参与 Service 的 RUNNING 判定。
     */
    fun connect(
        service: IMainService,
        client: AndroidApiClient,
    ) {
        val binder = service.asBinder()
        val plan = synchronized(connectionLock) {
            if (registeredBinder === binder && binder.isBinderAlive) {
                androidApiClient = client
                return
            }

            val detached = currentConnectionLocked()
            connectionGeneration += 1
            val generation = connectionGeneration
            val callback = createCallback(
                generation = connectionGeneration,
                service = service,
            )

            registeredService = service
            registeredBinder = binder
            registeredCallback = callback
            androidApiClient = client
            ConnectionPlan(detached, generation, callback)
        }

        _state.value = null
        _savedWifiList.value = null
        _informationSourceState.value = null
        unregisterDetached(plan.detached, operation = "注销已替换的 Wi-Fi 数据回调")

        scope.launch(Dispatchers.IO) {
            if (!isCurrentConnection(plan.generation, plan.callback)) return@launch

            runCatching { service.registerCallback(plan.callback) }
                .onSuccess {
                    if (isCurrentConnection(plan.generation, plan.callback)) {
                        Log.d(TAG, "已注册 Wi-Fi 数据回调，generation=${plan.generation}")
                    } else {
                        unregisterDetached(
                            DetachedConnection(service, binder, plan.callback),
                            operation = "清理已失效的 Wi-Fi 数据回调",
                        )
                    }
                }
                .onFailure { error ->
                    if (error is DeadObjectException ||
                        !isCurrentConnection(plan.generation, plan.callback)
                    ) {
                        Log.d(TAG, "注册 Wi-Fi 数据回调时连接已失效")
                    } else {
                        report(
                            operation = "注册 Wi-Fi 数据回调",
                            error = error,
                        )
                    }
                }
        }
    }

    /**
     * ProcessLauncher 失去 Service 所有权时同步作废 App 侧引用。
     * 远端注销只做后台尽力清理，不阻塞 Service 关闭或 StartupState 更新。
     */
    fun disconnect() {
        val detached = synchronized(connectionLock) {
            val current = currentConnectionLocked()
            connectionGeneration += 1
            registeredService = null
            registeredBinder = null
            registeredCallback = null
            androidApiClient = null
            current
        }

        _state.value = null
        _savedWifiList.value = null
        _informationSourceState.value = null
        unregisterDetached(detached, operation = "注销 Wi-Fi 数据回调")
    }

    private fun createCallback(
        generation: Long,
        service: IMainService,
    ): IMainServiceCallback = object : IMainServiceCallback.Stub() {
        override fun onWifiStateChanged(
            snapshotGeneration: Long,
            chunkCount: Int,
            totalBytes: Int,
        ) {
            if (!isCurrentConnection(generation, this)) return
            val callback = this

            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    WifiParcelTransport.decodeWifiState(
                        generation = snapshotGeneration,
                        chunkCount = chunkCount,
                        totalBytes = totalBytes,
                    ) { chunkIndex ->
                        service.getWifiStateChunk(callback, snapshotGeneration, chunkIndex)
                    }
                }

                if (!isCurrentConnection(generation, callback)) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!isCurrentConnection(generation, callback)) return@withContext
                    result
                        .onSuccess { _state.value = it }
                        .onFailure {
                            report(
                                operation = "解码 WifiState",
                                error = it,
                            )
                        }
                }
                acknowledgeWifiState(service, callback, generation, snapshotGeneration)
            }
        }

        override fun onSavedWifiListChanged(
            snapshotGeneration: Long,
            chunkCount: Int,
            totalBytes: Int,
        ) {
            if (!isCurrentConnection(generation, this)) return
            val callback = this

            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    WifiParcelTransport.decodeSavedWifiList(
                        generation = snapshotGeneration,
                        chunkCount = chunkCount,
                        totalBytes = totalBytes,
                    ) { chunkIndex ->
                        service.getSavedWifiListChunk(callback, snapshotGeneration, chunkIndex)
                    }
                }

                if (!isCurrentConnection(generation, callback)) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!isCurrentConnection(generation, callback)) return@withContext
                    result
                        .onSuccess { _savedWifiList.value = it }
                        .onFailure {
                            report(
                                operation = "解码 SavedWifiList",
                                error = it,
                            )
                        }
                }
                acknowledgeSavedWifiList(service, callback, generation, snapshotGeneration)
            }
        }

        override fun onWifiInformationSourceStateChanged(
            snapshotGeneration: Long,
            chunkCount: Int,
            totalBytes: Int,
        ) {
            if (!isCurrentConnection(generation, this)) return
            val callback = this

            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    WifiParcelTransport.decodeWifiInformationSourceState(
                        generation = snapshotGeneration,
                        chunkCount = chunkCount,
                        totalBytes = totalBytes,
                    ) { chunkIndex ->
                        service.getWifiInformationSourceStateChunk(
                            callback,
                            snapshotGeneration,
                            chunkIndex,
                        )
                    }
                }

                if (!isCurrentConnection(generation, callback)) return@launch

                withContext(Dispatchers.Main.immediate) {
                    if (!isCurrentConnection(generation, callback)) return@withContext
                    result
                        .onSuccess { incoming ->
                            _informationSourceState.update { current ->
                                mergeInformationSourceState(current, incoming)
                            }
                        }
                        .onFailure {
                            report(
                                operation = "解码 Wi-Fi 信息源状态",
                                error = it,
                            )
                        }
                }
                acknowledgeWifiInformationSourceState(
                    service,
                    callback,
                    generation,
                    snapshotGeneration,
                )
            }
        }

        override fun onMonitorRecordedBytesChanged(recordedBytes: Long) {
            if (recordedBytes < 0L || !isCurrentConnection(generation, this)) return
            _informationSourceState.update { current ->
                val statistics = current?.monitorStatistics
                if (
                    current?.source != WifiInformationSource.MONITOR ||
                    current.initializing ||
                    statistics == null
                ) {
                    current
                } else {
                    current.copy(
                        monitorStatistics = statistics.copy(
                            recordedBytes = maxOf(statistics.recordedBytes, recordedBytes),
                        ),
                    )
                }
            }
        }

        override fun onMonitorPcapExported(
            requestId: String,
            path: String,
            fileName: String,
        ) {
            if (!isCurrentConnection(generation, this)) return
            val callback = this
            scope.launch(Dispatchers.Main.immediate) {
                if (!isCurrentConnection(generation, callback)) return@launch
                _monitorPcapExports.emit(
                    MonitorPcapExportResult(
                        requestId = requestId,
                        path = path,
                        fileName = fileName,
                    ),
                )
            }
        }

        override fun onServiceError(
            source: String,
            operation: String,
            message: String,
            details: String,
        ) {
            if (!isCurrentConnection(generation, this)) return
            reportError(
                source,
                operation,
                IllegalStateException(message),
                details,
            )
        }
    }

    override fun updateSavedNetworks() {
        callService("请求刷新已保存 Wi-Fi 列表") {
            it.refreshSavedWifiNetworks()
        }
    }

    override fun setInformationSource(source: WifiInformationSource) {
        val appDataDirectory = requireNotNull(context.filesDir.parentFile)
        val rootfs = File(appDataDirectory, "rootfs")
        val runtime = File(context.noBackupFilesDir, "rftool-runtime")
        val terminal = File(context.applicationInfo.nativeLibraryDir, "libterminal.so")
        callService("切换 Wi-Fi 信息源为 ${source.displayName}") { service ->
            service.setWifiInformationSource(
                source.wireValue,
                rootfs.absolutePath,
                runtime.absolutePath,
                terminal.absolutePath,
            )
        }
    }

    override fun enterMonitorMode(
        command: String,
        targetChannel: Int,
        targetFrequencyMhz: Int,//TODO:这啥玩意有用吗
    ) {
        val appDataDirectory = requireNotNull(context.filesDir.parentFile)
        val rootfs = File(appDataDirectory, "rootfs")
        val runtime = File(context.noBackupFilesDir, "rftool-runtime")
        val terminal = File(context.applicationInfo.nativeLibraryDir, "libterminal.so")
        callService("进入监听模式，信道 $targetChannel，${targetFrequencyMhz} MHz") { service ->
            service.enterMonitorMode(
                command,
                targetChannel,
                targetFrequencyMhz,
                rootfs.absolutePath,
                runtime.absolutePath,
                terminal.absolutePath,
            )
        }
    }

    override fun exportAllMonitorPcap(): String {
        val requestId = UUID.randomUUID().toString()
        callService("导出全部监听模式 PCAP") { service ->
            service.exportMonitorPcap(
                requestId,
                "all",
                "",
                "",
                emptyArray(),
            )
        }
        return requestId
    }

    override fun exportMonitorDevicePcap(
        bssid: String,
        deviceMac: String,
        subtypeIds: Set<String>,
    ): String {
        require(subtypeIds.isNotEmpty()) { "局部导出至少选择一种包类型" }
        val requestId = UUID.randomUUID().toString()
        callService("导出指定设备监听模式 PCAP") { service ->
            service.exportMonitorPcap(
                requestId,
                "filtered",
                bssid,
                deviceMac,
                subtypeIds.toTypedArray(),
            )
        }
        return requestId
    }

    override fun releaseMonitorPcapExport(path: String) {
        callService("释放监听模式 PCAP 临时文件") {
            it.releaseMonitorPcapExport(path)
        }
    }

    override fun saveMonitorPcapExport(path: String, destination: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                BufferedInputStream(FileInputStream(File(path))).use { input ->
                    val output = context.contentResolver.openOutputStream(destination, "wt")
                        ?: throw IOException("无法打开导出文件写入流")
                    BufferedOutputStream(output).use { bufferedOutput ->
                        val copiedBytes = input.copyTo(bufferedOutput)
                        if (copiedBytes <= 0L) {
                            throw IOException("导出的 PCAP 临时文件为空")
                        }
                    }
                }
            } catch (error: CancellationException) {
                runCatching { context.contentResolver.delete(destination, null, null) }
                throw error
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(destination, null, null) }
                report("保存监听模式 PCAP", error)
            } finally {
                releaseMonitorPcapExport(path)
            }
        }
    }

    override fun saveMonitorHc22000(content: String, destination: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val output = context.contentResolver.openOutputStream(destination, "wt")
                    ?: throw IOException("无法打开 HC22000 文件写入流")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(content.trimEnd('\r', '\n'))
                    writer.write("\n")
                }
            } catch (error: CancellationException) {
                runCatching { context.contentResolver.delete(destination, null, null) }
                throw error
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(destination, null, null) }
                report("保存 HC22000", error)
            }
        }
    }

    override fun exportMonitorHandshakePcap(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ): String {
        val requestId = UUID.randomUUID().toString()
        callService("导出握手包 PCAP") { service ->
            service.exportMonitorHandshakePcap(
                requestId,
                bssid,
                deviceMac,
                handshakeId,
            )
        }
        return requestId
    }

    override fun exportMonitorDisconnectionPcap(
        bssid: String,
        deviceMac: String,
        disconnectionId: String,
    ): String {
        val requestId = UUID.randomUUID().toString()
        callService("导出断开事件 PCAP") { service ->
            service.exportMonitorDisconnectionPcap(
                requestId,
                bssid,
                deviceMac,
                disconnectionId,
            )
        }
        return requestId
    }

    override fun testMonitorHandshake(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
        password: String,
    ): String {
        val requestId = UUID.randomUUID().toString()
        scope.launch(Dispatchers.Default) {
            val outcome = runCatching {
                val record = _informationSourceState.value
                    ?.monitorStatistics
                    ?.accessPoints
                    ?.firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }
                    ?.devices
                    ?.firstOrNull { it.mac.equals(deviceMac, ignoreCase = true) }
                    ?.handshakes
                    ?.firstOrNull { it.id == handshakeId }
                    ?: error("找不到指定的握手记录")
                val hc22000 = record.hc22000
                    ?.takeIf(String::isNotBlank)
                    ?: error("该握手记录缺少可校验的 HC22000 数据")
                if (Hc22000Validator.validate(hc22000, password)) {
                    MonitorHandshakeTestOutcome.MATCHED
                } else {
                    MonitorHandshakeTestOutcome.NOT_MATCHED
                }
            }.getOrElse { error ->
                report("校验 WPA/WPA2 握手包", error)
                MonitorHandshakeTestOutcome.FAILED
            }
            _monitorHandshakeTestResults.emit(
                MonitorHandshakeTestResult(requestId = requestId, outcome = outcome),
            )
        }
        return requestId
    }

    override suspend fun saveWifiNetwork(ssid: String, password: String): Int =
        withContext(Dispatchers.IO) {
            val lease = currentLease()
                ?: throw IllegalStateException("service 未连接").also { error ->
                    report("保存 Wi-Fi 网络", error)
                }
            try {
                check(isCurrentLease(lease)) { "service 连接已经失效" }
                lease.service.saveWifiNetwork(ssid, password)
            } catch (error: Throwable) {
                report("保存 Wi-Fi 网络：$ssid", error)
                throw error
            }
        }

    /**
     * 同步等待 Service 的 WifiScanner.onSuccess/onFailure。
     * 调用线程由 ViewModel 决定；失败在这里进入 App 的统一错误广播后继续抛给调用者。
     */
    @Throws(Exception::class)
    override fun startScan() {
        try {
            val lease = currentLease()
                ?: throw IllegalStateException("service 未连接")

            Log.d(TAG, "向 Service 同步请求启动 Wi-Fi 扫描")
            val started = lease.service.startWifiScan()
            if (!started) {
                throw IllegalStateException("Service 未确认扫描已开始")
            }
            Log.d(TAG, "Service 已确认 Wi-Fi 扫描开始")
        } catch (error: Throwable) {
            report(
                operation = "发起 Wi-Fi 扫描",
                error = error,
            )
            throw error
        }
    }

    /** 单次系统命令只走 AndroidApi，不要求 Service 顺带刷新 WifiState。 */
    override fun setWifiEnabled(enabled: Boolean) {
        val lease = currentLease()
        if (lease == null) {
            report(
                operation = "设置 Wi-Fi 开关为 $enabled",
                error = IllegalStateException("AndroidApiClient 不可用"),
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            if (!isCurrentLease(lease)) return@launch

            // AndroidApiClient 已负责把所有异常送入统一错误广播；这里仅终止协程异常传播。
            runCatching { lease.client.setWifiEnabled(enabled) }
                .onFailure {
                    Log.e(TAG, "设置 Wi-Fi 开关失败：${it.message}", it)
                }
        }
    }

    /** 修改系统配置成功后，由 App 单独请求 Service 刷新 SavedWifiList。 */
    override fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) {
        val lease = currentLease()
        if (lease == null) {
            report(
                operation = "更新 Wi-Fi 配置 networkId=$networkId",
                error = IllegalStateException("AndroidApiClient 不可用"),
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            if (!isCurrentLease(lease)) return@launch

            val updated = runCatching {
                lease.client.updateWifiConfig(networkId, patch)
            }.onFailure {
                // AndroidApiClient 已广播详细错误。
                Log.e(TAG, "更新 Wi-Fi 配置失败：${it.message}", it)
            }.isSuccess

            if (updated) {
                callServiceNow("请求刷新已保存 Wi-Fi 列表", lease) {
                    it.refreshSavedWifiNetworks()
                }
            }
        }
    }

    override fun disconnectCurrentNetwork(networkId: Int) {
        val lease = currentLease()
        if (lease == null) {
            report(
                operation = "断开当前 Wi-Fi networkId=$networkId",
                error = IllegalStateException("AndroidApiClient 不可用"),
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            if (!isCurrentLease(lease)) return@launch

            val disconnected = runCatching {
                lease.client.disconnectCurrentNetwork(networkId)
            }.onFailure {
                // AndroidApiClient 已广播详细错误。
                Log.e(TAG, "断开当前 Wi-Fi 失败：${it.message}", it)
            }.isSuccess

            if (disconnected) {
                callServiceNow("请求刷新已保存 Wi-Fi 列表", lease) {
                    it.refreshSavedWifiNetworks()
                }
            }
        }
    }

    private fun acknowledgeWifiState(
        service: IMainService,
        callback: IMainServiceCallback,
        connectionGeneration: Long,
        snapshotGeneration: Long,
    ) {
        if (!isCurrentConnection(connectionGeneration, callback)) return
        runCatching { service.acknowledgeWifiState(callback, snapshotGeneration) }
            .onFailure { error ->
                if (error is DeadObjectException ||
                    !isCurrentConnection(connectionGeneration, callback)
                ) {
                    Log.d(TAG, "确认 WifiState 时连接已失效")
                } else {
                    report(
                        operation = "确认 WifiState 接收完成",
                        error = error,
                    )
                }
            }
    }

    private fun mergeInformationSourceState(
        current: WifiInformationSourceState?,
        incoming: WifiInformationSourceState,
    ): WifiInformationSourceState {
        val currentStatistics = current?.monitorStatistics
        val incomingStatistics = incoming.monitorStatistics
        return if (
            current?.source == WifiInformationSource.MONITOR &&
            incoming.source == WifiInformationSource.MONITOR &&
            !current.initializing &&
            !incoming.initializing &&
            currentStatistics != null &&
            incomingStatistics != null
        ) {
            incoming.copy(
                monitorStatistics = incomingStatistics.copy(
                    recordedBytes = maxOf(
                        currentStatistics.recordedBytes,
                        incomingStatistics.recordedBytes,
                    ),
                ),
            )
        } else {
            incoming
        }
    }

    private fun acknowledgeSavedWifiList(
        service: IMainService,
        callback: IMainServiceCallback,
        connectionGeneration: Long,
        snapshotGeneration: Long,
    ) {
        if (!isCurrentConnection(connectionGeneration, callback)) return
        runCatching { service.acknowledgeSavedWifiList(callback, snapshotGeneration) }
            .onFailure { error ->
                if (error is DeadObjectException ||
                    !isCurrentConnection(connectionGeneration, callback)
                ) {
                    Log.d(TAG, "确认 SavedWifiList 时连接已失效")
                } else {
                    report(
                        operation = "确认 SavedWifiList 接收完成",
                        error = error,
                    )
                }
            }
    }

    private fun acknowledgeWifiInformationSourceState(
        service: IMainService,
        callback: IMainServiceCallback,
        connectionGeneration: Long,
        snapshotGeneration: Long,
    ) {
        if (!isCurrentConnection(connectionGeneration, callback)) return
        runCatching {
            service.acknowledgeWifiInformationSourceState(callback, snapshotGeneration)
        }.onFailure { error ->
            if (error is DeadObjectException ||
                !isCurrentConnection(connectionGeneration, callback)
            ) {
                Log.d(TAG, "确认 Wi-Fi 信息源状态时连接已失效")
            } else {
                report(
                    operation = "确认 Wi-Fi 信息源状态接收完成",
                    error = error,
                )
            }
        }
    }

    private fun callService(
        operation: String,
        block: (IMainService) -> Unit,
    ) {
        val lease = currentLease()
        if (lease == null) {
            report(
                operation = operation,
                error = IllegalStateException("service 未连接"),
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            callServiceNow(operation, lease, block)
        }
    }

    private fun callServiceNow(
        operation: String,
        lease: ConnectionLease,
        block: (IMainService) -> Unit,
    ) {
        if (!isCurrentLease(lease)) return

        runCatching { block(lease.service) }
            .onFailure { error ->
                if (error is DeadObjectException && !isCurrentLease(lease)) {
                    Log.d(TAG, "$operation 时连接已失效")
                } else {
                    report(
                        operation = operation,
                        error = error,
                    )
                }
            }
    }

    private fun currentConnectionLocked(): DetachedConnection? {
        val service = registeredService ?: return null
        val binder = registeredBinder ?: return null
        val callback = registeredCallback ?: return null
        return DetachedConnection(service, binder, callback)
    }

    private fun unregisterDetached(
        detached: DetachedConnection?,
        operation: String,
    ) {
        if (detached == null || !detached.binder.isBinderAlive) return

        scope.launch(Dispatchers.IO) {
            runCatching {
                detached.service.unregisterCallback(detached.callback)
            }.onFailure { error ->
                if (error is DeadObjectException || !detached.binder.isBinderAlive) {
                    Log.d(TAG, "$operation 时旧 Service 已失效")
                } else {
                    report(
                        operation = operation,
                        error = error,
                    )
                }
            }
        }
    }

    private fun currentLease(): ConnectionLease? = synchronized(connectionLock) {
        val service = registeredService ?: return@synchronized null
        val binder = registeredBinder?.takeIf { it.isBinderAlive } ?: return@synchronized null
        val client = androidApiClient ?: return@synchronized null
        ConnectionLease(connectionGeneration, service, binder, client)
    }

    private fun isCurrentConnection(
        generation: Long,
        callback: IMainServiceCallback,
    ): Boolean = synchronized(connectionLock) {
        generation == connectionGeneration &&
            registeredCallback === callback &&
            registeredBinder?.isBinderAlive == true
    }

    private fun isCurrentLease(lease: ConnectionLease): Boolean = synchronized(connectionLock) {
        lease.generation == connectionGeneration &&
            registeredService === lease.service &&
            registeredBinder === lease.binder &&
            androidApiClient === lease.client &&
            lease.binder.isBinderAlive
    }

    private fun report(
        operation: String,
        error: Throwable,
        remoteDetails: String? = null,
    ) {
        reportError(
            "App.WifiListController",
            operation,
            error,
            remoteDetails,
        )
    }

    private companion object {
        const val TAG = "WifiListController"
    }
}
