package io.github.bszapp.wifitoolbox.terminal

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.container.isContainerSystemInstalled
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogEntry
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogState
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalManagerState
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalOutputAccumulator
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalOutputUpdate
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AppTerminalManager(
    context: Context,
    private val scope: CoroutineScope,
    private val reportError: (
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String?,
    ) -> Unit,
) {
    private val context = context.applicationContext
    private val lock = Any()
    private val creationLock = Any()
    private val terminals = linkedMapOf<Long, ManagedTerminal>()
    private val stateSignals = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(TerminalManagerState())
    val state: StateFlow<TerminalManagerState> = _state.asStateFlow()
    private var nextTerminalId = 1L
    private var aliveGeneration = 0L
    private var closed = false

    init {
        scope.launch(Dispatchers.Default) {
            for (signal in stateSignals) {
                delay(STATE_PUBLISH_INTERVAL_MILLIS)
                while (stateSignals.tryReceive().isSuccess) {
                    // 合并发布等待期间到达的状态更新。
                }
                publishStateNow()
            }
        }
    }

    fun createTerminal() {
        scope.launch(Dispatchers.IO) {
            runCatching { createTerminalNow() }
                .onFailure { error -> report("新建 App proot 终端", error) }
        }
    }

    fun clearLogs(terminalId: Long) {
        runCatching {
            requireTerminal(terminalId).clearLogs()
            signalStateChanged()
        }.onFailure { error -> report("清空 App 终端 $terminalId 日志", error) }
    }

    fun writeInput(terminalId: Long, text: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val terminal = requireTerminal(terminalId)
                synchronized(terminal.lock) {
                    check(!terminal.stopping) { "App 终端 $terminalId 正在停止" }
                    terminal.input.write(text)
                    terminal.input.newLine()
                    terminal.input.flush()
                }
            }.onFailure { error -> report("发送 App 终端 $terminalId 输入", error) }
        }
    }

    fun stopTerminal(terminalId: Long) {
        scope.launch(Dispatchers.IO) {
            runCatching { stopTerminalNow(terminalId) }
                .onFailure { error -> report("关闭 App 终端 $terminalId", error) }
        }
    }

    fun close() {
        val terminalIds = synchronized(lock) {
            if (closed) return
            closed = true
            terminals.keys.toList()
        }
        terminalIds.forEach { terminalId ->
            runCatching { stopTerminalNow(terminalId) }
                .onFailure { error -> Log.w(TAG, "关闭 App 终端 $terminalId 失败", error) }
        }
        stateSignals.close()
    }

    private fun createTerminalNow(): Long = synchronized(creationLock) {
        val terminalId = synchronized(lock) {
            check(!closed) { "App 终端管理器已关闭" }
            nextTerminalId++
            nextTerminalId - 1L
        }
        val rootfs = File(requireNotNull(context.filesDir.parentFile), "rootfs")
        val runtime = File(context.noBackupFilesDir, "app-terminal-runtime/$terminalId")
        val terminalBinary = File(context.applicationInfo.nativeLibraryDir, "libterminal.so")
        require(isContainerSystemInstalled(rootfs)) { "容器系统尚未安装" }
        require(terminalBinary.isFile && terminalBinary.canExecute()) {
            "libterminal.so 不可执行: ${terminalBinary.absolutePath}"
        }
        require(runtime.isDirectory || runtime.mkdirs()) {
            "无法创建 App 终端运行目录: ${runtime.absolutePath}"
        }

        val process = ProcessBuilder(
            terminalBinary.absolutePath,
            "session",
            "proot",
            "--rootfs",
            rootfs.absolutePath,
            "--runtime",
            runtime.absolutePath,
            "--host-path",
            HOST_TOOL_PATH,
        )
            .redirectErrorStream(true)
            .start()
        val managed = ManagedTerminal(
            id = terminalId,
            process = process,
            input = process.outputStream.bufferedWriter(Charsets.UTF_8),
        )
        synchronized(lock) {
            if (closed) {
                process.destroyForciblyCompat()
                throw IllegalStateException("App 终端管理器已关闭")
            }
            terminals[terminalId] = managed
            aliveGeneration++
        }
        signalStateChanged()
        Log.i(TAG, "App proot 终端启动成功: id=$terminalId")
        scope.launch(Dispatchers.IO) { collectOutput(managed) }
        terminalId
    }

    private fun collectOutput(terminal: ManagedTerminal) {
        var exitCode = -1
        try {
            terminal.process.inputStream.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    val update = terminal.outputAccumulator.consume(
                        String(buffer, 0, count),
                    )
                    if (terminal.applyOutput(update)) {
                        signalStateChanged()
                    }
                }
            }
            exitCode = terminal.process.waitFor()
        } catch (error: Throwable) {
            val stopping = synchronized(terminal.lock) { terminal.stopping }
            if (!stopping) report("读取 App 终端 ${terminal.id} 输出", error)
            exitCode = runCatching { terminal.process.waitFor() }.getOrDefault(-1)
        } finally {
            synchronized(lock) {
                if (terminals[terminal.id] === terminal) {
                    terminals.remove(terminal.id)
                    aliveGeneration++
                }
            }
            runCatching { terminal.input.close() }
            signalStateChanged()
            Log.i(TAG, "App proot 终端退出: id=${terminal.id} exitCode=$exitCode")
        }
    }

    private fun stopTerminalNow(terminalId: Long) {
        val terminal = synchronized(lock) { terminals[terminalId] } ?: return
        synchronized(terminal.lock) {
            if (terminal.stopping) return
            terminal.stopping = true
            runCatching { terminal.input.close() }
        }
        terminal.process.destroy()
        if (!terminal.process.waitForCompat(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            terminal.process.destroyForciblyCompat()
            terminal.process.waitForCompat(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun publishStateNow() {
        val snapshot = synchronized(lock) {
            val ids = terminals.keys.sorted()
            TerminalManagerState(
                aliveGeneration = aliveGeneration,
                aliveTerminalIds = ids,
                terminals = ids.associateWith { terminalId ->
                    requireNotNull(terminals[terminalId]).snapshot()
                },
            )
        }
        _state.value = snapshot
    }

    private fun signalStateChanged() {
        stateSignals.trySend(Unit)
    }

    private fun requireTerminal(terminalId: Long): ManagedTerminal =
        synchronized(lock) { terminals[terminalId] }
            ?: throw IOException("App 终端 $terminalId 不存在或已经退出")

    private fun report(operation: String, error: Throwable) {
        reportError("App.AppTerminalManager", operation, error, null)
    }

    private class ManagedTerminal(
        val id: Long,
        val process: Process,
        val input: BufferedWriter,
    ) {
        val lock = Any()
        val logs = ArrayDeque<TerminalLogEntry>()
        val outputAccumulator = TerminalOutputAccumulator()
        var nextLogId = 1L
        var logGeneration = 0L
        var stopping = false
        var inputPrompt: String? = null

        fun applyOutput(update: TerminalOutputUpdate): Boolean = synchronized(lock) {
            var changed = update.inputPromptChanged
            update.completedLines.forEach { line ->
                if (line.isNotEmpty()) {
                    logs.addLast(TerminalLogEntry(nextLogId++, line))
                    changed = true
                }
            }
            while (logs.size > MAX_LOG_LINES) logs.removeFirst()
            inputPrompt = update.inputPrompt
            if (!changed) return@synchronized false
            logGeneration++
            true
        }

        fun clearLogs() = synchronized(lock) {
            logs.clear()
            logGeneration++
        }

        fun snapshot(): TerminalLogState = synchronized(lock) {
            TerminalLogState(
                terminalId = id,
                generation = logGeneration,
                oldestAvailableId = logs.firstOrNull()?.id ?: nextLogId,
                latestId = logs.lastOrNull()?.id ?: nextLogId - 1L,
                lineCount = logs.size,
                entries = logs.toList(),
                inputPrompt = inputPrompt,
            )
        }
    }

    private companion object {
        const val TAG = "AppTerminalManager"
        const val MAX_LOG_LINES = 50_000
        const val STATE_PUBLISH_INTERVAL_MILLIS = 100L
        const val STOP_TIMEOUT_MILLIS = 1_500L
        const val HOST_TOOL_PATH =
            "/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/odm/bin:/apex/com.android.runtime/bin"
    }
}

private fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)

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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}

private const val PROCESS_WAIT_POLL_INTERVAL_MILLIS = 50L
