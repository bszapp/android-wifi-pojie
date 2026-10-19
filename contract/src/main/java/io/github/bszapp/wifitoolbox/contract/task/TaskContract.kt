package io.github.bszapp.wifitoolbox.contract.task

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * # Service 长时间任务契约
 *
 * ## 所有权
 *
 * Service 是任务、任务状态和任务日志的唯一权威来源。App 只能提交任务、请求停止、
 * 查询快照和按日志 ID 范围读取数据。App 与 UI 不得根据本地推测改写 Service 任务状态。
 *
 * ## 并发与任务 ID
 *
 * 普通任务同时只允许一个运行；弱密码检测任务共享一个调度器，可各自排队、冷却或占用窗口。
 * 任务请求被接受后才分配 ID；
 * ID 从 1 开始，在 Service 生命周期内严格递增且不复用。被任务占用或参数校验拒绝的请求
 * 不分配 ID。Service 重启后任务及其 ID 空间重新开始。
 *
 * ## 状态
 *
 * 任务获得 ID 后立即处于 [TaskExecutionState.RUNNING]。连接任务通过
 * [ConnectWifiTaskProgress] 表示当前阶段。任务离开执行器后统一进入
 * [TaskExecutionState.FINISHED]；FINISHED 不携带成功、失败或停止结果，具体结果只写入
 * 该任务日志。配置表单、提交中、弹窗显示等均属于 UI 状态，不得进入本契约。
 *
 * ## 停止
 *
 * 停止采用协作式语义。Service 收到停止指令后中断可中断的等待，并在系统调用返回后尽快
 * 结束。停止标记仅由 Service 内部维护，不属于任务公开状态。
 *
 * ## 日志
 *
 * 每个任务拥有独立日志 ID 范围，同时所有任务日志组成一个长期显示的全局范围。两种范围
 * 不设自动裁剪总量上限，日志 ID 从 0 开始，清空后继续递增。回调仅表示权威数据范围发生变化，App
 * 必须根据回调提供的 generation 和 ID 范围重新读取，不得把回调顺序当作最终状态顺序。
 *
 * ## ConnectWifiTask
 *
 * 连接任务通过 [ConnectWifiTaskType] 记录 UI 应显示“使用已保存的网络连接”还是“连接到
 * 网络”，通过 [ConnectWifiTarget] 独立记录实际连接目标。已保存目标使用 Android networkId；
 * 临时目标用于测试连通性。Service 必须删除同 SSID 的现有 WifiConfiguration（如有），再以
 * 任务输入的 SSID 和密码新增测试配置并使用该配置连接。该删除操作不可恢复；UI 在确认存在
 * 已保存配置时必须额外取得用户确认。任务无论以成功、失败、超时、停止或异常结束，都必须
 * 断开测试连接并删除当前测试配置。Service 必须先订阅长期运行的 Wi-Fi 日志分析器，再提交
 * 连接请求。进度和
 * 失败判断只使用订阅后收到的分析事件。出现其他
 * SSID 的任意分析事件时，本任务立即结束并在日志中记录失败。WPA 1/4 开始握手步骤计时；
 * 每次 2/4 将握手计数加一，计数大于配置的最大次数时立即结束；Key negotiation completed
 * 表示连接成功。所有结论都写入任务日志。
 *
 * ### 网卡测试目标
 *
 * [ConnectWifiTarget.NetworkCard] 仅在普通网卡模式、Root 服务和已安装容器下启动。
 * Service 通过 chroot 中的 managed_connect.py 直接使用 nl80211 和 EAPOL 完成
 * WPA2-PSK/CCMP 测试，不新增、更新或删除 Android 保存配置。已有连接先由网卡断开。
 * passwords 的每一项对应一次有效 M1 后发送的 M2；再次收到 M1 且没有下一项密码时
 * 立即中断，不循环、不复用末项。mac/name 为 null 时使用网卡当前 MAC / 系统设备名称。
 * 成功条件包含密钥协商及 DHCP 地址读回，IP 只写入日志；随后立即断开。停止及所有
 * 失败路径均须清理本次 IP、断开本次连接并恢复原 MAC。协议原始包、状态码和清理结果
 * 全部记录到通用任务日志。FINISHED 仍不携带结果，UI 不得推测结果。
 *
 * ## WpsPbcTask
 *
 * WPS-PBC 任务只允许在普通网卡模式且容器环境已配置时启动，与系统扫描或底层扫描来源无关。
 * 终端创建统一检查 Root 权限及容器安装情况。任务通过 Service 管理的
 * chroot 终端运行 `/wlantool` 工作目录中的 wps.py 双 FIFO 会话。run 命令指定 mode=pbc、
 * mac、localMac、excludeMacs、completeProtocol；终端使用输入中的interfaceName。FIFO log事件原样携带脚本每条非空输出，包括
 * E-Hash、AuthKey 等调试内容；控制台保留相同输出，Service 只把 FIFO 日志写入任务日志一次。
 * progress 事件携带 requestId、stage、packet、direction；阶段及 M1～M8 的发送/接收
 * 更新 [WpsHandshakeProgress]，调试内容留在通用日志中，不塞进任务快照。
 * 并从 FIFO result 中读取凭据。App 可以在任务运行
 * 期间更新持续捕获、自动保存、不使用完整协议和忽略重复握手设备选项。持续捕获完全由
 * Service 控制：本轮结束后，根据最新配置发送下一条 PBC run；脚本本身不控制持续捕获。
 * 每轮请求都显式传入当时的完整协议选项；开启忽略重复握手设备时，
 * 还必须通过 excludeMacs 传入本任务此前已经捕获到的全部 BSSID。运行时更新这两个脚本选项时，
 * Service 必须 cancel 当前一轮并等待清理，然后立即使用更新后的完整参数重新提交；不得等待持续捕获自然
 * 进入下一轮。自动保存只改变 Service 收到新凭据后的保存行为。
 * 保存网络时只新增或更新 Android 网络配置，并明确关闭该配置的自动加入，不触发连接。捕获
 * 结果按每次获取顺序保留，不按 BSSID、SSID 或密码去重；其中 mac 表示目标接入点 BSSID。
 * 普通网卡模式只约束任务的启动时机。任务获得 ID 后独立运行，切换扫描来源不得停止任务；
 * 会话初始化时释放所选网卡的当前连接；仅wlan0挂起系统supplicant，整个任务保持挂起，退出前确认恢复。
 * 其他网卡不暂停系统控制。本机MAC在每轮结束或取消后恢复。
 * 只有显式停止任务、任务自行结束或 Service 进程结束时才结束当前任务终端。
 *
 * ## WpsPinTask
 *
 * 仅使用用户输入的一个 PIN，不枚举或生成 PIN。普通模式的 Root 服务从当前扫描快照中
 * 选择同 SSID、带 WPS 广播的最强接入点，PIN 是否可用由实际握手返回。通过 chroot 中 wps.py 的双 FIFO 会话发送
 * run 请求（requestId、mac、pin、可选 localMac），接收 ready/log/progress/result/stopped。localMac 为本机
 * MAC，留空使用该轮开始时网卡的 MAC；每轮结束或取消后恢复原 MAC 和网卡 flags。会话初始化时复用
 * managed_connect的当前连接释放逻辑。终端使用输入中的interfaceName，仅wlan0挂起系统supplicant，
 * 结束时恢复并确认后才结束任务；其他网卡不暂停系统控制。
 * 脚本普通控制台输出原样进入任务日志；凭据通过 FIFO 结果进入现有 WPS 结果分页。
 * 获取后自动调用同一保存网络 API，不自动连接；保存失败通过错误广播报告并保留结果。
 * FINISHED 仍仅表示执行结束，具体成败写入日志。
 *
 * ## UsbMonitorTask
 *
 * 电脑控制只在 Root 服务、monitor 模式和已安装容器下启动。Service 暂停自身的扫描、
 * 跳频和录制，保留已捕获数据，再通过 libterminal 的 chroot 终端运行 USB FunctionFS。
 * 任务不设运行时限；USB 配置连接与电脑采集程序连接分别由实际 USB 事件和协议请求确认。
 * 拔线后任务等待重新连接。显式停止、服务退出或执行异常时须还原原 USB 配置；确认还原
 * 完成后才结束任务并交还网卡。退出不恢复信道，USB 交还 Android（如 ADB）。
 * UI 不自行维护任务 ID 或电脑连接状态。
 */

