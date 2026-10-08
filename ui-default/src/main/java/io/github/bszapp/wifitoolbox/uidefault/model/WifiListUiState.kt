package io.github.bszapp.wifitoolbox.uidefault.model

import android.net.Uri
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiListDataSource
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorMapFilterState
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestOutcome
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.contract.wifilist.SystemScanData
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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

    /** Service 已确认的实际网卡模式与扫描、抓取数据。 */
    val modeState = controller.wifiList.modeState

    val monitorPcapExports = controller.wifiList.monitorPcapExports

    private val handshakeActions = MonitorHandshakeUiState(controller.wifiList, scope)
    val monitorHandshakeTest = handshakeActions.state

    val monitorMapFilterState = controller.monitorMapFilterState

    fun updateMonitorMapFilterState(state: MonitorMapFilterState) =
        controller.updateMonitorMapFilterState(state)

    private val scanRequestGuard = AtomicBoolean(false)
    private val _isSendingScanRequest = MutableStateFlow(false)

    /** App 正在同步等待 Service 确认 WifiScanner 是否接受了扫描请求。 */
    val isSendingScanRequest: StateFlow<Boolean> = _isSendingScanRequest.asStateFlow()

    private var previousWifiState: WifiState? = null

    init {
        scope.launch {
            var previousMode = modeState.value?.mode
            modeState.collect { current ->
                val currentMode = current?.mode ?: return@collect
                val enteredMonitorMode =
                    previousMode == WifiMode.NORMAL && currentMode == WifiMode.MONITOR
                previousMode = currentMode

                if (enteredMonitorMode) {
                    val operationId = current.modeSwitch?.operationId
                    scope.launch {
                        val readyState = modeState.first { latest ->
                            latest == null || latest.mode != WifiMode.MONITOR ||
                                (latest.modeSwitch?.isRunning != true &&
                                    latest.monitorStatistics != null)
                        }
                        if (
                            readyState?.mode == WifiMode.MONITOR &&
                            (operationId == null || readyState.modeSwitch?.operationId == operationId)
                        ) {
                            startScan()
                        }
                    }
                }
            }
        }
        scope.launch {
            state.collect { current ->
                val previous = previousWifiState
                previousWifiState = current

                // 仅系统来源的 Disabled -> Enabled 触发原有的自动扫描。
                // 首次数据、底层来源和来源切换不触发。
                val mode = modeState.value
                val shouldAutoScan =
                    mode?.mode == WifiMode.NORMAL && mode.listDataSource == WifiListDataSource.SYSTEM &&
                    mode.modeSwitch?.isRunning != true &&
                    (previous as? WifiState.System)?.data is SystemScanData.Disabled &&
                        (current as? WifiState.System)?.data.let {
                            it is SystemScanData.Enabled && !it.isScanning
                        }

                if (shouldAutoScan) startScan()
            }
        }
    }

    fun updateSavedNetworks() = controller.wifiList.updateSavedNetworks()

    fun setMode(mode: WifiMode) =
        controller.wifiList.setMode(mode)

    fun setWifiListDataSource(source: WifiListDataSource) =
        controller.wifiList.setWifiListDataSource(source)
    fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int = 0, hopping: Boolean = false) =
        controller.wifiList.setMonitorCapture(enabled, frequencyMhz, hopping)
    fun clearMonitorCapture(handshakesOnly: Boolean) = controller.wifiList.clearMonitorCapture(handshakesOnly)
    fun interruptModeSwitch(operationId: Long) = controller.wifiList.interruptModeSwitch(operationId)
    fun interruptMonitorClear(operationId: Long) = controller.wifiList.interruptMonitorClear(operationId)

    fun enterMonitorMode(command: String) = controller.wifiList.enterMonitorMode(command)

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

    fun saveMonitorHc22000(content: String, destination: Uri) =
        controller.wifiList.saveMonitorHc22000(content, destination)

    fun exportMonitorHandshakePcap(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ) = controller.wifiList.exportMonitorHandshakePcap(
        bssid = bssid,
        deviceMac = deviceMac,
        handshakeId = handshakeId,
    )

    fun exportMonitorDisconnectionPcap(
        bssid: String,
        deviceMac: String,
        disconnectionId: String,
    ) = controller.wifiList.exportMonitorDisconnectionPcap(
        bssid = bssid,
        deviceMac = deviceMac,
        disconnectionId = disconnectionId,
    )

    fun testMonitorHandshake(
        bssid: String,
        deviceMac: String,
        handshakeId: String,
        password: String,
    ) {
        handshakeActions.test(bssid, deviceMac, handshakeId, password)
    }

    fun clearMonitorHandshakeTestResult() {
        handshakeActions.clear()
    }

    suspend fun saveWifiNetwork(ssid: String, password: String): Int =
        controller.wifiList.saveWifiNetwork(ssid, password)

    /**
     * ViewModel 层异步发送扫描请求，并维护“正在等待 Service 确认”的 UI 状态。
     * 错误已经由 App Controller 统一广播，这里只负责结束异步任务。
     */
    fun startScan() {
        if (modeState.value?.modeSwitch?.isRunning == true) return
        if (!scanRequestGuard.compareAndSet(false, true)) return
        _isSendingScanRequest.value = true

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (modeState.value?.modeSwitch?.isRunning == true) return@withContext
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

data class MonitorHandshakeTestUiState(
    val requestId: String? = null,
    val outcome: MonitorHandshakeTestOutcome? = null,
)

/** 列表 Sheet 与抓包详情复用同一套校验事件跟踪，不触发扫描或模式操作。 */
class MonitorHandshakeUiState(
    private val controller: io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController,
    scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(MonitorHandshakeTestUiState())
    val state = mutable.asStateFlow()
    init {
        scope.launch {
            controller.monitorHandshakeTestResults.collect { result ->
                if (result.requestId == mutable.value.requestId) {
                    mutable.value = MonitorHandshakeTestUiState(outcome = result.outcome)
                }
            }
        }
    }
    fun test(bssid: String, mac: String, id: String, password: String) {
        mutable.value = MonitorHandshakeTestUiState(requestId = controller.testMonitorHandshake(bssid, mac, id, password))
    }
    fun clear() { mutable.value = MonitorHandshakeTestUiState() }
}
