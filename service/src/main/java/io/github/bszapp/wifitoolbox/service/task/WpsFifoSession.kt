package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetwork
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshake
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshakeProgress
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshakeStage
import io.github.bszapp.wifitoolbox.contract.task.WpsPacketDirection
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.TerminalManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** PIN 与 PBC 共用，任务结束前必须收到系统 supplicant 恢复确认。 */
internal class WpsFifoSession(private val terminals: TerminalManager, private val context: TaskContext,
    private val onProgress: (WpsHandshakeProgress) -> Unit = {}) {
    private val sessionId = UUID.randomUUID().toString()
    private val commandFifoId = UUID.randomUUID().toString()
    private val eventFifoId = UUID.randomUUID().toString()
    private val events = LinkedBlockingQueue<Event>()
    private var directory: File? = null
    private var input: FileInputStream? = null
    private var writer: java.io.BufferedWriter? = null
    private var reader: Thread? = null
    private var terminalId: Long? = null
    @Volatile private var exited = false
    @Volatile private var exitCode = 0
    @Volatile private var readerStopping = false
    private var restored: Boolean? = null
    private var exitDelivered = false

    fun start() {
        context.ensureRunning()
        val dir = File(terminals.rootfsPathForFifo(), "tmp/wlantool-ipc/$sessionId")
        check(dir.mkdirs()) { "无法创建 WPS FIFO 目录" }
        directory = dir
        Os.chmod(dir.absolutePath, 448)
        val commands = File(dir, commandFifoId)
        val replies = File(dir, eventFifoId)
        Os.mkfifo(commands.absolutePath, 384)
        Os.mkfifo(replies.absolutePath, 384)
        input = FileInputStream(Os.open(replies.absolutePath, OsConstants.O_RDWR, 0))
        writer = FileOutputStream(Os.open(commands.absolutePath, OsConstants.O_RDWR, 0)).bufferedWriter(Charsets.UTF_8)
        reader = Thread(::readEvents, "wps-fifo-$sessionId").apply { isDaemon = true; start() }
        terminalId = terminals.createChrootTerminal(
            onExit = { _, code -> exitCode = code; exited = true },
        )
        terminals.writeInput(requireNotNull(terminalId),
            "exec python wps.py -i wlan0 --command-pipe /tmp/wlantool-ipc/$sessionId/$commandFifoId " +
                "--event-pipe /tmp/wlantool-ipc/$sessionId/$eventFifoId")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (true) {
            context.ensureRunning()
            check(System.nanoTime() < deadline) { "等待 WPS FIFO 初始化超时，请更新容器系统" }
            when (val event = nextEvent()) {
                is Event.Pipe -> when (event.value.optString("type")) {
                    "ready" -> { check(event.value.optInt("protocol") == 3) { "请更新容器系统以支持 WPS 日志和进度回传" }; return }
                    "error" -> error(event.value.optString("message"))
                    "stopped" -> { restored = event.value.optBoolean("supplicantRestored"); error("WPS 会话初始化失败") }
                }
                is Event.Exit -> error("WPS 脚本初始化时退出：${event.code}")
                is Event.Failure -> throw event.error
                else -> Unit
            }
        }
    }

    data class Result(
        val network: WpsCapturedNetwork?,
        val success: Boolean,
        val settingsChanged: Boolean,
        /** 收到 M5，即路由器已确认 PIN 前半段正确。 */
        val firstHalfValid: Boolean,
        /** 本次握手中观察到的最高 M 消息编号。 */
        val maxMMessage: Int,
        /** 本轮的终止原因；正常完成时为 null。 */
        val failure: Failure?,
        /** 本次握手抓到的 WPS 关键数据；不完整时为 null。 */
        val handshake: WpsHandshake?,
        /** 脚本回传的状态（GOT_PSK / WSC_NACK / WPS_FAIL / WPS_SUCCESS 等）。 */
        val status: String,
        /** 本次尝试被单次用时上限掐掉；握手走到哪都不影响，按一次异常处理。 */
        val timedOut: Boolean = false,
    )

    /**
     * 本轮的拒绝/失败原因，由脚本按 wpa_supplicant 的实际输出分类后回传。
     * [code] 为稳定短码：`WSC_NACK_FIRST_HALF`（前半段不匹配）、`WSC_NACK_SECOND_HALF`
     * （收到 M5 后被拒，后半段不匹配）、`WSC_NACK_CONFIG_<n>`（读到具体 CONFIG_ERROR）、
     * `WPS_TIMEOUT` / `WPS_OVERLAP` / `WPS_FAIL` / `SUPPPLICANT_REJECT` /
     * `EXCLUDED_AP` / `EXCEPTION`。
     */
    data class Failure(val code: String, val detail: String, val maxMMessage: Int)

    /**
     * 执行一轮 WPS 尝试。
     *
     * [timeoutMillis] 大于 0 时是单次尝试的用时上限：到点后向 FIFO 发既有的 `cancel` 命令，
     * wps.py 在当前进度（扫描、发 M2、等回包都一样）抛出中断，**只掐这一次尝试**，
     * 持久会话继续服务下一条命令；结果里 [Result.timedOut] 为 true。
     */
    fun run(request: JSONObject, timeoutMillis: Long = 0, changed: () -> Boolean = { false }): Result {
        val requestId = UUID.randomUUID().toString()
        send(request.put("type", "run").put("requestId", requestId))
        var cancelledForUpdate = false
        var timedOut = false
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            context.ensureRunning()
            if (!cancelledForUpdate && changed()) {
                send(JSONObject().put("type", "cancel").put("requestId", requestId))
                cancelledForUpdate = true
            }
            if (!timedOut && timeoutMillis > 0 && SystemClock.elapsedRealtime() - startedAt >= timeoutMillis) {
                send(JSONObject().put("type", "cancel").put("requestId", requestId))
                timedOut = true
            }
            when (val event = nextEvent()) {
                is Event.Pipe -> when (event.value.optString("type")) {
                    "progress" -> if (event.value.optString("requestId") == requestId) {
                        onProgress(WpsHandshakeProgress(
                            stage = WpsHandshakeStage.valueOf(event.value.getString("stage")),
                            packet = event.value.optString("packet").takeIf { it.isNotEmpty() },
                            direction = event.value.optString("direction").takeIf { it.isNotEmpty() }
                                ?.let(WpsPacketDirection::valueOf),
                        ))
                    }
                    "result" -> if (event.value.optString("requestId") == requestId) {
                        check(event.value.optBoolean("cleanupCompleted")) { "WPS 本轮资源清理失败" }
                        val value = event.value.optJSONObject("network")
                        val network = value?.let {
                            WpsCapturedNetwork(it.getString("ssid"), it.getString("mac"), it.getString("password"))
                        }
                        val failure = readFailure(event.value.optJSONObject("failure"))
                        context.log("WPS 本轮结束：${event.value.optString("message")}" + when {
                            timedOut -> "，原因=单次尝试超时（${timeoutMillis}ms）被中断"
                            failure == null -> ""
                            else -> "，原因=${failure.code}${if (failure.detail.isEmpty()) "" else " · ${failure.detail}"}"
                        })
                        return Result(
                            network = network,
                            success = event.value.optBoolean("success"),
                            settingsChanged = cancelledForUpdate,
                            firstHalfValid = event.value.optBoolean("firstHalfValid"),
                            maxMMessage = event.value.optInt("maxMMessage"),
                            failure = failure,
                            handshake = readHandshake(event.value.optJSONObject("handshake")),
                            status = event.value.optString("message"),
                            timedOut = timedOut,
                        )
                    }
                    "error" -> error(event.value.optString("message"))
                    "stopped" -> { restored = event.value.optBoolean("supplicantRestored"); error("WPS 会话提前结束") }
                }
                is Event.Exit -> error("WPS 终端意外退出：${event.code}")
                is Event.Failure -> throw event.error
                else -> Unit
            }
        }
    }

    private fun readFailure(value: JSONObject?): Failure? = value?.takeIf { it.optString("code").isNotEmpty() }?.let {
        Failure(
            code = it.getString("code"),
            detail = it.optString("detail"),
            maxMMessage = it.optInt("maxMMessage"),
        )
    }

    private fun readHandshake(value: JSONObject?): WpsHandshake? = value?.let {
        WpsHandshake(
            pke = it.optString("pke"),
            pkr = it.optString("pkr"),
            eNonce = it.optString("eNonce"),
            authKey = it.optString("authKey"),
            eHash1 = it.optString("eHash1"),
            eHash2 = it.optString("eHash2"),
            essid = it.optString("essid"),
        ).takeIf(WpsHandshake::complete)
    }

    private fun send(value: JSONObject) {
        check(!exited) { "WPS 终端已退出" }
        requireNotNull(writer).apply { write(value.toString()); newLine(); flush() }
    }

    private fun nextEvent(): Event? = events.poll(100, TimeUnit.MILLISECONDS).also { event ->
        if (event is Event.Pipe) recordLog(event.value)
        if (event is Event.Exit) exitDelivered = true
    }

    private fun recordLog(value: JSONObject) {
        if (value.optString("type") == "log") {
            value.getString("content").takeIf { it.isNotEmpty() }?.let(context::log)
        }
    }

    fun close() {
        var interrupted = Thread.interrupted()
        var stopped = terminalId == null
        try {
            if (terminalId != null) {
                if (!exited) runCatching { send(JSONObject().put("type", "shutdown")) }
                    .onFailure { context.log("发送 WPS 关闭指令失败：${it.message}") }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                var exitReceived = exitDelivered
                while (!exitReceived && System.nanoTime() < deadline) {
                    val event = try { nextEvent() } catch (_: InterruptedException) {
                        interrupted = true; Thread.interrupted(); continue
                    }
                    when (event) {
                        is Event.Pipe -> if (event.value.optString("type") == "stopped") {
                            restored = event.value.optBoolean("supplicantRestored")
                            context.log("WPS 系统 supplicant 恢复确认：$restored")
                        }
                        is Event.Exit -> exitReceived = true
                        is Event.Failure -> { context.log("WPS FIFO 关闭阶段读取失败：${event.error.message}"); break }
                        else -> Unit
                    }
                }
                terminals.stopTerminalAndAwait(requireNotNull(terminalId), "WPS FIFO 会话结束")
                stopped = true
                check(restored == true) { "WPS 会话未确认系统 supplicant 已恢复，请检查任务日志" }
            }
        } finally {
            if (stopped) {
                readerStopping = true
                reader?.let { thread ->
                    while (thread.isAlive) {
                        try { thread.join(250) } catch (_: InterruptedException) { interrupted = true }
                    }
                }
                writer?.close()
                input?.close()
                directory?.let { check(it.deleteRecursively()) { "无法清理 WPS FIFO 目录" } }
                // FIFO 中剩余的尾部日志也要完整转发。
                while (true) {
                    val event = events.poll() ?: break
                    if (event is Event.Pipe) recordLog(event.value)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun readEvents() {
        val source = requireNotNull(input)
        val poll = StructPollfd().apply { fd = source.fd; events = OsConstants.POLLIN.toShort() }
        val buffer = ByteArray(4096)
        val pending = ByteArrayOutputStream()
        try {
            while (!readerStopping) {
                if (Os.poll(arrayOf(poll), 200) <= 0) {
                    if (exited) { events.offer(Event.Exit(exitCode)); return }
                    continue
                }
                check(poll.revents.toInt() and OsConstants.POLLIN != 0) { "WPS FIFO 已关闭" }
                val count = Os.read(source.fd, buffer, 0, buffer.size)
                check(count > 0) { "WPS FIFO 已结束" }
                for (index in 0 until count) {
                    if (buffer[index] == 10.toByte()) {
                        val line = pending.toString("UTF-8")
                        pending.reset()
                        if (line.isNotBlank()) events.offer(Event.Pipe(JSONObject(line)))
                    } else {
                        check(pending.size() < 64 * 1024) { "WPS FIFO 单条回复超过传输上限" }
                        pending.write(buffer[index].toInt())
                    }
                }
            }
        } catch (error: Throwable) {
            if (!readerStopping) events.offer(Event.Failure(error))
        }
    }

    private sealed interface Event {
        data class Pipe(val value: JSONObject) : Event
        data class Exit(val code: Int) : Event
        data class Failure(val error: Throwable) : Event
    }
}

internal fun saveCapturedWpsNetwork(network: WpsCapturedNetwork, context: TaskContext, androidApi: AndroidApi,
    onChanged: () -> Unit, onError: (String, Throwable) -> Unit) {
    runCatching { androidApi.saveWifiNetwork(network.ssid, network.password) }.onSuccess {
        context.log("已保存到 Android：networkId=$it，自动加入=false")
    }.onFailure {
        context.log("自动保存失败：${it.message}")
        onError("自动保存 WPS 捕获网络", it)
    }
    onChanged()
}
