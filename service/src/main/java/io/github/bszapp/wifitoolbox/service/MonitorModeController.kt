package io.github.bszapp.wifitoolbox.service

import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
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
import java.util.concurrent.Executors
import org.json.JSONObject

/** Service 侧拥有监听模式的录制终端、统计终端、FIFO 和 pcap 文件状态。 */
internal class MonitorModeController(
    private val terminalManager: TerminalManager,
    private val onStatisticsChanged: (MonitorModeStatistics) -> Unit,
    private val onRecordedBytesChanged: (Long) -> Unit,
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
    private var pipeDirectory: File? = null
    private var captureFile: File? = null
    private var exportDirectory: File? = null
    private var targetChannel = 0
    private var targetFrequencyMhz = 0
    private var recordedBytes = 0L
    private var statisticsPublishScheduled = false
    private var stopping = false
    private var generation = 0L
    private val accessPoints = linkedMapOf<String, MutableAccessPoint>()
    private val handshakeArtifacts = linkedMapOf<HandshakeKey, MutableStoredHandshake>()
    private val disconnectionArtifacts =
        linkedMapOf<DisconnectionKey, MutableStoredDisconnection>()

    fun start(
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
        targetChannel: Int,
        targetFrequencyMhz: Int,
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
            accessPoints.clear()
            handshakeArtifacts.clear()
            disconnectionArtifacts.clear()
            captureFile = capture
            eventInput = input
            commandWriter = writer
            exportDirectory = pipes.exportDirectory
            this.targetChannel = targetChannel
            this.targetFrequencyMhz = targetFrequencyMhz
            recordedBytes = 0L
            statisticsPublishScheduled = false
            stopping = false
            generation
        }
        executor.execute { readStatistics(input, sessionGeneration) }

        try {
            val captureId = terminalManager.createChrootTerminal(
                rootfsPath = rootfsPath,
                runtimePath = runtimePath,
                terminalPath = terminalPath,
                onExit = { terminalId, exitCode ->
                    handleTerminalExit(terminalId, exitCode, "监听模式录制终端")
                },
            )
            synchronized(lock) { captureTerminalId = captureId }
            terminalManager.writeInput(captureId, CAPTURE_COMMAND)

            val statisticsId = terminalManager.createChrootTerminal(
                rootfsPath = rootfsPath,
                runtimePath = runtimePath,
                terminalPath = terminalPath,
                onExit = { terminalId, exitCode ->
                    handleTerminalExit(terminalId, exitCode, "监听模式统计终端")
                },
            )
            synchronized(lock) { statisticsTerminalId = statisticsId }
            terminalManager.writeInput(statisticsId, STATISTICS_COMMAND)

            publishEmptyStatistics()
            executor.execute { pollRecordedBytes(capture, sessionGeneration) }
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        val resources = synchronized(lock) {
            stopping = true
            generation += 1
            Resources(
                captureTerminalId = captureTerminalId,
                statisticsTerminalId = statisticsTerminalId,
                eventInput = eventInput,
                commandWriter = commandWriter,
                pipeDirectory = pipeDirectory,
            ).also {
                captureTerminalId = null
                statisticsTerminalId = null
                eventInput = null
                commandWriter = null
                pipeDirectory = null
                captureFile = null
                exportDirectory = null
                targetChannel = 0
                targetFrequencyMhz = 0
                recordedBytes = 0L
                statisticsPublishScheduled = false
                accessPoints.clear()
                handshakeArtifacts.clear()
                disconnectionArtifacts.clear()
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
        resources.pipeDirectory?.let { directory ->
            runCatching { directory.deleteRecursively() }
        }
        synchronized(lock) { stopping = false }
    }

    fun close() {
        stop()
        executor.shutdownNow()
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
        val commandPipe = File(directory, COMMAND_PIPE_NAME)
        val eventPipe = File(directory, EVENT_PIPE_NAME)
        val exports = File(directory, EXPORT_DIRECTORY_NAME)
        if (!exports.mkdirs()) {
            throw IOException("无法创建监听模式导出目录: ${exports.absolutePath}")
        }
        Os.chmod(exports.absolutePath, 493)
        Os.mkfifo(commandPipe.absolutePath, 384)
        Os.mkfifo(eventPipe.absolutePath, 384)
        synchronized(lock) { pipeDirectory = directory }
        return PipeFiles(commandPipe, eventPipe, exports)
    }

    private fun readStatistics(input: FileInputStream, sessionGeneration: Long) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    handleEvent(JSONObject(line), sessionGeneration)
                }
            }
            if (synchronized(lock) { generation == sessionGeneration && !stopping }) {
                throw IOException("监听模式统计脚本已关闭 FIFO")
            }
        } catch (error: Throwable) {
            if (synchronized(lock) { generation == sessionGeneration && !stopping }) {
                reportError("读取监听模式统计数据", error)
            }
        }
    }

    private fun handleEvent(event: JSONObject, sessionGeneration: Long) {
        when (event.optString("type")) {
            "statistics" -> handleStatistics(event, sessionGeneration)
            "exportCompleted" -> handleExportCompleted(event, sessionGeneration)
            "handshakeData" -> handleHandshakeData(event, sessionGeneration)
            "handshakeValidation" -> handleHandshakeValidation(event, sessionGeneration)
            "handshakeFinished" -> handleHandshakeFinished(event, sessionGeneration)
            "disconnectionData" -> handleDisconnectionData(event, sessionGeneration)
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

    private fun handleStatistics(event: JSONObject, sessionGeneration: Long) {
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
                            handshakes = parseHandshakes(
                                bssid = bssid,
                                deviceMac = mac,
                            ),
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
                    }
                }
            }
            scheduleStatisticsPublish(sessionGeneration)
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

    private fun parseHandshakes(
        bssid: String,
        deviceMac: String,
    ): List<MonitorHandshakeRecord> {
        val now = System.currentTimeMillis()
        return handshakeArtifacts
            .asSequence()
            .filter { (key, _) -> key.bssid == bssid && key.deviceMac == deviceMac }
            .map { (_, value) -> value.toRecord(now) }
            .sortedBy(MonitorHandshakeRecord::startUnixMillis)
            .toList()
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

    private fun handleHandshakeData(event: JSONObject, sessionGeneration: Long) {
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
                    startedAtMillis = System.currentTimeMillis(),
                ).also { it.pcap.write(header) }
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
            synchronizeHandshakeRecordsLocked(key)
        }
        scheduleStatisticsPublish(sessionGeneration)
    }

    private fun handleHandshakeValidation(event: JSONObject, sessionGeneration: Long) {
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
            synchronizeHandshakeRecordsLocked(key)
        }
        scheduleStatisticsPublish(sessionGeneration)
    }

    private fun handleHandshakeFinished(event: JSONObject, sessionGeneration: Long) {
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
            stored.finishedAtMillis = System.currentTimeMillis()
            synchronizeHandshakeRecordsLocked(key)
        }
        scheduleStatisticsPublish(sessionGeneration)
    }

    private fun handleDisconnectionData(event: JSONObject, sessionGeneration: Long) {
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
            }
            stored.nextSequence += 1
        }
        scheduleStatisticsPublish(sessionGeneration)
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

    private fun synchronizeAllHandshakeRecordsLocked() {
        accessPoints.values.forEach { accessPoint ->
            accessPoint.devices.keys.toList().forEach { deviceMac ->
                synchronizeHandshakeRecordsLocked(
                    HandshakeKey(accessPoint.bssid, deviceMac, ""),
                )
            }
        }
    }

    private fun synchronizeHandshakeRecordsLocked(key: HandshakeKey) {
        val accessPoint = accessPoints[key.bssid] ?: return
        val device = accessPoint.devices[key.deviceMac] ?: return
        accessPoint.devices[key.deviceMac] = device.copy(
            handshakes = parseHandshakes(
                bssid = key.bssid,
                deviceMac = key.deviceMac,
            ),
        )
    }

    private fun statisticsSnapshotLocked(): MonitorModeStatistics = MonitorModeStatistics(
        recordedBytes = recordedBytes,
        channel = targetChannel,
        frequencyMhz = targetFrequencyMhz,
        accessPoints = accessPoints.values
            .sortedBy { it.bssid }
            .map { accessPoint ->
                MonitorAccessPoint(
                    bssid = accessPoint.bssid,
                    ssid = accessPoint.ssid,
                    ssidVisibility = accessPoint.ssidVisibility,
                    securityProtocols = accessPoint.securityProtocols,
                    signal = accessPoint.signal,
                    devices = accessPoint.devices.values.sortedBy(MonitorDevice::mac),
                )
            },
        disconnections = disconnectionArtifacts.values
            .asSequence()
            .filter { it.completed }
            .map(MutableStoredDisconnection::toRecord)
            .sortedBy(MonitorDisconnectionRecord::timestampUnixMillis)
            .toList(),
    )

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
        )
    }

    private fun pollRecordedBytes(capture: File, sessionGeneration: Long) {
        while (isCurrentSession(sessionGeneration)) {
            val nextBytes = capture.length().coerceAtLeast(0L)
            val changed = synchronized(lock) {
                if (generation != sessionGeneration || stopping) return
                if (recordedBytes == nextBytes) {
                    false
                } else {
                    recordedBytes = nextBytes
                    true
                }
            }
            if (changed) onRecordedBytesChanged(nextBytes)
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
                synchronizeAllHandshakeRecordsLocked()
                statisticsSnapshotLocked()
            }
            snapshot?.let(onStatisticsChanged)
        }
    }

    private fun isCurrentSession(sessionGeneration: Long): Boolean = synchronized(lock) {
        generation == sessionGeneration && !stopping
    }

    private fun publishEmptyStatistics() {
        val statistics = synchronized(lock) {
            MonitorModeStatistics(
                recordedBytes = recordedBytes,
                channel = targetChannel,
                frequencyMhz = targetFrequencyMhz,
                accessPoints = emptyList(),
                disconnections = emptyList(),
            )
        }
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
        val pipeDirectory: File?,
    )

    private data class PipeFiles(
        val commandPipe: File,
        val eventPipe: File,
        val exportDirectory: File,
    )

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
        val pcap: ByteArrayOutputStream = ByteArrayOutputStream(),
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
        val pcap: ByteArrayOutputStream = ByteArrayOutputStream(),
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
        const val COMMAND_PIPE_NAME = "commands.fifo"
        const val EVENT_PIPE_NAME = "events.fifo"
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
                "--pcap /tmp/wlanlogs.pcap " +
                "--command-pipe /tmp/wlantool-monitor/commands.fifo " +
                "--event-pipe /tmp/wlantool-monitor/events.fifo"
    }
}
