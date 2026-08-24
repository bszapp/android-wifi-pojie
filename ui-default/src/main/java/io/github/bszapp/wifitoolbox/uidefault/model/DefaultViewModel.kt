package io.github.bszapp.wifitoolbox.uidefault.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTarget
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskConfig
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskInput
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.uidefault.model.task.TaskTracker
import kotlinx.coroutines.flow.StateFlow

class DefaultViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: IAppController = AppControllerProvider.get()

    /** App 内唯一错误广播源，UI 不再监听任何 Wi-Fi 专用错误流。 */
    val errors = controller.errors
    val serviceLogs = controller.serviceLogs
    val terminals = controller.terminals
    val tasks = controller.tasks
    val containerState = controller.containers.state

    val startup = StartupUiState(controller, viewModelScope)
    val wifiList = WifiListUiState(
        controller = controller,
        scope = viewModelScope,
    )
    private val taskTracker = TaskTracker(controller.tasks, viewModelScope)
    val currentTask: StateFlow<TaskSnapshot?> = taskTracker.currentTask
    val displayedTask: StateFlow<TrackedTaskState?> = taskTracker.trackedTask

    suspend fun startTask(request: TaskStartRequest): Long = taskTracker.startTask(request)

    suspend fun startWpsPbcTask(): Long = startTask(TaskStartRequest.wpsPbc())

    suspend fun startSavedNetworkConnectTask(
        networkId: Int,
        type: ConnectWifiTaskType,
        config: ConnectWifiTaskConfig,
    ): Long = startTask(
        TaskStartRequest.connectWifi(
            input = ConnectWifiTaskInput(
                type = type,
                target = ConnectWifiTarget.SavedNetwork(networkId),
            ),
            config = config,
        ),
    )

    suspend fun startTemporaryNetworkConnectTask(
        ssid: String,
        password: String,
        config: ConnectWifiTaskConfig,
    ): Long = startTask(
        TaskStartRequest.connectWifi(
            input = ConnectWifiTaskInput(
                type = ConnectWifiTaskType.CONNECT_TO_NETWORK,
                target = ConnectWifiTarget.TemporaryNetwork(ssid, password),
            ),
            config = config,
        ),
    )

    fun displayTask(taskId: Long) = taskTracker.track(taskId)

    fun stopDisplayedTask() = taskTracker.stopTrackedTask()

    fun updateDisplayedTask(update: TaskUpdateRequest) = taskTracker.updateTrackedTask(update)

    fun closeDisplayedTask() = taskTracker.clearTracking()

    fun installContainer() = controller.containers.install()
    fun updateContainer() = controller.containers.update()
    fun resetContainer() = controller.containers.reset()
    fun uninstallContainer() = controller.containers.uninstall()

    //TODO: 废弃的API
    fun startContainerTerminal() = controller.containers.startTerminal()
    fun stopContainerTerminal() = controller.containers.stopTerminal()
    fun runContainerWifiScan() = controller.containers.runWifiScan()
}
