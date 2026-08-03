package io.github.bszapp.wifitoolbox.contract.container

import kotlinx.coroutines.flow.StateFlow

interface IContainerController {
    val state: StateFlow<ContainerState>

    fun install()
    fun update()
    fun reset()
    fun uninstall()
    fun startTerminal()
    fun stopTerminal()
    fun runWifiScan()
}

data class ContainerState(
    val systemStatus: ContainerSystemStatus = ContainerSystemStatus.CHECKING,
    val installed: Boolean = false,
    val operation: ContainerOperation? = null,
    val progress: ContainerProgress? = null,
    val errorMessage: String? = null,
    val terminalStatus: ContainerTerminalStatus = ContainerTerminalStatus.STOPPED,
    val terminalMessage: String = "",
    val scanRunning: Boolean = false,
    val scanOutput: String = "",
    val scanExitCode: Int? = null,
) {
    val isBusy: Boolean
        get() = systemStatus == ContainerSystemStatus.WORKING

    val terminalReady: Boolean
        get() = terminalStatus == ContainerTerminalStatus.READY
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

data class ContainerProgress(
    val message: String,
    val detail: String,
    val fraction: Float,
)

enum class ContainerTerminalStatus {
    STOPPED,
    STARTING,
    READY,
    STOPPING,
    ERROR,
}
