package io.github.bszapp.wifitoolbox.service

import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDeviceRealtime
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorFrameGroupStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorFrameSubtypeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeFailureReason
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStep
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestOutcome
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSecurityProtocol
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSignalStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
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
    private val onExportCompleted: (requestId: String, path: String, fileName: String) -> Unit,
    private val onHandshakeTestResult: (
        requestId: String,
        outcome: MonitorHandshakeTestOutcome,
    ) -> Unit,
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
    private var stopping = false
    private var generation = 0L
    private val accessPoints = linkedMapOf<String, MutableAccessPoint>()

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
            captureFile = capture
            eventInput = input
            commandWriter = writer
            exportDirectory = pipes.exportDirectory
            this.targetChannel = targetChannel
            this.targetFrequencyMhz = targetFrequencyMhz
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
                accessPoints.clear()
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
        val command = synchronized(lock) {
            check(!stopping && captureFile != null) { "监听模式尚未运行" }
            val record = accessPoints[bssid]
                ?.devices
                ?.get(deviceMac)
                ?.handshakes
                ?.firstOrNull { it.id == handshakeId }
                ?: error("找不到指定的握手记录")
            check(record.exportPacketCount > 0) { "握手记录没有可导出的数据包" }
            val writer = commandWriter ?: error("监听模式命令通道尚未建立")
            val directory = exportDirectory ?: error("监听模式导出目录尚未建立")
            writer to JSONObject()
                .put("type", "export")
                .put("requestId", requestId)
                .put("mode", "handshake")
                .put("outputDirectory", CONTAINER_EXPORT_DIRECTORY)
                .put("bssid", bssid)
                .put("deviceMac", deviceMac)
                .put("handshakeId", handshakeId)
                .also { require(directory.isDirectory || directory.mkdirs()) }
        }
        synchronized(command.first) {
            command.first.write(command.second.toString())
            command.first.write("\n")
            command.first.flush()
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

    fun testHandshake(
        requestId: String,
        bssid: String,
        deviceMac: String,
        handshakeId: String,
        password: String,
    ) {
        val command = synchronized(lock) {
            check(!stopping && captureFile != null) { "监听模式尚未运行" }
            val accessPoint = accessPoints[bssid]
                ?: error("找不到接入点 $bssid")
            val ssid = accessPoint.ssid
                ?.takeIf(String::isNotBlank)
                ?: error("该接入点缺少可用于校验的网络名称")
            check(
                MonitorSecurityProtocol.WPA in accessPoint.securityProtocols ||
                    MonitorSecurityProtocol.WPA2 in accessPoint.securityProtocols,
            ) { "该接入点未识别为 WPA/WPA2-PSK" }
            val record = accessPoint.devices[deviceMac]
                ?.handshakes
                ?.firstOrNull { it.id == handshakeId }
                ?: error("找不到指定的握手记录")
            check(record.canValidate) { "该握手记录缺少可校验的 EAPOL 数据" }
            val writer = commandWriter ?: error("监听模式命令通道尚未建立")
            writer to JSONObject()
                .put("type", "test")
                .put("requestId", requestId)
                .put("bssid", bssid)
                .put("deviceMac", deviceMac)
                .put("handshakeId", handshakeId)
                .put("ssid", ssid)
                .put("password", password)
        }
        synchronized(command.first) {
            command.first.write(command.second.toString())
            command.first.write("\n")
            command.first.flush()
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
            "handshakeTestCompleted" -> {
                if (isCurrentSession(sessionGeneration)) {
                    onHandshakeTestResult(
                        event.getString("requestId"),
                        if (event.getBoolean("matched")) {
                            MonitorHandshakeTestOutcome.MATCHED
                        } else {
                            MonitorHandshakeTestOutcome.NOT_MATCHED
                        },
                    )
                }
            }
            "handshakeTestFailed" -> {
                if (isCurrentSession(sessionGeneration)) {
                    onHandshakeTestResult(
                        event.getString("requestId"),
                        MonitorHandshakeTestOutcome.FAILED,
                    )
                    reportError(
                        "校验 WPA/WPA2 握手包",
                        IOException(event.optString("message", "Python 校验失败")),
                    )
                }
            }
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
        if (accessPointUpdates != null) {
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
                            handshakes = parseHandshakes(deviceJson),
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
                        )
                    }
                }
            }
        }
        val statisticsSnapshot = synchronized(lock) {
            if (generation != sessionGeneration || stopping) return
            val accessPointSnapshot = accessPoints.values
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
                }
            MonitorModeStatistics(
                recordedBytes = event.getLong("recordedBytes"),
                channel = targetChannel,
                frequencyMhz = targetFrequencyMhz,
                accessPoints = accessPointSnapshot,
            )
        }
        onStatisticsChanged(statisticsSnapshot)
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

    private fun parseHandshakes(deviceJson: JSONObject): List<MonitorHandshakeRecord> {
        val handshakes = deviceJson.getJSONArray("handshakes")
        return buildList(handshakes.length()) {
            for (index in 0 until handshakes.length()) {
                val handshake = handshakes.getJSONObject(index)
                add(
                    MonitorHandshakeRecord(
                        id = handshake.getString("id"),
                        startUnixMillis = handshake.getLong("startUnixMillis"),
                        durationMillis = handshake.getLong("durationMillis"),
                        status = when (handshake.getString("status")) {
                            "inProgress" -> MonitorHandshakeStatus.IN_PROGRESS
                            "success" -> MonitorHandshakeStatus.SUCCESS
                            "failed" -> MonitorHandshakeStatus.FAILED
                            else -> throw IOException(
                                "未知握手记录状态: ${handshake.getString("status")}",
                            )
                        },
                        canValidate = handshake.getBoolean("canValidate"),
                        validationDataComplete = handshake.getBoolean(
                            "validationDataComplete",
                        ),
                        capturedSteps = buildList {
                            val steps = handshake.getJSONArray("capturedSteps")
                            for (stepIndex in 0 until steps.length()) {
                                add(parseHandshakeStep(steps.getString(stepIndex)))
                            }
                        },
                        failedAtStep = if (handshake.isNull("failedAtStep")) {
                            null
                        } else {
                            parseHandshakeStep(handshake.getString("failedAtStep"))
                        },
                        failureReason = if (handshake.isNull("failureReason")) {
                            null
                        } else {
                            parseHandshakeFailureReason(handshake.getString("failureReason"))
                        },
                        m2AttemptCount = handshake.getInt("m2AttemptCount"),
                        exportPacketCount = handshake.getInt("exportPacketCount"),
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

    private fun isCurrentSession(sessionGeneration: Long): Boolean = synchronized(lock) {
        generation == sessionGeneration && !stopping
    }

    private fun publishEmptyStatistics() {
        val statistics = synchronized(lock) {
            MonitorModeStatistics(
                recordedBytes = captureFile?.length() ?: 0L,
                channel = targetChannel,
                frequencyMhz = targetFrequencyMhz,
                accessPoints = emptyList(),
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
        const val CAPTURE_COMMAND =
            "exec tcpdump -U -i wlan0 -e -w /tmp/wlanlogs.pcap"
        const val STATISTICS_COMMAND =
            "exec /usr/bin/python3 -u /wlantool/monitor_stats.py " +
                "--pcap /tmp/wlanlogs.pcap " +
                "--command-pipe /tmp/wlantool-monitor/commands.fifo " +
                "--event-pipe /tmp/wlantool-monitor/events.fifo"
    }
}