enum class TaskExecutionState {
    RUNNING,
    FINISHED,
}

@Parcelize
sealed class TaskProgress : Parcelable {
    data class PskSecurityAudit(
        val revision: Long,
        val networkCount: Int,
        val config: PskSecurityAuditConfig,
        /** 当前选中且可用的网卡窗口；系统连接方式为空列表。 */
        val windows: List<String> = emptyList(),
    ) : TaskProgress()

    data class WpsSecurityAudit(
        val revision: Long,
        val networkCount: Int,
        val config: WpsSecurityAuditConfig,
    ) : TaskProgress()

    data class ConnectWifi(
        val stage: ConnectWifiStage,
    ) : TaskProgress()

    data class WpsPbc(
        val continuousCapture: Boolean,
        val autoSaveToDevice: Boolean,
        val useIncompleteProtocol: Boolean,
        val ignoreRepeatedDevices: Boolean,
        val networkCount: Int,
        val handshake: WpsHandshakeProgress? = null,
    ) : TaskProgress()

    data class WpsPin(
        val targetMac: String?,
        val networkCount: Int,
        val handshake: WpsHandshakeProgress = WpsHandshakeProgress(WpsHandshakeStage.PREPARING),
    ) : TaskProgress()

    data class UsbMonitor(
        val usbConnected: Boolean,
        val clientConnected: Boolean,
        val capturing: Boolean,
    ) : TaskProgress()
}

