package io.github.bszapp.wifitoolbox.contract.wifilist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 仅由该设备的认证、关联和 EAPOL 证据确定，不使用 AP 广播能力推断。 */
enum class MonitorDeviceProtocol { UNKNOWN, WPA_PSK, WPA2_PSK, WPA2_PSK_SHA256, SAE, OWE, OTHER }

enum class MonitorDecryptionStatus {
    PROTOCOL_UNKNOWN, NO_PASSWORD, PASSWORD_MISMATCH, INCOMPLETE_HANDSHAKE,
    READY, SECURE, UNSUPPORTED, LOADING,
}

enum class MonitorCommunicationType { DNS, HTTP, HTTPS, DHCP }

/** 仅按设备分页读取，不放入网卡状态广播。正文在服务端追加存储。 */
@Parcelize
data class MonitorCommunicationRecord(
    val id: String,
    val timestampUnixMillis: Long,
    val type: MonitorCommunicationType,
    val source: String,
    val destination: String,
    val summary: String,
    val complete: Boolean,
) : Parcelable

@Parcelize
data class MonitorCommunicationPage(
    val sessionGeneration: Long,
    val fromIndex: Long,
    val nextIndex: Long,
    val totalCount: Long,
    val records: List<MonitorCommunicationRecord>,
) : Parcelable

/** 游标是服务给出的不透明值；单页正文最多 64 KiB。-1 表示没有上一/下一页。 */
@Parcelize
data class MonitorCommunicationDetailPage(
    val sessionGeneration: Long,
    val recordId: String,
    val cursor: Long,
    val previousCursor: Long,
    val nextCursor: Long,
    val bytes: ByteArray,
    val totalBytes: Long,
    val complete: Boolean,
) : Parcelable
