package io.github.bszapp.wifitoolbox.service.container

import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.system.Os
import io.github.bszapp.wifitoolbox.contract.container.ContainerEnvironment
import io.github.bszapp.wifitoolbox.contract.container.ContainerOperation
import io.github.bszapp.wifitoolbox.contract.container.ContainerOperationRequest
import io.github.bszapp.wifitoolbox.contract.container.ContainerProgress
import io.github.bszapp.wifitoolbox.contract.container.ContainerState
import io.github.bszapp.wifitoolbox.contract.container.ContainerSystemStatus
import io.github.bszapp.wifitoolbox.contract.container.isContainerSystemInstalled
import io.github.bszapp.wifitoolbox.service.IContainerSystemCallback
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 容器状态与操作的唯一管理者；操作生命周期属于服务，与 App 回调的注册周期无关。 */
internal class ContainerSystemManager(
    private val trustedUid: () -> Int,
    private val terminalBinaryProvider: () -> File,
    private val mounts: ContainerMountManager,
    private val beforeDelete: () -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) {
    private val lock = Any()
    private val callbacks = RemoteCallbackList<IContainerSystemCallback>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "container-system-worker") }
    private val delivery = Executors.newSingleThreadExecutor { Thread(it, "container-state-delivery") }
    private var environment: ContainerEnvironment? = null
    private var currentState = ContainerState()
    private var running = false
    private var closed = false
    private var lastProgressUpdate = 0L
    private var operationThread: Thread? = null
    private var operationProcess: Process? = null
    private var operationArchive: ParcelFileDescriptor? = null
    private var interruptRequested = false
    private var nextOperationId = 0L

    fun state(): ContainerState = synchronized(lock) { currentState }

    fun initialize(next: ContainerEnvironment) {
        val data = File(next.appDataPath)
        require(data.isAbsolute && data.absolutePath != "/") {
            "容器数据目录必须为明确的绝对目录"
        }
        // 环境来自系统安装记录。非 Root 服务不访问私有目录进行挂载校验。
        if (android.os.Process.myUid() == 0) {
            require(data.canonicalPath == data.absolutePath) { "容器数据目录必须为规范路径" }
            require(Os.stat(data.absolutePath).st_uid == trustedUid()) { "容器数据目录不属于可信 App" }
        }
        synchronized(lock) {
            check(!closed) { "容器管理器已关闭" }
            if (environment == next) return
            check(!running) { "容器操作期间不能修改环境" }
            mounts.refresh(next)
            environment = next
            val installed = isContainerSystemInstalled(rootfs(next))
            publishLocked(ContainerState(systemStatus = installedStatus(installed), installed = installed))
        }
    }

    fun start(request: ContainerOperationRequest, archive: ParcelFileDescriptor?): Boolean {
        synchronized(lock) {
            check(!closed) { "容器管理器已关闭" }
            if (running) return false
            val env = requireNotNull(environment) { "容器环境尚未配置" }
            val descriptor = if (request.operation == ContainerOperation.UNINSTALL) null else {
                require(request.archiveOffset >= 0L && request.archiveLength > 0L) { "容器压缩包范围无效" }
                ParcelFileDescriptor.dup(requireNotNull(archive) { "缺少容器压缩包数据" }.fileDescriptor)
            }
            running = true
            interruptRequested = false
            operationArchive = descriptor
            lastProgressUpdate = SystemClock.elapsedRealtime()
            publishLocked(currentState.copy(
                systemStatus = ContainerSystemStatus.WORKING,
                installed = isContainerSystemInstalled(rootfs(env)),
                operation = request.operation,
                progress = ContainerProgress(request.operation.title, "", 0f),
                errorMessage = null,
                operationId = ++nextOperationId,
            ))
            try {
                worker.execute { runOperation(env, request, descriptor) }
            } catch (error: Throwable) {
                descriptor?.close()
                running = false
                operationArchive = null
                publishLocked(currentState.copy(systemStatus = ContainerSystemStatus.ERROR, progress = null,
                    errorMessage = error.message ?: error.javaClass.simpleName))
                throw error
            }
            return true
        }
    }

    fun register(callback: IContainerSystemCallback) {
        synchronized(lock) {
            check(!closed) { "容器管理器已关闭" }
            callbacks.register(callback)
            delivery.execute { push(callback, state()) }
        }
    }

    fun unregister(callback: IContainerSystemCallback) { callbacks.unregister(callback) }

    fun interrupt(operationId: Long) {
        val resources = synchronized(lock) {
            if (!running || currentState.operationId != operationId) return
            interruptRequested = true
            // 同一 worker 会复用于下一次操作；在运行标记锁内发送中断，避免打断后续任务。
            operationThread?.interrupt()
            Triple(operationThread, operationProcess, operationArchive)
        }
        resources.second?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.destroyForcibly() else it.destroy()
        }
        runCatching { resources.third?.close() }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted || synchronized(lock) { interruptRequested }) {
            throw InterruptedIOException("用户强制中断容器操作")
        }
    }

    private fun runOperation(env: ContainerEnvironment, request: ContainerOperationRequest, archive: ParcelFileDescriptor?) {
        var failure: Throwable? = null
        try {
            synchronized(lock) { operationThread = Thread.currentThread() }
            checkInterrupted()
            when (request.operation) {
                ContainerOperation.INSTALL -> {
                    beforeDelete()
                    mounts.unmount()
                    deletePaths(env, request.operation, listOf(rootfs(env)))
                    extract(env, request, requireNotNull(archive))
                }
                ContainerOperation.UPDATE -> extract(env, request, requireNotNull(archive))
                ContainerOperation.RESET -> {
                    beforeDelete()
                    mounts.unmount()
                    deletePaths(env, request.operation, listOf(rootfs(env), runtime(env)))
                    extract(env, request, requireNotNull(archive))
                }
                ContainerOperation.UNINSTALL -> {
                    beforeDelete()
                    mounts.unmount()
                    deletePaths(env, request.operation, listOf(rootfs(env), runtime(env)))
                }
            }
        } catch (error: Throwable) {
            failure = if (synchronized(lock) { interruptRequested }) InterruptedIOException("用户强制中断容器操作") else error
        } finally {
            runCatching { archive?.close() }
            // 包括失败、中断后的残留容器：是否挂载始终以最终存在的容器为准。
            Thread.interrupted()
            try { mounts.refresh(env) }
            catch (error: Throwable) {
                if (failure == null || failure is InterruptedIOException) failure = error
                else failure?.addSuppressed(error)
            }
            synchronized(lock) {
                val installed = isContainerSystemInstalled(rootfs(env))
                running = false
                operationThread = null
                operationProcess = null
                operationArchive = null
                publishLocked(currentState.copy(
                    systemStatus = if (failure == null || failure is InterruptedIOException) installedStatus(installed) else ContainerSystemStatus.ERROR,
                    installed = installed,
                    operation = request.operation,
                    errorMessage = failure?.let { it.message ?: it.javaClass.simpleName },
                ))
            }
            Thread.interrupted()
            failure?.takeUnless { it is InterruptedIOException }?.let { onError(request.operation.title, it) }
        }
    }

    private fun extract(env: ContainerEnvironment, request: ContainerOperationRequest, descriptor: ParcelFileDescriptor) {
        val owner = Os.stat(env.appDataPath)
        AssetFileDescriptor(descriptor, request.archiveOffset, request.archiveLength).createInputStream().use { input ->
            RootfsArchiveExtractor.extract(
                compressedSize = request.archiveLength,
                compressedInput = input,
                destination = rootfs(env),
                ownerUid = owner.st_uid,
                ownerGid = owner.st_gid,
            ) { entry, fraction -> updateProgress(request.operation, "正在解压", entry, fraction) }
        }
        check(isContainerSystemInstalled(rootfs(env))) { "rootfs 解压完成但校验失败" }
        updateProgress(request.operation, "正在解压", "", 1f)
    }

    private fun deletePaths(env: ContainerEnvironment, operation: ContainerOperation, paths: List<File>) {
        val targets = paths.filter { runCatching { Os.lstat(it.absolutePath) }.isSuccess }
        if (targets.isEmpty()) return
        targets.forEachIndexed { index, target ->
            checkInterrupted()
            val terminal = terminalBinaryProvider()
            check(terminal.isFile && terminal.canExecute()) {
                "未找到可执行的 libterminal.so: ${terminal.absolutePath}"
            }
            // 使用现有原生删除器验证范围、解除子挂载并输出逐项进度；不启动 su 或另一个权限源。
            val process = ProcessBuilder(terminal.absolutePath, "container", "delete", "--path", target.absolutePath,
                "--allowed-root", env.appDataPath).redirectErrorStream(true).start()
            try {
                synchronized(lock) { operationProcess = process }
                checkInterrupted()
                val output = StringBuilder()
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        checkInterrupted()
                        val event = runCatching { JSONObject(line) }.getOrNull()
                        if (event == null || !event.has("event")) {
                            if (output.length < 8192) output.appendLine(line.take(8192 - output.length))
                        } else {
                            val deleted = event.optLong("index", event.optLong("deleted", 0L))
                            val total = event.optLong("total", 0L)
                            val fraction = if (total > 0L) deleted.toFloat() / total else 0f
                            val message = when (event.optString("event")) {
                                "scan" -> "正在统计删除项"
                                "umount" -> "正在解除挂载"
                                else -> "正在删除"
                            }
                            val path = event.optString("path", target.path)
                            val detail = runCatching { File(path).relativeTo(File(env.appDataPath)).path }.getOrDefault(path)
                            updateProgress(operation, message, detail, (index + fraction) / targets.size)
                        }
                    }
                }
                if (!process.waitForCompat(300, TimeUnit.SECONDS)) {//TODO:报错啦，下一行也是：Call requires API level 26 (current min is 24): java.lang.Process#waitFor
                    throw IOException("删除容器超时: ${target.absolutePath}")
                }
                if (process.exitValue() != 0) throw IOException("删除容器失败: ${target.absolutePath}, exit=${process.exitValue()}, output=$output")
                updateProgress(operation, "正在删除", target.name, (index + 1f) / targets.size)
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) process.destroyForcibly() else process.destroy()
                synchronized(lock) { if (operationProcess === process) operationProcess = null }
            }
        }
    }

    private fun updateProgress(operation: ContainerOperation, message: String, detail: String, fraction: Float) {
        checkInterrupted()
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgressUpdate < PROGRESS_UPDATE_INTERVAL_MILLIS) return
            lastProgressUpdate = now
            publishLocked(currentState.copy(operation = operation,
                progress = ContainerProgress(message, detail, fraction.coerceIn(0f, 1f))))
        }
    }

    private fun publishLocked(next: ContainerState) {
        currentState = next.copy(revision = currentState.revision + 1L)
        val snapshot = currentState
        if (!closed) delivery.execute {
            val count = callbacks.beginBroadcast()
            try { for (index in 0 until count) push(callbacks.getBroadcastItem(index), snapshot) }
            finally { callbacks.finishBroadcast() }
        }
    }

    private fun push(callback: IContainerSystemCallback, snapshot: ContainerState) {
        runCatching { callback.onContainerStateChanged(snapshot) }.onFailure { callbacks.unregister(callback) }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            worker.shutdown()
        }
        // 已接收的安装操作先完成，避免退出服务后又重新挂载。
        var interrupted = false
        try {
            while (true) {
                try { if (worker.awaitTermination(1, TimeUnit.SECONDS)) break }
                catch (_: InterruptedException) { interrupted = true }
            }
            mounts.close()
        } finally {
            delivery.shutdown()
            if (interrupted) Thread.currentThread().interrupt()
        }
        callbacks.kill()
    }

    private fun rootfs(env: ContainerEnvironment) = File(env.appDataPath, "rootfs")
    private fun runtime(env: ContainerEnvironment) = File(env.appDataPath, "no_backup/rftool-runtime")
    private fun installedStatus(installed: Boolean) = if (installed) ContainerSystemStatus.INSTALLED else ContainerSystemStatus.NOT_INSTALLED

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MILLIS = 10L
    }
}

private fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)
    val deadline = SystemClock.elapsedRealtime() + unit.toMillis(timeout)
    while (true) {
        try { exitValue(); return true } catch (_: IllegalThreadStateException) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return false
            Thread.sleep(minOf(remaining, 50L))
        }
    }
}
