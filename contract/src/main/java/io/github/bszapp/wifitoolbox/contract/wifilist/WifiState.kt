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

    /** Wi-Fi 状态读取成功。 */
    sealed interface Data : WifiState {

        /** Wi-Fi 已关闭。 */
        @Parcelize
        data object Disabled : Data

        /** Wi-Fi 已开启。 */
        @Parcelize
        data class Enabled(
            val scanResults: List<ScanResult>,
            val isScanning: Boolean,
            val connection: WifiInfo?,
        ) : Data
    }

    /** Wi-Fi 状态读取或更新失败。 */
    @Parcelize
    data class Error(
        val exception: Exception,
    ) : WifiState
}

val WifiState?.isScanning: Boolean
    get() = (this as? WifiState.Data.Enabled)?.isScanning == true
