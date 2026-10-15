package io.github.bszapp.wifitoolbox.service.task

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.SecurityAuditConfig
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.service.TerminalManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 安全测试专用网卡执行器：一个实例对应一张网卡，持有自己的 Python/FIFO 会话，逐轮发送连接配置。 */
internal class SecurityAuditNetworkCardWorker(
    private val terminalManager: TerminalManager,
    private val deviceName: () -> String,
    /** 本会话固定使用的网卡；多线程下每张可分配网卡各开一个这样的实例。 */
    val interfaceName: String,
) {
    init {
        // 该值会原样拼进 chroot 里的 shell 命令，只接受内核网卡名（IFNAMSIZ 15 字节、无空白）。
        require(interfaceName.matches(INTERFACE_NAME)) { "非法网卡名：$interfaceName" }
    }

    @Volatile private var session: Session? = null

    /** mac 由调度器按 MAC 策略在每次连接前决定；null 表示本轮使用设备原 MAC。 */
    fun performTaskLogic(
        ssid: String,
        password: String,
        mac: String?,
        config: SecurityAuditConfig,
        context: TaskContext,
    ): ConnectionAttemptResult {
        context.ensureRunning()
        val current = ensureSession(config.connection.timeoutMillis, context)
        check(current.activeRequestId == null) { "上一轮网卡连接尚未清理" }
        val connection = config.connection
        val requestId = UUID.randomUUID().toString()
        val request = JSONObject().apply {
            put("type", "run")
            put("requestId", requestId)
            put("ssid", ssid)
            put("passwords", JSONArray(listOf(password)))
            put("mac", mac ?: JSONObject.NULL)
            put("hostname", deviceName())
            put("hostnameEncoding", "gbk")
            put("timeoutMillis", connection.timeoutMillis)
            put("handshakeTimeoutMillis", connection.failureFlags.handshakeTimeout
                ?.handshakeStepTimeoutMillis ?: JSONObject.NULL)
            put("maxHandshakeAttempts", connection.failureFlags.handshakeAttemptsExceeded
                ?.maxHandshakeAttempts ?: JSONObject.NULL)
            put("passwordError", connection.failureFlags.passwordError)
        }
        current.activeRequestId = requestId
        sendCommand(current, request)
        context.log("向持久网卡会话提交连接请求：iface=$interfaceName ssid=$ssid requestId=$requestId")

        var result = ConnectionAttemptResult.ERROR
        var exitCode: Int? = null
        while (exitCode == null) {
            context.ensureRunning()
            when (val event = current.events.poll(100, TimeUnit.MILLISECONDS)) {
                null -> Unit
                is Event.Pipe -> when (event.value.optString("type")) {
                    "output" -> if (event.value.optString("requestId") == requestId) {
                        result = receiveLine(event.value.optString("text"), context, result)
                    }
                    "result" -> if (event.value.optString("requestId") == requestId) {
                        current.activeRequestId = null
                        if (!event.value.optBoolean("cleanupCompleted")) {
                            throw ConnectionCleanupException(IOException("网卡脚本未完成本轮连接清理"))
                        }
                        exitCode = event.value.optInt("exitCode", 1)
                    }
                    "error" -> {
                        error("网卡脚本 FIFO 返回错误：${event.value.optString("message")}")
                    }
                    "stopped" -> {
                        current.supplicantRestored = event.value.optBoolean("supplicantRestored")
                        error("安全测试网卡脚本在本轮完成前结束会话")
                    }
                }
                is Event.Exit -> {
                    current.exited = true
                    error("安全测试网卡脚本会话意外退出：exitCode=${event.code}")
                }
                is Event.Failure -> throw event.error
            }
        }
        val code = requireNotNull(exitCode)
        context.log("安全测试网卡本轮结束：requestId=$requestId exitCode=$code")
        return if (code != 0 && result == ConnectionAttemptResult.SUCCESS) {
            ConnectionAttemptResult.ERROR
        } else result
    }

    /** 编辑、删除网络或停止任务时，先撤销本轮并等待脚本完成断开及资源回收。 */
    fun cleanConnection(context: TaskContext) {
        val current = session ?: return
        val requestId = current.activeRequestId ?: return
        var interrupted = Thread.interrupted()
        try {
            sendCommand(current, JSONObject().put("type", "cancel").put("requestId", requestId))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                val event = try {
                    current.events.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    continue
                }
                when (event) {
                    null -> Unit
                    is Event.Pipe -> when (event.value.optString("type")) {
                        "output" -> if (event.value.optString("requestId") == requestId) {
                            logLine(event.value.optString("text"), context)
                        }
                        "result" -> if (event.value.optString("requestId") == requestId) {
                            current.activeRequestId = null
                            check(event.value.optBoolean("cleanupCompleted")) { "取消后网卡连接清理失败" }
                            context.log("安全测试网卡本轮取消及清理完成：requestId=$requestId")
                            return
                        }
                        "stopped" -> {
                            current.supplicantRestored = event.value.optBoolean("supplicantRestored")
                            error("网卡会话已结束，无法确认本轮清理")
                        }
                        "error" -> error("取消网卡连接失败：${event.value.optString("message")}")
                    }
                    is Event.Exit -> error("取消网卡连接时脚本退出：${event.code}")
                    is Event.Failure -> throw event.error
                }
            }
            error("等待网卡连接取消及清理超时")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun sendCommand(current: Session, command: JSONObject) {
        synchronized(current.commandLock) {
            check(!current.exited) { "安全测试网卡脚本会话已退出" }
            val writer = requireNotNull(current.writer)
            writer.write(command.toString())
            writer.newLine()
            writer.flush()
        }
    }

    /** 任务结束或切换到系统连接方式时，由任务生命周期拥有者调用。 */
    fun closeSession(context: TaskContext) {
        val current = session ?: return
        var interrupted = Thread.interrupted()
        var terminalStopped = current.terminalId == null || current.exited
        try {
            if (current.terminalId != null) {
                if (!current.exited) {
                    runCatching {
                        sendCommand(current, JSONObject().put("type", "shutdown"))
                    }.onFailure { context.log("发送网卡会话关闭指令失败：${it.message}") }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ((!current.exited || current.supplicantRestored == null) && System.nanoTime() < deadline) {
                    val event = try {
                        current.events.poll(100, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                        Thread.interrupted()
                        continue
                    }
                    when (event) {
                        null -> Unit
                        is Event.Pipe -> when (event.value.optString("type")) {
                            "output" -> logLine(event.value.optString("text"), context)
                            "stopped" -> {
                                current.supplicantRestored = event.value.optBoolean("supplicantRestored")
                                context.log(if (current.supplicantRestored == true) {
                                    "网卡脚本已确认恢复系统 supplicant，正在退出终端"
                                } else {
                                    "网卡脚本结束，但未确认系统 supplicant 已恢复"
                                })
                            }
                            "error" -> context.log("网卡会话关闭事件：${event.value.optString("message")}")
                        }
                        is Event.Exit -> {
                            current.exited = true
                            context.log("网卡脚本会话已退出：exitCode=${event.code}")
                            break
                        }
                        is Event.Failure -> context.log("网卡事件 FIFO 读取失败：${event.error.message}")
                    }
                }
            }

            val terminalId = current.terminalId
            if (terminalId != null) {
                if (!current.exited) {
                    context.log("网卡脚本未在关闭等待期内退出，停止所属终端以触发 supplicant 守护恢复")
                }
                terminalManager.stopTerminalAndAwait(terminalId, "安全测试网卡会话结束")
                terminalStopped = true
            }
            if (!current.exited) {
                current.exited = true
            }
            // 恢复得到确认后才交还执行权；启动尚未创建终端时没有暂停过系统控制器。
            if (terminalId != null && current.supplicantRestored != true) {
                throw ConnectionCleanupException(IOException("未确认系统 supplicant 已恢复"))
            }
        } finally {
            try {
                if (terminalStopped) discardExitedSession(current)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun ensureSession(timeoutMillis: Long, context: TaskContext): Session {
        session?.let { current ->
            if (!current.exited) return current
            closeSession(context)
        }
        context.log("创建持久网卡脚本会话：iface=$interfaceName")
        context.updateProgress(TaskProgress.ConnectWifi(ConnectWifiStage.ROUTER_COMMUNICATION))
        val current = Session()
        session = current
        try {
            require(Process.myUid() == 0) { "安全测试网卡会话要求 Root 工作模式" }
            val rootfs = File(terminalManager.rootfsPathForFifo())
            val directory = File(rootfs, "tmp/wlantool-ipc/${current.sessionId}")
            check(directory.mkdirs()) { "无法创建安全测试 FIFO 目录：${directory.absolutePath}" }
            current.directory = directory
            Os.chmod(directory.absolutePath, 448)
            val commandFifo = File(directory, current.commandFifoId)
            val eventFifo = File(directory, current.eventFifoId)
            Os.mkfifo(commandFifo.absolutePath, 384)
            Os.mkfifo(eventFifo.absolutePath, 384)
            current.input = FileInputStream(Os.open(eventFifo.absolutePath, OsConstants.O_RDWR, 0))
            current.writer = FileOutputStream(Os.open(commandFifo.absolutePath, OsConstants.O_RDWR, 0))
                .writer(Charsets.UTF_8).buffered()
            current.readerThread = Thread({ readEvents(current) }, "security-audit-$interfaceName-fifo").also {
                it.isDaemon = true
                it.start()
            }
            val terminalId = terminalManager.createChrootTerminal(
                onExit = { _, code ->
                    current.exitCode = code
                    current.exited = true
                },
            )
            current.terminalId = terminalId
            context.log("安全测试网卡脚本终端已启动：terminalId=$terminalId iface=$interfaceName " +
                "commandFifoId=${current.commandFifoId} eventFifoId=${current.eventFifoId}")
            val commandPath = "/tmp/wlantool-ipc/${current.sessionId}/${current.commandFifoId}"
            val eventPath = "/tmp/wlantool-ipc/${current.sessionId}/${current.eventFifoId}"
            terminalManager.writeInput(
                terminalId,
                "exec python managed_connect.py --interface $interfaceName " +
                    "--command-pipe $commandPath --event-pipe $eventPath",
            )
            val readyDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                context.ensureRunning()
                check(System.nanoTime() < readyDeadline) { "等待安全测试网卡脚本 FIFO 会话准备超时" }
                when (val event = current.events.poll(100, TimeUnit.MILLISECONDS)) {
                    null -> Unit
                    is Event.Pipe -> when (event.value.optString("type")) {
                        "output" -> receiveLine(event.value.optString("text"), context, ConnectionAttemptResult.ERROR)
                        "ready" -> {
                            check(event.value.optInt("protocol") == 2) { "网卡脚本 FIFO 协议版本不匹配" }
                            context.log("安全测试网卡 FIFO 会话已就绪")
                            return current
                        }
                        "error" -> error("安全测试网卡 FIFO 会话启动失败：${event.value.optString("message")}")
                    }
                    is Event.Exit -> {
                        current.exited = true
                        error("安全测试网卡脚本准备阶段退出：${event.code}")
                    }
                    is Event.Failure -> throw event.error
                }
            }
        } catch (error: Throwable) {
            val interrupted = Thread.interrupted()
            try {
                // closeSession 成功停止终端后才释放 FIFO，失败则保留所有权供任务退出时重试。
                try { closeSession(context) }
                catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                    context.log("启动失败后的会话回收异常：${cleanup.message}")
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            throw error
        }
    }

    private fun readEvents(current: Session) {
        val input = requireNotNull(current.input)
        val pending = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val poll = StructPollfd().apply { fd = input.fd; events = OsConstants.POLLIN.toShort() }
        try {
            while (!current.readerStopping) {
                if (current.readerStopping) break
                if (Os.poll(arrayOf(poll), 200) <= 0) {
                    // 先读完退出前写出的结果/恢复确认，再派发退出，避免两个线程乱序。
                    if (current.exited && Os.poll(arrayOf(poll), 0) <= 0) {
                        current.events.offer(Event.Exit(current.exitCode))
                        break
                    }
                    continue
                }
                if (current.readerStopping) break
                if (poll.revents.toInt() and OsConstants.POLLIN == 0) {
                    if (!current.readerStopping) throw IOException("安全测试事件 FIFO 已关闭：${poll.revents}")
                    break
                }
                val count = Os.read(input.fd, buffer, 0, buffer.size)
                if (count <= 0) throw IOException("安全测试事件 FIFO 已结束")
                for (index in 0 until count) {
                    if (buffer[index] == 10.toByte()) {
                        val line = pending.toString("UTF-8").trim()
                        pending.reset()
                        if (line.isNotEmpty() && !current.readerStopping) {
                            current.events.offer(Event.Pipe(JSONObject(line)))
                        }
                    } else pending.write(buffer[index].toInt())
                }
            }
        } catch (error: Throwable) {
            if (!current.readerStopping) current.events.offer(Event.Failure(IOException("读取安全测试事件 FIFO 失败", error)))
        }
    }

    private fun receiveLine(
        line: String,
        context: TaskContext,
        previous: ConnectionAttemptResult,
    ): ConnectionAttemptResult {
        val text = if (line.endsWith(SCRIPT_READY)) SCRIPT_READY else line.trimStart()
        val result = parseResult(text, previous)
        if (LOG_PREFIXES.none { text.startsWith(it) }) return result
        context.log(text)
        val stage = when (text.trimEnd()) {
            "[*] Stage: Communicating with router" -> ConnectWifiStage.ROUTER_COMMUNICATION
            "[*] Stage: WPA handshake M1 (1/4)" -> ConnectWifiStage.WPA_HANDSHAKE_1_OF_4
            "[*] Stage: WPA handshake M2 (2/4)" -> ConnectWifiStage.WPA_HANDSHAKE_2_OF_4
            "[*] Stage: WPA handshake M3 (3/4)" -> ConnectWifiStage.WPA_HANDSHAKE_3_OF_4
            "[*] Stage: WPA handshake M4 (4/4)" -> ConnectWifiStage.WPA_HANDSHAKE_4_OF_4
            "[*] Stage: DHCP address acquisition" -> ConnectWifiStage.IP_NEGOTIATION
            else -> return result
        }
        context.updateProgress(TaskProgress.ConnectWifi(stage))
        return result
    }

    private fun discardExitedSession(current: Session) {
        current.readerStopping = true
        current.readerThread?.let { joinReader(it) }
        current.writer?.close()
        current.writer = null
        current.input?.close()
        current.input = null
        current.directory?.let { directory ->
            check(!directory.exists() || directory.deleteRecursively()) {
                "无法清理本次安全测试 FIFO 目录：${directory.absolutePath}"
            }
        }
        if (session === current) session = null
    }

    private fun joinReader(thread: Thread) {
        if (thread === Thread.currentThread()) return
        var interrupted = Thread.interrupted()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (thread.isAlive) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "安全测试 FIFO 读取线程尚未退出" }
                try { thread.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)) }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun logLine(line: String, context: TaskContext) {
        val text = line.trimStart()
        if (LOG_PREFIXES.any { text.startsWith(it) }) context.log(text)
    }

    private fun parseResult(text: String, previous: ConnectionAttemptResult): ConnectionAttemptResult = when {
        text == "[+] Diagnostic finished: success=true, cleanup completed=true" -> ConnectionAttemptResult.SUCCESS
        text.startsWith("[-] Association rejected") -> ConnectionAttemptResult.REJECTED
        text.startsWith("[-] Cleanup failed") -> ConnectionAttemptResult.ERROR
        text.startsWith("[-] Connectivity test failed:") -> when {
            text.contains("Password error") -> ConnectionAttemptResult.FAILED
            text.contains("Handshake timeout measured from the first M1") -> ConnectionAttemptResult.FAILED
            text.contains("TimeoutError") -> ConnectionAttemptResult.TIMEOUT
            text.contains("Handshake attempt limit exceeded") || text.contains("Password lines exhausted") -> ConnectionAttemptResult.FAILED
            text.contains("rejected access") || text.contains("AP rejected association") -> ConnectionAttemptResult.REJECTED
            text.contains("Driver reported association timeout") -> ConnectionAttemptResult.TIMEOUT
            text.contains("Association terminated") -> ConnectionAttemptResult.REJECTED
            previous == ConnectionAttemptResult.REJECTED -> previous
            else -> ConnectionAttemptResult.ERROR
        }
        else -> previous
    }

    private class Session {
        val sessionId = UUID.randomUUID().toString()
        val commandFifoId = UUID.randomUUID().toString()
        val eventFifoId = UUID.randomUUID().toString()
        val events = LinkedBlockingQueue<Event>()
        val commandLock = Any()
        var input: FileInputStream? = null
        var writer: java.io.BufferedWriter? = null
        var activeRequestId: String? = null
        @Volatile var supplicantRestored: Boolean? = null
        @Volatile var directory: File? = null
        @Volatile var terminalId: Long? = null
        @Volatile var exitCode = 0
        @Volatile var exited = false
        @Volatile var readerStopping = false
        @Volatile var readerThread: Thread? = null
    }

    private sealed interface Event {
        data class Pipe(val value: JSONObject) : Event
        data class Exit(val code: Int) : Event
        data class Failure(val error: IOException) : Event
    }

    private companion object {
        const val SCRIPT_READY = "[*] Managed Wi-Fi diagnostic ready (protocol=2)"
        val LOG_PREFIXES = listOf("[*] ", "[-] ", "[+] ")
        val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")
    }
}