typealias ConnectWifiTaskProgress = TaskProgress.ConnectWifi

/** 当前实际观察到的 WPS 阶段；packet 可为 M1～M8、M2D、WSC_Done 或 WSC_NACK。 */
@Parcelize
data class WpsHandshakeProgress(
    val stage: WpsHandshakeStage,
    val packet: String? = null,
    val direction: WpsPacketDirection? = null,
) : Parcelable

enum class WpsPacketDirection { SENDING, RECEIVED }

enum class WpsHandshakeStage {
    PREPARING, SCANNING, AUTHENTICATING, ASSOCIATING, ASSOCIATED,
    EAPOL_START, IDENTITY_REQUEST, IDENTITY_RESPONSE, HANDSHAKE,
    PROTOCOL_COMPLETED, SAVING_NETWORK, RESTORING_SYSTEM,
}

enum class ConnectWifiStage {
    ROUTER_COMMUNICATION,
    WPA_HANDSHAKE_1_OF_4,
    WPA_HANDSHAKE_2_OF_4,
    WPA_HANDSHAKE_3_OF_4,
    WPA_HANDSHAKE_4_OF_4,
    IP_NEGOTIATION,
}

enum class ConnectWifiTaskType {
    USE_SAVED_NETWORK,
    CONNECT_TO_NETWORK,
}

@Parcelize
sealed class ConnectWifiTarget : Parcelable {
    data class SavedNetwork(
        val networkId: Int,
    ) : ConnectWifiTarget()

    data class TemporaryNetwork(
        val ssid: String,
        val password: String,
    ) : ConnectWifiTarget()

    data class NetworkCard(
        val ssid: String,
        val passwords: List<String>,
        val mac: String? = null,
        val name: String? = null,
    ) : ConnectWifiTarget()
}

@Parcelize
data class ConnectWifiTaskInput(
    val type: ConnectWifiTaskType,
    val target: ConnectWifiTarget,
) : Parcelable

@Parcelize
data class HandshakeAttemptsExceededFlag(
    val maxHandshakeAttempts: Int,
) : Parcelable

@Parcelize
data class HandshakeTimeoutFlag(
    val handshakeStepTimeoutMillis: Long,
) : Parcelable

@Parcelize
data class ConnectWifiFailureFlags(
    val passwordError: Boolean,
    val handshakeAttemptsExceeded: HandshakeAttemptsExceededFlag?,
    val handshakeTimeout: HandshakeTimeoutFlag?,
) : Parcelable

@Parcelize
data class ConnectWifiTaskConfig(
    val timeoutMillis: Long,
    val failureFlags: ConnectWifiFailureFlags,
) : Parcelable

@Parcelize
data class ConnectWifiTaskRequest(
    val input: ConnectWifiTaskInput,
    val config: ConnectWifiTaskConfig,
) : Parcelable

@Parcelize
data class WpsPbcTaskInput(
    val continuousCapture: Boolean = false,
    val autoSaveToDevice: Boolean = true,
    val useIncompleteProtocol: Boolean = true,
    val ignoreRepeatedDevices: Boolean = true,
    val targetMac: String? = null,
    val interfaceName: String = "wlan0",
    val localMac: String? = null,
) : Parcelable

