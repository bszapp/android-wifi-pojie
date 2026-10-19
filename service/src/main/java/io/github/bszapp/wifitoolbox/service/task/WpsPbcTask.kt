package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.*
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.TerminalManager
import org.json.JSONArray
import org.json.JSONObject

internal class WpsPbcTask(
    input: WpsPbcTaskInput,
    private val terminalManager: TerminalManager,
    private val androidApi: AndroidApi,
    private val onSavedWifiNetworksChanged: () -> Unit,
    private val onError: (String, Throwable) -> Unit,
) : ServiceTask {
    private val lock = Any()
    private val networks = mutableListOf<WpsCapturedNetwork>()
    private var continuousCapture = input.continuousCapture
    private var autoSaveToDevice = input.autoSaveToDevice
    private var useIncompleteProtocol = input.useIncompleteProtocol
    private var ignoreRepeatedDevices = input.ignoreRepeatedDevices
    private var scriptSettingsRevision = 0L
    private val targetMac = input.targetMac?.lowercase()
    private val interfaceName = input.interfaceName
    private val localMac = input.localMac
    private var context: TaskContext? = null
    private var handshake: WpsHandshakeProgress? = null

    override fun run(context: TaskContext) {
        synchronized(lock) { this.context = context }
        publishProgress(context)
        val session = WpsFifoSession(terminalManager, context, interfaceName) { current ->
            synchronized(lock) { handshake = current }
            publishProgress(context)
        }
        try {
            session.start()
            var attempt = 0L
            while (true) {
                context.ensureRunning()
                val (revision, command) = synchronized(lock) {
                    scriptSettingsRevision to JSONObject().put("mode", "pbc")
                        .put("mac", targetMac ?: JSONObject.NULL)
                        .put("localMac", localMac ?: JSONObject.NULL)
                        .put("completeProtocol", !useIncompleteProtocol)
                        .put("excludeMacs", JSONArray(if (ignoreRepeatedDevices) {
                            networks.map { it.mac.lowercase() }.distinct()
                        } else emptyList<String>()))
                }
                context.log("开始第 ${++attempt} 次 WPS-PBC 捕获")
                val result = session.run(command) { synchronized(lock) { scriptSettingsRevision != revision } }
                result.network?.let { network ->
                    synchronized(lock) { networks += network }
                    context.recordCapturedNetwork(network)
                    context.log("已获取网络：ssid=${network.ssid} mac=${network.mac}")
                    publishProgress(context)
                    if (synchronized(lock) { autoSaveToDevice }) {
                        saveCapturedWpsNetwork(network, context, androidApi, onSavedWifiNetworksChanged, onError)
                    }
                }
                if (result.settingsChanged) {
                    context.log("脚本选项已更新，使用最新参数重新执行")
                    continue
                }
                if (!synchronized(lock) { continuousCapture }) return
                context.log("持续捕获已开启，提交下一轮 PBC 命令")
            }
        } finally {
            try { session.close() }
            finally { synchronized(lock) { this.context = null } }
        }
    }

    override fun update(update: TaskUpdateRequest): Boolean {
        val current: TaskContext?
        val message: String
        synchronized(lock) {
            message = when (val payload = update.payload) {
                is TaskUpdatePayload.WpsPbcContinuousCapture -> {
                    continuousCapture = payload.enabled
                    "持续捕获已更新为 ${payload.enabled}"
                }
                is TaskUpdatePayload.WpsPbcAutoSaveToDevice -> {
                    autoSaveToDevice = payload.enabled
                    "自动保存已更新为 ${payload.enabled}"
                }
                is TaskUpdatePayload.WpsPbcUseIncompleteProtocol -> {
                    if (useIncompleteProtocol != payload.enabled) {
                        useIncompleteProtocol = payload.enabled
                        scriptSettingsRevision++
                    }
                    "不使用完整协议已更新为 ${payload.enabled}"
                }
                is TaskUpdatePayload.WpsPbcIgnoreRepeatedDevices -> {
                    if (ignoreRepeatedDevices != payload.enabled) {
                        ignoreRepeatedDevices = payload.enabled
                        scriptSettingsRevision++
                    }
                    "忽略重复握手设备已更新为 ${payload.enabled}"
                }
                else -> return false
            }
            current = context
        }
        current?.let { it.log(message); publishProgress(it) }
        return true
    }

    private fun publishProgress(context: TaskContext) {
        val progress = synchronized(lock) {
            TaskProgress.WpsPbc(continuousCapture, autoSaveToDevice, useIncompleteProtocol,
                ignoreRepeatedDevices, networks.size, handshake)
        }
        context.updateProgress(progress)
    }
}
