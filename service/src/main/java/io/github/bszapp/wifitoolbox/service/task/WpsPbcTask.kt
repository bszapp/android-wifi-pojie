package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdatePayload
import io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetwork
import io.github.bszapp.wifitoolbox.contract.task.WpsPbcTaskInput
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.HybridTaskEnvironment
import io.github.bszapp.wifitoolbox.service.TerminalManager
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class WpsPbcTask(
    input: WpsPbcTaskInput,
    private val environment: HybridTaskEnvironment,
    private val terminalManager: TerminalManager,
    private val androidApi: AndroidApi,
    private val onSavedWifiNetworksChanged: () -> Unit,
    private val onError: (operation: String, error: Throwable) -> Unit,
) : ServiceTask {
    private val lock = Any()
    private val events = LinkedBlockingQueue<Event>()
    private val networks = mutableListOf<WpsCapturedNetwork>()
    private var continuousCapture = input.continuousCapture
    private var autoSaveToDevice = input.autoSaveToDevice
    private var useIncompleteProtocol = input.useIncompleteProtocol
    private var ignoreRepeatedDevices = input.ignoreRepeatedDevices
    private var scriptSettingsRevision = 0L
    private val targetMac = input.targetMac?.lowercase()
    private var terminalId: Long? = null
    private var context: TaskContext? = null

    override fun run(context: TaskContext) {
        synchronized(lock) { this.context = context }
        publishProgress(context)
        context.log(
            "启动 WPS-PBC：持续捕获=$continuousCapture 自动保存=$autoSaveToDevice " +
                "不使用完整协议=$useIncompleteProtocol 忽略重复握手设备=$ignoreRepeatedDevices " +
                "目标设备=${targetMac ?: "任意"}",
        )

        var attempt = 0L
        while (true) {
            context.ensureRunning()
            attempt++
            context.log("开始第 $attempt 次 WPS-PBC 捕获")
            when (val result = runSingleAttempt(context)) {
                AttemptResult.RestartForSettingsUpdate -> {
                    context.log("WPS-PBC 脚本参数已更新，使用最新选项重新开始本轮捕获")
                    continue
                }
                is AttemptResult.Exited -> {
                    if (result.exitCode != 0) {
                        throw IllegalStateException(
                            "WPS-PBC 脚本异常退出：exitCode=${result.exitCode}",
                        )
                    }
                }
            }

            val shouldContinue = synchronized(lock) { continuousCapture }
            if (!shouldContinue) {
                context.log("WPS-PBC 脚本已结束")
                return
            }
            context.log("持续捕获已开启，开始下一次 WPS-PBC 捕获")
        }
    }

    override fun update(update: TaskUpdateRequest): Boolean {
        val progressContext: TaskContext?
        synchronized(lock) {
            when (val payload = update.payload) {
                is TaskUpdatePayload.WpsPbcContinuousCapture -> {
                    continuousCapture = payload.enabled
                }
                is TaskUpdatePayload.WpsPbcAutoSaveToDevice -> {
                    autoSaveToDevice = payload.enabled
                }
                is TaskUpdatePayload.WpsPbcUseIncompleteProtocol -> {
                    if (useIncompleteProtocol != payload.enabled) {
                        useIncompleteProtocol = payload.enabled
                        scriptSettingsRevision++
                    }
                }
                is TaskUpdatePayload.WpsPbcIgnoreRepeatedDevices -> {
                    if (ignoreRepeatedDevices != payload.enabled) {
                        ignoreRepeatedDevices = payload.enabled
                        scriptSettingsRevision++
                    }
                }
            }
            progressContext = context
        }
        progressContext?.let {
            it.log(
                when (val payload = update.payload) {
                    is TaskUpdatePayload.WpsPbcContinuousCapture ->
                        "持续捕获已更新为 ${payload.enabled}"
                    is TaskUpdatePayload.WpsPbcAutoSaveToDevice ->
                        "自动保存已更新为 ${payload.enabled}"
                    is TaskUpdatePayload.WpsPbcUseIncompleteProtocol ->
                        "不使用完整协议已更新为 ${payload.enabled}"
                    is TaskUpdatePayload.WpsPbcIgnoreRepeatedDevices ->
                        "忽略重复握手设备已更新为 ${payload.enabled}"
                },
            )
            publishProgress(it)
        }
        return true
    }

    private fun runSingleAttempt(context: TaskContext): AttemptResult {
        val outputParser = AttemptOutputParser(targetMac)
        val settings = captureAttemptSettings()
        val id = terminalManager.createChrootTerminal(
            rootfsPath = environment.rootfsPath,
            runtimePath = environment.runtimePath,
            terminalPath = environment.terminalPath,
            onOutputLines = { terminalId, lines ->
                lines.forEach { line ->
                    events.offer(Event.OutputLine(terminalId, line))
                }
            },
            onExit = { terminalId, exitCode ->
                events.offer(Event.TerminalExited(terminalId, exitCode))
            },
        )
        synchronized(lock) { terminalId = id }

        try {
            val startCommand = buildStartCommand(settings)
            context.log("启动脚本：$startCommand")
            terminalManager.writeInput(id, startCommand)
            while (true) {
                context.ensureRunning()
                if (hasScriptSettingsChanged(settings.revision)) {
                    return AttemptResult.RestartForSettingsUpdate
                }
                when (val event = events.poll(EVENT_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    null -> Unit
                    is Event.OutputLine -> {
                        if (event.terminalId != id) continue
                        val line = event.line.removeSuffix("\r")
                        if (line.isNotEmpty()) context.log(line)
                        outputParser.accept(line)?.let { update ->
                            update.mac?.let { mac ->
                                context.log("WPS-PBC 解析：已识别接入点 MAC=$mac")
                            }
                            if (update.passwordCaptured) {
                                context.log("WPS-PBC 解析：已识别 WPA 密码")
                            }
                            update.ssid?.let { ssid ->
                                context.log("WPS-PBC 解析：已识别 SSID=$ssid")
                            }
                            update.network?.let { network ->
                                context.log("WPS-PBC 解析：捕获结果完整，正在发布到网络列表")
                                handleCapturedNetwork(context, network)
                            }
                        }
                    }
                    is Event.TerminalExited -> {
                        if (event.terminalId != id) continue
                        outputParser.incompleteResultMessage()?.let(context::log)
                        return AttemptResult.Exited(event.exitCode)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (terminalId == id) terminalId = null
            }
            terminalManager.stopTerminal(id, "WPS-PBC 单次脚本结束")
        }
    }

    private fun handleCapturedNetwork(
        context: TaskContext,
        network: WpsCapturedNetwork,
    ) {
        synchronized(lock) {
            networks += network
        }
        context.log("已获取网络：ssid=${network.ssid} mac=${network.mac}")
        publishProgress(context)

        val shouldSave = synchronized(lock) { autoSaveToDevice }
        if (!shouldSave) return
        runCatching {
            androidApi.saveWifiNetwork(network.ssid, network.password)
        }.onSuccess { networkId ->
            onSavedWifiNetworksChanged()
            context.log("已保存到 Android：networkId=$networkId，自动加入=false")
        }.onFailure { error ->
            onSavedWifiNetworksChanged()
            context.log(
                "自动保存失败：ssid=${network.ssid} mac=${network.mac} " +
                    "原因=${error.message ?: error.javaClass.name}",
            )
            onError("自动保存 WPS 捕获网络", error)
        }
    }

    private fun publishProgress(context: TaskContext) {
        val progress = synchronized(lock) {
            TaskProgress.WpsPbc(
                continuousCapture = continuousCapture,
                autoSaveToDevice = autoSaveToDevice,
                useIncompleteProtocol = useIncompleteProtocol,
                ignoreRepeatedDevices = ignoreRepeatedDevices,
                networks = networks.toList(),
            )
        }
        context.updateProgress(progress)
        context.log("WPS-PBC 进度已发布：networkCount=${progress.networks.size}")
    }

    private fun captureAttemptSettings(): AttemptSettings = synchronized(lock) {
        AttemptSettings(
            revision = scriptSettingsRevision,
            useIncompleteProtocol = useIncompleteProtocol,
            excludedMacs = if (ignoreRepeatedDevices) {
                networks.asSequence().map { it.mac.lowercase() }.distinct().toList()
            } else {
                emptyList()
            },
        )
    }

    private fun hasScriptSettingsChanged(revision: Long): Boolean = synchronized(lock) {
        scriptSettingsRevision != revision
    }

    private fun buildStartCommand(settings: AttemptSettings): String = buildString {
        append("exec python wps.py -i wlan0 --pbc ")
        append(
            if (settings.useIncompleteProtocol) {
                "--incomplete-protocol"
            } else {
                "--complete-protocol"
            },
        )
        targetMac?.let { append(" -mac ").append(it) }
        if (settings.excludedMacs.isNotEmpty()) {
            append(" -exclude ").append(settings.excludedMacs.joinToString(","))
        }
    }

    private data class AttemptSettings(
        val revision: Long,
        val useIncompleteProtocol: Boolean,
        val excludedMacs: List<String>,
    )

    private sealed interface AttemptResult {
        data object RestartForSettingsUpdate : AttemptResult

        data class Exited(
            val exitCode: Int,
        ) : AttemptResult
    }

    private class AttemptOutputParser(initialMac: String?) {
        private var mac = initialMac
        private var ssid: String? = null
        private var password: String? = null
        private var publishedResult: WpsCapturedNetwork? = null

        fun accept(line: String): ParseUpdate? {
            var parsedMac: String? = null
            var parsedSsid: String? = null
            var passwordCaptured = false

            extractMacAfter(line, SELECTED_AP_PREFIX)?.let { value ->
                mac = value
                parsedMac = value
            }
            if (mac == null) {
                extractMacAfter(line, ASSOCIATED_AP_PREFIX)?.let { value ->
                    mac = value
                    parsedMac = value
                }
            }
            extractQuotedValue(line, WPA_PSK_PREFIX)?.let { value ->
                password = value
                passwordCaptured = true
            }
            extractQuotedValue(line, AP_SSID_PREFIX)?.let { value ->
                ssid = value
                parsedSsid = value
            }

            val unpublishedResult = createResult()?.takeUnless { it == publishedResult }
            if (unpublishedResult != null) publishedResult = unpublishedResult
            if (
                parsedMac == null &&
                parsedSsid == null &&
                !passwordCaptured &&
                unpublishedResult == null
            ) {
                return null
            }
            return ParseUpdate(
                mac = parsedMac,
                ssid = parsedSsid,
                passwordCaptured = passwordCaptured,
                network = unpublishedResult,
            )
        }

        fun incompleteResultMessage(): String? {
            if (publishedResult != null) return null
            if (ssid == null && password == null) return null
            val missing = buildList {
                if (mac == null || !VALID_MAC.matches(mac!!) || mac == ZERO_MAC) add("接入点 MAC")
                if (ssid.isNullOrBlank()) add("SSID")
                if (password.isNullOrEmpty()) add("WPA 密码")
            }
            return "WPS-PBC 输出未形成完整网络结果：缺少${missing.joinToString("、")}"
        }

        private fun createResult(): WpsCapturedNetwork? {
            val capturedMac = mac ?: return null
            val capturedSsid = ssid?.takeIf(String::isNotBlank) ?: return null
            val capturedPassword = password?.takeIf(String::isNotEmpty) ?: return null
            if (!VALID_MAC.matches(capturedMac) || capturedMac == ZERO_MAC) return null
            return WpsCapturedNetwork(
                ssid = capturedSsid,
                mac = capturedMac,
                password = capturedPassword,
            )
        }

        private fun extractMacAfter(line: String, marker: String): String? {
            val markerIndex = line.indexOf(marker)
            if (markerIndex < 0) return null
            val candidate = line
                .substring(markerIndex + marker.length)
                .trimStart()
                .take(17)
                .lowercase()
            return candidate.takeIf {
                VALID_MAC.matches(it) && it != ZERO_MAC
            }
        }

        private fun extractQuotedValue(line: String, marker: String): String? {
            val markerIndex = line.indexOf(marker)
            if (markerIndex < 0) return null
            val valueStart = markerIndex + marker.length
            val valueEnd = line.lastIndexOf('\'')
            if (valueEnd < valueStart) return null
            return line.substring(valueStart, valueEnd)
        }
    }

    private data class ParseUpdate(
        val mac: String?,
        val ssid: String?,
        val passwordCaptured: Boolean,
        val network: WpsCapturedNetwork?,
    )

    private sealed interface Event {
        data class OutputLine(
            val terminalId: Long,
            val line: String,
        ) : Event

        data class TerminalExited(
            val terminalId: Long,
            val exitCode: Int,
        ) : Event
    }

    private companion object {
        const val EVENT_POLL_MILLIS = 250L
        const val ZERO_MAC = "00:00:00:00:00:00"
        const val SELECTED_AP_PREFIX = "[*] Selected AP:"
        const val ASSOCIATED_AP_PREFIX = "Associated with"
        const val WPA_PSK_PREFIX = "[+] WPA PSK: '"
        const val AP_SSID_PREFIX = "[+] AP SSID: '"
        val VALID_MAC = Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
    }
}
