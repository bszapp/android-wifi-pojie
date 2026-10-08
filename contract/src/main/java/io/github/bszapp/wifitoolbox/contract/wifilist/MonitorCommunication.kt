package io.github.bszapp.wifitoolbox.contract.wifilist

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 仅由该设备的认证、关联和 EAPOL 证据确定，不使用 AP 广播能力推断。 */
enum class MonitorDeviceProtocol { UNKNOWN, WPA_PSK, WPA2_PSK, WPA2_PSK_SHA256, SAE, OWE, OTHER }

enum class MonitorDecryptionStatus {
    PROTOCOL_UNKNOWN, NO_PASSWORD, PASSWORD_MISMATCH, INCOMPLETE_HANDSHAKE,
    READY, SECURE, UNSUPPORTED, LOADING,
}

enum class MonitorCommunicationType { DNS, HTTP, HTTPS, DHCP, TCP, UDP, ARP, ICMP, OTHER, WPA_HANDSHAKE }
enum class MonitorCommunicationTransport { TCP, UDP, OTHER }

/** 展示正文与原始字节分别保存；RAW_* 导出时不作字符转换。 */
enum class MonitorCommunicationChannel {
    DETAIL, REQUEST, RESPONSE, REQUEST_HEADERS, RESPONSE_HEADERS, REQUEST_BODY, RESPONSE_BODY,
    RAW_REQUEST, RAW_RESPONSE, RAW_REQUEST_BODY, RAW_RESPONSE_BODY, RAW_UPLOAD, RAW_DOWNLOAD, RAW_DNS,
}

/** 全局通信索引中的一条记录，不放入网卡状态广播。正文在服务端追加存储。 */
@Parcelize
data class MonitorCommunicationRecord(
    val id: String,
    val timestampUnixMillis: Long,
    val type: MonitorCommunicationType,
    val source: String,
    val destination: String,
    val summary: String,
    val complete: Boolean,
    val bssid: String = "",
    val deviceMac: String = "",
    val ssid: String = "",
    val deviceName: String = "",
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val revision: Long = 0,
    val matchesSearch: Boolean = true,
    val tcpStreamId: String = "",
    val transport: MonitorCommunicationTransport = MonitorCommunicationTransport.OTHER,
    val method: String = "",
    val url: String = "",
    val domain: String = "",
    val protocol: String = "",
    val statusCode: Int = 0,
    val contentType: String = "",
    val requestHeaderCount: Int = 0,
    val responseHeaderCount: Int = 0,
    /** 已分解为应用层请求的 TCP 容器仅用于读取原始流，不重复列出。 */
    val streamContainer: Boolean = false,
    /** 与解密通信共用列表与分页；PCAP 仍只在用户确认导出后传输。 */
    val handshake: MonitorHandshakeRecord? = null,
) : Parcelable

/** 服务分配的记录 ID 范围；空范围使用 oldestAvailableId > latestId。 */
@Parcelize
data class MonitorCommunicationRange(
    val sessionGeneration: Long,
    val oldestAvailableId: Long,
    val latestId: Long,
) : Parcelable

/** 闭区间；一次请求可以包含多个范围，去重后的 ID 总数不得超过 100。 */
@Parcelize
data class MonitorCommunicationIdRange(val fromId: Long, val toId: Long) : Parcelable

/** processedIds 表示本页已处理的请求 ID；达到字节上限时剩余 ID 由 App 下次请求。 */
@Parcelize
data class MonitorCommunicationPage(
    val range: MonitorCommunicationRange,
    val processedIds: LongArray,
    val records: List<MonitorCommunicationRecord>,
    val keyword: String = "",
) : Parcelable

/** 搜索只返回匹配记录的 ID 和内容版本，不参与记录同步游标。 */
@Parcelize
data class MonitorCommunicationSearchHit(val id: Long, val version: Long) : Parcelable

@Parcelize
data class MonitorCommunicationSearchPage(
    val sessionGeneration: Long,
    val keyword: String,
    val nextId: Long,
    val throughId: Long,
    val hits: List<MonitorCommunicationSearchHit>,
) : Parcelable

/** cursor 为本通道的字节偏移；单页正文最多 64 KiB，App 预取后供 UI 从本地读取。 */
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
    val channel: MonitorCommunicationChannel = MonitorCommunicationChannel.DETAIL,
    val record: MonitorCommunicationRecord? = null,
    val startLine: Long = 1,
) : Parcelable

/** App 本地镜像的查询参数与可见窗口；不通过 Binder 传送。 */
data class MonitorCommunicationQuery(val accessPoint: String = "", val device: String = "", val keyword: String = "",
    val protocols: Set<String> = emptySet(), val contentKinds: Set<String> = emptySet(), val statusGroups: Set<Int> = emptySet())
data class MonitorCommunicationWindow(val session: Long?, val count: Int, val rows: Map<Int, MonitorCommunicationRecord>,
    val viewed: Set<String> = emptySet(),
    val available: MonitorCommunicationAvailability = MonitorCommunicationAvailability())
data class MonitorCommunicationAvailability(val accessPoints: Set<String> = emptySet(), val devices: Set<String> = emptySet(),
    val protocols: Set<String> = emptySet(), val contentKinds: Set<String> = emptySet(), val statusGroups: Set<Int> = emptySet(),
    val devicePairs: Set<String> = emptySet())
