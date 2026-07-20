@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.wifilist

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
    private val getMainService: () -> IMainService?,
    private val getAndroidApiClient: () -> AndroidApiClient?,
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
    private var registeredBinder: IBinder? = null

    private val callback = object : IMainServiceCallback.Stub() {
        override fun onWifiStateChanged(payload: ParcelFileDescriptor) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    WifiParcelTransport.decodeWifiState(payload)
                }
                withContext(Dispatchers.Main.immediate) {
                    result
                        .onSuccess { _state.value = it }
                        .onFailure {
                            report(
                                operation = "解码 WifiState",
                                error = it,
                            )
                        }
                }
                acknowledgeWifiState()
            }
        }

        override fun onSavedWifiListChanged(payload: ParcelFileDescriptor) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    WifiParcelTransport.decodeSavedWifiList(payload)
                }
                withContext(Dispatchers.Main.immediate) {
                    result
                        .onSuccess { _savedWifiList.value = it }
                        .onFailure {
                            report(
                                operation = "解码 SavedWifiList",
                                error = it,
                            )
                        }
                }
                acknowledgeSavedWifiList()
            }
        }

        override fun onServiceError(
            source: String,
            operation: String,
            message: String,
            details: String,
        ) {
            reportError(
                source,
                operation,
                IllegalStateException(message),
                details,
            )
        }
    }

    override fun initialize() {
        scope.launch(Dispatchers.IO) {
            val service = getMainService()
            if (service == null) {
                report(
                    operation = "注册 Wi-Fi 数据回调",
                    error = IllegalStateException("service 未连接"),
                )
                return@launch
            }

            val binder = service.asBinder()
            if (registeredBinder !== binder) {
                registeredService?.let { old ->
                    runCatching { old.unregisterCallback(callback) }
                        .onFailure {
                            report(
                                operation = "注销旧 Wi-Fi 数据回调",
                                error = it,
                            )
                        }
                }

                registeredService = service
                registeredBinder = binder
                withContext(Dispatchers.Main.immediate) {
                    _state.value = null
                    _savedWifiList.value = null
                }

                runCatching { service.registerCallback(callback) }
                    .onFailure {
                        report(
                            operation = "注册 Wi-Fi 数据回调",
                            error = it,
                        )
                    }
            }
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
            val service = registeredService ?: getMainService()
                ?: throw IllegalStateException("service 未连接")

            Log.d(TAG, "向 Service 同步请求启动 Wi-Fi 扫描")
            val started = service.startWifiScan()
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
        scope.launch(Dispatchers.IO) {
            val client = getAndroidApiClient()
            if (client == null) {
                report(
                    operation = "设置 Wi-Fi 开关为 $enabled",
                    error = IllegalStateException("AndroidApiClient 不可用"),
                )
                return@launch
            }

            // AndroidApiClient 已负责把所有异常送入统一错误广播；这里仅终止协程异常传播。
            runCatching { client.setWifiEnabled(enabled) }
                .onFailure {
                    Log.e(TAG, "设置 Wi-Fi 开关失败：${it.message}", it)
                }
        }
    }

    /** 修改系统配置成功后，由 App 单独请求 Service 刷新 SavedWifiList。 */
    override fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) {
        scope.launch(Dispatchers.IO) {
            val client = getAndroidApiClient()
            if (client == null) {
                report(
                    operation = "更新 Wi-Fi 配置 networkId=$networkId",
                    error = IllegalStateException("AndroidApiClient 不可用"),
                )
                return@launch
            }

            val updated = runCatching {
                client.updateWifiConfig(networkId, patch)
            }.onFailure {
                // AndroidApiClient 已广播详细错误。
                Log.e(TAG, "更新 Wi-Fi 配置失败：${it.message}", it)
            }.isSuccess

            if (updated) {
                callServiceNow("请求刷新已保存 Wi-Fi 列表") {
                    it.refreshSavedWifiNetworks()
                }
            }
        }
    }

    private fun acknowledgeWifiState() {
        val service = registeredService ?: getMainService() ?: return
        runCatching { service.acknowledgeWifiState(callback) }
            .onFailure {
                report(
                    operation = "确认 WifiState 接收完成",
                    error = it,
                )
            }
    }

    private fun acknowledgeSavedWifiList() {
        val service = registeredService ?: getMainService() ?: return
        runCatching { service.acknowledgeSavedWifiList(callback) }
            .onFailure {
                report(
                    operation = "确认 SavedWifiList 接收完成",
                    error = it,
                )
            }
    }

    private fun callService(
        operation: String,
        block: (IMainService) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            callServiceNow(operation, block)
        }
    }

    private fun callServiceNow(
        operation: String,
        block: (IMainService) -> Unit,
    ) {
        val service = registeredService ?: getMainService()
        if (service == null) {
            report(
                operation = operation,
                error = IllegalStateException("service 未连接"),
            )
            return
        }

        runCatching { block(service) }
            .onFailure {
                report(
                    operation = operation,
                    error = it,
                )
            }
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
