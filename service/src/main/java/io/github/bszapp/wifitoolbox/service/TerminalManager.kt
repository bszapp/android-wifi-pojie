package io.github.bszapp.wifitoolbox.service

import android.util.Log
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogBatch
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogEntry
import java.io.BufferedWriter
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

internal class TerminalManager(
    private val onAliveTerminalsChanged: (AliveTerminalSnapshot) -> Unit,
    private val onTerminalLogRangeChanged: (TerminalLogRangeSnapshot) -> Unit,
) {
    private val lock = Any()
    private val creationLock = Any()
    private val terminals = linkedMapOf<Long, ManagedTerminal>()
    private var nextTerminalId = 1L
    private var aliveGeneration = 0L
    private var closed = false

    fun createTerminal(
        command: List<String>,
        onExit: (terminalId: Long, exitCode: Int) -> Unit = { _, _ -> },
    ): Long = synchronized(creationLock) {
        require(command.isNotEmpty()) { "终端启动命令不能为空" }
        synchronized(lock) { check(!closed) { "终端管理器已关闭" } }

        val displayCommand = formatCommand(command)
        val terminalId = synchronized(lock) { nextTerminalId }
        val startup = CompletableFuture<ManagedTerminal>()
        val ownerThread = Thread(
            {
                try {
                    Log.i(
                        TAG,
                        "准备启动终端: id=$terminalId " +
                            "ownerThread=${Thread.currentThread().name} command=$displayCommand",
                    )
                    val process = ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start()
                    val terminal = ManagedTerminal(
                        id = terminalId,
                        process = process,
                        input = process.outputStream.bufferedWriter(Charsets.UTF_8),
                        displayCommand = displayCommand,
                        ownerThread = Thread.currentThread(),
                        onExit = onExit,
                    )
                    val aliveSnapshot = synchronized(lock) {
                        if (closed) {
                            null
                        } else {
                            check(nextTerminalId == terminalId) {
                                "终端 ID 分配异常: expected=$terminalId actual=$nextTerminalId"
                            }
                            nextTerminalId++
                            terminals[terminalId] = terminal
                            aliveGeneration++
                            aliveSnapshotLocked()
                        }
                    }
                    if (aliveSnapshot == null) {
                        Log.w(
                            TAG,
                            "终端进程创建后管理器已关闭，立即强制结束: " +
                                "id=$terminalId command=$displayCommand",
                        )
                        process.destroyForcibly()//TODO:Call requires API level 26 (current min is 24): java.lang.Process#destroyForcibly，下一行也是
                        process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        throw IllegalStateException("终端管理器已关闭")
                    }

                    Log.i(
                        TAG,
                        "终端启动成功: id=$terminalId " +
                            "ownerThread=${Thread.currentThread().name} command=$displayCommand",
                    )
                    runCatching { onAliveTerminalsChanged(aliveSnapshot) }
                        .onFailure { error ->
                            Log.w(TAG, "发布终端 $terminalId 存活状态失败：${error.message}", error)
                        }
                    startup.complete(terminal)
                    collectOutput(terminal)
                } catch (error: Throwable) {
                    if (!startup.completeExceptionally(error)) {
                        Log.e(TAG, "终端 $terminalId owner thread 异常：${error.message}", error)
                    } else {
                        Log.e(TAG, "终端进程创建失败: id=$terminalId command=$displayCommand", error)
                    }
                }
            },
            "terminal-owner-$terminalId",
        ).apply {
            isDaemon = true
            start()
        }

        val terminal = awaitTerminalStart(startup)
        check(terminal.ownerThread === ownerThread) {
            "终端 $terminalId owner thread 不一致"
        }
        terminal.id
    }

    private fun awaitTerminalStart(startup: CompletableFuture<ManagedTerminal>): ManagedTerminal {
        var interrupted = false
        try {
            while (true) {
                try {
                    return startup.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (error: ExecutionException) {
                    val cause = error.cause ?: error
                    throw when (cause) {
                        is RuntimeException -> cause
                        is Error -> cause
                        else -> IOException("终端进程创建失败", cause)
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun writeInput(terminalId: Long, text: String) {
        val terminal = requireTerminal(terminalId)
        synchronized(terminal.lock) {
            check(!terminal.stopping) { "终端 $terminalId 正在停止" }
            terminal.input.write(text)
            terminal.input.newLine()
            terminal.input.flush()
        }
    }

    fun stopTerminal(terminalId: Long, reason: String = "调用方请求停止") {
        val terminal = synchronized(lock) { terminals[terminalId] }
        if (terminal == null) {
            Log.d(TAG, "忽略停止请求，终端已经退出: id=$terminalId reason=$reason")
            return
        }
        synchronized(terminal.lock) {
            if (terminal.stopping) {
                Log.d(
                    TAG,
                    "忽略重复停止请求: id=$terminalId reason=$reason " +
                        "existingReason=${terminal.stopReason}",
                )
                return
            }
            terminal.stopping = true
            terminal.stopReason = reason
            runCatching { terminal.input.close() }
        }
        Log.i(
            TAG,
            "请求停止终端: id=$terminalId reason=$reason " +
                "thread=${Thread.currentThread().name}",
        )
        terminal.process.destroy()
        if (!terminal.process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {//TODO:Call requires API level 26 (current min is 24): java.lang.Process#destroyForcibly，下面几行也有问题
            synchronized(terminal.lock) { terminal.forcedStop = true }
            Log.w(
                TAG,
                "终端在 ${STOP_TIMEOUT_MILLIS}ms 内未退出，执行强制结束: " +
                    "id=$terminalId reason=$reason",
            )
            terminal.process.destroyForcibly()
            if (!terminal.process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "强制结束后终端仍未确认退出: id=$terminalId reason=$reason")
            }
        }
    }

    fun aliveSnapshot(): AliveTerminalSnapshot = synchronized(lock) {
        aliveSnapshotLocked()
    }

    fun terminalSnapshots(): List<TerminalLogRangeSnapshot> =
        synchronized(lock) { terminals.values.toList() }.map(ManagedTerminal::rangeSnapshot)

    fun getLogCount(terminalId: Long): Int = requireTerminal(terminalId).rangeSnapshot().lineCount

    fun getLogRange(terminalId: Long): TerminalLogRangeSnapshot =
        requireTerminal(terminalId).rangeSnapshot()

    fun getLogs(
        terminalId: Long,
        fromIdInclusive: Long,
        toIdInclusive: Long,
    ): TerminalLogBatch = requireTerminal(terminalId).getLogs(fromIdInclusive, toIdInclusive)

    fun clearLogs(terminalId: Long) {
        val range = requireTerminal(terminalId).clearLogs()
        onTerminalLogRangeChanged(range)
    }

    fun close() {
        val openTerminals = synchronized(lock) {
            if (closed) return
            closed = true
            terminals.values.toList()
        }
        openTerminals.forEach { terminal ->
            stopTerminal(terminal.id, reason = "终端管理器关闭")
        }
        openTerminals.forEach { terminal ->
            if (terminal.ownerThread !== Thread.currentThread()) {
                terminal.ownerThread.join(OWNER_THREAD_JOIN_MILLIS)
                if (terminal.ownerThread.isAlive) {
                    Log.w(TAG, "终端 owner thread 未按时退出: id=${terminal.id}")
                }
            }
        }
    }

    private fun collectOutput(terminal: ManagedTerminal) {
        var exitCode = -1
        try {
            terminal.process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "终端 ${terminal.id}: $line")
                    onTerminalLogRangeChanged(terminal.appendLog(line))
                }
            }
            exitCode = terminal.process.waitFor()
        } catch (error: Throwable) {
            Log.w(TAG, "读取终端 ${terminal.id} 输出失败：${error.message}", error)
            exitCode = runCatching { terminal.process.waitFor() }.getOrDefault(-1)
        } finally {
            val exitState = terminal.exitState()
            val exitMessage = buildString {
                append("终端退出: id=${terminal.id} exitCode=$exitCode")
                append(" result=${describeExitCode(exitCode)}")
                append(" stopRequested=${exitState.stopRequested}")
                append(" forcedStop=${exitState.forcedStop}")
                append(" stopReason=${exitState.stopReason ?: "<无>"}")
                append(" command=${terminal.displayCommand}")
            }
            when {
                exitState.forcedStop -> Log.w(TAG, exitMessage)
                !exitState.stopRequested && exitCode != 0 -> Log.e(TAG, exitMessage)
                else -> Log.i(TAG, exitMessage)
            }
            val aliveSnapshot = synchronized(lock) {
                if (terminals[terminal.id] !== terminal) return@synchronized null
                terminals.remove(terminal.id)
                aliveGeneration++
                aliveSnapshotLocked()
            }
            runCatching { terminal.input.close() }
            aliveSnapshot?.let(onAliveTerminalsChanged)
            runCatching { terminal.onExit(terminal.id, exitCode) }
                .onFailure { error ->
                    Log.w(TAG, "终端 ${terminal.id} 退出回调失败：${error.message}", error)
                }
        }
    }

    private fun requireTerminal(terminalId: Long): ManagedTerminal =
        synchronized(lock) { terminals[terminalId] }
            ?: throw IOException("终端 $terminalId 不存在或已经退出")

    private fun aliveSnapshotLocked(): AliveTerminalSnapshot = AliveTerminalSnapshot(
        generation = aliveGeneration,
        terminalIds = terminals.keys.sorted().toLongArray(),
    )

    private class ManagedTerminal(
        val id: Long,
        val process: Process,
        val input: BufferedWriter,
        val displayCommand: String,
        val ownerThread: Thread,
        val onExit: (terminalId: Long, exitCode: Int) -> Unit,
    ) {
        val lock = Any()
        val logs = ArrayDeque<TerminalLogEntry>()
        var nextLogId = 1L
        var logGeneration = 0L
        var stopping = false
        var forcedStop = false
        var stopReason: String? = null

        fun exitState(): TerminalExitState = synchronized(lock) {
            TerminalExitState(
                stopRequested = stopping,
                forcedStop = forcedStop,
                stopReason = stopReason,
            )
        }

        fun appendLog(text: String): TerminalLogRangeSnapshot = synchronized(lock) {
            logs.addLast(TerminalLogEntry(nextLogId++, text))
            while (logs.size > MAX_LOG_LINES) logs.removeFirst()
            logGeneration++
            rangeSnapshotLocked()
        }

        fun clearLogs(): TerminalLogRangeSnapshot = synchronized(lock) {
            logs.clear()
            logGeneration++
            rangeSnapshotLocked()
        }

        fun rangeSnapshot(): TerminalLogRangeSnapshot = synchronized(lock) {
            rangeSnapshotLocked()
        }

        fun getLogs(fromIdInclusive: Long, toIdInclusive: Long): TerminalLogBatch =
            synchronized(lock) {
                val range = rangeSnapshotLocked()
                TerminalLogBatch(
                    terminalId = id,
                    generation = range.generation,
                    oldestAvailableId = range.oldestAvailableId,
                    latestId = range.latestId,
                    lineCount = range.lineCount,
                    entries = logs.filter { it.id in fromIdInclusive..toIdInclusive },
                )
            }

        private fun rangeSnapshotLocked(): TerminalLogRangeSnapshot = TerminalLogRangeSnapshot(
            terminalId = id,
            generation = logGeneration,
            oldestAvailableId = logs.firstOrNull()?.id ?: nextLogId,
            latestId = logs.lastOrNull()?.id ?: nextLogId - 1L,
            lineCount = logs.size,
        )
    }

    companion object {
        private const val TAG = "TerminalManager"
        private const val MAX_LOG_LINES = 5_000
        private const val STOP_TIMEOUT_MILLIS = 1_500L
        private const val OWNER_THREAD_JOIN_MILLIS = 1_500L

        private fun formatCommand(command: List<String>): String = command.joinToString(" ") { value ->
            if (value.all { it.isLetterOrDigit() || it in "/._:-" }) {
                value
            } else {
                "'${value.replace("'", "'\\''")}'"
            }
        }

        private fun describeExitCode(exitCode: Int): String = when {
            exitCode == 0 -> "正常退出"
            exitCode == 137 -> "SIGKILL(9)"
            exitCode == 143 -> "SIGTERM(15)"
            exitCode == 130 -> "SIGINT(2)"
            exitCode in 129..255 -> "signal=${exitCode - 128}"
            exitCode < 0 -> "未取得有效退出码"
            else -> "进程返回非零状态"
        }
    }
}

private data class TerminalExitState(
    val stopRequested: Boolean,
    val forcedStop: Boolean,
    val stopReason: String?,
)

internal data class AliveTerminalSnapshot(
    val generation: Long,
    val terminalIds: LongArray,
)

internal data class TerminalLogRangeSnapshot(
    val terminalId: Long,
    val generation: Long,
    val oldestAvailableId: Long,
    val latestId: Long,
    val lineCount: Int,
)
