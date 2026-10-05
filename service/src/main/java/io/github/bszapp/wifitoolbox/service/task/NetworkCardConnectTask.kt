package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTarget
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskConfig
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.service.TerminalManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 脚本拥有协议及清理逻辑；服务拥有任务、日志及进度，UI 只提交配置。 */
internal class NetworkCardConnectTask(
    private val target: ConnectWifiTarget.NetworkCard,
    private val config: ConnectWifiTaskConfig,
    private val terminalManager: TerminalManager,
    private val deviceName: String,
) : ServiceTask {
    override fun run(context: TaskContext) {
        val events = LinkedBlockingQueue<Event>()
        val id = terminalManager.createChrootTerminal(
            onOutputLines = { _, lines -> lines.forEach { events.offer(Event.Line(it)) } },
            onExit = { _, code -> events.offer(Event.Exit(code)) },
        )
        var exited = false
        var interrupted = false
        try {
            context.log("任务类型：连接到网络 · 测试连通性（网卡）")
            context.updateProgress(TaskProgress.ConnectWifi(ConnectWifiStage.ROUTER_COMMUNICATION))
            // 关闭回显及规范行缓冲：配置直接使用 JSON，长密码列表不受 PTY 单行缓冲限制。
            terminalManager.writeInput(id, "stty -echo -icanon min 1 time 0 && exec python managed_connect.py")
            val readyDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMillis)
            while (true) {
                context.ensureRunning()
                check(System.nanoTime() < readyDeadline) { "等待网卡脚本准备超时" }
                when (val event = events.poll(100, TimeUnit.MILLISECONDS)) {
                    null -> Unit
                    is Event.Exit -> { exited = true; error("网卡脚本在准备阶段退出：${event.code}") }
                    is Event.Line -> {
                        logLine(context, event.text)
                        // 初始 shell 提示可能紧挨第一条日志，准备提示使用固定英文文本。
                        if (event.text.endsWith(SCRIPT_READY)) break
                    }
                }
            }
            val request = JSONObject().apply {
                put("ssid", target.ssid)
                put("passwords", JSONArray(target.passwords))
                put("mac", target.mac ?: JSONObject.NULL)
                put("hostname", deviceName)
                put("hostnameEncoding", "gbk")
                put("timeoutMillis", config.timeoutMillis)
                put("handshakeTimeoutMillis", config.failureFlags.handshakeTimeout
                    ?.handshakeStepTimeoutMillis ?: JSONObject.NULL)
                put("maxHandshakeAttempts", config.failureFlags.handshakeAttemptsExceeded
                    ?.maxHandshakeAttempts ?: JSONObject.NULL)
                put("passwordError", config.failureFlags.passwordError)
            }
            context.ensureRunning()
            terminalManager.writeInput(id, request.toString())
            while (true) {
                context.ensureRunning()
                when (val event = events.poll(100, TimeUnit.MILLISECONDS)) {
                    null -> Unit
                    is Event.Exit -> {
                        exited = true
                        context.log("网卡脚本已退出：exitCode=${event.code}")
                        return
                    }
                    is Event.Line -> logLine(context, event.text)
                }
            }
        } catch (stop: InterruptedException) {
            interrupted = true
            Thread.interrupted()
            throw stop
        } finally {
            if (!exited) {
                // 等待脚本先清理连接及 MAC，再结束终端；停止不会直接跳过 Python finally。
                runCatching { terminalManager.writeInput(id, "{\"type\":\"stop\"}") }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!exited && System.nanoTime() < deadline) {
                    val event = try {
                        events.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                        Thread.interrupted()
                        continue
                    }
                    when (event) {
                        null -> Unit
                        is Event.Exit -> exited = true
                        is Event.Line -> logLine(context, event.text)
                    }
                }
                if (!exited) context.log("脚本清理未在 10 秒内确认完成，将停止终端；请检查网卡状态")
                terminalManager.stopTerminal(id, "网卡连通性测试结束")
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun logLine(context: TaskContext, line: String) {
        // 第一条准备提示可能紧接 shell 提示；其他脚本行按日志级别筛选。
        val text = if (line.endsWith(SCRIPT_READY)) SCRIPT_READY else line.trimStart()
        if (TASK_LOG_PREFIXES.none { text.startsWith(it) }) return
        context.log(text)
        val stage = when (text.trimEnd()) {
            "[*] Stage: Communicating with router" -> ConnectWifiStage.ROUTER_COMMUNICATION
            "[*] Stage: WPA handshake M1 (1/4)" -> ConnectWifiStage.WPA_HANDSHAKE_1_OF_4
            "[*] Stage: WPA handshake M2 (2/4)" -> ConnectWifiStage.WPA_HANDSHAKE_2_OF_4
            "[*] Stage: WPA handshake M3 (3/4)" -> ConnectWifiStage.WPA_HANDSHAKE_3_OF_4
            "[*] Stage: WPA handshake M4 (4/4)" -> ConnectWifiStage.WPA_HANDSHAKE_4_OF_4
            "[*] Stage: DHCP address acquisition" -> ConnectWifiStage.IP_NEGOTIATION
            else -> return
        }
        context.updateProgress(TaskProgress.ConnectWifi(stage))
    }

    private sealed interface Event {
        data class Line(val text: String) : Event
        data class Exit(val code: Int) : Event
    }

    private companion object {
        const val SCRIPT_READY = "[*] Managed Wi-Fi diagnostic ready (protocol=1)"
        val TASK_LOG_PREFIXES = listOf("[*] ", "[-] ", "[+] ")
    }
}
