package io.github.bszapp.wifitoolbox.service

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo

@Keep
open class MainService(
    serviceContext: Context? = null,
) : IMainService.Stub() {

    constructor(startupInfo: StartupInfo) : this(serviceContext = null) {
        Log.d(TAG, "使用启动参数初始化服务")
        initializeFromStartupInfo(startupInfo)
    }

    private val initializer = ServiceInitializer()

    private val communication = ServiceCommunication(
        startupInfoProvider = { initializer.requireStartupInfo() },
        serviceBinderProvider = { this.asBinder() },
    )

    private val wifiListController = WifiListController(
        androidApiProvider = { initializer.androidApi },
        onWifiStateChanged = communication::broadcastWifiState,
        onSavedWifiListChanged = communication::broadcastSavedWifiList,
        onError = { operation, error ->
            communication.broadcastServiceError(
                source = "Service.WifiListController",
                operation = operation,
                error = error,
            )
        },
    )

    private val wifiEventMonitor = ServiceWifiBroadcastLogger(
        onWifiStateChanged = wifiListController::onWifiStateMayHaveChanged,
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

    override fun refreshSavedWifiNetworks() = communication.callFromApp {
        Log.d(TAG, "应用请求刷新已保存 Wi-Fi 列表")
        wifiListController.refreshSavedNetworks()
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

    override fun acknowledgeWifiState(cb: IMainServiceCallback) {
        communication.acknowledgeWifiState(cb)
    }

    override fun acknowledgeSavedWifiList(cb: IMainServiceCallback) {
        communication.acknowledgeSavedWifiList(cb)
    }

    override fun shutdown() = communication.callFromApp {
        Log.d(TAG, "收到 shutdown，服务退出")
        wifiEventMonitor.stop()
        wifiListController.stop()
        Process.killProcess(Process.myPid())
    }

    override fun registerCallback(cb: IMainServiceCallback) {
        communication.registerCallback(cb)
        // 重连时分别发送当前两种原始数据；不存在的数据等首次初始化后再推送。
        wifiListController.getWifiState()?.let { communication.pushWifiState(cb, it) }
        wifiListController.getSavedWifiList()?.let { communication.pushSavedWifiList(cb, it) }
    }

    override fun unregisterCallback(cb: IMainServiceCallback) {
        communication.unregisterCallback(cb)
    }

    companion object {
        private const val TAG = "ToolboxMainService"
    }
}
