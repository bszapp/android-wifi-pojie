package io.github.bszapp.wifitoolbox.contract.wifilist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 元数据不携带累计子项；子项独立按键更新。 */
@Parcelize
sealed class MonitorChange : Parcelable {
    data class AccessPoint(val value: MonitorAccessPoint) : MonitorChange()
    data class Device(val bssid: String, val value: MonitorDevice) : MonitorChange()
    data class Handshake(val bssid: String, val deviceMac: String, val value: MonitorHandshakeRecord) : MonitorChange()
    data class Disconnection(val value: MonitorDisconnectionRecord) : MonitorChange()
}

@Parcelize
data class MonitorChangesPage(
    val header: MonitorModeStatistics,
    val nextRevision: Long,
    val changes: List<MonitorChange>,
) : Parcelable
