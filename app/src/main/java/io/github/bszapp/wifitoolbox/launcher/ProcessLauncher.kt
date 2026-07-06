package io.github.bszapp.wifitoolbox.launcher

import android.content.Context
import android.os.IBinder
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.startup.RunningException
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.startup.StartupState
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.MainService
import io.github.bszapp.wifitoolbox.service.MainServiceStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ProcessLauncher(private val context: Context) {

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
    }

    fun tryAutoReconnect() {
        ensureBrokerWatcher()

        ToolboxServiceProvider.getAliveBinder()?.let { binder ->
            launchJob?.cancel()
            launchJob = scope.launch {
                if (canAcceptBrokerBinder()) {
                    Log.d(TAG, "发现已存在的服务 Binder，直接恢复连接")
                    connectWithBinder(binder, null)
                }
            }
        } ?: run {
            _state.value = StartupState(status = StartupStatus.IDLE)
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
                    val currentMode = _state.value.selectedMode
                    Log.d(TAG, "收到服务主动投递的 Binder，恢复连接")
                    connectWithBinder(aliveBinder, currentMode)
                }
        }
    }

    private fun canAcceptBrokerBinder(): Boolean {
        return !(activeBinder?.isBinderAlive == true && mainService != null)
    }

    private fun connectWithBinder(binder: IBinder, requestedMode: StartupMode?) {
        try {
            if (!binder.isBinderAlive) {
                Log.w(TAG, "connectWithBinder 收到死亡 Binder")
                cleanupActive()
                _state.value = StartupState(
                    status = StartupStatus.IDLE,
                    selectedMode = requestedMode
                )
                return
            }

            if (activeBinder?.isBinderAlive == true && mainService != null) return

            val service = IMainService.Stub.asInterface(binder)
            if (!service.connect()) {
                Log.w(TAG, "connect() 被服务拒绝")
                cleanupActive()
                _state.value = StartupState(
                    status = StartupStatus.IDLE,
                    selectedMode = requestedMode
                )
                return
            }

            cleanupDeathRecipientOnly()

            activeBinder = binder
            mainService = service

            runCatching { service.watchApp(android.os.Binder()) }

            val uid = service.getUid()
            val uidStr = service.getUidStr()
            val pid = service.getPid()
            val serviceMode = service.getStartupMode().toStartupMode() ?: requestedMode
            val versionName = service.getStartupVersionName().takeIf { it.isNotBlank() }
            val versionCode = service.getStartupVersionCode().takeIf { it >= 0 }

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
                serviceUid = uid,
                serviceUidStr = uidStr,
                servicePid = pid,
                serviceVersionName = versionName,
                serviceVersionCode = versionCode
            )

            Log.d(
                TAG,
                "${serviceMode.displayName()} 服务启动成功，uid=$uid uidStr=$uidStr pid=$pid version=${versionName ?: "?"}(${versionCode ?: -1})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "connectWithBinder 失败: ${e.message}")
            cleanupActive()
            _state.value = StartupState(
                status = StartupStatus.IDLE,
                selectedMode = requestedMode
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
                withTimeout(5_000L) {
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
                if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) {
                    return@onFailure
                }

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
                    is ShizukuProcessLauncher -> launcher.forceStopService()
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

    companion object {
        private const val TAG = "ProcessLauncher"
    }
}

internal fun StartupMode?.displayName(): String = when (this) {
    StartupMode.SHIZUKU -> "Shizuku"
    StartupMode.SHIZUKU_TERMINAL -> "Shizuku Terminal"
    StartupMode.ROOT -> "Root"
    null -> "已存在"
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
