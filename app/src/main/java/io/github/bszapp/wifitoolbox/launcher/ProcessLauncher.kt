package io.github.bszapp.wifitoolbox.launcher

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.startup.AppVersion
import io.github.bszapp.wifitoolbox.contract.startup.RunningException
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.startup.StartupState
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.MainServiceStarter
import io.github.bszapp.wifitoolbox.tools.AndroidApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class ProcessLauncher(
    private val context: Context,
    private val onAndroidApiError: (operation: String, error: Throwable) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var launchJob: Job? = null
    private var brokerWatchJob: Job? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(StartupState())
    val state: kotlinx.coroutines.flow.StateFlow<StartupState> = _state

    private var activeLauncher: AutoCloseable? = null
    private var activeBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    var mainService: IMainService? = null
        private set

    var androidApiClient: AndroidApiClient? = null
        private set

    private fun cleanupActive() {
        cleanupDeathRecipientOnly()
        activeLauncher?.closeQuietly()
        activeLauncher = null
    }

    private fun cleanupDeathRecipientOnly() {
        deathRecipient?.let { recipient ->
            runCatching { activeBinder?.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null
        activeBinder = null
        mainService = null
        androidApiClient = null
    }

    fun tryAutoReconnect() {
        ensureBrokerWatcher()

        val binder = ToolboxServiceProvider.getAliveBinder()
        if (binder == null) {
            _state.value = StartupState(status = StartupStatus.IDLE)
            return
        }

        launchJob?.cancel()
        launchJob = scope.launch {
            if (!canAcceptBrokerBinder()) return@launch
            val info = validateBrokerBinder(binder) ?: run {
                ToolboxServiceProvider.clearBinder()
                _state.value = StartupState(status = StartupStatus.IDLE)
                return@launch
            }
            Log.d(TAG, "发现可信的已存在服务 Binder，直接恢复连接")
            connectWithBinder(binder, info.startupMode.toStartupMode(), startupInfoOverride = info)
        }
    }

    private fun ensureBrokerWatcher() {
        if (brokerWatchJob?.isActive == true) return

        brokerWatchJob = scope.launch {
            ToolboxServiceProvider.binderFlow
                .filter { it?.isBinderAlive == true }
                .collect { binder ->
                    val aliveBinder = binder ?: return@collect
                    if (!canAcceptBrokerBinder()) return@collect
                    val info = validateBrokerBinder(aliveBinder) ?: return@collect
                    Log.d(TAG, "收到服务主动投递的可信 Binder，恢复连接")
                    connectWithBinder(aliveBinder, info.startupMode.toStartupMode(), startupInfoOverride = info)
                }
        }
    }

    private fun canAcceptBrokerBinder(): Boolean =
        !(activeBinder?.isBinderAlive == true && mainService != null)

    /**
     * 预连接校验：只读取 StartupInfo，不更新 App 自己的连接状态。
     * 如果 UID 不匹配或服务未初始化，就把它当作不存在的旧服务。
     */
    private fun validateBrokerBinder(binder: IBinder): StartupInfo? {
        if (!binder.isBinderAlive) return null
        return runCatching {
            val service = IMainService.Stub.asInterface(binder)
            val info = service.getStartupInfo()
            if (!info.isTrustedForAppUid(Process.myUid())) {
                Log.w(TAG, "忽略服务 Binder：trustedUid=${info.trustedUid} appUid=${Process.myUid()}")
                return null
            }
            if (info.startupMode.toStartupMode() == null) {
                Log.w(TAG, "忽略服务 Binder：未知启动模式 ${info.startupMode}")
                return null
            }
            info
        }.onFailure {
            Log.w(TAG, "忽略无法验证的服务 Binder：${it.message}")
        }.getOrNull()
    }

    private fun connectWithBinder(
        binder: IBinder,
        requestedMode: StartupMode?,
        startupInfoOverride: StartupInfo? = null,
    ) {
        try {
            if (!binder.isBinderAlive) {
                Log.w(TAG, "connectWithBinder 收到死亡 Binder")
                cleanupActive()
                _state.value = StartupState(
                    status = StartupStatus.ERROR,
                    selectedMode = requestedMode,
                    errorException = Exception("服务 Binder 已死亡")
                )
                return
            }

            if (activeBinder?.isBinderAlive == true && mainService != null) return

            val service = IMainService.Stub.asInterface(binder)
            val launchInfo = startupInfoOverride ?: requestedMode?.let { createStartupInfo(it) }
            launchInfo?.let { service.initializeStartupInfo(it) }

            val startupInfo = startupInfoOverride ?: service.getStartupInfo()
            if (!startupInfo.isTrustedForAppUid(Process.myUid())) {
                throw SecurityException("服务启动信息 trustedUid=${startupInfo.trustedUid} 与 App UID=${Process.myUid()} 不一致")
            }

            val serviceMode = startupInfo.startupMode.toStartupMode()
            if (!service.connect()) {
                Log.w(TAG, "connect() 被服务拒绝")
                cleanupActive()
                _state.value = StartupState(
                    status = StartupStatus.ERROR,
                    selectedMode = serviceMode ?: requestedMode,
                    errorException = Exception("connect() 被服务拒绝")
                )
                return
            }

            cleanupDeathRecipientOnly()

            activeBinder = binder
            mainService = service
            androidApiClient = AndroidApiClient(
                service = service,
                onError = onAndroidApiError,
            )

            val recipient = IBinder.DeathRecipient {
                Log.e(TAG, "${serviceMode.displayName()} 服务进程崩溃或被终止")
                scope.launch(Dispatchers.Main) {
                    if (_state.value.status == StartupStatus.RUNNING) {
                        cleanupActive()
                        _state.value = StartupState(
                            status = StartupStatus.ERROR,
                            selectedMode = serviceMode,
                            errorException = RunningException("服务进程被强制停止")
                        )
                    }
                }
            }

            binder.linkToDeath(recipient, 0)
            deathRecipient = recipient

            _state.value = StartupState(
                status = StartupStatus.RUNNING,
                selectedMode = serviceMode,
                serviceUid = startupInfo.serviceUid,
                serviceUidStr = startupInfo.serviceUidText,
                servicePid = startupInfo.servicePid,
                serviceVersionName = startupInfo.versionName,
                serviceVersionCode = startupInfo.versionCode
            )

            Log.d(
                TAG,
                "${serviceMode.displayName()} 服务启动成功，uid=${startupInfo.serviceUid} " +
                        "uidStr=${startupInfo.serviceUidText} pid=${startupInfo.servicePid} " +
                        "version=${startupInfo.versionName}(${startupInfo.versionCode})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "connectWithBinder 失败: ${e.message}")
            cleanupActive()
            _state.value = StartupState(
                status = StartupStatus.ERROR,
                selectedMode = requestedMode,
                errorException = Exception("服务连接失败：${e.message ?: e::class.java.name}", e)
            )
        }
    }

    fun launch(mode: StartupMode) {
        val modeName = mode.displayName()
        Log.d(TAG, "开始以 $modeName 模式启动服务")

        ensureBrokerWatcher()
        launchJob?.cancel()
        launchJob = scope.launch {
            _state.value = StartupState(
                status = StartupStatus.LAUNCHING,
                selectedMode = mode
            )
            cleanupActive()

            runCatching {
                withTimeout(5.seconds) {
                    val (launcher, binder) = createLauncherAndBinder(mode)
                    activeLauncher = launcher
                    if (_state.value.status == StartupStatus.RUNNING &&
                        activeBinder?.isBinderAlive == true &&
                        mainService != null
                    ) {
                        return@withTimeout
                    }
                    connectWithBinder(binder, mode)
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) return@onFailure

                if (_state.value.status == StartupStatus.RUNNING &&
                    activeBinder?.isBinderAlive == true &&
                    mainService != null
                ) {
                    return@onFailure
                }

                cleanupActive()

                val err = when (e) {
                    is TimeoutCancellationException -> Exception("等待服务连接超时，su模式请以root或者system身份运行")
                    else -> e as? Exception ?: Exception(e.message)
                }

                Log.e(TAG, "$modeName 服务启动失败：${err.message}")

                _state.value = StartupState(
                    status = StartupStatus.ERROR,
                    selectedMode = mode,
                    errorException = err
                )
            }
        }
    }

    fun cancel() {
        val mode = _state.value.selectedMode
        val launcher = activeLauncher
        val service = mainService

        launchJob?.cancel()
        launchJob = null

        cleanupDeathRecipientOnly()
        activeLauncher = null
        _state.value = StartupState()

        Thread {
            runCatching { service?.shutdown() }
            runCatching {
                when (launcher) {
                    is RootProcessLauncher -> launcher.forceStopService()
                    is ShizukuProcessLauncher -> launcher.forceStopService(mode)
                    else -> launcher?.close()
                }
            }
            if (launcher == null) {
                runCatching {
                    when (mode) {
                        StartupMode.ROOT -> RootProcessLauncher(context).forceStopService()
                        StartupMode.SHIZUKU, StartupMode.SHIZUKU_TERMINAL ->
                            ShizukuProcessLauncher(context).forceStopService(mode)
                        null -> Unit
                    }
                }
            }
        }.start()
    }

    fun stop() {
        launchJob?.cancel()
        launchJob = null

        deathRecipient?.let { recipient ->
            runCatching { activeBinder?.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null

        runCatching { mainService?.shutdown() }

        val toClose = activeLauncher

        activeBinder = null
        activeLauncher = null
        mainService = null
        androidApiClient = null

        _state.value = StartupState()

        Log.d(TAG, "停止服务")

        Thread {
            toClose?.closeQuietly()
        }.start()
    }

    private suspend fun createLauncherAndBinder(mode: StartupMode): Pair<AutoCloseable, IBinder> =
        when (mode) {
            StartupMode.SHIZUKU -> launchViaShizukuDirect()
            StartupMode.SHIZUKU_TERMINAL -> launchViaShizukuTerminal()
            StartupMode.ROOT -> launchViaRoot()
        }

    private suspend fun launchViaShizukuDirect(): Pair<AutoCloseable, IBinder> {
        val launcher = ShizukuProcessLauncher(context)
        return launcher to launcher.getDirectServiceBinder()
    }

    private suspend fun launchViaShizukuTerminal(): Pair<AutoCloseable, IBinder> {
        val launcher = ShizukuProcessLauncher(context)
        return launcher to launcher.getTerminalServiceBinder(MainServiceStarter::class.java.name)
    }

    private suspend fun launchViaRoot(): Pair<AutoCloseable, IBinder> {
        val launcher = RootProcessLauncher(context)
        return launcher to launcher.getServiceBinder(MainServiceStarter::class.java.name)
    }

    private fun createStartupInfo(mode: StartupMode): StartupInfo = StartupInfo.forAppLaunch(
        mode = mode,
        uid = Process.myUid(),
        versionName = AppVersion.VERSION_NAME,
        versionCode = AppVersion.VERSION_CODE,
    )

    companion object {
        private const val TAG = "ProcessLauncher"
    }
}

internal fun StartupMode?.displayName(): String = when (this) {
    StartupMode.SHIZUKU -> "Shizuku"
    StartupMode.SHIZUKU_TERMINAL -> "Shizuku Terminal"
    StartupMode.ROOT -> "Root"
    null -> "未知"
}

internal fun String?.toStartupMode(): StartupMode? = when (this) {
    "SHIZUKU" -> StartupMode.SHIZUKU
    "SHIZUKU_TERMINAL" -> StartupMode.SHIZUKU_TERMINAL
    "ROOT" -> StartupMode.ROOT
    else -> null
}

private fun AutoCloseable?.closeQuietly() {
    try {
        this?.close()
    } catch (_: Exception) {
    }
}
