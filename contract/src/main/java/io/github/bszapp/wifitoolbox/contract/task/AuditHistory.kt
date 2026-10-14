package io.github.bszapp.wifitoolbox.contract.task

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * # 安全测试运行快照
 *
 * 服务调度与 App 实时同步使用本类型，运行现场不直接持久化。
 * 持久历史使用独立的 [AuditHistoryRecord]。
 */
enum class AuditRecordKind { DICTIONARY, WPS }

@Parcelize
sealed class AuditRecord : Parcelable {
    abstract val kind: AuditRecordKind
    abstract val id: String
    abstract val ssid: String
    abstract val startedAt: Long
    abstract val updatedAt: Long
    abstract val state: SecurityAuditNetworkState
    abstract val attempts: Long
    abstract val message: String
    abstract val password: String?

    val active: Boolean
        get() = state == SecurityAuditNetworkState.QUEUED ||
            state == SecurityAuditNetworkState.CONNECTING ||
            state == SecurityAuditNetworkState.COOLDOWN

    /** 已得到凭据或已给出确定结论的记录。 */
    val settled: Boolean
        get() = !active

    /** 进度百分比；WPS 的仙尘阶段不适用，返回 null。 */
    abstract val progress: Float?

    @Parcelize
    data class Dictionary(
        override val id: String,
        val input: SecurityAuditNetworkInput,
        val config: SecurityAuditConfig,
        override val startedAt: Long,
        override val updatedAt: Long,
        override val state: SecurityAuditNetworkState,
        val total: Int,
        val completed: Int = 0,
        override val attempts: Long = 0,
        val retryCount: Int = 0,
        val elapsedMillis: Long = 0,
        val averageAttemptMillis: Long? = null,
        val nextAttemptAt: Long? = null,
        val stage: ConnectWifiStage? = null,
        override val message: String = "排队中",
        override val password: String? = null,
        val dictionaryPosition: Int? = null,
    ) : AuditRecord() {
        override val kind: AuditRecordKind get() = AuditRecordKind.DICTIONARY
        override val ssid: String get() = input.ssid
        override val progress: Float
            get() = if (total <= 0) 0f else completed.toFloat() / total
    }

    @Parcelize
    data class Wps(
        override val id: String,
        override val ssid: String,
        val config: WpsSecurityAuditConfig,
        override val startedAt: Long,
        override val updatedAt: Long,
        override val state: SecurityAuditNetworkState,
        override val attempts: Long = 0,
        override val message: String = "排队中",
        override val password: String? = null,
        val mac: String? = null,
        val phase: WpsSecurityAuditPhase = WpsSecurityAuditPhase.FAIRY_DUST,
        val vulnPin: String? = null,
        val firstHalf: String? = null,
        /** [WpsSecurityAuditPhase.FIRST_HALF] 当前游标，`0..10000`。 */
        val firstHalfIndex: Int = 0,
        /** [WpsSecurityAuditPhase.SECOND_HALF] 当前游标，`0..1000`。 */
        val secondHalfIndex: Int = 0,
        val retryCount: Int = 0,
        val nextAttemptAt: Long? = null,
        val averageAttemptMillis: Long? = null,
        /** 当前会话实时阶段，仅用于服务快照与界面同步，不写入历史文件。 */
        val handshakeProgress: WpsHandshakeProgress? = null,
        val handshake: WpsHandshake? = null,
        val elapsedMillis: Long = 0,
        /** 最终取得网络密码时使用的 PIN，与离线计算出的 [vulnPin] 分开记录。 */
        val pin: String? = null,
        /** 取得密码时所处阶段；[phase] 在完成后统一为 DONE。旧记录未知时保留 null。 */
        val passwordFoundPhase: WpsSecurityAuditPhase? = null,
    ) : AuditRecord() {
        override val kind: AuditRecordKind get() = AuditRecordKind.WPS
        override val progress: Float?
            get() = when (phase) {
                WpsSecurityAuditPhase.FAIRY_DUST -> null
                WpsSecurityAuditPhase.FIRST_HALF -> firstHalfIndex.coerceAtMost(10000) / 10000f
                WpsSecurityAuditPhase.SECOND_HALF -> secondHalfIndex.coerceAtMost(1000) / 1000f
                WpsSecurityAuditPhase.DONE -> 1f
            }
    }
}

/** App 数据库历史；不含调度状态、运行配置或实时提示。 */
sealed class AuditHistoryRecord {
    abstract val id: String
    abstract val kind: AuditRecordKind
    abstract val ssid: String
    abstract val updatedAt: Long
    abstract val password: String?

    data class Dictionary(
        override val id: String,
        override val ssid: String,
        val passwords: List<String>,
        val completed: Int,
        override val password: String?,
        override val updatedAt: Long,
        val elapsedMillis: Long,
        val averageAttemptMillis: Long?,
        val dictionaryPosition: Int?,
    ) : AuditHistoryRecord() {
        override val kind get() = AuditRecordKind.DICTIONARY
        val total get() = passwords.size
    }

    data class Wps(
        override val id: String,
        override val ssid: String,
        val mac: String?,
        override val updatedAt: Long,
        val phase: WpsSecurityAuditPhase,
        val firstHalf: String?,
        val firstHalfIndex: Int,
        val secondHalfIndex: Int,
        val attempts: Long,
        val elapsedMillis: Long,
        val averageAttemptMillis: Long?,
        val pin: String?,
        override val password: String?,
        val passwordFoundPhase: WpsSecurityAuditPhase?,
    ) : AuditHistoryRecord() {
        override val kind get() = AuditRecordKind.WPS
    }
}

/** 分页读取安全测试历史；[revision] 变化表示本任务的网络列表已更新。 */
@Parcelize
data class AuditRecordPage(
    val revision: Long,
    val fromIndex: Int,
    val totalCount: Int,
    val records: List<AuditRecord>,
) : Parcelable
