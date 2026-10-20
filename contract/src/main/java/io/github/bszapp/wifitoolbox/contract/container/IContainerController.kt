package io.github.bszapp.wifitoolbox.contract.container

import android.os.Parcelable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize

interface IContainerController {
    val state: StateFlow<ContainerState>

    fun install()
    fun update()
    fun reset()
    fun uninstall()
    fun interrupt(operationId: Long)
}

@Parcelize
data class ContainerState(
    val systemStatus: ContainerSystemStatus = ContainerSystemStatus.CHECKING,
    val installed: Boolean = false,
    val operation: ContainerOperation? = null,
    val progress: ContainerProgress? = null,
    val errorMessage: String? = null,
    val revision: Long = 0L,
    val operationId: Long = 0L,
) : Parcelable {
    val isBusy: Boolean
        get() = systemStatus == ContainerSystemStatus.WORKING

}

enum class ContainerSystemStatus {
    CHECKING,
    NOT_INSTALLED,
    INSTALLED,
    WORKING,
    ERROR,
}

enum class ContainerOperation(val title: String) {
    INSTALL("安装容器系统"),
    UPDATE("更新容器系统"),
    RESET("重置容器"),
    UNINSTALL("卸载容器"),
}

@Parcelize
data class ContainerProgress(
    val message: String,
    val detail: String,
    val fraction: Float,
) : Parcelable

/** 容器文件操作环境；Root 服务负责时由系统安装记录生成，App 负责时使用自身数据目录。 */
@Parcelize
data class ContainerEnvironment(
    val appDataPath: String,
) : Parcelable

/**
 * 安装、更新、重置需要同时传入只读压缩包文件描述符。
 * offset / length 限定描述符内的压缩包范围，支持直接读取 APK 中未压缩的 asset。
 * 卸载不需要压缩包。服务已运行容器操作时忽略新的请求。
 */
@Parcelize
data class ContainerOperationRequest(
    val operation: ContainerOperation,
    val archiveOffset: Long = 0L,
    val archiveLength: Long = 0L,
) : Parcelable
