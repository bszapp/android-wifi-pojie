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
 * 同一个 Service 进程同时只允许一个运行中任务，不提供排队。任务请求被接受后才分配 ID；
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
 * 都最多保存 50,000 行，清空后日志 ID 继续递增。回调仅表示权威数据范围发生变化，App
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
 * ## WpsPbcTask
 *
 * WPS-PBC 任务只允许在混合扫描模式已经完成初始化时启动。任务通过 Service 管理的
 * chroot 终端运行 `/wlantool` 工作目录中的 `python wps.py -i wlan0 --pbc`；指定目标时追加
 * `-mac <BSSID>`。Service 必须把脚本 stdout 与 stderr 的每一条非空输出原样写入任务日志，
 * 并从脚本输出的 Selected AP、WPA PSK 和 AP SSID 行组合出捕获结果。App 可以在任务运行
 * 期间更新持续捕获、自动保存、不使用完整协议和忽略重复握手设备选项。持续捕获完全由
 * Service 控制：本轮脚本结束后，根据最新配置重新运行同一脚本；脚本本身不接收持续捕获
 * 控制命令。每轮脚本启动都必须显式传入当时的不使用完整协议选项；开启忽略重复握手设备时，
 * 还必须通过 `-exclude` 传入本任务此前已经捕获到的全部 BSSID。运行时更新这两个脚本选项时，
 * Service 必须结束当前一轮脚本，并立即使用更新后的完整参数重新启动；不得等待持续捕获自然
 * 进入下一轮。自动保存只改变 Service 收到新凭据后的保存行为。
 * 保存网络时只新增或更新 Android 网络配置，并明确关闭该配置的自动加入，不触发连接。捕获
 * 结果按每次获取顺序保留，不按 BSSID、SSID 或密码去重；其中 mac 表示目标接入点 BSSID。
 * 混合扫描模式只约束任务的启动时机。任务获得 ID 后独立运行，切换信息源不得停止任务；
 * 只有显式停止任务、任务自行结束或 Service 进程结束时才结束当前任务终端。
 */

enum class TaskExecutionState {
    RUNNING,
    FINISHED,
}

@Parcelize
sealed class TaskProgress : Parcelable {
    data class ConnectWifi(
        val stage: ConnectWifiStage,
    ) : TaskProgress()

    data class WpsPbc(
        val continuousCapture: Boolean,
        val autoSaveToDevice: Boolean,
        val useIncompleteProtocol: Boolean,
        val ignoreRepeatedDevices: Boolean,
        val networks: List<WpsCapturedNetwork>,
    ) : TaskProgress()
}

typealias ConnectWifiTaskProgress = TaskProgress.ConnectWifi

enum class ConnectWifiStage {
    ROUTER_COMMUNICATION,
    WPA_HANDSHAKE_1_OF_4,
    WPA_HANDSHAKE_2_OF_4,
    WPA_HANDSHAKE_3_OF_4,
    WPA_HANDSHAKE_4_OF_4,
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
) : Parcelable

@Parcelize
data class WpsCapturedNetwork(
    val ssid: String,
    val mac: String,
    val password: String,
) : Parcelable

@Parcelize
sealed class TaskRequestPayload : Parcelable {
    data class ConnectWifi(
        val request: ConnectWifiTaskRequest,
    ) : TaskRequestPayload()

    data class WpsPbc(
        val input: WpsPbcTaskInput,
    ) : TaskRequestPayload()
}

@Parcelize
sealed class TaskUpdatePayload : Parcelable {
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
    }
}

@Parcelize
data class TaskSnapshot(
    val taskId: Long,
    val request: TaskStartRequest,
    val state: TaskExecutionState,
    val progress: TaskProgress?,
) : Parcelable
