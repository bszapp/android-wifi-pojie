package io.github.bszapp.wifitoolbox

import android.os.Process
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.startup.IStartupController
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import io.github.bszapp.wifitoolbox.launcher.ProcessLauncher
import io.github.bszapp.wifitoolbox.settings.SettingsManager
import io.github.bszapp.wifitoolbox.wifilist.WifiListController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ToolboxApp : Application(), IAppController {

    private lateinit var processLauncher: ProcessLauncher
    override lateinit var settings: SettingsManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _isExiting = MutableStateFlow(false)
    override val isExiting: StateFlow<Boolean> = _isExiting.asStateFlow()

    private var wifiStateReceiver: BroadcastReceiver? = null
    private var lastWifiEnabled: Boolean? = null

    override val startup = object : IStartupController {
        override val state get() = processLauncher.state
        override fun launch(mode: StartupMode) = processLauncher.launch(mode)
        override fun cancel() = processLauncher.cancel()
        override fun stop(exit: Boolean) {
            processLauncher.stop()
            if (exit) {
                appScope.launch {
                    delay(200.milliseconds)
                    Process.killProcess(Process.myPid())
                }
                _isExiting.value = true
            }
        }
    }

    override val wifiList: IWifiListController by lazy {
        WifiListController(
            scope = appScope,
            getAndroidApi = { processLauncher.androidApiClient }
        )
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        processLauncher = ProcessLauncher(this)
        AppControllerProvider.register(this)
        processLauncher.tryAutoReconnect()

        appScope.launch {
            startup.state.collect { state ->
                if (state.status == StartupStatus.RUNNING) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
                            // 广播只作为“状态可能变化/数据可能更新”的提示。
                            // 不读取也不信任广播携带的 Wi-Fi 状态，向服务确认当前真实状态。
                            val enabled = processLauncher.androidApiClient?.isWifiEnabled() ?: return
                            val previousEnabled = lastWifiEnabled
                            lastWifiEnabled = enabled

                            Log.d(TAG, "Wi-Fi 状态变化: previous=$previousEnabled, current=$enabled")
                            val wifiController = wifiList as WifiListController
                            wifiController.updateWifiEnabled(enabled)

                            when {
                                previousEnabled == false && enabled -> wifiController.startScan()
                                enabled -> wifiController.refreshScanResults()
                            }
                        }
                    }
                    registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
                    wifiStateReceiver = receiver

                    // 先同步一次真实状态。首次进入不主动扫描，只读取系统已有扫描结果。
                    val enabled = processLauncher.androidApiClient?.isWifiEnabled() ?: false
                    lastWifiEnabled = enabled
                    Log.d(TAG, "服务就绪，Wi-Fi 当前状态: $enabled")
                    val wifiController = wifiList as WifiListController
                    wifiController.updateWifiEnabled(enabled)
                    if (enabled) wifiController.refreshScanResults()
                } else {
                    wifiStateReceiver?.let {
                        unregisterReceiver(it)
                        wifiStateReceiver = null
                        lastWifiEnabled = null
                        Log.d(TAG, "服务停止，注销 Wi-Fi 广播")
                    }
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        wifiStateReceiver?.let {
            unregisterReceiver(it)
            wifiStateReceiver = null
            lastWifiEnabled = null
        }
        appScope.cancel()
    }

    companion object {
        private const val TAG = "ToolboxApp"
    }
}