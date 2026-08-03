package io.github.bszapp.wifitoolbox.uidefault.model

import android.net.Uri
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiListUiState(
    private val controller: IAppController,
    private val scope: CoroutineScope,
) {

    /** Service 独立维护并传输的 Wi-Fi 运行状态。 */
    val state = controller.wifiList.state

    /** 与 WifiState 同级、独立传输的已保存 Wi-Fi 列表。 */
    val savedWifiList = controller.wifiList.savedWifiList

    /** Service 保存的信息源目标与初始化状态。 */
    val informationSourceState = controller.wifiList.informationSourceState

    val monitorPcapExports = controller.wifiList.monitorPcapExports

    val monitorHandshakeTestResults = controller.wifiList.monitorHandshakeTestResults

    private val scanRequestGuard = AtomicBoolean(false)
    private val _isSendingScanRequest = MutableStateFlow(false)

    /** App 正在同步等待 Service 确认 WifiScanner 是否接受了扫描请求。 */
    val isSendingScanRequest: StateFlow<Boolean> = _isSendingScanRequest.asStateFlow()

    private var previousWifiState: WifiState? = null

    init {
        scope.launch {
            state.collect { current ->
                val previous = previousWifiState
                previousWifiState = current

                // App 初次订阅时 null -> Enabled 不扫描。
                // Disabled/Error -> Enabled 时，App 只发送扫描指令；数据刷新由扫描任务完成。
                val shouldAutoScan =
                    (previous is WifiState.Data.Disabled || previous is WifiState.Error) &&
                        current is WifiState.Data.Enabled &&
                        !current.isScanning

                if (shouldAutoScan) startScan()
            }
        }
    }

    fun updateSavedNetworks() = controller.wifiList.updateSavedNetworks()

    fun setInformationSource(source: WifiInformationSource) =
        controller.wifiList.setInformationSource(source)

    fun enterMonitorMode(command: String, targetChannel: Int, targetFrequencyMhz: Int) =
        controller.wifiList.enterMonitorMode(command, targetChannel, targetFrequencyMhz)

    fun exportAllMonitorPcap() = controller.wifiList.exportAllMonitorPcap()

    fun exportMonitorDevicePcap(
        bssid: String,
        deviceMac: String,
        subtypeIds: Set<String>,
    ) = controller.wifiList.exportMonitorDevicePcap(bssid, deviceMac, subtypeIds)

    fun releaseMonitorPcapExport(path: String) =
        controller.wifiList.releaseMonitorPcapExport(path)

    fun saveMonitorPcapExport(path: String, destination: Uri) =
        controller.wifiList.saveMonitorPcapExport(path, destination)

    fun exportMonitorHandshakePcap(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ) = controller.wifiList.exportMonitorHandshakePcap(
        bssid = bssid,
        deviceMac = deviceMac,
        handshakeId = handshakeId,
    )

    fun testMonitorHandshake(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
        password: String,
    ) = controller.wifiList.testMonitorHandshake(
        bssid = bssid,
        deviceMac = deviceMac,
        handshakeId = handshakeId,
        password = password,
    )

    /**
     * ViewModel 层异步发送扫描请求，并维护“正在等待 Service 确认”的 UI 状态。
     * 错误已经由 App Controller 统一广播，这里只负责结束异步任务。
     */
    fun startScan() {
        if (!scanRequestGuard.compareAndSet(false, true)) return
        _isSendingScanRequest.value = true

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controller.wifiList.startScan()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // App.WifiListController 已发送到统一 errors 广播。
            } finally {
                _isSendingScanRequest.value = false
                scanRequestGuard.set(false)
            }
        }
    }

    fun setWifiEnabled(enabled: Boolean) = controller.wifiList.setWifiEnabled(enabled)

    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) =
        controller.wifiList.updateWifiConfig(networkId, patch)

    fun disconnectCurrentNetwork(networkId: Int) =
        controller.wifiList.disconnectCurrentNetwork(networkId)
}
