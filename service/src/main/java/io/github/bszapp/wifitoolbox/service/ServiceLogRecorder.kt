package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogBatch
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogEntry
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 服务进程自己的、纯内存的 logcat 记录器。 */
internal object ServiceLogRecorder {
    private val lock = Any()
    private val entries = ArrayDeque<ServiceLogEntry>()

    private var totalCharacters = 0
    private var nextId = 1L
    private var logcatProcess: java.lang.Process? = null
    private var stopping = false
    private var onVisibleRangeChanged: ((oldestAvailableId: Long, latestId: Long) -> Unit)? = null
    private var pendingLog: PendingLog? = null
    private var pendingFlushTask: ScheduledFuture<*>? = null
    private val pendingFlushExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "toolbox-service-log-grouping").apply { isDaemon = true }
    }

    fun start() {
        val process = synchronized(lock) {
            if (logcatProcess != null) return

            stopping = false
            redirectStandardStreamsToLogcat()
            appendLocked("[ServiceLogRecorder] 开始采集服务日志，pid=${Process.myPid()}")

            val created = runCatching {
                ProcessBuilder(
                    "logcat",
                    "--pid=${Process.myPid()}",
                    "-v",
                    "threadtime",
                    "-T",
                    "1",
                    "*:V",
                )
                    .redirectErrorStream(false)
                    .start()
            }.getOrElse { error ->
                appendLocked(
                    "[ServiceLogRecorder] 无法启动 logcat：" +
                        (error.message ?: error.javaClass.name),
                )
                return
            }

            logcatProcess = created
            created
        }

        notifyCurrentVisibleRange()
        startReader(
            name = "toolbox-service-logcat-output",
            stream = process.inputStream,
            prefix = "",
        )
        startReader(
            name = "toolbox-service-logcat-error",
            stream = process.errorStream,
            prefix = "[logcat stderr] ",
        )
        Thread(
            {
                val exitCode = runCatching { process.waitFor() }.getOrNull()
                val shouldRecord = synchronized(lock) {
                    if (logcatProcess === process) logcatProcess = null
                    !stopping
                }
                if (shouldRecord) {
                    append(
                        "[ServiceLogRecorder] logcat 已退出" +
                            (exitCode?.let { "，exitCode=$it" } ?: ""),
                    )
                }
            },
            "toolbox-service-logcat-waiter",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun setOnVisibleRangeChanged(
        listener: ((oldestAvailableId: Long, latestId: Long) -> Unit)?,
    ) {
        val range = synchronized(lock) {
            onVisibleRangeChanged = listener
            visibleRangeLocked()
        }
        if (listener != null) runCatching { listener(range.first, range.second) }
    }

    fun latestId(): Long = synchronized(lock) { nextId - 1L }

    fun visibleRange(): Pair<Long, Long> = synchronized(lock) { visibleRangeLocked() }

    fun getRange(fromIdInclusive: Long, toIdInclusive: Long): ServiceLogBatch =
        synchronized(lock) {
            val latestId = nextId - 1L
            val oldestAvailableId = entries.peekFirst()?.id ?: nextId
            val selected = if (fromIdInclusive <= toIdInclusive) {
                entries.filter { it.id in fromIdInclusive..toIdInclusive }
            } else {
                emptyList()
            }
            ServiceLogBatch(
                oldestAvailableId = oldestAvailableId,
                latestId = latestId,
                entries = selected,
            )
        }

    fun clear() {
        val notification = synchronized(lock) {
            pendingFlushTask?.cancel(false)
            pendingFlushTask = null
            pendingLog = null
            entries.clear()
            totalCharacters = 0
            val range = visibleRangeLocked()
            Triple(onVisibleRangeChanged, range.first, range.second)
        }
        notification.first?.let { listener ->
            runCatching { listener(notification.second, notification.third) }
        }
    }

    fun stop() {
        val process = synchronized(lock) {
            stopping = true
            onVisibleRangeChanged = null
            pendingFlushTask?.cancel(false)
            pendingFlushTask = null
            pendingLog = null
            logcatProcess.also { logcatProcess = null }
        }

        runCatching { process?.destroy() }
        runCatching { process?.inputStream?.close() }
        runCatching { process?.errorStream?.close() }
        runCatching { process?.outputStream?.close() }
    }

    private fun startReader(
        name: String,
        stream: java.io.InputStream,
        prefix: String,
    ) {
        Thread(
            {
                runCatching {
                    stream.bufferedReader().useLines { sequence ->
                        sequence.forEach { line -> append(prefix + line) }
                    }
                }.onFailure { error ->
                    val shouldRecord = synchronized(lock) { !stopping }
                    if (shouldRecord) {
                        append(
                            "[ServiceLogRecorder] 读取 $name 失败：" +
                                (error.message ?: error.javaClass.name),
                        )
                    }
                }
            },
            name,
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun append(line: String) {
        val notification = synchronized(lock) {
            val currentPending = pendingLog
            val committed = if (
                currentPending != null &&
                isStackTraceContinuation(currentPending, line)
            ) {
                currentPending.append(line)
                null
            } else {
                commitPendingLocked().also {
                    pendingLog = PendingLog(
                        rawLine = boundedLine(line),
                        source = parseThreadtimeLine(line),
                    )
                }
            }
            schedulePendingFlushLocked()
            committed?.let { visibleRangeNotificationLocked() }
        }
        notifyVisibleRange(notification)
    }

    private fun appendLocked(line: String): ServiceLogEntry {
        val boundedLine = boundedLine(line)
        val entry = ServiceLogEntry(
            id = nextId++,
            tag = extractTag(boundedLine),
            rawLine = boundedLine,
        )

        entries.addLast(entry)
        totalCharacters += entry.tag.length + entry.rawLine.length

        while (
            entries.size > MAX_BUFFER_ENTRIES ||
            totalCharacters > MAX_BUFFER_CHARACTERS
        ) {
            val removed = entries.removeFirst()
            totalCharacters -= removed.tag.length + removed.rawLine.length
        }
        return entry
    }

    private fun schedulePendingFlushLocked() {
        pendingFlushTask?.cancel(false)
        pendingFlushTask = pendingFlushExecutor.schedule(
            ::flushPending,
            STACK_TRACE_GROUPING_DELAY_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun flushPending() {
        val notification = synchronized(lock) {
            pendingFlushTask = null
            commitPendingLocked()?.let { visibleRangeNotificationLocked() }
        }
        notifyVisibleRange(notification)
    }

    private fun commitPendingLocked(): ServiceLogEntry? {
        val pending = pendingLog ?: return null
        pendingLog = null
        return appendLocked(pending.rawLine)
    }

    private fun visibleRangeNotificationLocked(): Triple<
        ((oldestAvailableId: Long, latestId: Long) -> Unit)?,
        Long,
        Long,
    > {
        val range = visibleRangeLocked()
        return Triple(onVisibleRangeChanged, range.first, range.second)
    }

    private fun notifyVisibleRange(
        notification: Triple<
            ((oldestAvailableId: Long, latestId: Long) -> Unit)?,
            Long,
            Long,
        >?,
    ) {
        notification?.first?.let { listener ->
            runCatching { listener(notification.second, notification.third) }
        }
    }

    private fun isStackTraceContinuation(pending: PendingLog, line: String): Boolean {
        val parsed = parseThreadtimeLine(line)
        val message = (parsed?.message ?: line).trimStart()
        val pendingSource = pending.source

        if (parsed == null) {
            return pendingSource != null &&
                !line.startsWith("---------") &&
                !line.startsWith("[ServiceLogRecorder]") &&
                !line.startsWith("[logcat stderr]")
        }
        if (pendingSource == null || !parsed.hasSameEmitter(pendingSource)) return false

        return parsed.timestamp == pendingSource.timestamp ||
            STACK_TRACE_CONTINUATION_PATTERN.containsMatchIn(message) ||
            THROWABLE_HEADER_PATTERN.matches(message)
    }

    private fun ThreadtimeLine.hasSameEmitter(other: ThreadtimeLine): Boolean =
        pid == other.pid &&
            tid == other.tid &&
            priority == other.priority &&
            tag == other.tag

    private fun boundedLine(line: String): String = if (line.length <= MAX_LINE_CHARACTERS) {
        line
    } else {
        line.take(MAX_LINE_CHARACTERS) + "…"
    }

    private fun notifyCurrentVisibleRange() {
        val notification = synchronized(lock) {
            val range = visibleRangeLocked()
            Triple(onVisibleRangeChanged, range.first, range.second)
        }
        notification.first?.let { listener ->
            runCatching { listener(notification.second, notification.third) }
        }
    }

    private fun visibleRangeLocked(): Pair<Long, Long> =
        (entries.peekFirst()?.id ?: nextId) to (nextId - 1L)

    private fun extractTag(line: String): String {
        if (line.startsWith("[logcat stderr]")) return "logcat"
        return parseThreadtimeLine(line.substringBefore('\n'))?.tag
            ?: "Unknown"
    }

    private fun parseThreadtimeLine(line: String): ThreadtimeLine? {
        val values = THREADTIME_PATTERN.matchEntire(line)?.groupValues ?: return null
        return ThreadtimeLine(
            timestamp = values[1],
            pid = values[2],
            tid = values[3],
            priority = values[4],
            tag = values[5].trim(),
            message = values[6],
        )
    }

    private fun redirectStandardStreamsToLogcat() {
        runCatching {
            System.setOut(
                PrintStream(
                    LogcatLineOutputStream(Log.INFO, "System.out"),
                    true,
                    Charsets.UTF_8.name(),
                ),
            )
            System.setErr(
                PrintStream(
                    LogcatLineOutputStream(Log.WARN, "System.err"),
                    true,
                    Charsets.UTF_8.name(),
                ),
            )
        }.onFailure { error ->
            appendLocked(
                "[ServiceLogRecorder] 标准输出转入 logcat 失败：" +
                    (error.message ?: error.javaClass.name),
            )
        }
    }

    private class LogcatLineOutputStream(
        private val priority: Int,
        private val tag: String,
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        @Synchronized
        override fun write(value: Int) {
            when (value) {
                '\n'.code -> emitLine()
                '\r'.code -> Unit
                else -> buffer.write(value)
            }
        }

        @Synchronized
        override fun flush() {
            emitLine()
        }

        @Synchronized
        override fun close() {
            emitLine()
        }

        private fun emitLine() {
            if (buffer.size() == 0) return

            val text = buffer.toByteArray().toString(Charsets.UTF_8)
            buffer.reset()
            text.chunked(MAX_LOGCAT_MESSAGE_CHARACTERS).forEach { part ->
                Log.println(priority, tag, part)
            }
        }
    }

    private data class PendingLog(
        var rawLine: String,
        val source: ThreadtimeLine?,
    ) {
        fun append(line: String) {
            if (rawLine.length >= MAX_LINE_CHARACTERS) return
            val remaining = MAX_LINE_CHARACTERS - rawLine.length - 1
            if (remaining <= 0) return
            rawLine += "\n" + line.take(remaining)
        }
    }

    private data class ThreadtimeLine(
        val timestamp: String,
        val pid: String,
        val tid: String,
        val priority: String,
        val tag: String,
        val message: String,
    )

    private val THREADTIME_PATTERN = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+(.+?)\s*:\s?(.*)$""",
    )
    private val STACK_TRACE_CONTINUATION_PATTERN = Regex(
        """^(?:at\s+|Caused by:\s*|Suppressed:\s*|Wrapped by:\s*|\.\.\.\s+\d+\s+more\b)""",
    )
    private val THROWABLE_HEADER_PATTERN = Regex(
        """^(?:Exception in thread\s+.+|(?:[A-Za-z_\x24][\w\x24]*\.)*[A-Za-z_\x24][\w\x24]*(?:Exception|Error|Throwable)(?::.*)?)$""",
    )

    private const val MAX_LOGCAT_MESSAGE_CHARACTERS = 3_000
    private const val MAX_LINE_CHARACTERS = 16 * 1024
    private const val MAX_BUFFER_ENTRIES = 4_000
    private const val MAX_BUFFER_CHARACTERS = 512 * 1024
    private const val STACK_TRACE_GROUPING_DELAY_MILLIS = 300L
}
