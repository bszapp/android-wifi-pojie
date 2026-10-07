@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.contract.wifilist

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Service 维护并原样同步给 App 的 Wi-Fi 运行状态。
 *
 * 已保存网络列表不属于本类型，使用同级的 [SavedWifiList] 独立维护与传输。
 */
sealed interface WifiState : Parcelable {

    /** 系统扫描作用域的数据；null 表示尚未取得该来源的数据。 */
    @Parcelize
    data class System(val data: SystemScanData?) : WifiState

    /** 底层扫描作用域的数据，不包含 Android 的 Wi-Fi 开关状态。 */
    @Parcelize
    data class Underlying(val data: UnderlyingScanData?) : WifiState

    /** 监听模式的扫描快照，与普通模式的两个来源互斥。 */
    @Parcelize
    data class Monitor(
        val scanResults: List<ScanResult>,
        val isScanning: Boolean,
    ) : WifiState
}

sealed interface SystemScanData : Parcelable {
    @Parcelize
    data object Disabled : SystemScanData

    @Parcelize
    data class Enabled(
        val scanResults: List<ScanResult>,
        val isScanning: Boolean,
        val connection: WifiInfo?,
    ) : SystemScanData
}

@Parcelize
data class UnderlyingScanData(
    val scanResults: List<ScanResult>,
    val isScanning: Boolean,
    val connection: WifiInfo?,
) : Parcelable

val WifiState?.scanResults: List<ScanResult>
    get() = when (this) {
        is WifiState.System -> (data as? SystemScanData.Enabled)?.scanResults.orEmpty()
        is WifiState.Underlying -> data?.scanResults.orEmpty()
        is WifiState.Monitor -> scanResults
        null -> emptyList()
    }

val WifiState?.connection: WifiInfo?
    get() = when (this) {
        is WifiState.System -> (data as? SystemScanData.Enabled)?.connection
        is WifiState.Underlying -> data?.connection
        is WifiState.Monitor, null -> null
    }

val WifiState?.isScanning: Boolean
    get() = when (this) {
        is WifiState.System -> (data as? SystemScanData.Enabled)?.isScanning == true
        is WifiState.Underlying -> data?.isScanning == true
        is WifiState.Monitor -> isScanning
        null -> false
    }

val WifiState?.hasData: Boolean
    get() = when (this) {
        is WifiState.System -> data != null
        is WifiState.Underlying -> data != null
        is WifiState.Monitor -> true
        null -> false
    }
