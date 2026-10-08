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
    /** App 已完成本地通信索引或详情增量同步后更新，页面只读取本地镜像。 */
    val monitorCommunicationUpdates: StateFlow<Long>

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
    fun setWifiListDataSource(source: WifiListDataSource)
    fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int = 0, hopping: Boolean = false)
    fun clearMonitorCapture(handshakesOnly: Boolean)
    fun interruptModeSwitch(operationId: Long)
    fun interruptMonitorClear(operationId: Long)

    /** 请求 Service 执行进入脚本；持续抓取时单独选择信道。 */
    fun enterMonitorMode(command: String)

    suspend fun monitorCommunicationWindow(query: MonitorCommunicationQuery, first: Int, last: Int): MonitorCommunicationWindow
    suspend fun readMonitorCommunicationDetail(sessionGeneration: Long, bssid: String, deviceMac: String, recordId: String, cursor: Long, channel: MonitorCommunicationChannel = MonitorCommunicationChannel.DETAIL): MonitorCommunicationDetailPage
    suspend fun saveMonitorCommunication(sessionGeneration: Long, bssid: String, deviceMac: String, recordId: String, channel: MonitorCommunicationChannel, destination: Uri)

    /** 完整预取当前各通道的已捕获内容到 App 磁盘，成功后才允许导航。 */
    suspend fun prepareMonitorCommunicationDetail(sessionGeneration: Long, bssid: String, deviceMac: String, recordId: String, onProgress: (Long, Long) -> Unit): String
    /** 仅访问已完成的本地快照，不向服务发起读取。 */
    suspend fun readPreparedMonitorCommunicationDetail(snapshotId: String, cursor: Long, channel: MonitorCommunicationChannel): MonitorCommunicationDetailPage
    suspend fun savePreparedMonitorCommunication(snapshotId: String, channel: MonitorCommunicationChannel, destination: Uri)
    fun releasePreparedMonitorCommunication(snapshotId: String)
    suspend fun viewedMonitorCommunications(sessionGeneration: Long, recordIds: List<String>): Set<String>
    suspend fun markMonitorCommunicationViewed(sessionGeneration: Long, recordId: String)

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
