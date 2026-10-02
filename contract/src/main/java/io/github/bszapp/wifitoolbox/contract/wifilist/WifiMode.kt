package io.github.bszapp.wifitoolbox.contract.wifilist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class WifiMode(
    val wireValue: Int,
    val displayName: String,
) {
    NORMAL(0, "普通模式"),
    MONITOR(2, "监听模式");

    companion object {
        fun fromWireValue(value: Int): WifiMode =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("未知 Wi-Fi 信息源: $value")
    }
}

@Parcelize
data class WifiModeState(
    val mode: WifiMode,
    val hybridScanEnabled: Boolean = false,
    val capturing: Boolean = false,
    val hoppingCapture: Boolean = false,
    val clearingCapture: Boolean = false,
    val availableChannels: List<MonitorChannel> = emptyList(),
    val monitorStatistics: MonitorModeStatistics? = null,
    val modeSwitch: WifiModeSwitch? = null,
    val captureClearProgress: MonitorCaptureClearProgress? = null,
) : Parcelable

enum class MonitorCaptureClearStage { PREPARING, SWITCHING, FINISHED }

/** 清理操作进度；完成后保留内容供 Sheet 退场，不作为网卡模式。 */
@Parcelize
data class MonitorCaptureClearProgress(
    val handshakesOnly: Boolean,
    val isRunning: Boolean,
    val stage: MonitorCaptureClearStage = MonitorCaptureClearStage.PREPARING,
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) : Parcelable

/** 切换操作的进度，不代表网卡类型；结束后保留目标供界面退场使用。 */
@Parcelize
data class WifiModeSwitch(
    val targetMode: WifiMode,
    val isRunning: Boolean,
) : Parcelable

@Parcelize
data class MonitorChannel(val channel: Int, val frequencyMhz: Int) : Parcelable

@Parcelize
data class MonitorModeStatistics(
    val sessionGeneration: Long = 0L,
    val revision: Long = 0L,
    val recordedBytes: Long = 0L,
    val channel: Int = 0,
    val frequencyMhz: Int = 0,
    val accessPoints: List<MonitorAccessPoint> = emptyList(),
    val disconnections: List<MonitorDisconnectionRecord> = emptyList(),
    val nonHandshakeBytes: Long = 0L,
) : Parcelable

@Parcelize
data class MonitorMapFilterState(
    val showProbeOnlyDevices: Boolean = true,
    val showUnknownNetworks: Boolean = false,
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
    val probeOnly: Boolean = false,
) : Parcelable

@Parcelize
data class MonitorHandshakeRecord(
    val id: String,
    val startUnixMillis: Long,
    val durationMillis: Long,
    val status: MonitorHandshakeStatus,
    val canValidate: Boolean,
    val captureQuality: MonitorHandshakeCaptureQuality = MonitorHandshakeCaptureQuality.COMPLETE,
    val capturedSteps: List<MonitorHandshakeStep> = emptyList(),
    val failedAtStep: MonitorHandshakeStep? = null,
    val failureReason: MonitorHandshakeFailureReason? = null,
    val m2AttemptCount: Int = 0,
    val exportPacketCount: Int = 0,
    val hc22000: String? = null,
) : Parcelable

enum class MonitorHandshakeCaptureQuality {
    COMPLETE,
    DATA_INCOMPLETE,
    PARTIALLY_MISSING,
}

enum class MonitorHandshakeStatus {
    IN_PROGRESS,
    SUCCESS,
    FAILED,
    UNKNOWN,
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
data class MonitorDisconnectionRecord(
    val id: String,
    val timestampUnixMillis: Long,
    val bssid: String,
    val deviceMac: String,
    val type: MonitorDisconnectionType,
    val reasonCode: Int? = null,
    val exportPacketCount: Int = 1,
) : Parcelable

enum class MonitorDisconnectionType {
    DISASSOCIATION,
    DEAUTHENTICATION,
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
