package io.github.bszapp.wifitoolbox.contract.wifilist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class WifiInformationSource(
    val wireValue: Int,
    val displayName: String,
) {
    SYSTEM(0, "系统模式"),
    HYBRID(1, "混合模式"),
    MONITOR(2, "监听模式");

    companion object {
        fun fromWireValue(value: Int): WifiInformationSource =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("未知 Wi-Fi 信息源: $value")
    }
}

@Parcelize
data class WifiInformationSourceState(
    val source: WifiInformationSource,
    val initializing: Boolean,
    val monitorStatistics: MonitorModeStatistics? = null,
) : Parcelable

@Parcelize
data class MonitorModeStatistics(
    val recordedBytes: Long = 0L,
    val channel: Int = 0,
    val frequencyMhz: Int = 0,
    val accessPoints: List<MonitorAccessPoint> = emptyList(),
) : Parcelable

@Parcelize
data class MonitorAccessPoint(
    val bssid: String,
    val ssid: String?,
    val ssidVisibility: MonitorSsidVisibility = MonitorSsidVisibility.UNKNOWN,
    val securityProtocols: List<MonitorSecurityProtocol> = emptyList(),
    val signal: MonitorSignalStatistics? = null,
    val devices: List<MonitorDevice>,
) : Parcelable

enum class MonitorSecurityProtocol {
    WPA,
    WPA2,
}

enum class MonitorSsidVisibility {
    UNKNOWN,
    VISIBLE,
    HIDDEN,
}

@Parcelize
data class MonitorDevice(
    val mac: String,
    val name: String?,
    val frameGroups: List<MonitorFrameGroupStatistics> = emptyList(),
    val handshakes: List<MonitorHandshakeRecord> = emptyList(),
    val realtime: MonitorDeviceRealtime = MonitorDeviceRealtime(),
) : Parcelable

@Parcelize
data class MonitorHandshakeRecord(
    val id: String,
    val startUnixMillis: Long,
    val durationMillis: Long,
    val status: MonitorHandshakeStatus,
    val canValidate: Boolean,
    val validationDataComplete: Boolean = false,
    val capturedSteps: List<MonitorHandshakeStep> = emptyList(),
    val failedAtStep: MonitorHandshakeStep? = null,
    val failureReason: MonitorHandshakeFailureReason? = null,
    val m2AttemptCount: Int = 0,
    val exportPacketCount: Int = 0,
) : Parcelable

enum class MonitorHandshakeStatus {
    IN_PROGRESS,
    SUCCESS,
    FAILED,
}

enum class MonitorHandshakeStep {
    AUTHENTICATION,
    ASSOCIATION,
    EAPOL_MESSAGE_1,
    EAPOL_MESSAGE_2,
    EAPOL_MESSAGE_3,
    EAPOL_MESSAGE_4,
    DISCONNECTION,
}

enum class MonitorHandshakeFailureReason {
    ROUTER_REJECTED_CONNECTION,
    M2_RETRY_LIMIT_EXCEEDED,
    DISCONNECTED_AFTER_M2,
    DISCONNECTED_DURING_HANDSHAKE,
    REPLACED_BY_NEW_ATTEMPT,
}

@Parcelize
data class MonitorFrameGroupStatistics(
    val id: String,
    val displayName: String,
    val packetCount: Long,
    val byteCount: Long,
    val subtypes: List<MonitorFrameSubtypeStatistics>,
) : Parcelable

@Parcelize
data class MonitorFrameSubtypeStatistics(
    val id: String,
    val displayName: String,
    val packetCount: Long,
    val byteCount: Long,
) : Parcelable

@Parcelize
data class MonitorDeviceRealtime(
    val uploadBytesPerSecond: Long = 0L,
    val downloadBytesPerSecond: Long = 0L,
    val signal: MonitorSignalStatistics? = null,
) : Parcelable

@Parcelize
data class MonitorSignalStatistics(
    val latestDbm: Int,
    val averageDbm: Float,
    val minimumDbm: Int,
    val maximumDbm: Int,
    val sampleCount: Int,
    val lastSeenUnixMillis: Long,
) : Parcelable