@Parcelize
data class WpsCapturedNetwork(
    val ssid: String,
    val mac: String,
    val password: String,
) : Parcelable

@Parcelize
data class WpsPinTaskInput(val ssid: String, val pin: String, val localMac: String? = null,
    val interfaceName: String = "wlan0") : Parcelable

@Parcelize
sealed class TaskRequestPayload : Parcelable {
    data class PskSecurityAudit(val input: PskSecurityAuditTaskInput) : TaskRequestPayload()

    data class WpsSecurityAudit(val input: WpsSecurityAuditTaskInput) : TaskRequestPayload()

    data class ConnectWifi(
        val request: ConnectWifiTaskRequest,
    ) : TaskRequestPayload()

    data class WpsPbc(
        val input: WpsPbcTaskInput,
    ) : TaskRequestPayload()

    data class WpsPin(val input: WpsPinTaskInput) : TaskRequestPayload()

    data object UsbMonitor : TaskRequestPayload()
}

@Parcelize
sealed class TaskUpdatePayload : Parcelable {
    data class PskSecurityAuditRemove(val ssid: String, val mac: String? = null) : TaskUpdatePayload()
    data class PskSecurityAuditStopRecord(val recordId: String) : TaskUpdatePayload()
    /** 停止当前网络在指定候选池内的全部成员，不影响共享该池的其他网络。 */
    data class PskSecurityAuditStopGroup(val ssid: String, val groupId: String) : TaskUpdatePayload()
    data class PskSecurityAuditConfigure(val config: PskSecurityAuditConfig) : TaskUpdatePayload()

    data class WpsSecurityAuditAdd(val targets: List<WpsSecurityAuditTarget>) : TaskUpdatePayload()
    /** PIN漏洞检测运行中可追加网络、更新配置、移除明确目标。 */
    data class WpsSecurityAuditConfigure(val config: WpsSecurityAuditConfig) : TaskUpdatePayload()

    data class WpsPbcContinuousCapture(
        val enabled: Boolean,
    ) : TaskUpdatePayload()

    data class WpsPbcAutoSaveToDevice(
        val enabled: Boolean,
    ) : TaskUpdatePayload()

    data class WpsPbcUseIncompleteProtocol(
        val enabled: Boolean,
    ) : TaskUpdatePayload()

    data class WpsPbcIgnoreRepeatedDevices(
        val enabled: Boolean,
    ) : TaskUpdatePayload()
}

@Parcelize
data class TaskUpdateRequest(
    val payload: TaskUpdatePayload,
) : Parcelable

@Parcelize
data class TaskStartRequest(
    val payload: TaskRequestPayload,
) : Parcelable {
    companion object {
        fun usbMonitor() = TaskStartRequest(TaskRequestPayload.UsbMonitor)

        fun wpsPin(ssid: String, pin: String, localMac: String? = null, interfaceName: String = "wlan0") =
            TaskStartRequest(TaskRequestPayload.WpsPin(WpsPinTaskInput(ssid, pin, localMac, interfaceName)))

        fun connectWifi(
            input: ConnectWifiTaskInput,
            config: ConnectWifiTaskConfig,
        ) = TaskStartRequest(
            payload = TaskRequestPayload.ConnectWifi(
                request = ConnectWifiTaskRequest(input, config),
            ),
        )

        fun wpsPbc(
            input: WpsPbcTaskInput = WpsPbcTaskInput(),
        ) = TaskStartRequest(
            payload = TaskRequestPayload.WpsPbc(input),
        )

        fun wpsPskSecurityAudit(input: WpsSecurityAuditTaskInput) = TaskStartRequest(
            payload = TaskRequestPayload.WpsSecurityAudit(input),
        )
    }
}

@Parcelize
data class TaskSnapshot(
    val taskId: Long,
    val request: TaskStartRequest,
    val state: TaskExecutionState,
    val progress: TaskProgress?,
) : Parcelable

/** 累计结果按索引读取；允许同一网络重复出现。 */
@Parcelize
data class WpsCapturedNetworkPage(
    val fromIndex: Int,
    val totalCount: Int,
    val networks: List<WpsCapturedNetwork>,
) : Parcelable
