package io.github.bszapp.wifitoolbox.contract.task

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * # 在线弱密码检测
 *
 * 通用任务管理器中同时只有一个弱密码检测任务。一次提交可在它内部创建多个测试记录，
 * 即使目标 SSID 相同，各记录也有自己的记录 ID 和运行状态；日志归属同一个顶层任务。
 * Service 使用弱密码检测专用系统/网卡执行器，按最久未尝试优先调度，默认单线程串行执行；
 * 网卡方式只使用 [PskSecurityAuditConfig.selectedInterfaces] 选中的可用网卡并行执行，
 * 每个窗口一次只跑一个密码，内部测试做完一次尝试即回到队尾重新排号。
 * 超时/拒绝接入按配置重试及指数冷却。6 表示无限重试，0 表示不重试。
 * 队列为空时调度器与顶层任务一同结束；之后再次提交才创建新的顶层任务 ID。
 *
 * 字典内容通过 FD 流传输，不进入 Binder Parcelable。顶层任务通过通用任务 ID 停止，
 * 内部测试记录通过记录 ID 单独停止；
 * 历史记录另有跨 Service 生命周期稳定的存储键。
 * 保留字典位置、尝试计数、同 SSID 同候选池的累计用时和结果；App 分页读取并持久化历史，不自行推进任务。
 * 独立测试首次启动时提供完整字典，运行中不可替换。选择已有测试记录共享时复用一份候选池，
 * 同组成员按领取法占用候选：各网卡领取互不相同的候选；只选一张时串行运行，
 * 未选网卡时保持排队；某成员发现密码后，服务停止同组其余成员，其他独立记录继续运行。
 * UI 的配置和展示状态不属于任务。
 * 系统测试按旧版更新配置；空密码使用已有配置。每轮断开并保留配置，下一轮继续更新。
 * 单条记录停止、字典耗尽或执行异常后遗忘配置；成功的网络保留配置。不备份或恢复原密码。
 * 网卡方式独立控制脚本终端及清理，不修改 Android 保存配置，不复用普通连通性测试任务。
 */
enum class PskSecurityAuditConnectionMethod { SYSTEM, NETWORK_CARD }

/**
 * 网卡方式的 MAC 策略：设备 MAC 始终使用本机真实地址。
 *
 * 异常时更换从首次连接起就使用随机地址并保持不变，同一密码的连续重试次数达到
 * [PskSecurityAuditConfig.macChangeThreshold] 时，在该次连接前换成新的随机地址，换过之后
 * 仍失败则继续换；推进到下一个字典项会把重试计数清零，此时沿用已换过的地址。
 * 每次更换在每次连接前都使用新的随机地址。
 * 用于评估路由器对异常环境的风控能力。
 */
enum class PskSecurityAuditMacMode { DEVICE, ON_FAILURE, EVERY_ATTEMPT }

@Parcelize
data class PskSecurityAuditConfig(
    val connectionMethod: PskSecurityAuditConnectionMethod = PskSecurityAuditConnectionMethod.SYSTEM,
    val connection: ConnectWifiTaskConfig = ConnectWifiTaskConfig(
        timeoutMillis = 5000,
        failureFlags = ConnectWifiFailureFlags(false, HandshakeAttemptsExceededFlag(1), null),
    ),
    val retryCount: Int = 6,
    val cooldownBaseMillis: Long = 1000,
    val handshakeTimeoutMillis: Long = 1500,
    val maxHandshakeAttempts: Int = 1,
    val macMode: PskSecurityAuditMacMode = PskSecurityAuditMacMode.DEVICE,
    val macChangeThreshold: Int = 1,
    /**
     * 网卡多选（实验性），仅网卡连接方式使用。空列表表示暂停分配，已有记录保持排队。
     * 每张选中且可用的网卡一次只跑一个密码；仅wlan0会话暂停系统supplicant。
     */
    val selectedInterfaces: List<String> = listOf("wlan0"),
    val detailedLogging: Boolean = false,
) : Parcelable

@Parcelize
data class PskSecurityAuditNetworkInput(
    val ssid: String,
    val dictionaryId: String,
    val dictionaryNames: List<String>,
    val resourceIds: List<String>,
    val startIndex: Int = 0,
    /** 共享现有内部测试记录的候选池；为 null 时提交一份新字典。 */
    val sharedWithRecordId: String? = null,
) : Parcelable

@Parcelize
data class PskSecurityAuditTaskInput(
    val config: PskSecurityAuditConfig,
    val network: PskSecurityAuditNetworkInput,
) : Parcelable

enum class PskSecurityAuditNetworkState { QUEUED, CONNECTING, COOLDOWN, SUCCEEDED, EXHAUSTED, STOPPED, ERROR }

