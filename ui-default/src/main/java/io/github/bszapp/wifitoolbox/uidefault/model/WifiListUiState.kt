package io.github.bszapp.wifitoolbox.uidefault.model

import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.wifilist.ScanStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class WifiListUiState(private val controller: IAppController, scope: CoroutineScope) {

    val status = controller.wifiList.state
        .map { it.status }
        .stateIn(scope, SharingStarted.Eagerly, null as ScanStatus?)

    val results = controller.wifiList.state
        .map { it.scanResults }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val isScanning = controller.wifiList.state
        .map { it.isScanning }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val errorMessage = controller.wifiList.state
        .map { it.errorException?.message }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun startScan() = controller.wifiList.startScan()
    fun setWifiEnabled(enabled: Boolean) = controller.wifiList.setWifiEnabled(enabled)
    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) =
        controller.wifiList.updateWifiConfig(networkId, patch)

    val savedWifiList = controller.wifiList.savedWifiList
}
