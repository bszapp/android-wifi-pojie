package io.github.bszapp.wifitoolbox.contract.task

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * # 授权网络在线字典安全测试
 *
 * 一批网络占用一个通用任务；同一 SSID 在运行队列中只出现一次。Service 使用安全测试
 * 专用系统/网卡执行器，按最久未尝试优先调度；超时/拒绝接入按配置重试及指数冷却。6 表示无限
 * 重试，0 表示不重试。队列为空即结束本批任务，后续添加创建新任务 ID。
 *
 * 字典内容通过 FD 流传输，不进入 Binder Parcelable。每个网络记录由 Service 分配 UUID，
 * 保留字典位置、尝试计数、用时和结果；App 分页读取并持久化历史，不自行推进任务。
 * 增加或替换字典需要再次取得授权确认。UI 的配置、三秒等待、勾选和展示状态不属于任务。
 * 系统测试按旧版更新配置；空密码使用已有配置。每轮断开并保留配置，下一轮继续更新。
 * 网络移除、字典耗尽或执行异常后遗忘配置；成功的网络保留配置。不备份或恢复原密码。
 * 网卡方式独立控制脚本终端及清理，不修改 Android 保存配置，不复用普通连通性测试任务。
 */
enum class SecurityAuditConnectionMethod { SYSTEM, NETWORK_CARD }

@Parcelize
data class SecurityAuditConfig(
    val connectionMethod: SecurityAuditConnectionMethod = SecurityAuditConnectionMethod.SYSTEM,
    val connection: ConnectWifiTaskConfig = ConnectWifiTaskConfig(
        timeoutMillis = 5000,
        failureFlags = ConnectWifiFailureFlags(false, HandshakeAttemptsExceededFlag(1), null),
    ),
    val retryCount: Int = 6,
    val cooldownBaseMillis: Long = 0,
    val handshakeTimeoutMillis: Long = 1500,
    val maxHandshakeAttempts: Int = 1,
    val randomMac: Boolean = false,
) : Parcelable

@Parcelize
data class SecurityAuditNetworkInput(
    val ssid: String,
    val dictionaryId: String,
    val dictionaryNames: List<String>,
    val resourceIds: List<String>,
    val startIndex: Int = 0,
) : Parcelable

@Parcelize
data class SecurityAuditTaskInput(
    val config: SecurityAuditConfig,
    val network: SecurityAuditNetworkInput,
) : Parcelable

enum class SecurityAuditNetworkState { QUEUED, CONNECTING, COOLDOWN, SUCCEEDED, EXHAUSTED, STOPPED, ERROR }

@Parcelize
data class SecurityAuditNetworkRecord(
    val id: String,
    val input: SecurityAuditNetworkInput,
    val config: SecurityAuditConfig,
    val startedAt: Long,
    val updatedAt: Long,
    val state: SecurityAuditNetworkState,
    val total: Int,
    val completed: Int = 0,
    val attempts: Long = 0,
    val retryCount: Int = 0,
    val elapsedMillis: Long = 0,
    val averageAttemptMillis: Long? = null,
    val nextAttemptAt: Long? = null,
    val stage: ConnectWifiStage? = null,
    val message: String = "排队中",
    val password: String? = null,
    val dictionaryPosition: Int? = null,
) : Parcelable {
    val active: Boolean get() = state == SecurityAuditNetworkState.QUEUED ||
        state == SecurityAuditNetworkState.CONNECTING || state == SecurityAuditNetworkState.COOLDOWN
}

@Parcelize
data class SecurityAuditNetworkPage(
    val revision: Long,
    val fromIndex: Int,
    val totalCount: Int,
    val networks: List<SecurityAuditNetworkRecord>,
) : Parcelable
