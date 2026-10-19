package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshakeProgress
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshakeStage
import io.github.bszapp.wifitoolbox.contract.task.WpsPinTaskInput
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.TerminalManager
import org.json.JSONObject

internal class WpsPinTask(private val input: WpsPinTaskInput, private val targetMac: String,
    private val terminals: TerminalManager, private val androidApi: AndroidApi,
    private val onChanged: () -> Unit, private val onError: (String, Throwable) -> Unit) : ServiceTask {
    override fun run(context: TaskContext) {
        var progress = TaskProgress.WpsPin(targetMac, 0)
        context.updateProgress(progress)
        context.log("使用 PIN 配网：${input.ssid}，本次最强接入点=$targetMac")
        val session = WpsFifoSession(terminals, context, input.interfaceName) { handshake ->
            progress = progress.copy(handshake = handshake)
            context.updateProgress(progress)
        }
        try {
            session.start()
            val result = session.run(JSONObject().put("mode", "pin").put("mac", targetMac)
                .put("localMac", input.localMac ?: JSONObject.NULL)
                .put("pin", input.pin).put("completeProtocol", true))
            val network = result.network
            if (network != null) {
                context.recordCapturedNetwork(network)
                progress = progress.copy(networkCount = 1,
                    handshake = WpsHandshakeProgress(WpsHandshakeStage.SAVING_NETWORK))
                context.updateProgress(progress)
                saveCapturedWpsNetwork(network, context, androidApi, onChanged, onError)
                context.log("已获取网络名称和密码：${network.ssid}")
            } else context.log("未获取到网络密码")
            context.log(if (result.success) "WPS PIN 配网完成" else "WPS PIN 本轮未完成协议，详情见日志")
        } finally {
            context.updateProgress(progress.copy(handshake = WpsHandshakeProgress(WpsHandshakeStage.RESTORING_SYSTEM)))
            session.close()
        }
    }
}
