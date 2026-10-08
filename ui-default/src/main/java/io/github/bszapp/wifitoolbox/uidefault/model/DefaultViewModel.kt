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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.bszapp.wifitoolbox.contract.startup.RuntimeUpdateTarget

data class ConnectWifiSheetCloseRequest(
    val id: Long,
    val sheetInstanceId: String,
)

class DefaultViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: IAppController = AppControllerProvider.get()

    /** App 内唯一错误广播源，UI 不再监听任何 Wi-Fi 专用错误流。 */
    val errors = controller.errors
    val serviceLogs = controller.serviceLogs
    val appLogs = controller.appLogs
    val terminals = controller.terminals
    val tasks = controller.tasks
    val containerState = controller.containers.state

    val startup = StartupUiState(controller, viewModelScope) { exit, error ->
        showConfirmationDialog(
            title = "服务退出失败",
            content = "${error.message ?: error.javaClass.name}\n\n确认断开与服务的连接？服务可能仍在运行，未完成保存的任务数据可能丢失。" +
                if (exit) "确认后将退出应用。" else "确认后将返回工作模式选择界面。",
            confirmButtonText = "确认断开",
            onConfirmed = { controller.startup.disconnect(exit) },
        )
    }
    val wifiList = WifiListUiState(
        controller = controller,
        scope = viewModelScope,
    )
    private val taskTracker = TaskTracker(controller.tasks, viewModelScope)
    val currentTask: StateFlow<TaskSnapshot?> = taskTracker.currentTask
    val displayedTask: StateFlow<TrackedTaskState?> = taskTracker.trackedTask

    private val confirmationDialogManager =
        UiConfirmationDialogManager(viewModelScope)
    val confirmationDialogs = confirmationDialogManager.dialogs

    init {
        viewModelScope.launch {
            var updateDialogId: Long? = null
            controller.runtimeUpdates.prompt.collect { prompt ->
                updateDialogId?.let(confirmationDialogManager::dismiss)
                updateDialogId = prompt?.let {
                    val serviceUpdate = it.target == RuntimeUpdateTarget.SERVICE
                    val failed = it.errorMessage != null
                    showConfirmationDialog(
                        title = when {
                            failed -> "服务退出失败"
                            serviceUpdate -> "服务版本有更新"
                            else -> "容器系统有更新"
                        },
                        content = when {
                            failed -> "${it.errorMessage}\n\n确认断开旧服务，并按原权限方式强制重启？旧服务尚未保存的任务数据可能丢失。"
                            serviceUpdate -> "是否立即重启服务？服务将正常退出，并沿用初始化时的权限加载新版本。"
                            else -> "容器系统未安装、缺少版本记录或版本低于应用。是否使用应用内置容器覆盖安装？已有文件会替换，其他文件会保留。"
                        },
                        confirmButtonText = if (failed) "强制重启" else "确定",
                        onDismissed = { controller.runtimeUpdates.dismiss(it) },
                        onCancelled = { controller.runtimeUpdates.dismiss(it) },
                        onConfirmed = { controller.runtimeUpdates.confirm(it) },
                    )
                }
            }
        }
    }

    private var nextConnectWifiSheetCloseRequestId = 1L
    private val _connectWifiSheetCloseRequest =
        MutableStateFlow<ConnectWifiSheetCloseRequest?>(null)
    val connectWifiSheetCloseRequest:
        StateFlow<ConnectWifiSheetCloseRequest?> =
        _connectWifiSheetCloseRequest

    suspend fun startTask(request: TaskStartRequest): Long = taskTracker.startTask(request)

    suspend fun startWpsPbcTask(): Long = startTask(TaskStartRequest.wpsPbc())

    suspend fun startUsbMonitorTask(): Long = startTask(TaskStartRequest.usbMonitor())

    fun stopUsbMonitorTask() {
        currentTask.value?.takeIf {
            it.request.payload is io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload.UsbMonitor
        }?.let { controller.tasks.stopTask(it.taskId) }
    }

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

    suspend fun startNetworkCardConnectTask(
        ssid: String,
        passwords: List<String>,
        mac: String?,
        name: String?,
        config: ConnectWifiTaskConfig,
    ): Long = startTask(
        TaskStartRequest.connectWifi(
            input = ConnectWifiTaskInput(
                type = ConnectWifiTaskType.CONNECT_TO_NETWORK,
                target = ConnectWifiTarget.NetworkCard(ssid, passwords, mac, name),
            ),
            config = config,
        ),
    )

    fun showConfirmationDialog(
        title: String,
        content: String,
        cancelButtonText: String = "取消",
        confirmButtonText: String = "确定",
        onDismissed: suspend () -> Unit = {},
        onCancelled: suspend () -> Unit = {},
        onConfirmed: suspend () -> Unit = {},
        onDismissFinished: suspend () -> Unit = {},
    ): Long = confirmationDialogManager.show(
        title = title,
        content = content,
        cancelButtonText = cancelButtonText,
        confirmButtonText = confirmButtonText,
        onDismissed = onDismissed,
        onCancelled = onCancelled,
        onConfirmed = onConfirmed,
        onDismissFinished = onDismissFinished,
    )

    fun dismissConfirmationDialog(dialogId: Long) {
        confirmationDialogManager.dismiss(dialogId)
    }

    fun cancelConfirmationDialog(dialogId: Long) {
        confirmationDialogManager.cancel(dialogId)
    }

    fun confirmConfirmationDialog(dialogId: Long) {
        confirmationDialogManager.confirm(dialogId)
    }

    fun confirmationDialogDismissFinished(dialogId: Long) {
        confirmationDialogManager.onDismissFinished(dialogId)
    }

    fun confirmSavedConfigurationConnectivityTest(
        sheetInstanceId: String,
        ssid: String,
        password: String,
        config: ConnectWifiTaskConfig,
    ) {
        showConfirmationDialog(
            title = "确认开始测试",
            content = "该网络存在已保存配置。开始后会永久删除原配置，测试结束时只删除测试配置，不会恢复原配置。确定继续？",
            confirmButtonText = "开始",
            onConfirmed = {
                runCatching {
                    startTemporaryNetworkConnectTask(
                        ssid = ssid,
                        password = password,
                        config = config,
                    )
                }.onSuccess {
                    _connectWifiSheetCloseRequest.value =
                        ConnectWifiSheetCloseRequest(
                            id = nextConnectWifiSheetCloseRequestId++,
                            sheetInstanceId = sheetInstanceId,
                        )
                }
            },
        )
    }

    fun consumeConnectWifiSheetCloseRequest(requestId: Long) {
        if (_connectWifiSheetCloseRequest.value?.id == requestId) {
            _connectWifiSheetCloseRequest.value = null
        }
    }

    fun displayTask(taskId: Long) = taskTracker.track(taskId)

    fun stopDisplayedTask() = taskTracker.stopTrackedTask()

    fun updateDisplayedTask(update: TaskUpdateRequest) = taskTracker.updateTrackedTask(update)

    fun closeDisplayedTask() = taskTracker.clearTracking()

    fun installContainer() = controller.containers.install()
    fun updateContainer() = controller.containers.update()
    fun resetContainer() = controller.containers.reset()
    fun uninstallContainer() = controller.containers.uninstall()
    fun interruptContainer(operationId: Long) = controller.containers.interrupt(operationId)

    //TODO: 废弃的API
}
