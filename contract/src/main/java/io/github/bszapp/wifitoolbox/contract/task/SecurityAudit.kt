package io.github.bszapp.wifitoolbox.contract.task

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * # 授权网络在线字典安全测试
 *
 * 一批网络运行记录占用一个通用任务；同一 SSID 可以有多个独立记录，每条记录有自己的 ID。
 * Service 使用安全测试专用系统/网卡执行器，按最久未尝试优先调度，默认单线程串行执行；
 * 开启 [SecurityAuditConfig.multithreaded]（仅网卡方式）后以可用网卡为窗口并行执行，
 * 每个窗口一次只跑一个密码，任务做完一次尝试即回到队尾重新排号。
 * 超时/拒绝接入按配置重试及指数冷却。6 表示无限重试，0 表示不重试。
 * 队列为空即结束本批任务，后续添加创建新任务 ID。
 *
 * 字典内容通过 FD 流传输，不进入 Binder Parcelable。每条运行记录由 Service 分配 UUID，可按记录 ID 单独停止；停止批次会停止全部记录。
 * 保留字典位置、尝试计数、用时和结果；App 分页读取并持久化历史，不自行推进任务。
 * 独立记录首次启动时提供完整字典，运行中不可替换。选择已有记录共享时复用一份候选池，
 * 同组成员按领取法占用候选：多线程开启时各成员领取互不相同的候选并行尝试，
 * 未开启时同组成员串行领取候选；某成员发现密码后，服务停止同组其余成员，其他独立记录继续运行。
 * 新增网络或加入共享池仍需授权确认。
 * UI 的配置、三秒等待、勾选和展示状态不属于任务。
 * 系统测试按旧版更新配置；空密码使用已有配置。每轮断开并保留配置，下一轮继续更新。
 * 单条记录停止、字典耗尽或执行异常后遗忘配置；成功的网络保留配置。不备份或恢复原密码。
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
    /**
     * 开启多线程运行（实验性）。仅 [SecurityAuditConnectionMethod.NETWORK_CARD] 支持：
     * 服务把可作为普通 Wi-Fi 客户端的网卡当作窗口，把排队记录分配到空闲窗口并行尝试，
     * 每张网卡一次只跑一个密码，做完一次尝试即回到队尾重新排号。
     */
    val multithreaded: Boolean = false,
) : Parcelable

@Parcelize
data class SecurityAuditNetworkInput(
    val ssid: String,
    val dictionaryId: String,
    val dictionaryNames: List<String>,
    val resourceIds: List<String>,
    val startIndex: Int = 0,
    /** 共享现有运行记录的候选池；为 null 时提交一份新字典。 */
    val sharedWithRecordId: String? = null,
    /**
     * 一次「运行」创建的并行实例数（1～6），默认 1 即只创建一条运行记录。
     * 大于 1 时这些实例接进同一个候选池，各网卡按领取法分领互不相同的密码，不重复试同一个。
     */
    val subtaskCount: Int = 1,
) : Parcelable

@Parcelize
data class SecurityAuditTaskInput(
    val config: SecurityAuditConfig,
    val network: SecurityAuditNetworkInput,
) : Parcelable

enum class SecurityAuditNetworkState { QUEUED, CONNECTING, COOLDOWN, SUCCEEDED, EXHAUSTED, STOPPED, ERROR }

