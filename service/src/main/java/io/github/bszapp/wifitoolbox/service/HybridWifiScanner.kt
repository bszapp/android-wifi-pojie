package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.container.isContainerSystemInstalled
import java.io.ByteArrayOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** 底层扫描作用域的终端与 FIFO；stop 返回时所有所属线程和终端均已结束。 */
internal class HybridWifiScanner(private val terminalManager: TerminalManager) {
    private val lock = Any()
    private val stopLock = Any()
    private var session: Session? = null
    @Volatile private var unexpectedExitHandler: ((Int) -> Unit)? = null

    fun setUnexpectedExitHandler(handler: ((Int) -> Unit)?) {
        unexpectedExitHandler = handler
    }

    fun start(): CompletableFuture<Unit> = synchronized(lock) {
        session?.let { return@synchronized it.ready }
        val current = Session()
        session = current
        current.startThread = Thread({
            try {
                require(Process.myUid() == 0) { "Chroot 扫描终端要求 Root 工作模式" }
                val rootfs = File(terminalManager.rootfsPathForFifo())
                require(isContainerSystemInstalled(rootfs)) { "容器系统尚未安装" }
                checkRunning(current)
                val directory = File(rootfs, "tmp/wlantool-ipc/${current.id}")
                check(directory.mkdirs()) { "无法创建扫描通信目录: $directory" }
                current.directory = directory
                Os.chmod(directory.absolutePath, 448)
                //TODO:以后要是多个管道怎么办？
                val commands = File(directory, current.commandFifoId)
                val events = File(directory, current.eventFifoId)
                Os.mkfifo(commands.absolutePath, 384)
                Os.mkfifo(events.absolutePath, 384)
                current.input = FileInputStream(Os.open(events.absolutePath, OsConstants.O_RDWR, 0))
                current.writer = FileOutputStream(Os.open(commands.absolutePath, OsConstants.O_RDWR, 0))
                    .bufferedWriter(Charsets.UTF_8)
                checkRunning(current)
                current.readerThread = Thread({ readEvents(current) }, "underlying-scan-fifo").also { it.start() }
                val id = terminalManager.createChrootTerminal(onExit = { terminalId, code ->
                    try {
                        if (!current.stopping && isCurrent(current)) {
                            current.ready.completeExceptionally(IOException("扫描终端 $terminalId 已退出: $code"))
                            unexpectedExitHandler?.invoke(code)
                        }
                    } finally { current.terminalExited.countDown() }
                })
                current.terminalId = id
                checkRunning(current)
                terminalManager.writeInput(id,
                    "exec /usr/bin/python3 -u /wlantool/terminal_bridge.py " +
                        "--command-pipe /tmp/wlantool-ipc/${current.id}/${current.commandFifoId} " +
                        "--event-pipe /tmp/wlantool-ipc/${current.id}/${current.eventFifoId}")
            } catch (error: Throwable) {
                current.ready.completeExceptionally(error)
                // 资源统一由拥有该会话的扫描作用域 finally 释放。
            } finally {
                if (current.stopping) current.terminalId?.let {
                    runCatching { terminalManager.stopTerminalAndAwait(it, "扫描创建期间作用域已结束") }
                        .onFailure { error -> Log.e("HybridWifiScanner", "回收延迟创建的终端失败", error) }
                }
            }
        }, "underlying-scan-start").also { it.start() }
        current.ready
    }

    fun readPreviousResults(onMessage: (JSONObject) -> Unit): CompletableFuture<Unit> =
        submitRequest(true, onMessage, {})

    fun runWifiScan(
        onMessage: (JSONObject) -> Unit,
        onFinished: (Int) -> Unit,
    ): CompletableFuture<Unit> = submitRequest(false, onMessage, onFinished)

    private fun submitRequest(
        readOnly: Boolean,
        onMessage: (JSONObject) -> Unit,
        onFinished: (Int) -> Unit,
    ): CompletableFuture<Unit> {
        val current: Session
        val request = Request(UUID.randomUUID().toString(), readOnly, onMessage, onFinished)
        synchronized(lock) {
            current = requireNotNull(session) { "扫描终端不存在" }
            checkRunning(current)
            check(current.ready.isDone && !current.ready.isCompletedExceptionally) { "扫描终端尚未就绪" }
            check(current.request == null) { "底层扫描正在执行" }
            current.request = request
        }
        try {
            val args = listOf("-i", "wlan0") + if (readOnly) listOf("--read-results") else emptyList()
            //TODO:我不喜欢加上-scan才能扫描，删了这个参数，包括脚本本身
            writeRequest(current, JSONObject().put("type", "run").put("requestId", request.id)
                .put("script", "/wlantool/scan.py").put("args", JSONArray(args)))
        } catch (error: Throwable) {
            synchronized(lock) { if (current.request === request) current.request = null }
            request.confirmation.completeExceptionally(error)
        }
        return request.confirmation
    }

