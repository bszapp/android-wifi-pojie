// QA 无头渲染专用 wifi 框架类型桩：成员面 = 列表页 UI 的消费面。
// nullability 对齐 android.jar 的平台类型语义（集中在 BSSID/capabilities/ssid 等使用位）。

package android.net.wifi

import android.os.Parcel
import android.os.Parcelable

private fun unsupported(): Nothing = throw IllegalStateException("渲染桩未实现")

class ScanResult() : Parcelable {
    @JvmField var SSID: String? = null
    @JvmField var BSSID: String = ""
    @JvmField var capabilities: String = "[ESS]"
    @JvmField var frequency: Int = 0
    @JvmField var centerFreq0: Int = 0
    @JvmField var centerFreq1: Int = 0
    @JvmField var level: Int = 0
    @JvmField var timestamp: Long = 0L
    @JvmField var channelWidth: Int = 0
    @JvmField var extra: Long = 0L

    constructor(source: ScanResult) : this() {
        SSID = source.SSID
        BSSID = source.BSSID
        capabilities = source.capabilities
        frequency = source.frequency
        level = source.level
        timestamp = source.timestamp
        channelWidth = source.channelWidth
        extra = source.extra
    }

    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = unsupported()

    companion object CREATOR : Parcelable.Creator<ScanResult> {
        const val WIFI_STANDARD_LEGACY = 0
        const val WIFI_STANDARD_11N = 4
        const val WIFI_STANDARD_11AC = 5
        const val WIFI_STANDARD_11AX = 6
        const val WIFI_STANDARD_11AD = 7
        const val WIFI_STANDARD_11BE = 8
        const val CHANNEL_WIDTH_20MHZ = 0
        const val CHANNEL_WIDTH_40MHZ = 1
        const val CHANNEL_WIDTH_80MHZ = 2
        const val CHANNEL_WIDTH_160MHZ = 3
        const val CHANNEL_WIDTH_80MHZ_PLUS_MHZ = 4

        override fun createFromParcel(source: Parcel): ScanResult = ScanResult()
        override fun newArray(size: Int): Array<ScanResult?> = arrayOfNulls(size)
    }
}

enum class SupplicantState {
    DISCONNECTED,
    INTERFACE_DISABLED,
    INACTIVE,
    SCANNING,
    AUTHENTICATING,
    ASSOCIATING,
    ASSOCIATED,
    FOUR_WAY_HANDSHAKE,
    GROUP_HANDSHAKE,
    COMPLETED,
    DORMANT,
    UNINITIALIZED,
    INVALID,
}

class WifiInfo : Parcelable {
    var SSID: String? = null
    var ssid: String = ""
    var bssid: String? = null
    var networkId: Int = -1
    var frequency: Int = 0
    var rssi: Int = 0
    var linkSpeed: Int = 0
    var txLinkSpeedMbps: Int = 0
    var rxLinkSpeedMbps: Int = 0
    var supplicantState: SupplicantState = SupplicantState.COMPLETED
    var wifiStandard: Int = 0
    var ipAddress: Int = 0
    var hiddenSSID: Boolean = false
    var apMldMacAddress: String = ""
    var apMloLinkId: Int = -1
    var currentSecurityType: Int = SECURITY_TYPE_PSK
    var macAddress: String = "02:00:00:00:00:00"
    var receivedSignalStrengthIndicator: Int = 0

    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = unsupported()

    companion object CREATOR : Parcelable.Creator<WifiInfo> {
        const val SECURITY_TYPE_OPEN = 0
        const val SECURITY_TYPE_WEP = 1
        const val SECURITY_TYPE_PSK = 2
        const val SECURITY_TYPE_EAP = 3
        const val SECURITY_TYPE_SAE = 4
        const val SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT = 5
        const val SECURITY_TYPE_OWE = 6
        const val SECURITY_TYPE_WAPI_PSK = 7
        const val SECURITY_TYPE_WAPI_CERT = 8
        const val SECURITY_TYPE_EAP_WPA3_ENTERPRISE = 9
        const val SECURITY_TYPE_OSEN = 10
        const val SECURITY_TYPE_PASSPOINT_R1_R2 = 11
        const val SECURITY_TYPE_PASSPOINT_R3 = 12
        const val SECURITY_TYPE_DPP = 13

        override fun createFromParcel(source: Parcel): WifiInfo = WifiInfo()
        override fun newArray(size: Int): Array<WifiInfo?> = arrayOfNulls(size)
    }
}

class WifiConfiguration : Parcelable {
    var SSID: String? = null
    var BSSID: String? = null
    var networkId: Int = -1
    var status: Int = Status.ENABLED
    var preSharedKey: String? = null
    var hiddenSSID: Boolean = false
    var priority: Int = 0
    val allowedKeyManagement: java.util.BitSet = java.util.BitSet()
    val allowedAuthAlgorithms: java.util.BitSet = java.util.BitSet()
    val allowedProtocols: java.util.BitSet = java.util.BitSet()
    val allowedPairwiseCiphers: java.util.BitSet = java.util.BitSet()
    val allowedGroupCiphers: java.util.BitSet = java.util.BitSet()
    val wepKeys: Array<String?> = arrayOfNulls(4)
    var macRandomizationSetting: Int = RANDOMIZATION_PERSISTENT
    var randomizedMacAddress: String = "02:00:00:00:5e:aa"

    object Status {
        const val ENABLED = 0
        const val DISABLED = 1
        const val CURRENT = 2
    }

    object KeyMgmt {
        const val NONE = 0
        const val WPA_PSK = 1
        const val WPA_EAP = 2
        const val IEEE8021X = 3
        const val WPA2_PSK = 4
        const val SAE = 5
        const val OWE = 6
        const val WAPI_PSK = 7
        const val WAPI_CERT = 8
        const val FILS_SHA256 = 9
        const val FILS_SHA384 = 10
        const val SUITE_B_192 = 11
        const val OSEN = 12
        const val DPP = 13
    }

    object Protocol {
        const val WPA = 0
        const val RSN = 1
        const val WAPI = 2
    }

    object AuthAlgorithm {
        const val OPEN = 0
        const val LEAP = 1
        const val SHARED = 2
    }

    object PairwiseCipher {
        const val NONE = 0
        const val TKIP = 1
        const val CCMP = 2
        const val GCMP_128 = 3
        const val GCMP_256 = 4
    }

    object GroupCipher {
        const val WEP40 = 0
        const val WEP104 = 1
        const val TKIP = 2
        const val CCMP = 3
        const val GCMP_128 = 4
        const val GCMP_256 = 5
    }

    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = unsupported()

    companion object CREATOR : Parcelable.Creator<WifiConfiguration> {
        const val RANDOMIZATION_NONE = 0
        const val RANDOMIZATION_PERSISTENT = 1
        const val RANDOMIZATION_NON_PERSISTENT = 2
        const val RANDOMIZATION_AUTO = 3

        override fun createFromParcel(source: Parcel): WifiConfiguration = WifiConfiguration()
        override fun newArray(size: Int): Array<WifiConfiguration?> = arrayOfNulls(size)
    }
}

class WifiManager {
    companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"

        fun calculateSignalLevel(rssi: Int, numLevels: Int): Int {
            val strong = -55
            val weak = -85
            if (rssi >= strong) return numLevels - 1
            if (rssi <= weak) return 0
            return 1 + (numLevels - 1) * (rssi - weak) / (strong - weak)
        }
    }
}
