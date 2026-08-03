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
 * 连接任务的输入目前只有 Android 已保存网络的 networkId。Service 必须先订阅长期运行的
 * Wi-Fi 日志分析器，再调用 IWifiManager.enableNetwork(networkId, true)。进度和失败判断只
 * 使用订阅后收到的分析事件。出现其他 SSID 的任意分析事件时，本任务立即结束并在日志中
 * 记录失败。WPA 1/4 开始握手步骤计时；每次 2/4 将握手计数加一，计数大于配置的最大次数
 * 时立即结束；Key negotiation completed 表示连接成功。所有结论都写入任务日志。
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
}

typealias ConnectWifiTaskProgress = TaskProgress.ConnectWifi

enum class ConnectWifiStage {
    ROUTER_COMMUNICATION,
    WPA_HANDSHAKE_1_OF_4,
    WPA_HANDSHAKE_2_OF_4,
    WPA_HANDSHAKE_3_OF_4,
    WPA_HANDSHAKE_4_OF_4,
}

@Parcelize
data class ConnectWifiTaskInput(
    val networkId: Int,
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
sealed class TaskRequestPayload : Parcelable {
    data class ConnectWifi(
        val request: ConnectWifiTaskRequest,
    ) : TaskRequestPayload()
}

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
    }
}

@Parcelize
data class TaskSnapshot(
    val taskId: Long,
    val request: TaskStartRequest,
    val state: TaskExecutionState,
    val progress: TaskProgress?,
) : Parcelable
