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
import io.github.bszapp.wifitoolbox.wifilist.WifiListController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ToolboxApp : Application(), IAppController {

    private lateinit var processLauncher: ProcessLauncher

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _isExiting = MutableStateFlow(false)
    override val isExiting: StateFlow<Boolean> = _isExiting.asStateFlow()

    private var wifiStateReceiver: BroadcastReceiver? = null

    override val startup = object : IStartupController {
        override val state get() = processLauncher.state
        override fun launch(mode: StartupMode) = processLauncher.launch(mode)
        override fun cancel() = processLauncher.cancel()
        override fun stop(exit: Boolean) {
            processLauncher.stop()
            if (exit) {
                appScope.launch {
                    delay(200)
                    Process.killProcess(Process.myPid())
                }
                _isExiting.value = true
            }
        }
    }

    override val wifiList: IWifiListController by lazy {
        WifiListController(
            scope = appScope,
            getService = { processLauncher.mainService }
        )
    }

    override fun onCreate() {
        super.onCreate()
        processLauncher = ProcessLauncher(this)
        AppControllerProvider.register(this)
        processLauncher.tryAutoReconnect()

        appScope.launch {
            startup.state.collect { state ->
                if (state.status == StartupStatus.RUNNING) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
                            val extra = intent.getIntExtra(
                                WifiManager.EXTRA_WIFI_STATE,
                                WifiManager.WIFI_STATE_UNKNOWN
                            )
                            // 只处理终态，ENABLING/DISABLING 忽略
                            if (extra != WifiManager.WIFI_STATE_ENABLED &&
                                extra != WifiManager.WIFI_STATE_DISABLED) return
                            // 不信任广播数据，问服务确认实际状态
                            val enabled = processLauncher.mainService?.isWifiEnabled() ?: return
                            Log.d(TAG, "Wi-Fi 状态变化: $enabled")
                            (wifiList as WifiListController).updateWifiEnabled(enabled)
                            wifiList.startScan()
                        }
                    }
                    registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
                    wifiStateReceiver = receiver

                    // 先同步一次真实状态，再决定要不要扫描
                    val enabled = processLauncher.mainService?.isWifiEnabled() ?: false
                    Log.d(TAG, "服务就绪，Wi-Fi 当前状态: $enabled")
                    (wifiList as WifiListController).updateWifiEnabled(enabled)
                    wifiList.startScan()
                } else {
                    wifiStateReceiver?.let {
                        unregisterReceiver(it)
                        wifiStateReceiver = null
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
        }
        appScope.cancel()
    }

    companion object {
        private const val TAG = "ToolboxApp"
    }
}