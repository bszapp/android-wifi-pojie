package io.github.bszapp.wifitoolbox.container

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.container.ContainerOperation
import io.github.bszapp.wifitoolbox.contract.container.ContainerProgress
import io.github.bszapp.wifitoolbox.contract.container.ContainerState
import io.github.bszapp.wifitoolbox.contract.container.ContainerSystemStatus
import io.github.bszapp.wifitoolbox.contract.container.ContainerTerminalStatus
import io.github.bszapp.wifitoolbox.contract.container.IContainerController
import io.github.bszapp.wifitoolbox.contract.container.isContainerSystemInstalled
import io.github.bszapp.wifitoolbox.service.IContainerTerminalCallback
import io.github.bszapp.wifitoolbox.service.IMainService
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class ContainerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val reportError: (
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String?,
    ) -> Unit,
) : IContainerController {
    private val _state = MutableStateFlow(ContainerState())
    override val state: StateFlow<ContainerState> = _state.asStateFlow()

    private val operationLock = Any()
    private val progressUpdateLock = Any()
    private val connectionLock = Any()
    private var operationRunning = false
    private var lastProgressUpdateElapsedRealtime = 0L
    private var connectionGeneration = 0L
    private var service: IMainService? = null
    private var serviceBinder: IBinder? = null
    private var callback: IContainerTerminalCallback? = null

    init {
        scope.launch(Dispatchers.IO) {
            val installed = isContainerSystemInstalled(rootfsDirectory())
            _state.update {
                it.copy(
                    systemStatus = if (installed) ContainerSystemStatus.INSTALLED else ContainerSystemStatus.NOT_INSTALLED,
                    installed = installed,
                )
            }
        }
    }

    fun connect(newService: IMainService) {
        val generation: Long
        val newCallback: IContainerTerminalCallback
        val detached = synchronized(connectionLock) {
            val old = currentConnection()
            connectionGeneration++
            generation = connectionGeneration
            newCallback = createCallback(generation)
            service = newService
            serviceBinder = newService.asBinder()
            callback = newCallback
            old
        }
        unregister(detached)
        scope.launch(Dispatchers.IO) {
            runCatching { newService.registerContainerTerminalCallback(newCallback) }
                .onFailure { error ->
                    if (isCurrent(generation, newCallback)) {
                        report("注册容器终端回调", error)
                    }
                }
        }
    }

    fun disconnect() {
        val detached = synchronized(connectionLock) {
            val old = currentConnection()
            connectionGeneration++
            service = null
            serviceBinder = null
            callback = null
            old
        }
        unregister(detached)
        _state.update {
            it.copy(
                terminalStatus = ContainerTerminalStatus.STOPPED,
                terminalMessage = "服务已断开",
                scanRunning = false,
            )
        }
    }

    override fun install() = runContainerOperation(ContainerOperation.INSTALL) {
        deletePartialRootfs()
        extractRootfs(ContainerOperation.INSTALL)
    }

    override fun update() = runContainerOperation(ContainerOperation.UPDATE) {
        extractRootfs(ContainerOperation.UPDATE)
    }

    override fun reset() = runContainerOperation(ContainerOperation.RESET) {
        stopTerminalBeforeContainerChange()
        deleteContainerFiles(ContainerOperation.RESET)
        extractRootfs(ContainerOperation.RESET)
    }

    override fun uninstall() = runContainerOperation(ContainerOperation.UNINSTALL) {
        stopTerminalBeforeContainerChange()
        deleteContainerFiles(ContainerOperation.UNINSTALL)
    }

    override fun startTerminal() {
        if (!_state.value.installed) {
            report("启动扫描终端", IllegalStateException("容器系统尚未安装"))
            return
        }
        _state.update {
            it.copy(
                terminalStatus = ContainerTerminalStatus.STARTING,
                terminalMessage = "正在请求启动扫描终端",
                scanRunning = false,
            )
        }
        callService("启动扫描终端") { remote ->
            remote.startContainerTerminal(
                rootfsDirectory().absolutePath,
                runtimeDirectory().absolutePath,
                resolveTerminalBinary().absolutePath,
            )
        }
    }

    override fun stopTerminal() {
        _state.update {
            it.copy(
                terminalStatus = ContainerTerminalStatus.STOPPING,
                terminalMessage = "正在停止扫描终端",
                scanRunning = false,
            )
        }
        callService("停止扫描终端") { it.stopContainerTerminal() }
    }

    override fun runWifiScan() {
        if (!_state.value.terminalReady || _state.value.scanRunning) return
        _state.update {
            it.copy(
                scanRunning = true,
                scanOutput = "",
                scanExitCode = null,
                terminalMessage = "正在执行 Wi-Fi 扫描",
            )
        }
        callService("执行容器 Wi-Fi 扫描") { it.runContainerWifiScan() }
    }

    private fun runContainerOperation(
        operation: ContainerOperation,
        block: suspend () -> Unit,
    ) {
        synchronized(operationLock) {
            if (operationRunning) return
            operationRunning = true
        }
        val installedBefore = isContainerSystemInstalled(rootfsDirectory())
        synchronized(progressUpdateLock) {
            lastProgressUpdateElapsedRealtime = SystemClock.elapsedRealtime()
        }
        _state.update {
            it.copy(
                systemStatus = ContainerSystemStatus.WORKING,
                installed = installedBefore,
                operation = operation,
                progress = ContainerProgress(operation.title, "", 0f),
                errorMessage = null,
            )
        }
        scope.launch(Dispatchers.IO) {
            try {
                block()
                val installed = isContainerSystemInstalled(rootfsDirectory())
                _state.update {
                    it.copy(
                        systemStatus = if (installed) ContainerSystemStatus.INSTALLED else ContainerSystemStatus.NOT_INSTALLED,
                        installed = installed,
                        operation = null,
                        progress = null,
                        errorMessage = null,
                        terminalStatus = if (installed) it.terminalStatus else ContainerTerminalStatus.STOPPED,
                        terminalMessage = if (installed) it.terminalMessage else "容器系统已卸载",
                        scanRunning = false,
                    )
                }
            } catch (error: Throwable) {
                val installed = isContainerSystemInstalled(rootfsDirectory())
                _state.update {
                    it.copy(
                        systemStatus = ContainerSystemStatus.ERROR,
                        installed = installed,
                        operation = operation,
                        progress = null,
                        errorMessage = error.message ?: error.javaClass.simpleName,
                        scanRunning = false,
                    )
                }
                report(operation.title, error)
            } finally {
                synchronized(operationLock) { operationRunning = false }
            }
        }
    }

    private suspend fun extractRootfs(operation: ContainerOperation) {
        val archive = resolveArchive()
        try {
            val destination = rootfsDirectory()
            if (!destination.isDirectory && !destination.mkdirs()) {
                throw IOException("无法创建 rootfs: ${destination.absolutePath}")
            }
            archive.openStream().use { input ->
                RootfsArchiveExtractor.extract(
                    compressedSize = archive.length,
                    compressedInput = input,
                    destination = destination,
                ) { entry, fraction ->
                    updateProgress(operation, "正在解压", entry, fraction)
                }
            }
        } finally {
            archive.cleanup()
        }
        if (!isContainerSystemInstalled(rootfsDirectory())) {
            throw IOException("rootfs 解压完成但校验失败")
        }
        updateProgress(operation, "正在解压", "", 1f)
    }

    private fun deletePartialRootfs() {
        val rootfs = rootfsDirectory()
        if (!pathExists(rootfs)) return
        if (!rootfs.deleteRecursively() && pathExists(rootfs)) {
            executePrivilegedDelete(rootfs, ContainerOperation.INSTALL, 0, 1)
        }
    }

    private fun stopTerminalBeforeContainerChange() {
        val current = synchronized(connectionLock) { service }
        if (current != null && current.asBinder().isBinderAlive) {
            current.stopContainerTerminal()
        }
        _state.update {
            it.copy(
                terminalStatus = ContainerTerminalStatus.STOPPED,
                terminalMessage = "扫描终端已停止",
                scanRunning = false,
            )
        }
    }

    private fun deleteContainerFiles(operation: ContainerOperation) {
        val targets = listOf(rootfsDirectory(), runtimeDirectory()).filter(::pathExists)
        if (targets.isEmpty()) {
            updateProgress(operation, "正在删除", "", 1f)
            return
        }
        targets.forEachIndexed { index, target ->
            executePrivilegedDelete(target, operation, index, targets.size)
        }
    }

    private fun executePrivilegedDelete(
        target: File,
        operation: ContainerOperation,
        targetIndex: Int,
        targetCount: Int,
    ) {
        val terminal = resolveTerminalBinary()
        val allowedRoot = requireNotNull(context.filesDir.parentFile)
        val nativeCommand = listOf(
            terminal.absolutePath,
            "container",
            "delete",
            "--path",
            target.absolutePath,
            "--allowed-root",
            allowedRoot.absolutePath,
        ).joinToString(" ", transform = ::shellQuote)
        val process = ProcessBuilder("su", "-c", nativeCommand)
            .redirectErrorStream(true)
            .start()
        val rawOutput = mutableListOf<String>()
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val event = runCatching { JSONObject(line) }.getOrNull()
                if (event == null || !event.has("event")) {
                    if (line.isNotBlank()) rawOutput += line
                    return@forEach
                }
                val index = event.optLong("index", event.optLong("deleted", 0L))
                val total = event.optLong("total", 0L)
                val localFraction = if (total > 0) index.toFloat() / total else 0f
                val overall = ((targetIndex + localFraction) / targetCount).coerceIn(0f, 1f)
                val message = when (event.optString("event")) {
                    "scan" -> "正在统计删除项"
                    "umount" -> "正在解除挂载"
                    else -> "正在删除"
                }
                updateProgress(operation, message, relativePath(event.optString("path", target.path)), overall)
            }
        }
        if (!process.waitForCompat(300, TimeUnit.SECONDS)) {//TODO:报错啦，下一行也是：Call requires API level 26 (current min is 24): java.lang.Process#waitFor
            process.destroyForciblyCompat()
            throw IOException("删除容器超时: ${target.absolutePath}")
        }
        if (process.exitValue() != 0) {
            throw IOException(
                "删除容器失败: ${target.absolutePath}, exit=${process.exitValue()}, output=${rawOutput.joinToString(" | ")}",
            )
        }
        updateProgress(operation, "正在删除", relativePath(target.absolutePath), (targetIndex + 1f) / targetCount)
    }

    private fun updateProgress(
        operation: ContainerOperation,
        message: String,
        detail: String,
        fraction: Float,
    ) {
        val now = SystemClock.elapsedRealtime()
        val shouldPublish = synchronized(progressUpdateLock) {
            if (now - lastProgressUpdateElapsedRealtime < PROGRESS_UPDATE_INTERVAL_MILLIS) {
                false
            } else {
                lastProgressUpdateElapsedRealtime = now
                true
            }
        }
        if (!shouldPublish) return
        _state.update {
            it.copy(
                progress = ContainerProgress(message, detail, fraction.coerceIn(0f, 1f)),
                operation = operation,
            )
        }
    }

    private fun createCallback(generation: Long): IContainerTerminalCallback =
        object : IContainerTerminalCallback.Stub() {
            override fun onContainerTerminalEvent(eventJson: String) {
                if (!isCurrent(generation, this)) return
                runCatching { handleTerminalEvent(JSONObject(eventJson)) }
                    .onFailure { report("解析容器终端事件", it) }
            }
        }

    private fun handleTerminalEvent(event: JSONObject) {
        when (event.optString("type")) {
            "state" -> {
                val status = runCatching {
                    ContainerTerminalStatus.valueOf(event.optString("status"))
                }.getOrDefault(ContainerTerminalStatus.ERROR)
                _state.update {
                    it.copy(
                        terminalStatus = status,
                        terminalMessage = event.optString("message"),
                        scanRunning = if (status == ContainerTerminalStatus.READY) it.scanRunning else false,
                    )
                }
            }
            "scan_reset" -> _state.update {
                it.copy(scanRunning = true, scanOutput = "", scanExitCode = null)
            }
            "started" -> _state.update {
                it.copy(
                    scanRunning = true,
                    terminalMessage = "正在执行 ${event.optString("displayCommand")}",
                )
            }
            "output" -> _state.update {
                it.copy(scanOutput = it.scanOutput + event.optString("text"))
            }
            "finished" -> _state.update {
                val code = event.optInt("exitCode")
                it.copy(
                    scanRunning = false,
                    scanExitCode = code,
                    terminalMessage = "扫描完成，退出码 $code",
                )
            }
            "error" -> _state.update {
                it.copy(
                    scanRunning = false,
                    scanExitCode = null,
                    terminalMessage = "扫描执行失败：${event.optString("message")}",
                )
            }
        }
    }

    private fun callService(operation: String, block: (IMainService) -> Unit) {
        val remote = synchronized(connectionLock) { service }
        if (remote == null || !remote.asBinder().isBinderAlive) {
            val error = IllegalStateException("service 未连接")
            onTerminalCallFailure(operation, error)
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching { block(remote) }
                .onFailure { onTerminalCallFailure(operation, it) }
        }
    }

    private fun onTerminalCallFailure(operation: String, error: Throwable) {
        _state.update {
            it.copy(
                terminalStatus = ContainerTerminalStatus.ERROR,
                terminalMessage = error.message ?: error.javaClass.simpleName,
                scanRunning = false,
            )
        }
        report(operation, error)
    }

    private fun unregister(connection: Connection?) {
        if (connection == null || !connection.binder.isBinderAlive) return
        scope.launch(Dispatchers.IO) {
            runCatching { connection.service.unregisterContainerTerminalCallback(connection.callback) }
                .onFailure {
                    if (it !is DeadObjectException && connection.binder.isBinderAlive) {
                        Log.w(TAG, "注销旧容器终端回调失败", it)
                    }
                }
        }
    }

    private fun currentConnection(): Connection? {
        val currentService = service ?: return null
        val binder = serviceBinder ?: return null
        val currentCallback = callback ?: return null
        return Connection(currentService, binder, currentCallback)
    }

    private fun isCurrent(generation: Long, value: IContainerTerminalCallback): Boolean =
        synchronized(connectionLock) {
            generation == connectionGeneration && callback === value && serviceBinder?.isBinderAlive == true
        }

    private fun rootfsDirectory(): File = File(requireNotNull(context.filesDir.parentFile), "rootfs")
    private fun runtimeDirectory(): File = File(context.noBackupFilesDir, "rftool-runtime")

    private fun pathExists(file: File): Boolean = runCatching { Os.lstat(file.absolutePath) }.isSuccess

    private fun resolveTerminalBinary(): File {
        val file = File(context.applicationInfo.nativeLibraryDir, "libterminal.so")
        if (file.isFile && file.canExecute()) return file
        throw IOException("未找到可执行的 libterminal.so: ${file.absolutePath}")
    }

    private fun resolveArchive(): RootfsArchive {
        return runCatching { context.assets.openFd(ROOTFS_ASSET) }.fold(
            onSuccess = { RootfsArchive.Asset(it) },
            onFailure = {
                val temporary = File(context.noBackupFilesDir, ROOTFS_ASSET)
                context.assets.open(ROOTFS_ASSET).use { input ->
                    temporary.outputStream().use(input::copyTo)
                }
                RootfsArchive.Temporary(temporary)
            },
        )
    }

    private fun relativePath(path: String): String = runCatching {
        File(path).relativeTo(requireNotNull(context.filesDir.parentFile)).path
    }.getOrDefault(path)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun report(operation: String, error: Throwable) {
        reportError("App.ContainerController", operation, error, null)
    }

    private data class Connection(
        val service: IMainService,
        val binder: IBinder,
        val callback: IContainerTerminalCallback,
    )

    private sealed interface RootfsArchive {
        val length: Long
        fun openStream(): InputStream
        fun cleanup() = Unit

        data class Asset(val descriptor: AssetFileDescriptor) : RootfsArchive {
            override val length: Long get() = descriptor.length
            override fun openStream(): InputStream = descriptor.createInputStream()
            override fun cleanup() = descriptor.close()
        }

        data class Temporary(val file: File) : RootfsArchive {
            override val length: Long get() = file.length()
            override fun openStream(): InputStream = file.inputStream()
            override fun cleanup() { file.delete() }
        }
    }

    private companion object {
        const val TAG = "ContainerController"
        const val ROOTFS_ASSET = "rootfs.tar.xz"
        const val PROGRESS_UPDATE_INTERVAL_MILLIS = 10L
    }
}

private fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        return waitFor(timeout, unit)
    }

    val deadline = SystemClock.elapsedRealtime() + unit.toMillis(timeout)
    while (true) {
        try {
            exitValue()
            return true
        } catch (_: IllegalThreadStateException) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return false
            Thread.sleep(minOf(remaining, PROCESS_WAIT_POLL_INTERVAL_MILLIS))
        }
    }
}

private fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        destroy()
    }
}

private const val PROCESS_WAIT_POLL_INTERVAL_MILLIS = 50L
