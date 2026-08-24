package io.github.bszapp.wifitoolbox

import android.app.Application
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiException
import io.github.bszapp.wifitoolbox.container.ContainerController
import io.github.bszapp.wifitoolbox.contract.container.IContainerController
import io.github.bszapp.wifitoolbox.contract.error.AppError
import io.github.bszapp.wifitoolbox.contract.startup.IStartupController
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorMapFilterState
import io.github.bszapp.wifitoolbox.launcher.ProcessLauncher
import io.github.bszapp.wifitoolbox.logs.ServiceLogController
import io.github.bszapp.wifitoolbox.terminal.TerminalController
import io.github.bszapp.wifitoolbox.task.TaskController
import io.github.bszapp.wifitoolbox.navigation.PredictiveBackController
import io.github.bszapp.wifitoolbox.settings.SettingsManager
import io.github.bszapp.wifitoolbox.wifilist.WifiListController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class ToolboxApp : Application(), IAppController {

    private lateinit var processLauncher: ProcessLauncher
    private lateinit var wifiListController: WifiListController
    private lateinit var serviceLogController: ServiceLogController
    private lateinit var terminalController: TerminalController
    private lateinit var taskController: TaskController
    private lateinit var containerController: ContainerController
    override lateinit var settings: SettingsManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var predictiveBackController: PredictiveBackController
    private val _isExiting = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val isExiting: StateFlow<Boolean> = _isExiting.asStateFlow()
    private val _monitorMapFilterState = kotlinx.coroutines.flow.MutableStateFlow(
        MonitorMapFilterState(),
    )
    override val monitorMapFilterState: StateFlow<MonitorMapFilterState> =
        _monitorMapFilterState.asStateFlow()

    override fun updateMonitorMapFilterState(state: MonitorMapFilterState) {
        _monitorMapFilterState.value = state
    }

    private val _errors = MutableSharedFlow<AppError>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val errors: SharedFlow<AppError> = _errors.asSharedFlow()

    override val startup = object : IStartupController {
        override val state get() = processLauncher.state
        override fun launch(mode: StartupMode) = processLauncher.launch(mode)
        override fun cancel() = processLauncher.cancel()
        override fun stop(exit: Boolean) {
            processLauncher.stop()
            if (exit) {
                appScope.launch {
                    delay(200.milliseconds)
                    Process.killProcess(Process.myPid())
                }
                _isExiting.value = true
            }
        }
    }

    override val wifiList: IWifiListController
        get() = wifiListController

    override val serviceLogs: ServiceLogController
        get() = serviceLogController

    override val terminals: TerminalController
        get() = terminalController

    override val tasks: TaskController
        get() = taskController

    override val containers: IContainerController
        get() = containerController

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        predictiveBackController = PredictiveBackController(
            application = this,
        ).also { it.start() }
        wifiListController = WifiListController(
            context = this,
            scope = appScope,
            reportError = ::publishError,
        )
        serviceLogController = ServiceLogController(scope = appScope)
        terminalController = TerminalController(
            context = this,
            scope = appScope,
            reportError = ::publishError,
        )
        taskController = TaskController(
            scope = appScope,
            reportError = ::publishError,
        )
        containerController = ContainerController(
            context = this,
            scope = appScope,
            reportError = ::publishError,
        )
        processLauncher = ProcessLauncher(
            context = this,
            onAndroidApiError = { operation, error ->
                publishError(
                    source = "App.AndroidApiClient",
                    operation = operation,
                    error = error,
                    remoteDetails = null,
                )
            },
            onServiceConnected = { service, androidApi ->
                wifiListController.connect(service, androidApi)
                serviceLogController.connect(service)
                terminalController.connect(service)
                taskController.connect(service)
                containerController.connect(service)
            },
            onServiceDisconnected = {
                wifiListController.disconnect()
                serviceLogController.disconnect()
                terminalController.disconnect()
                taskController.disconnect()
                containerController.disconnect()
            },
        )
        AppControllerProvider.register(this)
        processLauncher.tryAutoReconnect()
    }

    /** App 内唯一错误发布入口。UI 只监听 [errors]。 */
    private fun publishError(
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String?,
    ) {
        val now = System.currentTimeMillis()
        val conciseMessage = error.message
            ?.substringBefore("\n\nService ")
            ?.takeIf { it.isNotBlank() }
            ?: error.javaClass.name

        val details = buildString {
            appendLine("时间：${formatTimestamp(now)}")
            appendLine("来源：$source")
            appendLine("操作：$operation")
            appendLine(
                "线程：${Thread.currentThread().name} " +
                    "(id=${Thread.currentThread().id})",
            )
            appendLine("异常类型：${error.javaClass.name}")
            appendLine("异常消息：${error.message ?: "<无>"}")

            val androidApiRemoteStack =
                (error as? AndroidApiException)?.remoteStackTrace
            if (!androidApiRemoteStack.isNullOrBlank()) {
                appendLine()
                appendLine("Service AndroidApi 调用栈：")
                appendLine(androidApiRemoteStack)
            }

            if (!remoteDetails.isNullOrBlank()) {
                appendLine()
                appendLine("Service 远端调用栈：")
                appendLine(remoteDetails)
            }

            appendLine()
            appendLine("App 调用栈：")
            append(error.stackTraceToString())
        }

        val appError = AppError(
            source = source,
            operation = operation,
            message = "$operation：$conciseMessage",
            details = details,
            timestampMillis = now,
        )

        Log.e(TAG, "发布统一错误：${appError.message}\n${appError.details}")
        if (!_errors.tryEmit(appError)) {
            appScope.launch { _errors.emit(appError) }
        }
    }

    private fun formatTimestamp(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(timestampMillis))

    override fun onTerminate() {
        super.onTerminate()
        predictiveBackController.stop()
        terminalController.close()
        appScope.cancel()
    }

    private companion object {
        const val TAG = "ToolboxApp"
    }
}
