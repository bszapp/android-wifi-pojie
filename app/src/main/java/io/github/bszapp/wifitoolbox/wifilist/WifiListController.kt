@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.wifilist

import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiParcelTransport
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.IMainServiceCallback
import io.github.bszapp.wifitoolbox.tools.AndroidApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 侧只保存 Service 数据的只读镜像并转发用户命令。
 *
 * 所有错误都交给 ToolboxApp 的统一错误广播源；本控制器不再拥有独立扫描错误流。
 */
class WifiListController(
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
        unregisterDetached(detached, operation = "注销 Wi-Fi 数据回调")
    }

    private fun createCallback(
        generation: Long,
        service: IMainService,
    ): IMainServiceCallback = object : IMainServiceCallback.Stub() {
        override fun onWifiStateChanged(payload: ParcelFileDescriptor) {
            if (!isCurrentConnection(generation, this)) {
                runCatching { payload.close() }
                return
            }
            val callback = this

            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    WifiParcelTransport.decodeWifiState(payload)
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
                acknowledgeWifiState(service, callback, generation)
            }
        }

        override fun onSavedWifiListChanged(payload: ParcelFileDescriptor) {
            if (!isCurrentConnection(generation, this)) {
                runCatching { payload.close() }
                return
            }
            val callback = this

            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    WifiParcelTransport.decodeSavedWifiList(payload)
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
                acknowledgeSavedWifiList(service, callback, generation)
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

    private fun acknowledgeWifiState(
        service: IMainService,
        callback: IMainServiceCallback,
        generation: Long,
    ) {
        if (!isCurrentConnection(generation, callback)) return
        runCatching { service.acknowledgeWifiState(callback) }
            .onFailure { error ->
                if (error is DeadObjectException || !isCurrentConnection(generation, callback)) {
                    Log.d(TAG, "确认 WifiState 时连接已失效")
                } else {
                    report(
                        operation = "确认 WifiState 接收完成",
                        error = error,
                    )
                }
            }
    }

    private fun acknowledgeSavedWifiList(
        service: IMainService,
        callback: IMainServiceCallback,
        generation: Long,
    ) {
        if (!isCurrentConnection(generation, callback)) return
        runCatching { service.acknowledgeSavedWifiList(callback) }
            .onFailure { error ->
                if (error is DeadObjectException || !isCurrentConnection(generation, callback)) {
                    Log.d(TAG, "确认 SavedWifiList 时连接已失效")
                } else {
                    report(
                        operation = "确认 SavedWifiList 接收完成",
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
