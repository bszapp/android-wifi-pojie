package io.github.bszapp.wifitoolbox.contract.wifilist

import android.net.Uri
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** App 侧只负责接收 Service 的原始数据并向 UI 转发命令。 */
interface IWifiListController {
    val state: StateFlow<WifiState?>
    val savedWifiList: StateFlow<SavedWifiList?>
    val modeState: StateFlow<WifiModeState?>
    val monitorPcapExports: SharedFlow<MonitorPcapExportResult>
    val monitorHandshakeTestResults: SharedFlow<MonitorHandshakeTestResult>

    /** 请求 Service 只更新独立的 SavedWifiList。 */
    fun updateSavedNetworks()

    /**
     * 同步等待 Service 确认 WifiScanner 已接受扫描请求。
     * 成功返回后扫描任务继续在 Service 后台运行；失败直接向调用者抛出异常。
     */
    @Throws(Exception::class)
    fun startScan()

    /** 请求 Service 切换网卡模式；实际模式由 Service 读取并发布。 */
    fun setMode(mode: WifiMode)
    fun setHybridScanEnabled(enabled: Boolean)
    fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int = 0, hopping: Boolean = false)
    fun clearMonitorCapture(handshakesOnly: Boolean)

    /** 请求 Service 执行进入脚本；持续抓取时单独选择信道。 */
    fun enterMonitorMode(command: String)

    fun exportAllMonitorPcap(): String

    fun exportMonitorDevicePcap(
        bssid: String,
        deviceMac: String,
        subtypeIds: Set<String>,
    ): String

    fun releaseMonitorPcapExport(path: String)

    fun saveMonitorPcapExport(path: String, destination: Uri)

    fun saveMonitorHc22000(content: String, destination: Uri)

    fun exportMonitorHandshakePcap(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ): String

    fun exportMonitorDisconnectionPcap(
        bssid: String,
        deviceMac: String,
        disconnectionId: String,
    ): String

    fun testMonitorHandshake(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
        password: String,
    ): String

    suspend fun saveWifiNetwork(ssid: String, password: String): Int
    fun setWifiEnabled(enabled: Boolean)
    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch)
    fun disconnectCurrentNetwork(networkId: Int)
}
