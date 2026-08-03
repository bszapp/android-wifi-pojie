package io.github.bszapp.wifitoolbox.service

import android.os.Build
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogBatch
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogEntry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 一个日志来源对应一个纯内存 logcat 记录器；解析与缓存逻辑由所有来源共用。 */
internal class LogcatRecorder(
    private val recorderName: String,
    private val threadName: String,
    private val startMessage: String,
    private val boundaryTag: String,
    private val processId: Int? = null,
    private val filterSpecs: List<String> = listOf("*:V"),
    private val redirectStandardStreams: Boolean = false,
) {
    private val lock = Any()
    private val entries = ArrayDeque<ServiceLogEntry>()
    private val entryListeners = linkedSetOf<(ServiceLogEntry) -> Unit>()

    private var nextId = 1L
    private var logcatProcess: java.lang.Process? = null
    private var stopping = false
    private var onVisibleRangeChanged: ((oldestAvailableId: Long, latestId: Long) -> Unit)? = null

    fun start() {
        val process = synchronized(lock) {
            if (logcatProcess != null) return

            stopping = false
            val captureStartedAtMillis = System.currentTimeMillis()
            val captureBoundary = CaptureBoundary(
                tag = boundaryTag,
                message = "${Process.myPid()}-${System.nanoTime()}",
            )
            if (redirectStandardStreams) redirectStandardStreamsToLogcat()
            appendLocked("[$recorderName] $startMessage")
            Log.i(captureBoundary.tag, captureBoundary.message)

            val command = buildList {
                add("logcat")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    add("--proto")
                }
                processId?.let { add("--pid=$it") }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
                    add("-v")
                    add("long")
                    add("-v")
                    add("epoch")
                }
                add("-T")
                add(logcatSinceArgument(captureStartedAtMillis - LOGCAT_BOUNDARY_LOOKBACK_MILLIS))
                addAll(filterSpecs)
            }
            val created = runCatching {
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
            }.getOrElse { error ->
                appendLocked(
                    "[$recorderName] 无法启动 logcat：" +
                        (error.message ?: error.javaClass.name),
                )
                return
            }

            logcatProcess = created
            created to captureBoundary
        }

        notifyCurrentVisibleRange()
        startLogcatReader(process.first.inputStream, process.second)
        startErrorReader(
            name = "$threadName-error",
            stream = process.first.errorStream,
            prefix = "[logcat stderr] ",
        )
        Thread(
            {
                val exitCode = runCatching { process.first.waitFor() }.getOrNull()
                val shouldRecord = synchronized(lock) {
                    if (logcatProcess === process.first) logcatProcess = null
                    !stopping
                }
                if (shouldRecord) {
                    append(
                        "[$recorderName] logcat 已退出" +
                            (exitCode?.let { "，exitCode=$it" } ?: ""),
                    )
                }
            },
            "$threadName-waiter",
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

    fun subscribeEntries(listener: (ServiceLogEntry) -> Unit): AutoCloseable {
        synchronized(lock) { entryListeners += listener }
        return AutoCloseable {
            synchronized(lock) { entryListeners -= listener }
        }
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
            entries.clear()
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
            entryListeners.clear()
            logcatProcess.also { logcatProcess = null }
        }

        runCatching { process?.destroy() }
        runCatching { process?.inputStream?.close() }
        runCatching { process?.errorStream?.close() }
        runCatching { process?.outputStream?.close() }
    }

    private fun startLogcatReader(
        stream: InputStream,
        startAfter: CaptureBoundary,
    ) {
        Thread(
            {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                        readProtobufLogcat(stream, startAfter)
                    } else {
                        readTextLogcat(stream, startAfter)
                    }
                }.onFailure { error ->
                    val shouldRecord = synchronized(lock) { !stopping }
                    if (shouldRecord) {
                        append(
                            "[$recorderName] 读取 $threadName-output 失败：" +
                                (error.message ?: error.javaClass.name),
                        )
                    }
                }
            },
            "$threadName-output",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startErrorReader(
        name: String,
        stream: InputStream,
        prefix: String,
    ) {
        Thread(
            {
                runCatching {
                    stream.bufferedReader().useLines { sequence ->
                        sequence.forEach { line ->
                            append(prefix + line)
                        }
                    }
                }.onFailure { error ->
                    val shouldRecord = synchronized(lock) { !stopping }
                    if (shouldRecord) {
                        append(
                            "[$recorderName] 读取 $name 失败：" +
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

    private fun readTextLogcat(
        stream: InputStream,
        startAfter: CaptureBoundary,
    ) {
        var boundaryReached = false
        var pendingHeader: LongLogcatHeader? = null
        val pendingLines = mutableListOf<String>()

        fun commitPending() {
            val header = pendingHeader ?: return
            if (pendingLines.isEmpty()) return

            val record = LogcatRecord(
                timestampMillis = header.timestampMillis,
                pid = header.pid,
                tid = header.tid,
                priority = header.priority,
                tag = header.tag,
                message = pendingLines.joinToString("\n").trimEnd('\n'),
            )
            pendingLines.clear()

            if (!boundaryReached) {
                boundaryReached = record.tag == startAfter.tag &&
                    record.message == startAfter.message
                return
            }
            append(record)
        }

        stream.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.replace("\r", "")
                val header = parseLongLogcatHeader(line)
                if (header != null) {
                    commitPending()
                    pendingHeader = header
                } else if (!line.startsWith(LOGCAT_BUFFER_MARKER_PREFIX)) {
                    pendingHeader?.let { pendingLines += line }
                }
            }
        }
        commitPending()
    }

    private fun readProtobufLogcat(
        stream: InputStream,
        startAfter: CaptureBoundary,
    ) {
        val input = BufferedInputStream(stream)
        skipInitialBufferMarker(input)
        var boundaryReached = false

        while (true) {
            val sizeBytes = input.readExactlyOrNull(PROTOBUF_SIZE_BYTES) ?: return
            val recordSize = littleEndianLong(sizeBytes)
            if (recordSize !in 0..MAX_PROTOBUF_RECORD_BYTES.toLong()) {
                throw IllegalStateException("logcat protobuf 记录长度非法：$recordSize")
            }

            val record = parseLogcatEntryProto(input.readExactly(recordSize.toInt()))
            if (!boundaryReached) {
                boundaryReached = record.tag == startAfter.tag &&
                    record.message == startAfter.message
            } else {
                append(record)
            }
        }
    }

    private fun skipInitialBufferMarker(input: BufferedInputStream) {
        val prefix = LOGCAT_BUFFER_MARKER_PREFIX.toByteArray(Charsets.UTF_8)
        input.mark(prefix.size + 1)
        val candidate = ByteArray(prefix.size)
        val count = input.readUpTo(candidate)
        if (count == prefix.size && candidate.contentEquals(prefix)) {
            while (true) {
                val value = input.read()
                if (value < 0 || value == '\n'.code) return
            }
        }
        input.reset()
    }

    private fun parseLongLogcatHeader(line: String): LongLogcatHeader? {
        val values = LONG_EPOCH_HEADER_PATTERN.matchEntire(line)?.groupValues ?: return null
        val seconds = values[1].toLongOrNull() ?: return null
        val fractionalNanos = values[2]
            .take(PROTOBUF_NANOSECOND_DIGITS)
            .padEnd(PROTOBUF_NANOSECOND_DIGITS, '0')
            .toLongOrNull()
            ?: return null
        return LongLogcatHeader(
            timestampMillis = seconds * 1_000L + fractionalNanos / 1_000_000L,
            pid = values[3].toLongOrNull() ?: return null,
            tid = values[4].toLongOrNull() ?: return null,
            priority = values[5].single(),
            tag = values[6],
        )
    }

    private fun parseLogcatEntryProto(bytes: ByteArray): LogcatRecord {
        val input = ProtoInput(bytes)
        var timeSec = 0L
        var timeNsec = 0L
        var priority = 0
        var pid = 0L
        var tid = 0L
        var tag = ByteArray(0)
        var message = ByteArray(0)

        while (input.hasRemaining()) {
            val key = input.readVarint().toInt()
            val fieldNumber = key ushr 3
            val wireType = key and PROTOBUF_WIRE_TYPE_MASK
            when (fieldNumber) {
                PROTO_TIME_SEC_FIELD -> timeSec = input.readVarintField(wireType)
                PROTO_TIME_NSEC_FIELD -> timeNsec = input.readVarintField(wireType)
                PROTO_PRIORITY_FIELD -> priority = input.readVarintField(wireType).toInt()
                PROTO_PID_FIELD -> pid = input.readVarintField(wireType)
                PROTO_TID_FIELD -> tid = input.readVarintField(wireType)
                PROTO_TAG_FIELD -> tag = input.readBytesField(wireType)
                PROTO_MESSAGE_FIELD -> message = input.readBytesField(wireType)
                else -> input.skipField(wireType)
            }
        }

        val tagEnd = (tag.size - 1).coerceAtLeast(0)
        val messageEnd = if (message.lastOrNull() == '\n'.code.toByte()) {
            message.size - 1
        } else {
            message.size
        }
        return LogcatRecord(
            timestampMillis = timeSec * 1_000L + timeNsec / 1_000_000L,
            pid = pid,
            tid = tid,
            priority = protobufPriority(priority),
            tag = tag.copyOfRange(0, tagEnd).toString(Charsets.UTF_8),
            message = message.copyOfRange(0, messageEnd).toString(Charsets.UTF_8),
        )
    }

    private fun formatThreadtimeRecord(record: LogcatRecord): String {
        val timestamp = synchronized(threadtimeDateFormat) {
            threadtimeDateFormat.format(Date(record.timestampMillis))
        }
        val prefix = buildString {
            append(timestamp)
            append(' ')
            append(record.pid.toString().padStart(5))
            append(' ')
            append(record.tid.toString().padStart(5))
            append(' ')
            append(record.priority)
            append(' ')
            append(record.tag)
            append(": ")
        }
        return record.message.split('\n').joinToString("\n") { messageLine ->
            prefix + messageLine
        }
    }

    private fun protobufPriority(priority: Int): Char = when (priority) {
        2 -> 'V'
        3 -> 'D'
        4 -> 'I'
        5 -> 'W'
        6 -> 'E'
        8 -> 'A'
        else -> 'E'
    }

    private fun InputStream.readUpTo(destination: ByteArray): Int {
        var offset = 0
        while (offset < destination.size) {
            val count = read(destination, offset, destination.size - offset)
            if (count < 0) break
            offset += count
        }
        return offset
    }

    private fun InputStream.readExactlyOrNull(size: Int): ByteArray? {
        val result = ByteArray(size)
        val count = readUpTo(result)
        if (count == 0) return null
        if (count != size) throw EOFException("logcat protobuf 数据在记录中间结束")
        return result
    }

    private fun InputStream.readExactly(size: Int): ByteArray =
        readExactlyOrNull(size) ?: throw EOFException("logcat protobuf 数据提前结束")

    private fun littleEndianLong(bytes: ByteArray): Long {
        var value = 0L
        bytes.forEachIndexed { index, byte ->
            value = value or ((byte.toLong() and 0xFFL) shl (index * 8))
        }
        return value
    }

    private fun append(line: String) {
        val notification = synchronized(lock) {
            val entry = appendLocked(line)
            EntryNotification(
                entry = entry,
                listeners = entryListeners.toList(),
                visibleRange = visibleRangeNotificationLocked(),
            )
        }
        notifyEntry(notification)
    }

    private fun append(record: LogcatRecord) {
        val rawLine = formatThreadtimeRecord(record)
        val expanded = expandElidedStackFrames(rawLine.lineSequence().toList())
        val notification = synchronized(lock) {
            val entry = appendLocked(expanded)
            EntryNotification(
                entry = entry,
                listeners = entryListeners.toList(),
                visibleRange = visibleRangeNotificationLocked(),
            )
        }
        notifyEntry(notification)
    }

    private fun notifyEntry(notification: EntryNotification) {
        notification.listeners.forEach { listener ->
            runCatching { listener(notification.entry) }
        }
        notifyVisibleRange(notification.visibleRange)
    }

    private fun appendLocked(line: String): ServiceLogEntry {
        val entry = ServiceLogEntry(
            id = nextId++,
            tag = extractTag(line),
            rawLine = line,
        )

        entries.addLast(entry)

        while (entries.size > MAX_BUFFER_ENTRIES) entries.removeFirst()
        return entry
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

    private fun expandElidedStackFrames(lines: List<String>): String {
        val output = ArrayList<String>(lines.size)
        val contexts = mutableMapOf<Int, StackTraceContext>()

        lines.forEach { rawLine ->
            val parsed = parseThreadtimeLine(rawLine)
            val message = parsed?.message ?: rawLine
            val leadingWhitespace = message.takeWhile { it == ' ' || it == '\t' }
            val content = message.substring(leadingWhitespace.length)
            val indentation = stackTraceIndentation(leadingWhitespace)

            when {
                STACK_FRAME_PATTERN.matches(content) -> {
                    val level = (indentation - 1).coerceAtLeast(0)
                    contexts.getOrPut(level) { StackTraceContext() }.frames += content
                    output += rawLine
                }

                ENCLOSED_THROWABLE_PATTERN.containsMatchIn(content) -> {
                    val level = indentation
                    contexts.keys.removeAll { it > level }
                    val enclosingFrames = if (content.startsWith("Suppressed:")) {
                        contexts[level - 1]?.frames.orEmpty()
                    } else {
                        contexts[level]?.frames.orEmpty()
                    }
                    contexts[level] = StackTraceContext(
                        enclosingFrames = enclosingFrames.toList(),
                    )
                    output += rawLine
                }

                ELIDED_FRAME_PATTERN.matches(content) -> {
                    val count = ELIDED_FRAME_PATTERN.matchEntire(content)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                    val level = (indentation - 1).coerceAtLeast(0)
                    val context = contexts[level]
                    val expandedFrames = count
                        ?.takeIf { it > 0 && it <= context?.enclosingFrames.orEmpty().size }
                        ?.let { context?.enclosingFrames?.takeLast(it) }

                    if (context != null && !expandedFrames.isNullOrEmpty()) {
                        expandedFrames.forEach { frame ->
                            context.frames += frame
                            output += replaceThreadtimeMessage(
                                rawLine,
                                leadingWhitespace + frame,
                            )
                        }
                    } else {
                        output += rawLine
                    }
                }

                indentation == 0 && THROWABLE_HEADER_PATTERN.matches(content) -> {
                    contexts.clear()
                    contexts[0] = StackTraceContext()
                    output += rawLine
                }

                else -> output += rawLine
            }
        }

        return output.joinToString("\n")
    }

    private fun replaceThreadtimeMessage(line: String, message: String): String {
        val match = THREADTIME_PATTERN.matchEntire(line) ?: return message
        val range = match.groups[6]?.range ?: return message
        return line.replaceRange(range, message)
    }

    private fun stackTraceIndentation(whitespace: String): Int {
        var indentation = 0
        var spaces = 0
        whitespace.forEach { character ->
            if (character == '\t') {
                indentation++
                spaces = 0
            } else {
                spaces++
                if (spaces == STACK_TRACE_SPACES_PER_INDENT) {
                    indentation++
                    spaces = 0
                }
            }
        }
        return indentation
    }

    private fun logcatSinceArgument(epochMillis: Long): String =
        "${epochMillis / 1_000}.${(epochMillis % 1_000).toString().padStart(3, '0')}"

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
                "[$recorderName] 标准输出转入 logcat 失败：" +
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

    private class ProtoInput(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun hasRemaining(): Boolean = position < bytes.size

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (shift < Long.SIZE_BITS) {
                if (position >= bytes.size) throw EOFException("protobuf varint 数据不完整")
                val current = bytes[position++].toInt() and 0xFF
                value = value or ((current and 0x7F).toLong() shl shift)
                if (current and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalStateException("protobuf varint 长度非法")
        }

        fun readVarintField(wireType: Int): Long {
            require(wireType == PROTOBUF_VARINT_WIRE_TYPE) {
                "protobuf 字段类型错误：$wireType"
            }
            return readVarint()
        }

        fun readBytesField(wireType: Int): ByteArray {
            require(wireType == PROTOBUF_LENGTH_DELIMITED_WIRE_TYPE) {
                "protobuf 字段类型错误：$wireType"
            }
            val size = readVarint()
            if (size !in 0..(bytes.size - position).toLong()) {
                throw EOFException("protobuf bytes 数据不完整")
            }
            val end = position + size.toInt()
            return bytes.copyOfRange(position, end).also { position = end }
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                PROTOBUF_VARINT_WIRE_TYPE -> readVarint()
                PROTOBUF_FIXED_64_WIRE_TYPE -> skipBytes(Long.SIZE_BYTES)
                PROTOBUF_LENGTH_DELIMITED_WIRE_TYPE -> {
                    val size = readVarint()
                    if (size > Int.MAX_VALUE) {
                        throw IllegalStateException("protobuf 字段长度非法：$size")
                    }
                    skipBytes(size.toInt())
                }
                PROTOBUF_FIXED_32_WIRE_TYPE -> skipBytes(Int.SIZE_BYTES)
                else -> throw IllegalStateException("不支持的 protobuf wire type：$wireType")
            }
        }

        private fun skipBytes(count: Int) {
            if (count < 0 || position + count > bytes.size) {
                throw EOFException("protobuf 字段数据不完整")
            }
            position += count
        }
    }

    private data class CaptureBoundary(
        val tag: String,
        val message: String,
    )

    private data class EntryNotification(
        val entry: ServiceLogEntry,
        val listeners: List<(ServiceLogEntry) -> Unit>,
        val visibleRange: Triple<
            ((oldestAvailableId: Long, latestId: Long) -> Unit)?,
            Long,
            Long,
        >,
    )

    private data class StackTraceContext(
        val enclosingFrames: List<String> = emptyList(),
        val frames: MutableList<String> = mutableListOf(),
    )

    private data class LongLogcatHeader(
        val timestampMillis: Long,
        val pid: Long,
        val tid: Long,
        val priority: Char,
        val tag: String,
    )

    private data class LogcatRecord(
        val timestampMillis: Long,
        val pid: Long,
        val tid: Long,
        val priority: Char,
        val tag: String,
        val message: String,
    )

    private data class ThreadtimeLine(
        val timestamp: String,
        val pid: String,
        val tid: String,
        val priority: String,
        val tag: String,
        val message: String,
    )

    private companion object {
        val THREADTIME_PATTERN = Regex(
            """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+(.+?)\s*:\s?(.*)$""",
        )
        val LONG_EPOCH_HEADER_PATTERN = Regex(
            """^\[\s*(\d+)\.(\d+)\s+(\d+):\s*(\d+)\s+([VDIWEFAS])/(.*?)\s*]$""",
        )
        val THROWABLE_HEADER_PATTERN = Regex(
            """^(?:Exception in thread\s+.+|(?:[A-Za-z_\x24][\w\x24]*\.)*[A-Za-z_\x24][\w\x24]*(?:Exception|Error|Throwable)(?::.*)?)$""",
        )
        val STACK_FRAME_PATTERN = Regex("""^at\s+.+\(.+\)$""")
        val ENCLOSED_THROWABLE_PATTERN = Regex(
            """^(?:Caused by:|Suppressed:|Wrapped by:)\s*.+$""",
        )
        val ELIDED_FRAME_PATTERN = Regex("""^\.\.\.\s+(\d+)\s+more$""")
        val threadtimeDateFormat = SimpleDateFormat(
            "MM-dd HH:mm:ss.SSS",
            Locale.US,
        )

        const val MAX_LOGCAT_MESSAGE_CHARACTERS = 3_000
        const val MAX_BUFFER_ENTRIES = 50_000
        const val STACK_TRACE_SPACES_PER_INDENT = 4
        const val LOGCAT_BOUNDARY_LOOKBACK_MILLIS = 1_000L
        const val LOGCAT_BUFFER_MARKER_PREFIX = "--------- beginning of"
        const val PROTOBUF_SIZE_BYTES = 8
        const val MAX_PROTOBUF_RECORD_BYTES = 1024 * 1024
        const val PROTOBUF_NANOSECOND_DIGITS = 9
        const val PROTOBUF_WIRE_TYPE_MASK = 0x07
        const val PROTOBUF_VARINT_WIRE_TYPE = 0
        const val PROTOBUF_FIXED_64_WIRE_TYPE = 1
        const val PROTOBUF_LENGTH_DELIMITED_WIRE_TYPE = 2
        const val PROTOBUF_FIXED_32_WIRE_TYPE = 5
        const val PROTO_TIME_SEC_FIELD = 1
        const val PROTO_TIME_NSEC_FIELD = 2
        const val PROTO_PRIORITY_FIELD = 3
        const val PROTO_PID_FIELD = 5
        const val PROTO_TID_FIELD = 6
        const val PROTO_TAG_FIELD = 7
        const val PROTO_MESSAGE_FIELD = 8
    }
}

/** 两种服务侧日志来源共用同一组进程级实例，入口和 Binder 服务均可幂等启动。 */
internal object ServiceLogRecorders {
    val service = LogcatRecorder(
        recorderName = "ServiceLogRecorder",
        threadName = "toolbox-service-logcat",
        startMessage = "开始采集服务日志，pid=${Process.myPid()}",
        boundaryTag = "SvcLogBoundary",
        processId = Process.myPid(),
        redirectStandardStreams = true,
    )

    val systemWifi = LogcatRecorder(
        recorderName = "SystemWifiLogRecorder",
        threadName = "toolbox-system-wifi-logcat",
        startMessage = "开始采集系统wifi日志",
        boundaryTag = "WifiLogBoundary",
        filterSpecs = listOf(
            "wpa_supplicant:V",
            "WifiLogBoundary:V",
            "*:S",
        ),
    )

    fun start() {
        service.start()
        systemWifi.start()
    }
}