    fun stop() = synchronized(stopLock) {
        val current = synchronized(lock) {
            session?.also { it.stopping = true }
        } ?: return@synchronized
        val error = IOException("底层扫描作用域结束")
        current.ready.completeExceptionally(error)
        current.request?.confirmation?.completeExceptionally(error)
        // 先等待创建线程归还所有资源，禁止 stop 后继续创建终端/FIFO。
        joinOwnedThread(current.startThread)
        current.terminalId?.let {
            terminalManager.stopTerminalAndAwait(it, "底层扫描作用域结束")
            check(current.terminalExited.await(5, TimeUnit.SECONDS)) { "扫描终端退出回调尚未结束" }
        }
        // reader 使用短 poll，不依赖跨线程 close 能否打断阻塞 read。
        joinOwnedThread(current.readerThread)
        current.writer?.close()
        current.input?.close()
        current.directory?.let {
            check(!it.exists() || it.deleteRecursively()) { "无法清理扫描 FIFO 目录: $it" }
        }
        synchronized(lock) {
            if (session === current) session = null
        }
    }

    fun close() = stop()

    private fun readEvents(current: Session) {
        val input = requireNotNull(current.input)
        val pending = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val poll = StructPollfd().apply { fd = input.fd; events = OsConstants.POLLIN.toShort() }
        try {
            while (!current.stopping) {
                if (Os.poll(arrayOf(poll), 200) <= 0 || current.stopping) continue
                if (poll.revents.toInt() and OsConstants.POLLIN == 0) {
                    throw IOException("扫描 FIFO 已关闭: ${poll.revents}")
                }
                val count = Os.read(input.fd, buffer, 0, buffer.size)
                if (count <= 0) throw IOException("扫描 FIFO 已结束")
                for (index in 0 until count) {
                    if (buffer[index] == 10.toByte()) {
                        val line = pending.toString("UTF-8").trim()
                        pending.reset()
                        if (line.isNotEmpty() && !current.stopping) handleEvent(current, JSONObject(line))
                    } else pending.write(buffer[index].toInt())
                }
            }
        } catch (error: Throwable) {
            if (!current.stopping && isCurrent(current)) {
                current.ready.completeExceptionally(error)
                current.request?.confirmation?.completeExceptionally(error)
                Log.e("HybridWifiScanner", "扫描 FIFO 读取失败", error)
                unexpectedExitHandler?.invoke(-1)
            }
        }
    }

    private fun handleEvent(current: Session, event: JSONObject) {
        if (current.stopping || !isCurrent(current)) return
        when (event.optString("type")) {
            "ready" -> current.ready.complete(Unit)
            "output" -> {
                val request = synchronized(lock) {
                    current.request?.takeIf { it.id == event.optString("requestId") }
                } ?: return
                request.output.append(event.optString("text"))
                while (!current.stopping) {
                    val newline = request.output.indexOf("\n")
                    if (newline < 0) break
                    val line = request.output.substring(0, newline).trim()
                    request.output.delete(0, newline + 1)
                    if (line.isBlank()) continue
                    val payload = JSONObject(line)
                    if (payload.optString("action") in listOf("start_scan_callback", "read_results_callback")) {
                        if (payload.optBoolean("ok")) {
                            if (!request.readOnly) request.confirmation.complete(Unit)
                        }
                        else request.confirmation.completeExceptionally(IOException(payload.optString("message")))
                    }
                    if (!current.stopping) request.onMessage(payload)
                }
            }
            "finished", "error" -> {
                val request = synchronized(lock) {
                    current.request?.takeIf { it.id == event.optString("requestId") }?.also { current.request = null }
                } ?: return
                val code = if (event.optString("type") == "finished") event.optInt("exitCode") else -1
                if (request.readOnly && code == 0) request.confirmation.complete(Unit)
                else request.confirmation.completeExceptionally(IOException(
                    event.optString("message", "扫描脚本在请求确认前退出: $code")))
                if (!current.stopping) request.onFinished(code)
            }
        }
    }

    private fun writeRequest(current: Session, request: JSONObject) {
        val writer = requireNotNull(current.writer)
        synchronized(writer) {
            checkRunning(current)
            writer.write(request.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun checkRunning(current: Session) {
        check(!current.stopping && isCurrent(current)) { "扫描作用域已结束" }
    }

    private fun isCurrent(current: Session): Boolean = synchronized(lock) { session === current }

    private fun joinOwnedThread(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        thread.join(25_000)
        check(!thread.isAlive) { "扫描资源线程未结束: ${thread.name}" }
    }

    private class Session {
        val id = UUID.randomUUID().toString()
        val commandFifoId = UUID.randomUUID().toString()
        val eventFifoId = UUID.randomUUID().toString()
        val ready = CompletableFuture<Unit>()
        val terminalExited = CountDownLatch(1)
        @Volatile var stopping = false
        var startThread: Thread? = null
        var readerThread: Thread? = null
        var terminalId: Long? = null
        var directory: File? = null
        var writer: BufferedWriter? = null
        var input: FileInputStream? = null
        @Volatile var request: Request? = null
    }

    private class Request(
        val id: String,
        val readOnly: Boolean,
        val onMessage: (JSONObject) -> Unit,
        val onFinished: (Int) -> Unit,
    ) {
        val confirmation = CompletableFuture<Unit>()
        val output = StringBuilder()
    }
}
