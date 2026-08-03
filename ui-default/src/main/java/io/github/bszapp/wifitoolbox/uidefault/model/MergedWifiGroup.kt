@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.model

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

data class MergedWifiGroup(
    val ssid: String,
    val networks: List<ScanResult>,
    val savedWifiList: List<WifiConfiguration>,
    val connection: WifiInfo?,
    val virtualAccessPoint: WifiInfo?,
) {
    val strongest: ScanResult? get() = networks.firstOrNull()

    val displaySsid: String
        get() = ssid.takeIf { it.isNotEmpty() } ?: "<隐藏的网络>"

    val accessPointCount: Int
        get() = networks.size + if (virtualAccessPoint != null) 1 else 0

    val signalDbm: Int?
        get() = strongest?.level?.takeUnless { it == 0 }

    val signalDisplay: String
        get() = signalDbm?.let { "${it}dBm" } ?: "未知"

    val hasUnknownSignal: Boolean
        get() = signalDbm == null

    val isConnected: Boolean
        get() = connection != null

    fun isCurrentAccessPoint(ap: ScanResult): Boolean =
        connection?.bssid.equals(ap.BSSID, ignoreCase = true)

    fun isCurrentConfiguration(config: WifiConfiguration): Boolean =
        connection?.networkId == config.networkId

    companion object {
        fun buildFrom(
            results: List<ScanResult>,
            savedWifiList: List<WifiConfiguration>,
            connection: WifiInfo?,
        ): List<MergedWifiGroup> {
            val connectionSsid = connection?.normalizedSsid()
            val visible = results.filter { !it.SSID.isNullOrEmpty() }
            val hidden = results.filter { it.SSID.isNullOrEmpty() }

            val mergedVisible = visible
                .groupBy { it.SSID!! }
                .values
                .map { group ->
                    val ssid = group.first().SSID!!
                    createGroup(
                        ssid = ssid,
                        networks = group,
                        savedWifiList = savedWifiList,
                        connection = connection.takeIf { connectionSsid == ssid },
                    )
                }

            val mergedHidden = if (hidden.isNotEmpty()) {
                listOf(
                    createGroup(
                        ssid = "",
                        networks = hidden,
                        savedWifiList = emptyList(),
                        connection = null,
                    ),
                )
            } else {
                emptyList()
            }

            val groups = (mergedVisible + mergedHidden).toMutableList()
            if (connection != null && connectionSsid != null &&
                groups.none { it.ssid == connectionSsid }
            ) {
                groups += createGroup(
                    ssid = connectionSsid,
                    networks = emptyList(),
                    savedWifiList = savedWifiList,
                    connection = connection,
                )
            }

            return groups.sortedWith(
                compareByDescending<MergedWifiGroup> { it.isConnected }
                    .thenBy { it.hasUnknownSignal }
                    .thenByDescending { it.signalDbm ?: Int.MIN_VALUE },
            )
        }

        private fun createGroup(
            ssid: String,
            networks: List<ScanResult>,
            savedWifiList: List<WifiConfiguration>,
            connection: WifiInfo?,
        ): MergedWifiGroup {
            val sortedNetworks = networks.sortedByDescending { it.level }
            val currentBssid = connection?.bssid
            val hasCurrentAccessPoint = currentBssid != null && sortedNetworks.any {
                currentBssid.equals(it.BSSID, ignoreCase = true)
            }
            return MergedWifiGroup(
                ssid = ssid,
                networks = sortedNetworks,
                savedWifiList = savedWifiList.filter { it.SSID?.trim('"') == ssid },
                connection = connection,
                virtualAccessPoint = connection.takeUnless { hasCurrentAccessPoint },
            )
        }

        private fun WifiInfo.normalizedSsid(): String? = ssid
            ?.takeUnless { it == WifiManager.UNKNOWN_SSID }
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() }
    }
}
