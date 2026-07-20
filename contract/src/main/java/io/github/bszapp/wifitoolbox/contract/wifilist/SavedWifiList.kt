@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.contract.wifilist

import android.net.wifi.WifiConfiguration
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Service 维护并原样同步给 App 的已保存 Wi-Fi 配置列表。
 *
 * 本类型与 [WifiState] 同级，彼此不包含、互不转换。
 */
@Parcelize
data class SavedWifiList(
    val networks: List<WifiConfiguration>,
) : Parcelable
