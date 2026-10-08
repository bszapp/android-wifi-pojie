package io.github.bszapp.wifitoolbox.launcher

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.BuildConfig
import io.github.bszapp.wifitoolbox.contract.startup.RunningException
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.startup.StartupState
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.MainServiceStarter
import io.github.bszapp.wifitoolbox.tools.AndroidApiClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class ProcessLauncher(
    private val context: Context,
    private val onAndroidApiError: (operation: String, error: Throwable) -> Unit,
    private val onServiceConnected: (IMainService, AndroidApiClient) -> Unit,
    private val onServiceDisconnected: () -> Unit,
    private val onServiceCrash: () -> Exception?,
    private val onBeforeServiceStop: suspend (IMainService) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var launchJob: Job? = null
    private var brokerWatchJob: Job? = null
    private var autoReconnectEnabled = true
    private var stoppingBinder: IBinder? = null
    private var expectedServiceUid: Int? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(StartupState())
    val state: kotlinx.coroutines.flow.StateFlow<StartupState> = _state

    private var activeLauncher: AutoCloseable? = null
    private var activeBinder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    private var nextConnectionId = 0L
    private var mainService: IMainService? = null

    private var androidApiClient: AndroidApiClient? = null

    private fun cleanupActive() {
        cleanupDeathRecipientOnly()
        ToolboxServiceProvider.clearBinder()
        activeLauncher?.closeQuietly()
        activeLauncher = null
    }

    private fun cleanupDeathRecipientOnly() {
        val hadConnection = activeBinder != null || mainService != null || androidApiClient != null
        deathRecipient?.let { recipient ->
            runCatching { activeBinder?.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null
        activeBinder = null
        mainService = null
        androidApiClient = null
        if (hadConnection) notifyServiceDisconnected()
    }

    private fun notifyServiceDisconnected() {
        runCatching(onServiceDisconnected)
            .onFailure { Log.w(TAG, "通知 App 侧 Service 已断开失败：${it.message}") }
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
        autoReconnectEnabled && stoppingBinder == null &&
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
            expectedServiceUid?.let { expected ->
                check(startupInfo.serviceUid == expected) {
                    "服务重启权限不一致：原 UID=$expected，新 UID=${startupInfo.serviceUid}"
                }
            }
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

            lateinit var recipient: IBinder.DeathRecipient
            recipient = IBinder.DeathRecipient {
                Log.e(TAG, "${serviceMode.displayName()} 服务进程崩溃或被终止")
                scope.launch(Dispatchers.Main) {
                    if (activeBinder === binder &&
                        deathRecipient === recipient &&
                        stoppingBinder !== binder &&
                        _state.value.status == StartupStatus.RUNNING
                    ) {
                        val crashException = runCatching {
                            withContext(Dispatchers.IO) { onServiceCrash() }
                        }.onFailure { error ->
                            Log.e(TAG, "读取服务进程崩溃报告失败", error)
                        }.getOrNull()
                        cleanupActive()
                        _state.value = StartupState(
                            status = StartupStatus.ERROR,
                            selectedMode = serviceMode,
                            errorException = crashException ?: RunningException("服务进程被强制停止")
                        )
                    }
                }
            }

            binder.linkToDeath(recipient, 0)
            deathRecipient = recipient
            autoReconnectEnabled = true

            _state.value = StartupState(
                status = StartupStatus.RUNNING,
                selectedMode = serviceMode,
                serviceInfo = startupInfo,
                connectionId = ++nextConnectionId
            )

            runCatching { onServiceConnected(service, androidApiClient!!) }
                .onFailure {
                    Log.e(TAG, "通知 App 侧 Service 已连接失败：${it.message}", it)
                }

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

    fun launch(mode: StartupMode) = launch(mode, serviceUid = null)

    private fun launch(mode: StartupMode, serviceUid: Int?, replacedServicePid: Int? = null) {
        expectedServiceUid = serviceUid
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
                    val (launcher, launchedBinder) = createLauncherAndBinder(mode, serviceUid)
                    activeLauncher = launcher
                    // 强制重启时旧服务仍可能投递 Binder；独立进程必须等到新 PID 再连接。
                    val binder = if (replacedServicePid != null && mode != StartupMode.SHIZUKU) {
                        ToolboxServiceProvider.binderFlow.filterNotNull().first { candidate ->
                            candidate.isBinderAlive && runCatching {
                                val info = IMainService.Stub.asInterface(candidate).getStartupInfo()
                                info.servicePid != replacedServicePid &&
                                    info.versionCode == BuildConfig.VERSION_CODE.toLong() &&
                                    info.isTrustedForAppUid(Process.myUid())
                            }.getOrDefault(false)
                        }
                    } else launchedBinder
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

        scope.launch {
            try { if (service != null) withContext(Dispatchers.IO) { onBeforeServiceStop(service) } }
            catch (error: Throwable) { onAndroidApiError("保存任务并退出服务", error); return@launch }
            cleanupDeathRecipientOnly()
            ToolboxServiceProvider.clearBinder()
            activeLauncher = null
            _state.value = StartupState()
            withContext(Dispatchers.IO) {
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
            }
        }
    }

    suspend fun stop(resetStartupState: Boolean = true) = withContext(Dispatchers.Main) {
        launchJob?.cancel()
        launchJob = null

        val service = mainService
        val binder = activeBinder
        stoppingBinder = binder
        try {
            try {
                if (service != null) withContext(Dispatchers.IO) {
                    onBeforeServiceStop(service)
                    service.shutdown()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // shutdown 会结束服务进程；Binder 随进程死亡属于退出完成。
                if (binder?.isBinderAlive == true) {
                    onAndroidApiError("保存任务并退出服务", error)
                    throw error
                }
            }
            disconnect(resetStartupState)
            Log.d(TAG, "停止服务")
        } finally {
            if (stoppingBinder === binder) stoppingBinder = null
        }
    }

    /** 复用服务初始化时的启动方式及实际 UID；正常退出失败时保留连接供用户确认。 */
    suspend fun restart(force: Boolean = false) = withContext(Dispatchers.Main) {
        val info = requireNotNull(_state.value.serviceInfo) { "缺少原服务启动信息" }
        val mode = requireNotNull(info.startupMode.toStartupMode()) { "未知服务启动方式" }
        if (force) disconnect() else stop()
        launch(mode, info.serviceUid, info.servicePid)
    }

    /** 仅断开应用侧连接，不再请求服务退出。主动启动服务后恢复自动接收 Binder。 */
    suspend fun disconnect(resetStartupState: Boolean = true) = withContext(Dispatchers.Main) {
        autoReconnectEnabled = false
        launchJob?.cancel()
        launchJob = null
        val toClose = activeLauncher
        cleanupDeathRecipientOnly()
        ToolboxServiceProvider.clearBinder()
        activeLauncher = null
        withContext(Dispatchers.IO) { toClose?.closeQuietly() }
        // 退出应用时保留当前启动状态，交由退出广播关闭 Activity，避免先跳到模式选择页。
        if (resetStartupState) _state.value = StartupState()
    }

    private suspend fun createLauncherAndBinder(mode: StartupMode, serviceUid: Int?): Pair<AutoCloseable, IBinder> =
        when (mode) {
            StartupMode.SHIZUKU -> launchViaShizukuDirect(serviceUid)
            StartupMode.SHIZUKU_TERMINAL -> launchViaShizukuTerminal(serviceUid)
            StartupMode.ROOT -> launchViaRoot(serviceUid)
        }

    private suspend fun launchViaShizukuDirect(serviceUid: Int?): Pair<AutoCloseable, IBinder> {
        val launcher = ShizukuProcessLauncher(context)
        checkShizukuUid(launcher, serviceUid)
        return launcher to launcher.getDirectServiceBinder()
    }

    private suspend fun launchViaShizukuTerminal(serviceUid: Int?): Pair<AutoCloseable, IBinder> {
        val launcher = ShizukuProcessLauncher(context)
        checkShizukuUid(launcher, serviceUid)
        return launcher to launcher.getTerminalServiceBinder(MainServiceStarter::class.java.name)
    }

    private suspend fun launchViaRoot(serviceUid: Int?): Pair<AutoCloseable, IBinder> {
        val launcher = RootProcessLauncher(context, serviceUid)
        return launcher to launcher.getServiceBinder(MainServiceStarter::class.java.name)
    }

    private suspend fun checkShizukuUid(launcher: ShizukuProcessLauncher, serviceUid: Int?) {
        if (serviceUid == null) return
        launcher.ensurePermission()
        check(rikka.shizuku.Shizuku.getUid() == serviceUid) {
            "Shizuku 当前权限与原服务 UID=$serviceUid 不一致，请恢复原权限后重试"
        }
    }

        private fun createStartupInfo(mode: StartupMode): StartupInfo = StartupInfo.forAppLaunch(
            mode = mode,
            uid = Process.myUid(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            serviceCrashReportPath = File(context.cacheDir, "service-crash-report").absolutePath,
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
