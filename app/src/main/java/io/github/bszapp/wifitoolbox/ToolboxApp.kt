package io.github.bszapp.wifitoolbox

import android.app.Application
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.hashcat.HashcatStartupCheck
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.error.AppErrorFormatter
import io.github.bszapp.wifitoolbox.error.ErrorReportManager
import io.github.bszapp.wifitoolbox.container.ContainerController
import io.github.bszapp.wifitoolbox.contract.container.IContainerController
import io.github.bszapp.wifitoolbox.contract.error.AppError
import io.github.bszapp.wifitoolbox.contract.startup.IStartupController
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorMapFilterState
import io.github.bszapp.wifitoolbox.launcher.ProcessLauncher
import io.github.bszapp.wifitoolbox.logs.ServiceLogController
import io.github.bszapp.wifitoolbox.logs.AppLogController
import io.github.bszapp.wifitoolbox.terminal.TerminalController
import io.github.bszapp.wifitoolbox.task.TaskController
import io.github.bszapp.wifitoolbox.navigation.PredictiveBackController
import io.github.bszapp.wifitoolbox.settings.SettingsManager
import io.github.bszapp.wifitoolbox.wifilist.WifiListController
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

    lateinit var errorReports: ErrorReportManager
        private set
    private var normalStartupStarted = false
    internal val hasStartedRuntime: Boolean
        get() = normalStartupStarted

    private lateinit var processLauncher: ProcessLauncher
    private lateinit var wifiListController: WifiListController
    private lateinit var serviceLogController: ServiceLogController
    private lateinit var appLogController: AppLogController
    private lateinit var terminalController: TerminalController
    private lateinit var taskController: TaskController
    private lateinit var hashcatController: io.github.bszapp.wifitoolbox.hashcat.HashcatTaskController
    private lateinit var containerController: ContainerController
    override lateinit var settings: SettingsManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var predictiveBackController: PredictiveBackController
    private val _exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val exitRequests: SharedFlow<Unit> = _exitRequests.asSharedFlow()
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
            processLauncher.stop {
                if (exit) {
                    appScope.launch {
                        delay(200.milliseconds)
                        Process.killProcess(Process.myPid())
                    }
                    _exitRequests.tryEmit(Unit)
                }
            }
        }
    }

    override val wifiList: IWifiListController
        get() = wifiListController

    override val serviceLogs: ServiceLogController
        get() = serviceLogController

    override val appLogs: AppLogController
        get() = appLogController

    override val terminals: TerminalController
        get() = terminalController

    override val tasks: TaskController
        get() = taskController

    override val hashcat: io.github.bszapp.wifitoolbox.contract.hashcat.IHashcatController
        get() = hashcatController

    override val containers: IContainerController
        get() = containerController

    override fun onCreate() {
        super.onCreate()
        errorReports = ErrorReportManager(this)
        errorReports.install(
            captureLogs = { file ->
                if (::appLogController.isInitialized) appLogController.saveCapturedLogs(file)
            },
            onFatalError = {
                // Only App-owned work is cancelled. No service shutdown request is sent.
                appScope.cancel()
            },
        )
        // MainActivity selects report-only or normal startup from its launch Intent.
        // Cached reports alone never put a newly opened App into report mode.
    }

    internal fun startNormalRuntime() {
        errorReports.cancelScheduledRestart()
        initializeRuntime()
    }

    /** Start a fresh App process through normal onCreate; leave the service untouched. */
    fun restartFromErrorReport() = errorReports.restartApplication()

    private fun initializeRuntime() {
        if (normalStartupStarted) return
        normalStartupStarted = true
        appLogController = AppLogController(scope = appScope)
        appLogController.start()
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
        hashcatController = io.github.bszapp.wifitoolbox.hashcat.HashcatTaskController(this, appScope, ::publishError)
        containerController = ContainerController(
            context = this,
            scope = appScope,
            reportError = ::publishError,
        )
        processLauncher = ProcessLauncher(
            context = this,
            onBeforeServiceStop = hashcatController::prepareServiceShutdown,
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
                hashcatController.connect(service)
            },
            onServiceDisconnected = {
                wifiListController.disconnect()
                serviceLogController.disconnect()
                terminalController.disconnect()
                taskController.disconnect()
                containerController.disconnect()
                hashcatController.disconnect()
            },
        )
        AppControllerProvider.register(this)
        processLauncher.tryAutoReconnect()
        appScope.launch(Dispatchers.IO) {
            HashcatStartupCheck.run(this@ToolboxApp)
        }
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

        val details = AppErrorFormatter.format(
            source = source,
            operation = operation,
            error = error,
            remoteDetails = remoteDetails,
            timestampMillis = now,
        ).fullText

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

    override fun onTerminate() {
        super.onTerminate()
        if (::predictiveBackController.isInitialized) predictiveBackController.stop()
        if (::terminalController.isInitialized) terminalController.close()
        if (::appLogController.isInitialized) appLogController.close()
        appScope.cancel()
    }

    private companion object {
        const val TAG = "ToolboxApp"
    }
}
