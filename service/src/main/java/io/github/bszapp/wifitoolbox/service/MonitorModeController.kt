package io.github.bszapp.wifitoolbox.service

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChange
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChannel
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCaptureClearProgress
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCaptureClearStage
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChangesPage
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDeviceRealtime
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDisconnectionRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDisconnectionType
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorFrameGroupStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorFrameSubtypeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeCaptureQuality
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeFailureReason
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStep
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSecurityProtocol
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSignalStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Service 侧拥有监听模式的录制终端、统计终端、FIFO 和 pcap 文件状态。 */
internal class MonitorModeController(
    private val terminalManager: TerminalManager,
    private val captureChannel: () -> MonitorChannel?,
    private val onStatisticsChanged: (MonitorModeStatistics) -> Unit,
    private val onRecordedBytesChanged: (Long, Long) -> Unit,
    private val onExportCompleted: (requestId: String, path: String, fileName: String) -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) {
    private val lock = Any()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "monitor-mode").apply { isDaemon = true }
    }

    private var captureTerminalId: Long? = null
    private var statisticsTerminalId: Long? = null
    private var eventInput: FileInputStream? = null
    private var commandWriter: OutputStreamWriter? = null
    private var pipeFiles: PipeFiles? = null
    private var captureFile: File? = null
    private var exportDirectory: File? = null
    private var recordedBytes = 0L
    private var nonHandshakeBytes = 0L
    private var captureParts: List<File> = emptyList()
    private var clearProgress: MonitorCaptureClearProgress? = null
    private var statisticsPublishScheduled = false
    private var stopping = false
    private var capturing = false
    private var clearing = false
    private var generation = 0L
    private var mirrorGeneration = 0L
    private var activeMirror = CaptureMirror()
    private var preparedMirror = CaptureMirror()
    private val changes get() = activeMirror.changes
    private val activeHandshakes get() = activeMirror.activeHandshakes
    private val handshakeArtifacts get() = activeMirror.handshakeArtifacts
    private val disconnectionArtifacts get() = activeMirror.disconnectionArtifacts
    @Volatile private var diagnosticSession: DiagnosticSession? = null

    /** 心跳只读取诊断快照，不等待业务锁或 FIFO。 */
    private class DiagnosticSession(val generation: Long) {
        @Volatile var active = true
        @Volatile var phase = "waitingEvent"
        @Volatile var phaseSince = SystemClock.elapsedRealtime()
        @Volatile var lastReceivedAt = 0L
        @Volatile var lastHandledAt = 0L
        @Volatile var eventType = "none"
        @Volatile var source = "none"
        @Volatile var eventCount = 0L
        @Volatile var characterCount = 0L
        @Volatile var eventCounts = "{}"
        @Volatile var state = "notPublished"
        @Volatile var stateAt = 0L
        val publishedHeaders = AtomicLong()
        @Volatile var lastPublishedAt = 0L
        @Volatile var lastSignalUnixMillis = 0L
        @Volatile var lastPageAt = 0L
        @Volatile var pageCount = 0L
        @Volatile var lastPage = "none"
    }

    fun start(
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ) {
        stop()

        val rootfs = File(rootfsPath)
        val capture = File(rootfs, CAPTURE_FILE_RELATIVE_PATH)
        val parent = requireNotNull(capture.parentFile)
        require(parent.isDirectory || parent.mkdirs()) {
            "无法创建监听模式临时目录: ${parent.absolutePath}"
        }
        if (capture.exists() && !capture.delete()) {
            throw IOException("无法删除旧的 /tmp/wlanlogs.pcap")
        }

        val pipes = preparePipes(rootfs)
        val input = FileInputStream(
            Os.open(pipes.eventPipe.absolutePath, OsConstants.O_RDWR, 0),
        )
        val writer = FileOutputStream(
            Os.open(pipes.commandPipe.absolutePath, OsConstants.O_RDWR, 0),
        ).writer(Charsets.UTF_8)
        val sessionGeneration = synchronized(lock) {
            generation += 1
            mirrorGeneration++
            activeMirror = CaptureMirror()
            preparedMirror = CaptureMirror()
            captureFile = capture
            captureParts = listOf(capture)
            nonHandshakeBytes = 0L
            clearProgress = null
            eventInput = input
            commandWriter = writer
            exportDirectory = pipes.exportDirectory
            recordedBytes = 0L
            statisticsPublishScheduled = false
            stopping = false
            generation
        }
        val diagnostic = DiagnosticSession(sessionGeneration)
        diagnosticSession = diagnostic
        Log.d(TAG, "[MonitorDiagnostic] start session=$sessionGeneration epoch=$mirrorGeneration " +
            "eventFifo=${pipes.eventFifoId} commandFifo=${pipes.commandFifoId}")
        executor.execute { diagnosticHeartbeat(diagnostic) }
        executor.execute { readStatistics(input, sessionGeneration) }

        try {
            val statisticsId = terminalManager.createChrootTerminal(
                rootfsPath = rootfsPath,
                runtimePath = runtimePath,
                terminalPath = terminalPath,
                onExit = { terminalId, exitCode ->
                    handleTerminalExit(terminalId, exitCode, "监听模式统计终端")
                },
            )
            synchronized(lock) { statisticsTerminalId = statisticsId }
            Log.d(TAG, "[MonitorDiagnostic] statisticsTerminal session=$sessionGeneration terminalId=$statisticsId")
            terminalManager.writeInput(
                statisticsId,
                "$STATISTICS_COMMAND --command-pipe /tmp/$PIPE_DIRECTORY_NAME/${pipes.commandFifoId} " +
                    "--event-pipe /tmp/$PIPE_DIRECTORY_NAME/${pipes.eventFifoId}",
            )

            publishEmptyStatistics()
            executor.execute { pollRecordedBytes(sessionGeneration) }
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        val diagnostic = diagnosticSession
        val started = SystemClock.elapsedRealtime()
        Log.d(TAG, "[MonitorDiagnostic] stopBegin session=${diagnostic?.generation}")
        val resources = synchronized(lock) {
            stopping = true
            generation += 1
            Resources(
                captureTerminalId = captureTerminalId,
                statisticsTerminalId = statisticsTerminalId,
                eventInput = eventInput,
                commandWriter = commandWriter,
                pipeFiles = pipeFiles,
            ).also {
                captureTerminalId = null
                statisticsTerminalId = null
                eventInput = null
                commandWriter = null
                pipeFiles = null
                captureFile = null
                exportDirectory = null
                recordedBytes = 0L
                statisticsPublishScheduled = false
                capturing = false
                clearing = false
                activeMirror = CaptureMirror()
                preparedMirror = CaptureMirror()
                captureParts = emptyList()
                nonHandshakeBytes = 0L
                clearProgress = null
            }
        }

        runCatching { resources.eventInput?.close() }
        runCatching { resources.commandWriter?.close() }
        resources.statisticsTerminalId?.let { terminalId ->
            terminalManager.stopTerminal(terminalId, reason = "退出监听模式，停止统计")
        }
        resources.captureTerminalId?.let { terminalId ->
            terminalManager.stopTerminal(terminalId, reason = "退出监听模式，停止录制")
        }
        resources.pipeFiles?.directory?.let { directory ->
            runCatching { directory.deleteRecursively() }
        }
        synchronized(lock) { stopping = false }
        diagnostic?.active = false
        Log.d(TAG, "[MonitorDiagnostic] stopEnd session=${diagnostic?.generation} elapsedMs=${SystemClock.elapsedRealtime() - started}")
    }

    fun close() {
        stop()
        executor.shutdownNow()
    }

    fun isCapturing(): Boolean = synchronized(lock) { capturing }
    fun isClearing(): Boolean = synchronized(lock) { clearing }
    fun captureClearProgress(): MonitorCaptureClearProgress? = synchronized(lock) { clearProgress }

    fun setCapture(enabled: Boolean) {
        val previous = synchronized(lock) {
            check(captureFile != null && !clearing)
            capturing.also { if (enabled) capturing = true }
        }
        try {
            sendCommand(JSONObject().put("type", "capture").put("enabled", enabled))
        } catch (error: Throwable) {
            synchronized(lock) { capturing = previous }
            throw error
        }
    }

    fun clearCapture(handshakesOnly: Boolean) {
        synchronized(lock) {
            check(captureFile != null && !clearing) { "抓取数据正在清理" }
            clearing = true
            clearProgress = MonitorCaptureClearProgress(handshakesOnly, isRunning = true)
        }
        publishEmptyStatistics()
        try {
            sendCommand(JSONObject().put("type", "clear").put("handshakesOnly", handshakesOnly))
        } catch (error: Throwable) {
            synchronized(lock) {
                clearing = false
                clearProgress = clearProgress?.copy(isRunning = false)
            }
            publishEmptyStatistics()
            throw error
        }
    }

    private fun sendCommand(command: JSONObject) {
        val started = SystemClock.elapsedRealtime()
        val type = command.optString("type")
        val session = diagnosticSession?.generation
        Log.d(TAG, "[MonitorDiagnostic] commandSendBegin session=$session type=$type " +
            "enabled=${command.opt("enabled")} handshakesOnly=${command.opt("handshakesOnly")} " +
            "requestId=${command.opt("requestId")}")
        val writer = synchronized(lock) { commandWriter ?: error("监听命令通道尚未连接") }
        try {
            synchronized(writer) { writer.write(command.toString() + "\n"); writer.flush() }
            Log.d(TAG, "[MonitorDiagnostic] commandSendEnd session=$session type=$type elapsedMs=${SystemClock.elapsedRealtime() - started}")
        } catch (error: Throwable) {
            Log.w(TAG, "[MonitorDiagnostic] commandSendFailed session=$session type=$type " +
                "elapsedMs=${SystemClock.elapsedRealtime() - started} errorType=${error.javaClass.name}")
            throw error
        }
    }

    fun exportPcap(
        requestId: String,
        mode: String,
        bssid: String,
        deviceMac: String,
        subtypeIds: Array<String>,
    ) {
        val command = synchronized(lock) {
            check(!stopping && captureFile != null) { "监听模式尚未运行" }
            val writer = commandWriter ?: error("监听模式命令通道尚未建立")
            val directory = exportDirectory ?: error("监听模式导出目录尚未建立")
            writer to JSONObject()
                .put("type", "export")
                .put("requestId", requestId)
                .put("mode", mode)
                .put("outputDirectory", CONTAINER_EXPORT_DIRECTORY)
                .put("bssid", bssid)
                .put("deviceMac", deviceMac)
                .put("subtypeIds", org.json.JSONArray(subtypeIds))
                .also { require(directory.isDirectory || directory.mkdirs()) }
        }
        synchronized(command.first) {
            command.first.write(command.second.toString())
            command.first.write("\n")
            command.first.flush()
        }
    }

    fun exportHandshakePcap(
        requestId: String,
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ) {
        val export = synchronized(lock) {
            check(!stopping && captureFile != null) { "监听模式尚未运行" }
            val stored = handshakeArtifacts[HandshakeKey(bssid, deviceMac, handshakeId)]
                ?: error("找不到指定的握手记录")
            val directory = exportDirectory ?: error("监听模式导出目录尚未建立")
            val pcapBytes = stored.pcap.toByteArray()
            require(pcapBytes.size >= PCAP_GLOBAL_HEADER_BYTES) { "握手记录尚无可导出的 PCAP 数据" }
            StoredPcapExport(generation, directory, pcapBytes)
        }
        exportStoredPcap(requestId, export, "导出握手包 PCAP")
    }

    fun exportDisconnectionPcap(
        requestId: String,
        bssid: String,
        deviceMac: String,
        disconnectionId: String,
    ) {
        val export = synchronized(lock) {
            check(!stopping && captureFile != null) { "监听模式尚未运行" }
            val stored = disconnectionArtifacts[
                DisconnectionKey(bssid, deviceMac, disconnectionId)
            ] ?: error("找不到指定的断开记录")
            val directory = exportDirectory ?: error("监听模式导出目录尚未建立")
            val pcapBytes = stored.pcap.toByteArray()
            require(pcapBytes.size >= PCAP_GLOBAL_HEADER_BYTES) {
                "断开记录尚无可导出的 PCAP 数据"
            }
            StoredPcapExport(generation, directory, pcapBytes)
        }
        exportStoredPcap(requestId, export, "导出断开事件 PCAP")
    }

    private fun exportStoredPcap(
        requestId: String,
        export: StoredPcapExport,
        operation: String,
    ) {
        executor.execute {
            var partial: File? = null
            try {
                check(isCurrentSession(export.sessionGeneration)) { "监听模式会话已结束" }
                require(export.directory.isDirectory || export.directory.mkdirs()) {
                    "无法创建监听模式导出目录"
                }
                val target = uniquePcapFile(export.directory)
                val partialFile = File(target.parentFile, target.name + ".part")
                partial = partialFile
                FileOutputStream(partialFile).use { output ->
                    output.write(export.pcapBytes)
                    output.fd.sync()
                }
                check(isCurrentSession(export.sessionGeneration)) { "监听模式会话已结束" }
                if (!partialFile.renameTo(target)) {
                    throw IOException("无法完成 PCAP 导出")
                }
                onExportCompleted(requestId, target.absolutePath, target.name)
            } catch (error: Throwable) {
                runCatching { partial?.delete() }
                if (isCurrentSession(export.sessionGeneration)) {
                    reportError(operation, error)
                }
            }
        }
    }

    fun releaseExport(path: String) {
        val target = File(path).canonicalFile
        val directory = synchronized(lock) { exportDirectory?.canonicalFile } ?: return
        if (target.parentFile != directory || target.extension != "pcap") {
            throw SecurityException("拒绝删除监听模式导出目录以外的文件")
        }
        if (target.exists() && !target.delete()) {
            throw IOException("无法删除监听模式临时导出文件: ${target.absolutePath}")
        }
    }

    private fun preparePipes(rootfs: File): PipeFiles {
        val tmp = File(rootfs, "tmp")
        require(tmp.isDirectory || tmp.mkdirs()) {
            "无法创建 rootfs/tmp: ${tmp.absolutePath}"
        }
        val directory = File(tmp, PIPE_DIRECTORY_NAME)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("无法清理监听模式通信目录: ${directory.absolutePath}")
        }
        if (!directory.mkdirs()) {
            throw IOException("无法创建监听模式通信目录: ${directory.absolutePath}")
        }
        Os.chmod(directory.absolutePath, 457)
        val exports = File(directory, EXPORT_DIRECTORY_NAME)
        if (!exports.mkdirs()) {
            throw IOException("无法创建监听模式导出目录: ${exports.absolutePath}")
        }
        Os.chmod(exports.absolutePath, 493)
        val pipes = PipeFiles(
            directory = directory,
            commandFifoId = UUID.randomUUID().toString(),
            eventFifoId = UUID.randomUUID().toString(),
            exportDirectory = exports,
        )
        synchronized(lock) { pipeFiles = pipes }
        Os.mkfifo(pipes.commandPipe.absolutePath, 384)
        Os.mkfifo(pipes.eventPipe.absolutePath, 384)
        return pipes
    }

    private fun readStatistics(input: FileInputStream, sessionGeneration: Long) {
        val diagnostic = diagnosticSession?.takeIf { it.generation == sessionGeneration }
        val counts = linkedMapOf<String, Long>()
        var countsAt = 0L
        Log.d(TAG, "[MonitorDiagnostic] fifoReaderBegin session=$sessionGeneration")
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    val started = SystemClock.elapsedRealtime()
                    diagnostic?.apply {
                        phase = "decodeEvent"
                        phaseSince = started
                        lastReceivedAt = started
                        eventCount++
                        characterCount += line.length
                    }
                    val event = JSONObject(line)
                    val type = event.optString("type")
                    val source = event.optString("source", "control")
                    counts["$source:$type"] = (counts["$source:$type"] ?: 0L) + 1L
                    diagnostic?.apply { eventType = type; this.source = source; phase = "handleEvent" }
                    val immediate = when (type) {
                        "captureState", "captureFiles", "captureReset", "commandFailed", "exportFailed" -> true
                        "clearProgress" -> event.optString("stage") != "preparing"
                        else -> false
                    }
                    if (immediate) Log.d(TAG, "[MonitorDiagnostic] eventBegin session=$sessionGeneration type=$type " +
                        "source=$source characters=${line.length} capturing=${event.opt("capturing")} " +
                        "retainPrepared=${event.opt("retainPrepared")} stage=${event.opt("stage")}")
                    handleEvent(event, sessionGeneration)
                    val finished = SystemClock.elapsedRealtime()
                    diagnostic?.apply { lastHandledAt = finished; phase = "waitingEvent"; phaseSince = finished }
                    if (finished - countsAt >= 1000L) {
                        diagnostic?.eventCounts = counts.toString()
                        countsAt = finished
                    }
                    if (immediate || finished - started >= 1000L) Log.d(TAG,
                        "[MonitorDiagnostic] eventEnd session=$sessionGeneration type=$type source=$source elapsedMs=${finished - started}")
                }
            }
            if (synchronized(lock) { generation == sessionGeneration && !stopping }) {
                throw IOException("监听模式统计脚本已关闭 FIFO")
            }
        } catch (error: Throwable) {
            diagnostic?.apply { phase = "readerFailed"; phaseSince = SystemClock.elapsedRealtime() }
            Log.w(TAG, "[MonitorDiagnostic] fifoReaderFailed session=$sessionGeneration " +
                "lastType=${diagnostic?.eventType} lastSource=${diagnostic?.source} errorType=${error.javaClass.name}")
            if (synchronized(lock) { generation == sessionGeneration && !stopping }) {
                synchronized(lock) {
                    clearing = false
                    clearProgress = clearProgress?.copy(isRunning = false)
                }
                publishEmptyStatistics()
                reportError("读取监听模式统计数据", error)
            }
        }
        Log.d(TAG, "[MonitorDiagnostic] fifoReaderEnd session=$sessionGeneration events=${diagnostic?.eventCount}")
    }

    private fun diagnosticHeartbeat(diagnostic: DiagnosticSession) {
        while (diagnostic.active) {
            try { Thread.sleep(1000L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!diagnostic.active) return
            val now = SystemClock.elapsedRealtime()
            Log.d(TAG, "[MonitorDiagnostic] heartbeat session=${diagnostic.generation} phase=${diagnostic.phase} " +
                "phaseAgeMs=${now - diagnostic.phaseSince} lastType=${diagnostic.eventType} source=${diagnostic.source} " +
                "events=${diagnostic.eventCount} characters=${diagnostic.characterCount} counts=${diagnostic.eventCounts} " +
                "receiveAgeMs=${if (diagnostic.lastReceivedAt == 0L) -1L else now - diagnostic.lastReceivedAt} " +
                "handledAgeMs=${if (diagnostic.lastHandledAt == 0L) -1L else now - diagnostic.lastHandledAt} " +
                "lastSignalUnixMillis=${diagnostic.lastSignalUnixMillis} state=${diagnostic.state} " +
                "stateAgeMs=${if (diagnostic.stateAt == 0L) -1L else now - diagnostic.stateAt} " +
                "publishedHeaders=${diagnostic.publishedHeaders.get()} publishAgeMs=${if (diagnostic.lastPublishedAt == 0L) -1L else now - diagnostic.lastPublishedAt} " +
                "pages=${diagnostic.pageCount} lastPage=${diagnostic.lastPage}")
        }
    }

    private fun diagnosticStateLocked() {
        diagnosticSession?.stateAt = SystemClock.elapsedRealtime()
        diagnosticSession?.state = "epoch=$mirrorGeneration revision=${changes.revision} capturing=$capturing " +
            "clearing=$clearing stopping=$stopping clearStage=${clearProgress?.stage} " +
            "clearProcessedBytes=${clearProgress?.processedBytes} clearTotalBytes=${clearProgress?.totalBytes} recordedBytes=$recordedBytes " +
            "nonHandshakeBytes=$nonHandshakeBytes parts=${captureParts.size} aps=${activeMirror.accessPoints.size} " +
            "preparedRevision=${preparedMirror.changes.revision} preparedAps=${preparedMirror.accessPoints.size} " +
            "activeHandshakes=${activeHandshakes.size} publishScheduled=$statisticsPublishScheduled"
    }

    private fun handleEvent(event: JSONObject, sessionGeneration: Long) {
        val mirror = synchronized(lock) {
            if (generation != sessionGeneration || stopping) return
            if (event.optString("source") == "retained") preparedMirror else activeMirror
        }
        when (event.optString("type")) {
            "captureState" -> {
                synchronized(lock) {
                    if (generation != sessionGeneration || stopping) return
                    capturing = event.getBoolean("capturing")
                    clearing = false
                    clearProgress = clearProgress?.copy(isRunning = false)
                }
                publishEmptyStatistics()
            }
            "captureFiles" -> {
                synchronized(lock) {
                    if (generation != sessionGeneration || stopping) return
                    val paths = event.getJSONArray("paths")
                    captureParts = List(paths.length()) { capturePartLocked(paths.getString(it)) }
                }
            }
            "captureReset" -> {
                synchronized(lock) {
                    if (generation != sessionGeneration || stopping) return
                    if (event.getBoolean("retainPrepared")) {
                        activeMirror = preparedMirror.fork()
                        if (!event.isNull("addedSegment")) {
                            captureParts = captureParts + capturePartLocked(event.getString("addedSegment"))
                        }
                    } else {
                        activeMirror = CaptureMirror()
                        preparedMirror = CaptureMirror()
                        captureParts = listOf(requireNotNull(captureFile))
                    }
                    // 切换预备镜像；App 按新世代通过原有分页接口同步。
                    mirrorGeneration++
                    nonHandshakeBytes = 0L
                    recordedBytes = recordedSizeLocked()
                }
                publishEmptyStatistics()
            }
            "captureSizes" -> {
                val changed = synchronized(lock) {
                    if (generation != sessionGeneration || stopping) return
                    val bytes = event.getLong("nonHandshakeBytes")
                    require(bytes >= 0L) { "可清理大小不能为负数" }
                    val total = if (clearing) recordedBytes else recordedSizeLocked()
                    (nonHandshakeBytes != bytes || recordedBytes != total).also {
                        nonHandshakeBytes = bytes
                        recordedBytes = total
                    }
                }
                if (changed) scheduleStatisticsPublish(sessionGeneration)
            }
            "clearProgress" -> {
                synchronized(lock) {
                    if (generation != sessionGeneration || stopping) return
                    clearProgress = clearProgress?.copy(
                        stage = when (event.getString("stage")) {
                            "preparing" -> MonitorCaptureClearStage.PREPARING
                            "switching" -> MonitorCaptureClearStage.SWITCHING
                            "finished" -> MonitorCaptureClearStage.FINISHED
                            else -> throw IOException("未知清理阶段: $event")
                        },
                        processedBytes = event.getLong("processedBytes").coerceAtLeast(0L),
                        totalBytes = event.getLong("totalBytes").coerceAtLeast(0L),
                    )
                }
                publishEmptyStatistics()
            }
            "statistics" -> handleStatistics(event, sessionGeneration, mirror)
            "exportCompleted" -> handleExportCompleted(event, sessionGeneration)
            "handshakeData" -> handleHandshakeData(event, sessionGeneration, mirror)
            "handshakeValidation" -> handleHandshakeValidation(event, sessionGeneration, mirror)
            "handshakeFinished" -> handleHandshakeFinished(event, sessionGeneration, mirror)
            "disconnectionData" -> handleDisconnectionData(event, sessionGeneration, mirror)
            "exportFailed" -> {
                if (isCurrentSession(sessionGeneration)) {
                    reportError(
                        "导出监听模式 PCAP",
                        IOException(event.optString("message", "Python 导出失败")),
                    )
                }
            }
            "commandFailed" -> {
                if (isCurrentSession(sessionGeneration)) {
                    reportError(
                        "处理监听模式命令",
                        IOException(event.optString("message", "Python 命令处理失败")),
                    )
                }
            }
            else -> throw IOException("监听模式统计脚本返回未知事件: $event")
        }
    }

    private fun handleStatistics(event: JSONObject, sessionGeneration: Long, mirror: CaptureMirror) {
        val changes = mirror.changes
        val accessPoints = mirror.accessPoints
        val active = synchronized(lock) {
            captureFile.takeIf { generation == sessionGeneration && !stopping }
        } != null
        if (!active) return
        val accessPointUpdates = event.optJSONArray("accessPointUpdates")
        if (accessPointUpdates != null && accessPointUpdates.length() > 0) {
            synchronized(lock) {
                if (generation != sessionGeneration || stopping) return
                for (index in 0 until accessPointUpdates.length()) {
                    val item = accessPointUpdates.getJSONObject(index)
                    val bssid = item.getString("bssid")
                    val accessPoint = accessPoints.getOrPut(bssid) {
                        MutableAccessPoint(bssid = bssid)
                    }
                    if (!item.isNull("ssid")) {
                        accessPoint.ssid = item.getString("ssid")
                    }
                    accessPoint.ssidVisibility = when (item.optString("ssidVisibility")) {
                        "hidden" -> MonitorSsidVisibility.HIDDEN
                        "visible" -> MonitorSsidVisibility.VISIBLE
                        else -> MonitorSsidVisibility.UNKNOWN
                    }
                    accessPoint.securityProtocols = buildList {
                        val protocols = item.getJSONArray("securityProtocols")
                        for (protocolIndex in 0 until protocols.length()) {
                            add(
                                when (protocols.getString(protocolIndex)) {
                                    "wpa" -> MonitorSecurityProtocol.WPA
                                    "wpa2" -> MonitorSecurityProtocol.WPA2
                                    else -> throw IOException(
                                        "未知监听模式安全协议: ${protocols.getString(protocolIndex)}",
                                    )
                                },
                            )
                        }
                    }
                    accessPoint.signal = parseSignal(item.optJSONObject("signal"))
                    changes.put("ap:$bssid", MonitorChange.AccessPoint(accessPoint.toMetadata()))
                    val devices = item.getJSONArray("devices")
                    for (deviceIndex in 0 until devices.length()) {
                        val deviceJson = devices.getJSONObject(deviceIndex)
                        val mac = deviceJson.getString("mac")
                        accessPoint.devices[mac] = MonitorDevice(
                            mac = mac,
                            name = if (deviceJson.isNull("name")) {
                                null
                            } else {
                                deviceJson.getString("name").takeIf(String::isNotBlank)
                            },
                            frameGroups = parseFrameGroups(deviceJson),
                            handshakes = emptyList(),
                            realtime = MonitorDeviceRealtime(
                                uploadBytesPerSecond = deviceJson.optLong(
                                    "uploadBytesPerSecond",
                                    0L,
                                ),
                                downloadBytesPerSecond = deviceJson.optLong(
                                    "downloadBytesPerSecond",
                                    0L,
                                ),
                                signal = parseSignal(deviceJson.optJSONObject("signal")),
                            ),
                            probeOnly = deviceJson.optBoolean("probeOnly", false),
                        )
                        changes.put("device:$bssid:$mac", MonitorChange.Device(bssid, accessPoint.devices.getValue(mac)))
                    }
                }
            }
            if (synchronized(lock) { mirror === activeMirror }) scheduleStatisticsPublish(sessionGeneration)
        }
    }

    private fun parseFrameGroups(deviceJson: JSONObject): List<MonitorFrameGroupStatistics> {
        val groups = deviceJson.getJSONArray("frameGroups")
        return buildList(groups.length()) {
            for (groupIndex in 0 until groups.length()) {
                val group = groups.getJSONObject(groupIndex)
                val subtypes = group.getJSONArray("subtypes")
                add(
                    MonitorFrameGroupStatistics(
                        id = group.getString("id"),
                        displayName = group.getString("displayName"),
                        packetCount = group.getLong("packetCount"),
                        byteCount = group.getLong("byteCount"),
                        subtypes = buildList(subtypes.length()) {
                            for (subtypeIndex in 0 until subtypes.length()) {
                                val subtype = subtypes.getJSONObject(subtypeIndex)
                                add(
                                    MonitorFrameSubtypeStatistics(
                                        id = subtype.getString("id"),
                                        displayName = subtype.getString("displayName"),
                                        packetCount = subtype.getLong("packetCount"),
                                        byteCount = subtype.getLong("byteCount"),
                                    ),
                                )
                            }
                        },
                    ),
                )
            }
        }
    }

    private fun parseHandshakeStep(value: String): MonitorHandshakeStep = when (value) {
        "authentication" -> MonitorHandshakeStep.AUTHENTICATION
        "association" -> MonitorHandshakeStep.ASSOCIATION
        "eapol1" -> MonitorHandshakeStep.EAPOL_MESSAGE_1
        "eapol2" -> MonitorHandshakeStep.EAPOL_MESSAGE_2
        "eapol3" -> MonitorHandshakeStep.EAPOL_MESSAGE_3
        "eapol4" -> MonitorHandshakeStep.EAPOL_MESSAGE_4
        "disconnection" -> MonitorHandshakeStep.DISCONNECTION
        else -> throw IOException("未知握手阶段: $value")
    }

    private fun parseHandshakeFailureReason(value: String): MonitorHandshakeFailureReason =
        when (value) {
            "routerRejectedConnection" ->
                MonitorHandshakeFailureReason.ROUTER_REJECTED_CONNECTION
            "m2RetryLimitExceeded" ->
                MonitorHandshakeFailureReason.M2_RETRY_LIMIT_EXCEEDED
            "disconnectedAfterM2" ->
                MonitorHandshakeFailureReason.DISCONNECTED_AFTER_M2
            "disconnectedDuringHandshake" ->
                MonitorHandshakeFailureReason.DISCONNECTED_DURING_HANDSHAKE
            "replacedByNewAttempt" ->
                MonitorHandshakeFailureReason.REPLACED_BY_NEW_ATTEMPT
            else -> throw IOException("未知握手失败原因: $value")
        }

    private fun parseHandshakeCaptureQuality(value: String): MonitorHandshakeCaptureQuality =
        when (value) {
            "complete" -> MonitorHandshakeCaptureQuality.COMPLETE
            "dataIncomplete" -> MonitorHandshakeCaptureQuality.DATA_INCOMPLETE
            "partiallyMissing" -> MonitorHandshakeCaptureQuality.PARTIALLY_MISSING
            else -> throw IOException("未知握手捕获质量: $value")
        }

    private fun handleHandshakeData(event: JSONObject, sessionGeneration: Long, mirror: CaptureMirror) {
        val activeHandshakes = mirror.activeHandshakes
        val handshakeArtifacts = mirror.handshakeArtifacts
        val handshake = event.getJSONObject("handshake")
        val key = parseHandshakeKey(handshake)
        val sequence = event.getInt("sequence")
        val part = decodeBase64(event.getString("pcapPartBase64"), "握手 PCAP 分块")
        require(part.isNotEmpty() && part.size <= MAX_HANDSHAKE_EVENT_PART_BYTES) {
            "Python 返回的握手 PCAP 分块长度无效"
        }
        synchronized(lock) {
            if (generation != sessionGeneration || stopping) return
            val stored = handshakeArtifacts.getOrPut(key) {
                require(sequence == 0) { "握手 PCAP 首个分块序号必须为 0" }
                val header = decodeBase64(
                    event.getString("pcapHeaderBase64"),
                    "握手 PCAP 文件头",
                )
                requireValidPcapHeader(header)
                MutableStoredHandshake(
                    key = key,
                    startedAtMillis = activeMirror.handshakeArtifacts[key]?.startedAtMillis ?: System.currentTimeMillis(),
                ).also { it.pcap.write(header); activeHandshakes.add(key) }
            }
            require(sequence == stored.nextSequence) {
                "握手 PCAP 分块序号不连续，预期 ${stored.nextSequence}，实际 $sequence"
            }
            require(
                stored.pcap.size() + stored.pendingPacket.size() + part.size <=
                    MAX_HANDSHAKE_PCAP_BYTES,
            ) {
                "握手 PCAP 超过服务缓存上限"
            }
            stored.pendingPacket.write(part)
            if (event.getBoolean("packetComplete")) {
                stored.pcap.write(stored.pendingPacket.toByteArray())
                stored.pendingPacket.reset()
                stored.completedPacketCount += 1
            }
            stored.nextSequence += 1
            applyHandshakeMetadata(stored, handshake)
            synchronizeHandshakeRecordsLocked(key, mirror)
        }
        if (synchronized(lock) { mirror === activeMirror }) scheduleStatisticsPublish(sessionGeneration)
    }

    private fun handleHandshakeValidation(event: JSONObject, sessionGeneration: Long, mirror: CaptureMirror) {
        val handshakeArtifacts = mirror.handshakeArtifacts
        val handshake = event.getJSONObject("handshake")
        val key = parseHandshakeKey(handshake)
        val hc22000 = event.getString("hc22000")
        require(hc22000.length <= MAX_HC22000_LENGTH && hc22000.startsWith("WPA*02*")) {
            "Python 返回的 HC22000 文本无效"
        }
        synchronized(lock) {
            if (generation != sessionGeneration || stopping) return
            val stored = handshakeArtifacts[key] ?: error("收到 HC22000 时握手数据尚未建立")
            stored.hc22000 = hc22000
            applyHandshakeMetadata(stored, handshake)
            synchronizeHandshakeRecordsLocked(key, mirror)
        }
        if (synchronized(lock) { mirror === activeMirror }) scheduleStatisticsPublish(sessionGeneration)
    }

    private fun handleHandshakeFinished(event: JSONObject, sessionGeneration: Long, mirror: CaptureMirror) {
        val activeHandshakes = mirror.activeHandshakes
        val handshakeArtifacts = mirror.handshakeArtifacts
        val handshake = event.getJSONObject("handshake")
        val key = parseHandshakeKey(handshake)
        synchronized(lock) {
            if (generation != sessionGeneration || stopping) return
            val stored = handshakeArtifacts[key] ?: error("收到结束事件时握手数据尚未建立")
            applyHandshakeMetadata(stored, handshake)
            require(stored.pendingPacket.size() == 0) {
                "握手结束时仍有未完成的 PCAP 数据包分块"
            }
            require(stored.completedPacketCount == stored.exportPacketCount) {
                "握手结束时 PCAP 包数量不一致，服务收到 ${stored.completedPacketCount} 个，" +
                    "Python 声明 ${stored.exportPacketCount} 个"
            }
            stored.status = when (handshake.getString("status")) {
                "success" -> MonitorHandshakeStatus.SUCCESS
                "failed" -> MonitorHandshakeStatus.FAILED
                "unknown" -> MonitorHandshakeStatus.UNKNOWN
                else -> throw IOException("握手结束事件包含无效状态: $handshake")
            }
            stored.finishedAtMillis = if (mirror === preparedMirror) {
                activeMirror.handshakeArtifacts[key]?.finishedAtMillis ?: System.currentTimeMillis()
            } else System.currentTimeMillis()
            activeHandshakes.remove(key)
            synchronizeHandshakeRecordsLocked(key, mirror)
        }
        if (synchronized(lock) { mirror === activeMirror }) scheduleStatisticsPublish(sessionGeneration)
    }

    private fun handleDisconnectionData(event: JSONObject, sessionGeneration: Long, mirror: CaptureMirror) {
        val changes = mirror.changes
        val disconnectionArtifacts = mirror.disconnectionArtifacts
        val disconnection = event.getJSONObject("disconnection")
        val key = DisconnectionKey(
            bssid = disconnection.getString("bssid"),
            deviceMac = disconnection.getString("deviceMac"),
            disconnectionId = disconnection.getString("id"),
        )
        val sequence = event.getInt("sequence")
        val part = decodeBase64(event.getString("pcapPartBase64"), "断开事件 PCAP 分块")
        require(part.isNotEmpty() && part.size <= MAX_HANDSHAKE_EVENT_PART_BYTES) {
            "Python 返回的断开事件 PCAP 分块长度无效"
        }
        synchronized(lock) {
            if (generation != sessionGeneration || stopping) return
            val stored = disconnectionArtifacts.getOrPut(key) {
                require(sequence == 0) { "断开事件 PCAP 首个分块序号必须为 0" }
                val header = decodeBase64(
                    event.getString("pcapHeaderBase64"),
                    "断开事件 PCAP 文件头",
                )
                requireValidPcapHeader(header)
                MutableStoredDisconnection(
                    key = key,
                    timestampUnixMillis = disconnection.getLong("timestampUnixMillis"),
                    type = when (disconnection.getString("disconnectionType")) {
                        "disassociation" -> MonitorDisconnectionType.DISASSOCIATION
                        "deauthentication" -> MonitorDisconnectionType.DEAUTHENTICATION
                        else -> throw IOException("未知断开事件类型: $disconnection")
                    },
                    reasonCode = if (disconnection.isNull("reasonCode")) {
                        null
                    } else {
                        disconnection.getInt("reasonCode")
                    },
                    exportPacketCount = disconnection.getInt("exportPacketCount"),
                ).also { it.pcap.write(header) }
            }
            require(sequence == stored.nextSequence) {
                "断开事件 PCAP 分块序号不连续，预期 ${stored.nextSequence}，实际 $sequence"
            }
            require(stored.pcap.size() + stored.pendingPacket.size() + part.size <=
                MAX_HANDSHAKE_PCAP_BYTES) {
                "断开事件 PCAP 超过服务缓存上限"
            }
            stored.pendingPacket.write(part)
            if (event.getBoolean("packetComplete")) {
                stored.pcap.write(stored.pendingPacket.toByteArray())
                stored.pendingPacket.reset()
                stored.completed = true
                changes.put("disconnection:${key.bssid}:${key.deviceMac}:${key.disconnectionId}", MonitorChange.Disconnection(stored.toRecord()))
            }
            stored.nextSequence += 1
        }
        if (synchronized(lock) { mirror === activeMirror }) scheduleStatisticsPublish(sessionGeneration)
    }

    private fun parseHandshakeKey(handshake: JSONObject): HandshakeKey = HandshakeKey(
        bssid = handshake.getString("bssid"),
        deviceMac = handshake.getString("deviceMac"),
        handshakeId = handshake.getString("id"),
    )

    private fun applyHandshakeMetadata(
        stored: MutableStoredHandshake,
        handshake: JSONObject,
    ) {
        stored.captureQuality = parseHandshakeCaptureQuality(
            handshake.getString("captureQuality"),
        )
        stored.capturedSteps = buildList {
            val steps = handshake.getJSONArray("capturedSteps")
            for (index in 0 until steps.length()) {
                add(parseHandshakeStep(steps.getString(index)))
            }
        }
        stored.failedAtStep = if (handshake.isNull("failedAtStep")) {
            null
        } else {
            parseHandshakeStep(handshake.getString("failedAtStep"))
        }
        stored.failureReason = if (handshake.isNull("failureReason")) {
            null
        } else {
            parseHandshakeFailureReason(handshake.getString("failureReason"))
        }
        stored.m2AttemptCount = handshake.getInt("m2AttemptCount")
        stored.exportPacketCount = handshake.getInt("exportPacketCount")
    }

    private fun synchronizeHandshakeRecordsLocked(key: HandshakeKey, mirror: CaptureMirror = activeMirror) {
        val stored = mirror.handshakeArtifacts[key] ?: return
        mirror.changes.put("handshake:${key.bssid}:${key.deviceMac}:${key.handshakeId}",
            MonitorChange.Handshake(key.bssid, key.deviceMac, stored.toRecord(System.currentTimeMillis())))
    }

    private fun MutableAccessPoint.toMetadata() = MonitorAccessPoint(
        bssid = bssid, ssid = ssid, ssidVisibility = ssidVisibility,
        securityProtocols = securityProtocols, signal = signal, devices = emptyList(),
    )

    private fun statisticsSnapshotLocked(): MonitorModeStatistics {
        val channel = captureChannel()
        return MonitorModeStatistics(
            sessionGeneration = mirrorGeneration, revision = changes.revision,
            recordedBytes = recordedBytes, channel = channel?.channel ?: 0,
            frequencyMhz = channel?.frequencyMhz ?: 0, nonHandshakeBytes = nonHandshakeBytes,
        )
    }

    fun changesPage(sessionGeneration: Long, afterRevision: Long): MonitorChangesPage = synchronized(lock) {
        require(afterRevision >= 0L) { "监听同步版本不能为负数" }
        val started = SystemClock.elapsedRealtime()
        val page = changes.page(statisticsSnapshotLocked(), if (sessionGeneration == mirrorGeneration) afterRevision else 0L)
        diagnosticSession?.let { diagnostic ->
            diagnostic.pageCount++
            diagnostic.lastPage = "requestedEpoch=$sessionGeneration after=$afterRevision epoch=${page.header.sessionGeneration} " +
                "revision=${page.header.revision} next=${page.nextRevision} count=${page.changes.size} elapsedMs=${SystemClock.elapsedRealtime() - started}"
            if (started - diagnostic.lastPageAt >= 1000L || sessionGeneration != mirrorGeneration) {
                diagnostic.lastPageAt = started
                Log.d(TAG, "[MonitorDiagnostic] changesPage session=${diagnostic.generation} ${diagnostic.lastPage}")
            }
        }
        page
    }

    private fun decodeBase64(value: String, name: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (error: IllegalArgumentException) {
        throw IOException("$name Base64 无效", error)
    }

    private fun requireValidPcapHeader(header: ByteArray) {
        require(header.size == PCAP_GLOBAL_HEADER_BYTES) { "握手 PCAP 文件头长度无效" }
        val magic = header.copyOfRange(0, 4)
        require(PCAP_MAGIC_VALUES.any { magic.contentEquals(it) }) {
            "握手 PCAP 文件头格式无效"
        }
    }

    private fun handleExportCompleted(event: JSONObject, sessionGeneration: Long) {
        val directory = synchronized(lock) {
            exportDirectory?.takeIf { generation == sessionGeneration && !stopping }
        } ?: return
        val containerPath = event.getString("path")
        val fileName = event.getString("fileName")
        if (File(containerPath).name != fileName || !fileName.endsWith(".pcap")) {
            throw IOException("Python 返回了无效的 PCAP 文件名: $event")
        }
        val target = File(directory, fileName).canonicalFile
        if (target.parentFile != directory.canonicalFile || !target.isFile) {
            throw IOException("Python 返回的 PCAP 路径不在导出目录内: $event")
        }
        onExportCompleted(event.getString("requestId"), target.absolutePath, fileName)
    }

    private fun uniquePcapFile(directory: File): File {
        var timestamp = System.currentTimeMillis()
        while (true) {
            val target = File(directory, "$timestamp.pcap")
            if (!target.exists() && !File(directory, "$timestamp.pcap.part").exists()) {
                return target
            }
            timestamp += 1L
        }
    }

    private fun parseSignal(value: JSONObject?): MonitorSignalStatistics? {
        value ?: return null
        return MonitorSignalStatistics(
            latestDbm = value.getInt("latestDbm"),
            averageDbm = value.getDouble("averageDbm").toFloat(),
            minimumDbm = value.getInt("minimumDbm"),
            maximumDbm = value.getInt("maximumDbm"),
            sampleCount = value.getInt("sampleCount"),
            lastSeenUnixMillis = value.getLong("lastSeenUnixMillis"),
        ).also { signal ->
            diagnosticSession?.let { it.lastSignalUnixMillis = maxOf(it.lastSignalUnixMillis, signal.lastSeenUnixMillis) }
        }
    }

    /** 只接收主文件和本会话 capture 目录内的 PCAP 段。 */
    private fun capturePartLocked(path: String): File {
        val main = requireNotNull(captureFile).canonicalFile
        val rootfs = requireNotNull(main.parentFile?.parentFile)
        val part = File(rootfs, path.removePrefix("/")).canonicalFile
        val directory = File(main.parentFile, "$PIPE_DIRECTORY_NAME/capture").canonicalFile
        require(part == main || (part.parentFile == directory && part.extension == "pcap")) {
            "录制分段路径超出本会话范围"
        }
        return part
    }

    private fun recordedSizeLocked(): Long {
        var total = 0L
        var headers = 0
        for (file in captureParts) {
            val size = file.length().coerceAtLeast(0L)
            if (size >= PCAP_GLOBAL_HEADER_BYTES) {
                total += size
                headers++
            }
        }
        return total - (headers - 1).coerceAtLeast(0) * PCAP_GLOBAL_HEADER_BYTES.toLong()
    }

    private fun pollRecordedBytes(sessionGeneration: Long) {
        var diagnosticAt = 0L
        while (isCurrentSession(sessionGeneration)) {
            val update = synchronized(lock) {
                if (generation != sessionGeneration || stopping) return
                // 分段导出的逻辑大小只含一个全局文件头；清理切换时由事件原子更新。
                val nextBytes = recordedSizeLocked()
                if (clearing || recordedBytes == nextBytes) null
                else {
                    recordedBytes = nextBytes
                    mirrorGeneration to nextBytes
                }
            }
            update?.let { (epoch, bytes) -> onRecordedBytesChanged(epoch, bytes) }
            val hasActive = synchronized(lock) { activeHandshakes.isNotEmpty() }
            if (hasActive) scheduleStatisticsPublish(sessionGeneration)
            val now = SystemClock.elapsedRealtime()
            if (now - diagnosticAt >= 1000L) {
                synchronized(lock) { diagnosticStateLocked() }
                diagnosticAt = now
            }
            try {
                Thread.sleep(RECORDED_BYTES_POLL_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun scheduleStatisticsPublish(sessionGeneration: Long) {
        val shouldSchedule = synchronized(lock) {
            if (generation != sessionGeneration || stopping || statisticsPublishScheduled) {
                false
            } else {
                statisticsPublishScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        executor.execute {
            try {
                Thread.sleep(STATISTICS_PUBLISH_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            val snapshot = synchronized(lock) {
                if (generation != sessionGeneration || stopping) return@synchronized null
                statisticsPublishScheduled = false
                activeHandshakes.forEach { synchronizeHandshakeRecordsLocked(it) }
                diagnosticStateLocked()
                statisticsSnapshotLocked()
            }
            snapshot?.let {
                diagnosticSession?.apply { publishedHeaders.incrementAndGet(); lastPublishedAt = SystemClock.elapsedRealtime() }
                onStatisticsChanged(it)
            }
        }
    }

    fun statisticsHeader(): MonitorModeStatistics = synchronized(lock) { statisticsSnapshotLocked() }

    fun isCurrentSnapshot(sessionGeneration: Long): Boolean = synchronized(lock) { mirrorGeneration == sessionGeneration && !stopping }

    fun isCurrentSession(sessionGeneration: Long): Boolean = synchronized(lock) {
        generation == sessionGeneration && !stopping
    }

    private fun publishEmptyStatistics() {
        val statistics = synchronized(lock) {
            diagnosticStateLocked()
            statisticsSnapshotLocked()
        }
        diagnosticSession?.apply { publishedHeaders.incrementAndGet(); lastPublishedAt = SystemClock.elapsedRealtime() }
        onStatisticsChanged(statistics)
    }

    private fun handleTerminalExit(
        terminalId: Long,
        exitCode: Int,
        terminalName: String,
    ) {
        val unexpected = synchronized(lock) {
            !stopping &&
                (captureTerminalId == terminalId || statisticsTerminalId == terminalId)
        }
        if (unexpected) {
            synchronized(lock) {
                capturing = false
                clearing = false
                clearProgress = clearProgress?.copy(isRunning = false)
            }
            publishEmptyStatistics()
            reportError(
                "${terminalName}意外退出",
                IllegalStateException("终端 $terminalId 已退出，退出码 $exitCode"),
            )
        }
    }

    private fun reportError(operation: String, error: Throwable) {
        Log.e(TAG, "$operation 失败：${error.message}", error)
        onError(operation, error)
    }

    private data class Resources(
        val captureTerminalId: Long?,
        val statisticsTerminalId: Long?,
        val eventInput: FileInputStream?,
        val commandWriter: OutputStreamWriter?,
        val pipeFiles: PipeFiles?,
    )

    private data class PipeFiles(
        val directory: File,
        val commandFifoId: String,
        val eventFifoId: String,
        val exportDirectory: File,
    ) {
        val commandPipe: File get() = File(directory, commandFifoId)
        val eventPipe: File get() = File(directory, eventFifoId)
    }

    private data class HandshakeKey(
        val bssid: String,
        val deviceMac: String,
        val handshakeId: String,
    )

    private data class DisconnectionKey(
        val bssid: String,
        val deviceMac: String,
        val disconnectionId: String,
    )

    /** 清理切换只复制索引和可变元数据，握手 PCAP 的不可变块共享。 */
    private class SharedPcapBuffer private constructor(
        private var tail: Part?,
        private var byteCount: Int,
    ) {
        private class Part(val previous: Part?, val bytes: ByteArray)
        constructor() : this(null, 0)
        fun write(bytes: ByteArray) {
            tail = Part(tail, bytes)
            byteCount += bytes.size
        }
        fun size(): Int = byteCount
        fun fork(): SharedPcapBuffer = SharedPcapBuffer(tail, byteCount)
        fun toByteArray(): ByteArray {
            val result = ByteArray(byteCount)
            var offset = byteCount
            var part = tail
            while (part != null) {
                offset -= part.bytes.size
                part.bytes.copyInto(result, offset)
                part = part.previous
            }
            return result
        }
    }

    private class CaptureMirror(
        val changes: MonitorChangeStore = MonitorChangeStore(),
    ) {
        val activeHandshakes = linkedSetOf<HandshakeKey>()
        val accessPoints = linkedMapOf<String, MutableAccessPoint>()
        val handshakeArtifacts = linkedMapOf<HandshakeKey, MutableStoredHandshake>()
        val disconnectionArtifacts = linkedMapOf<DisconnectionKey, MutableStoredDisconnection>()

        fun fork(): CaptureMirror = CaptureMirror(changes.fork()).also { copy ->
            copy.activeHandshakes.addAll(activeHandshakes)
            accessPoints.forEach { (key, value) ->
                copy.accessPoints[key] = value.copy(devices = LinkedHashMap(value.devices))
            }
            handshakeArtifacts.forEach { (key, value) ->
                copy.handshakeArtifacts[key] = value.copy(
                    pcap = value.pcap.fork(),
                    pendingPacket = ByteArrayOutputStream().also { value.pendingPacket.writeTo(it) },
                )
            }
            disconnectionArtifacts.forEach { (key, value) ->
                copy.disconnectionArtifacts[key] = value.copy(
                    pcap = value.pcap.fork(),
                    pendingPacket = ByteArrayOutputStream().also { value.pendingPacket.writeTo(it) },
                )
            }
        }
    }

    private data class MutableStoredHandshake(
        val key: HandshakeKey,
        val startedAtMillis: Long,
        var finishedAtMillis: Long? = null,
        var status: MonitorHandshakeStatus = MonitorHandshakeStatus.IN_PROGRESS,
        var captureQuality: MonitorHandshakeCaptureQuality =
            MonitorHandshakeCaptureQuality.COMPLETE,
        var capturedSteps: List<MonitorHandshakeStep> = emptyList(),
        var failedAtStep: MonitorHandshakeStep? = null,
        var failureReason: MonitorHandshakeFailureReason? = null,
        var m2AttemptCount: Int = 0,
        var exportPacketCount: Int = 0,
        var hc22000: String? = null,
        var nextSequence: Int = 0,
        var completedPacketCount: Int = 0,
        val pcap: SharedPcapBuffer = SharedPcapBuffer(),
        val pendingPacket: ByteArrayOutputStream = ByteArrayOutputStream(),
    ) {
        fun toRecord(nowMillis: Long): MonitorHandshakeRecord = MonitorHandshakeRecord(
            id = key.handshakeId,
            startUnixMillis = startedAtMillis,
            durationMillis = (finishedAtMillis ?: nowMillis).minus(startedAtMillis).coerceAtLeast(0L),
            status = status,
            canValidate = hc22000 != null,
            captureQuality = captureQuality,
            capturedSteps = capturedSteps,
            failedAtStep = failedAtStep,
            failureReason = failureReason,
            m2AttemptCount = m2AttemptCount,
            exportPacketCount = exportPacketCount,
            hc22000 = hc22000,
        )
    }

    private data class MutableStoredDisconnection(
        val key: DisconnectionKey,
        val timestampUnixMillis: Long,
        val type: MonitorDisconnectionType,
        val reasonCode: Int?,
        val exportPacketCount: Int,
        var nextSequence: Int = 0,
        var completed: Boolean = false,
        val pcap: SharedPcapBuffer = SharedPcapBuffer(),
        val pendingPacket: ByteArrayOutputStream = ByteArrayOutputStream(),
    ) {
        fun toRecord(): MonitorDisconnectionRecord = MonitorDisconnectionRecord(
            id = key.disconnectionId,
            timestampUnixMillis = timestampUnixMillis,
            bssid = key.bssid,
            deviceMac = key.deviceMac,
            type = type,
            reasonCode = reasonCode,
            exportPacketCount = exportPacketCount,
        )
    }

    private data class StoredPcapExport(
        val sessionGeneration: Long,
        val directory: File,
        val pcapBytes: ByteArray,
    )

    private data class MutableAccessPoint(
        val bssid: String,
        var ssid: String? = null,
        var ssidVisibility: MonitorSsidVisibility = MonitorSsidVisibility.UNKNOWN,
        var securityProtocols: List<MonitorSecurityProtocol> = emptyList(),
        var signal: MonitorSignalStatistics? = null,
        val devices: MutableMap<String, MonitorDevice> = linkedMapOf(),
    )

    private companion object {
        const val TAG = "MonitorModeController"
        const val CAPTURE_FILE_RELATIVE_PATH = "tmp/wlanlogs.pcap"
        const val PIPE_DIRECTORY_NAME = "wlantool-monitor"
        const val EXPORT_DIRECTORY_NAME = "exports"
        const val CONTAINER_EXPORT_DIRECTORY = "/tmp/wlantool-monitor/exports"
        const val PCAP_GLOBAL_HEADER_BYTES = 24
        const val MAX_HANDSHAKE_PCAP_BYTES = 64 * 1024 * 1024
        const val MAX_HANDSHAKE_EVENT_PART_BYTES = 24 * 1024
        const val MAX_HC22000_LENGTH = 256 * 1024
        const val RECORDED_BYTES_POLL_INTERVAL_MILLIS = 50L
        const val STATISTICS_PUBLISH_INTERVAL_MILLIS = 50L
        val PCAP_MAGIC_VALUES = arrayOf(
            byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte()),
            byteArrayOf(0x4d, 0x3c, 0xb2.toByte(), 0xa1.toByte()),
            byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte()),
            byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0x3c, 0x4d),
        )
        const val CAPTURE_COMMAND =
            "exec tcpdump -U -i wlan0 -e -w /tmp/wlanlogs.pcap"
        const val STATISTICS_COMMAND =
            "exec /usr/bin/python3 -u /wlantool/monitor_stats.py " +
                "--pcap /tmp/wlanlogs.pcap"
    }
}
