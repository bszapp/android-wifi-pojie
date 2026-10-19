package io.github.bszapp.wifitoolbox.ui.widget

import android.net.wifi.ScanResult
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskConfig
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.ui.model.ConnectWifiSheetCloseRequest
import io.github.bszapp.wifitoolbox.ui.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiListContent
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiListPageActions
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiListPageState

/**
 * 列表页薄壳：只负责从 ViewModel 收集状态并映射成 [WifiListPageState]，
 * 动作映射到 [WifiListPageActions]；实际 UI 全部在 [WifiListContent]，
 * 便于 Android Studio 预览与无头渲染直接构造假数据。
 */
@Composable
fun WifiList(
    modifier: Modifier = Modifier,
    vm: DefaultViewModel = viewModel(),
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    onSaveHc22000: (content: String, fileName: String) -> Unit = { _, _ -> },
    onInlineConnectTaskChanged: (Long?) -> Unit = {},
) {
    val wifiState by vm.wifiList.state.collectAsStateWithLifecycle()
    val deviceAssessments by vm.wifiList.deviceAssessments.collectAsStateWithLifecycle()
    val savedWifiList by vm.wifiList.savedWifiList.collectAsStateWithLifecycle()
    val modeState by vm.wifiList.modeState.collectAsStateWithLifecycle()
    val handshakeTest by vm.wifiList.monitorHandshakeTest.collectAsStateWithLifecycle()
    val currentTask by vm.currentTask.collectAsStateWithLifecycle()
    val submode by vm.pskSecurityAudit.submode.collectAsStateWithLifecycle()
    val auditBatch by vm.pskSecurityAudit.batch.collectAsStateWithLifecycle()
    val auditTasks by vm.pskSecurityAudit.auditTasks.collectAsStateWithLifecycle()
    val auditHistory by vm.pskSecurityAudit.history.collectAsStateWithLifecycle()
    val displayedTask by vm.displayedTask.collectAsStateWithLifecycle()
    val connectWifiSheetCloseRequest by
        vm.connectWifiSheetCloseRequest.collectAsStateWithLifecycle()
    val scannedWifiNetwork by vm.scannedWifiNetwork.collectAsStateWithLifecycle()

    val state = WifiListPageState(
        wifiState = wifiState,
        deviceAssessments = deviceAssessments,
        savedWifiList = savedWifiList,
        modeState = modeState,
        handshakeTest = handshakeTest,
        currentTask = currentTask,
        submode = submode,
        auditBatch = auditBatch,
        auditTasks = auditTasks,
        auditHistory = auditHistory,
        displayedTask = displayedTask,
        connectWifiSheetCloseRequest = connectWifiSheetCloseRequest,
        scannedWifiNetwork = scannedWifiNetwork,
    )
    val actions = remember(vm) {
        object : WifiListPageActions {
            override fun setWifiEnabled(enabled: Boolean) = vm.wifiList.setWifiEnabled(enabled)
            override fun disconnectCurrentNetwork(networkId: Int) =
                vm.wifiList.disconnectCurrentNetwork(networkId)

            override fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) =
                vm.wifiList.updateWifiConfig(networkId, patch)

            override suspend fun saveWifiNetwork(ssid: String, password: String): Int =
                vm.wifiList.saveWifiNetwork(ssid, password)

            override suspend fun startSavedNetworkConnectTask(
                networkId: Int,
                type: ConnectWifiTaskType,
                config: ConnectWifiTaskConfig,
            ): Long = vm.startSavedNetworkConnectTask(networkId, type, config)

            override suspend fun startTemporaryNetworkConnectTask(
                ssid: String,
                password: String,
                config: ConnectWifiTaskConfig,
            ): Long = vm.startTemporaryNetworkConnectTask(ssid, password, config)

            override suspend fun startNetworkCardConnectTask(
                ssid: String,
                passwords: List<String>,
                mac: String?,
                name: String?,
                config: ConnectWifiTaskConfig,
            ): Long = vm.startNetworkCardConnectTask(ssid, passwords, mac, name, config)

            override fun confirmSavedConfigurationConnectivityTest(
                sheetInstanceId: String,
                ssid: String,
                password: String,
                config: ConnectWifiTaskConfig,
            ) = vm.confirmSavedConfigurationConnectivityTest(sheetInstanceId, ssid, password, config)

            override fun displayTask(taskId: Long) = vm.displayTask(taskId)
            override fun consumeWifiQrNetwork() = vm.consumeWifiQrNetwork()
            override fun consumeConnectWifiSheetCloseRequest(requestId: Long) =
                vm.consumeConnectWifiSheetCloseRequest(requestId)

            override fun auditOpenNetwork(ssid: String, saved: Boolean, append: Boolean) =
                vm.pskSecurityAudit.openNetwork(ssid, saved, append)

            override fun auditOpenWpsNetwork(
                ssid: String,
                saved: Boolean,
                accessPoints: List<ScanResult>,
            ) = vm.pskSecurityAudit.openWpsNetwork(ssid, saved, accessPoints)

            override fun auditStopNetwork(ssid: String, mac: String?) =
                vm.pskSecurityAudit.stopNetwork(ssid, mac)

            override fun auditStopRecord(recordId: String) = vm.pskSecurityAudit.stopRecord(recordId)

            override fun auditStopDictionaryNetwork(ssid: String) =
                vm.pskSecurityAudit.stopDictionaryNetwork(ssid)

            override fun auditStopGroup(ssid: String, groupId: String) =
                vm.pskSecurityAudit.stopGroup(ssid, groupId)

            override fun auditAddMember(ssid: String, groupId: String) =
                vm.pskSecurityAudit.addMember(ssid, groupId)

            override fun auditManualNetwork() = vm.pskSecurityAudit.manualNetwork()
            override fun openWpsPin(ssid: String) = vm.openWpsPin(ssid)
            override fun clearMonitorHandshakeTestResult() =
                vm.wifiList.clearMonitorHandshakeTestResult()

            override fun exportMonitorDevicePcap(bssid: String, deviceMac: String, subtypeIds: Set<String>) =
                vm.wifiList.exportMonitorDevicePcap(bssid, deviceMac, subtypeIds)

            override fun testMonitorHandshake(
                bssid: String,
                deviceMac: String,
                handshakeId: String,
                password: String,
            ) = vm.wifiList.testMonitorHandshake(bssid, deviceMac, handshakeId, password)

            override fun exportMonitorHandshakePcap(bssid: String, deviceMac: String, handshakeId: String) =
                vm.wifiList.exportMonitorHandshakePcap(bssid, deviceMac, handshakeId)

            override fun stopDisplayedTask() = vm.stopDisplayedTask()
            override fun closeDisplayedTask() = vm.closeDisplayedTask()
        }
    }

    WifiListContent(
        state = state,
        actions = actions,
        modifier = modifier,
        listState = listState,
        contentPadding = contentPadding,
        onSaveHc22000 = onSaveHc22000,
        onInlineConnectTaskChanged = onInlineConnectTaskChanged,
    )
}
