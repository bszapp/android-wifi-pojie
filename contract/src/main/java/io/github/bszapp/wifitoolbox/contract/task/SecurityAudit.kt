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
 * 每个网络只在首次启动时提供完整字典，运行中不可替换。新增网络仍需授权确认。
 * UI 的配置、三秒等待、勾选和展示状态不属于任务。
 * 系统测试按旧版更新配置；空密码使用已有配置。每轮断开并保留配置，下一轮继续更新。
 * 网络移除、字典耗尽或执行异常后遗忘配置；成功的网络保留配置。不备份或恢复原密码。
 * 网卡方式独立控制脚本终端及清理，不修改 Android 保存配置，不复用普通连通性测试任务。
 */
enum class SecurityAuditConnectionMethod { SYSTEM, NETWORK_CARD }

/**
 * 网卡方式的 MAC 策略：设备 MAC 始终使用本机真实地址。
 *
 * 异常时更换从首次连接起就使用随机地址并保持不变，同一密码的连续重试次数达到
 * [SecurityAuditConfig.macChangeThreshold] 时，在该次连接前换成新的随机地址，换过之后
 * 仍失败则继续换；推进到下一个字典项会把重试计数清零，此时沿用已换过的地址。
 * 每次更换在每次连接前都使用新的随机地址。
 * 用于评估路由器对异常环境的风控能力。
 */
enum class SecurityAuditMacMode { DEVICE, ON_FAILURE, EVERY_ATTEMPT }

@Parcelize
data class SecurityAuditConfig(
    val connectionMethod: SecurityAuditConnectionMethod = SecurityAuditConnectionMethod.SYSTEM,
    val connection: ConnectWifiTaskConfig = ConnectWifiTaskConfig(
        timeoutMillis = 5000,
        failureFlags = ConnectWifiFailureFlags(false, HandshakeAttemptsExceededFlag(1), null),
    ),
    val retryCount: Int = 6,
    val cooldownBaseMillis: Long = 1000,
    val handshakeTimeoutMillis: Long = 1500,
    val maxHandshakeAttempts: Int = 1,
    val macMode: SecurityAuditMacMode = SecurityAuditMacMode.DEVICE,
    val macChangeThreshold: Int = 1,
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

