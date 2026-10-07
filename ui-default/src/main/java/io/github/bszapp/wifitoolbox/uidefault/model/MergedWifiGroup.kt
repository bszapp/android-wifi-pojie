@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.model

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
import io.github.bszapp.wifitoolbox.contract.wifilist.createScanResultCompat

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
            capturedAccessPoints: List<MonitorAccessPoint> = emptyList(),
        ): List<MergedWifiGroup> {
            val connectionSsid = connection?.normalizedSsid()
            val capturedByBssid = capturedAccessPoints.associateBy { it.bssid.lowercase() }
            val mergedResults = results.map { result ->
                val captured = capturedByBssid[result.BSSID.lowercase()]
                val capturedSsid = captured?.ssid
                val capturedSignal = captured?.signal?.latestDbm
                val shouldKeepHidden = captured?.ssidVisibility == MonitorSsidVisibility.HIDDEN ||
                    (captured?.ssidVisibility != MonitorSsidVisibility.VISIBLE && result.SSID.isNullOrEmpty())
                if ((!shouldKeepHidden && result.SSID.isNullOrEmpty() && !capturedSsid.isNullOrEmpty()) || capturedSignal != null) {
                    ScanResult(result).apply {
                        if (!shouldKeepHidden && SSID.isNullOrEmpty() && !capturedSsid.isNullOrEmpty()) {
                            SSID = capturedSsid
                        }
                        if (capturedSignal != null) level = capturedSignal
                    }
                } else result
            }
            val scannedBssids = results.mapTo(hashSetOf()) { it.BSSID.lowercase() }
            // 仅抓包发现的接入点使用最后一次捕获的信号；未读取到的频率保持未知。
            val capturedOnly = capturedAccessPoints.filter { it.bssid.lowercase() !in scannedBssids }
                .map { point ->
                    createScanResultCompat().apply {
                        SSID = if (point.ssidVisibility == MonitorSsidVisibility.HIDDEN) "" else point.ssid ?: ""
                        BSSID = point.bssid
                        level = point.signal?.latestDbm ?: 0
                        frequency = 0
                        capabilities = point.securityProtocols.joinToString("") { "[${it.name}]" }
                    }
                }
            val allResults = mergedResults + capturedOnly
            val visible = allResults.filter { !it.SSID.isNullOrEmpty() }
            val hidden = allResults.filter { it.SSID.isNullOrEmpty() }

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
            val sortedNetworks = networks.sortedWith(compareBy<ScanResult> { it.level == 0 }.thenByDescending { it.level })
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
