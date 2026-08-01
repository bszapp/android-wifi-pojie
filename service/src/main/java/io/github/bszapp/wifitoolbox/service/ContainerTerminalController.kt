package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal class ContainerTerminalController(
    private val terminalManager: TerminalManager,
    private val publishEvent: (String) -> Unit,
) {
    private val lock = Any()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "container-terminal").apply { isDaemon = true }
    }

    private var status = STATUS_STOPPED
    private var statusMessage = "扫描终端未启动"
    private var terminalId: Long? = null
    private var commandWriter: BufferedWriter? = null
    private var eventInput: FileInputStream? = null
    private var pipeDirectory: File? = null
    private var readySignal: CountDownLatch? = null
    private var startupFuture: CompletableFuture<Unit>? = null
    private var currentRequestId: String? = null
    private var currentScanConfirmation: CompletableFuture<Unit>? = null
    private var currentScanMessageHandler: ((JSONObject) -> Unit)? = null
    private var currentScanFinishedHandler: ((Int) -> Unit)? = null
    private val currentScanOutput = StringBuilder()
    private var stopping = false

    fun snapshotJson(): String = synchronized(lock) {
        stateEvent(status, statusMessage).toString()
    }

    fun start(
        rootfsPath: String,
        runtimePath: String,
        terminalPath: String,
    ): CompletableFuture<Unit> {
        val future = synchronized(lock) {
            if (status == STATUS_READY) return CompletableFuture.completedFuture(Unit)
            if (status == STATUS_STARTING) {
                return startupFuture
                    ?: CompletableFuture<Unit>().apply {
                        completeExceptionally(
                            IllegalStateException("扫描终端启动状态缺少确认对象"),
                        )
                    }
            }
            CompletableFuture<Unit>().also {
                startupFuture = it
                status = STATUS_STARTING
                statusMessage = "正在初始化通信管道"
                stopping = false
            }
        }
        publishState()

        executor.execute {
            try {
                require(Process.myUid() == 0) { "Chroot 扫描终端要求 Root 工作模式" }
                val rootfs = File(rootfsPath)
                val runtime = File(runtimePath)
                val terminal = File(terminalPath)
                require(rootfsShellPresent(rootfs)) { "容器系统尚未安装" }
                require(terminal.isFile && terminal.canExecute()) {
                    "libterminal.so 不可执行: ${terminal.absolutePath}"
                }
                runtime.mkdirs()

                val pipes = preparePipes(rootfs)
                val eventStream = FileInputStream(
                    Os.open(pipes.eventPipe.absolutePath, OsConstants.O_RDWR, 0),
                )
                val writer = FileOutputStream(
                    Os.open(pipes.commandPipe.absolutePath, OsConstants.O_RDWR, 0),
                ).bufferedWriter(Charsets.UTF_8)
                val latch = CountDownLatch(1)
                synchronized(lock) {
                    eventInput = eventStream
                    commandWriter = writer
                    readySignal = latch
                    statusMessage = "正在启动 Chroot 扫描终端"
                }
                publishState()
                executor.execute { readEvents(eventStream) }

                val createdTerminalId = terminalManager.createTerminal(
                    command = listOf(
                        terminal.absolutePath,
                        "session",
                        "chroot",
                        "--rootfs",
                        rootfs.absolutePath,
                        "--runtime",
                        runtime.absolutePath,
                        "--host-path",
                        HOST_TOOL_PATH,
                    ),
                    onExit = ::onTerminalExited,
                )
                val stopImmediately = synchronized(lock) {
                    if (stopping) {
                        true
                    } else {
                        terminalId = createdTerminalId
                        statusMessage = "正在启动通信程序"
                        false
                    }
                }
                if (stopImmediately) {
                    terminalManager.stopTerminal(
                        createdTerminalId,
                        reason = "扫描终端启动期间收到停止请求",
                    )
                    return@execute
                }
                publishState()
                terminalManager.writeInput(createdTerminalId, BRIDGE_COMMAND)

                if (!latch.await(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    throw IOException("等待通信程序就绪超时")
                }
            } catch (error: Throwable) {
                future.completeExceptionally(error)
                fail(error.message ?: error.javaClass.simpleName)
                stopInternal(
                    publishStopped = false,
                    stopReason = "扫描终端启动失败清理",
                )
            }
        }
        return future
    }

    fun runWifiScan(
        onMessage: (JSONObject) -> Unit = {},
        onFinished: (Int) -> Unit = {},
    ): CompletableFuture<Unit> {
        val confirmation = CompletableFuture<Unit>()
        val requestId = synchronized(lock) {
            check(status == STATUS_READY) { "扫描终端尚未就绪" }
            check(currentRequestId == null) { "Wi-Fi 扫描正在执行" }
            UUID.randomUUID().toString().also {
                currentRequestId = it
                currentScanConfirmation = confirmation
                currentScanMessageHandler = onMessage
                currentScanFinishedHandler = onFinished
                currentScanOutput.setLength(0)
            }
        }
        publish(
            JSONObject()
                .put("type", "scan_reset")
                .put("requestId", requestId),
        )
        try {
            writeRequest(
                JSONObject()
                    .put("type", "run")
                    .put("requestId", requestId)
                    .put("script", SCAN_SCRIPT)
                    .put("args", JSONArray(listOf("-i", "wlan0", "-scan"))),//TODO:我不喜欢加上-scan才能扫描，删了这个参数，包括脚本本身
            )
        } catch (error: Throwable) {
            synchronized(lock) {
                currentRequestId = null
                currentScanConfirmation = null
                currentScanMessageHandler = null
                currentScanFinishedHandler = null
                currentScanOutput.setLength(0)
            }
            confirmation.completeExceptionally(error)
            publish(
                JSONObject()
                    .put("type", "error")
                    .put("requestId", requestId)
                    .put("message", error.message ?: error.javaClass.simpleName),
            )
            throw error
        }
        return confirmation
    }

    fun stop() {
        synchronized(lock) {
            if (status == STATUS_STOPPED) return
            status = STATUS_STOPPING
            statusMessage = "正在停止扫描终端"
        }
        publishState()
        stopInternal(
            publishStopped = true,
            stopReason = "应用请求停止扫描终端",
        )
    }

    fun close() {
        stopInternal(
            publishStopped = false,
            stopReason = "服务关闭",
        )
        executor.shutdownNow()
    }

    private fun preparePipes(rootfs: File): PipeFiles {
        val tmp = File(rootfs, "tmp")
        if (!tmp.isDirectory && !tmp.mkdirs()) {
            throw IOException("无法创建 rootfs/tmp: ${tmp.absolutePath}")
        }
        val directory = File(tmp, PIPE_DIRECTORY_NAME)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("无法清理旧通信目录: ${directory.absolutePath}")
        }
        if (!directory.mkdirs()) {
            throw IOException("无法创建通信目录: ${directory.absolutePath}")
        }
        Os.chmod(directory.absolutePath, 448)
        val commandPipe = File(directory, COMMAND_PIPE_NAME)
        val eventPipe = File(directory, EVENT_PIPE_NAME)
        Os.mkfifo(commandPipe.absolutePath, 384)
        Os.mkfifo(eventPipe.absolutePath, 384)
        synchronized(lock) { pipeDirectory = directory }
        return PipeFiles(commandPipe, eventPipe)
    }

    private fun rootfsShellPresent(rootfs: File): Boolean {
        return runCatching {
            val type = Os.lstat(File(rootfs, "bin/sh").absolutePath).st_mode and OsConstants.S_IFMT
            type == OsConstants.S_IFREG || type == OsConstants.S_IFLNK
        }.getOrDefault(false)
    }

    private fun readEvents(input: FileInputStream) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) handleBridgeEvent(JSONObject(line))
                }
            }
            if (!synchronized(lock) { stopping }) {
                throw IOException("通信程序已关闭事件管道")
            }
        } catch (error: Throwable) {
            if (!synchronized(lock) { stopping }) {
                fail(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun handleBridgeEvent(event: JSONObject) {
        when (event.optString("type")) {
            "ready" -> {
                val future = synchronized(lock) {
                    status = STATUS_READY
                    statusMessage = "扫描终端已启动"
                    readySignal?.countDown()
                    startupFuture.also { startupFuture = null }
                }
                future?.complete(Unit)
                publishState()
            }
            "started" -> publish(event)
            "output" -> {
                publish(event)
                handleScanOutput(
                    requestId = event.optString("requestId"),
                    text = event.optString("text"),
                )
            }
            "finished", "error" -> {
                val requestId = event.optString("requestId")
                val completion = synchronized(lock) {
                    if (requestId == currentRequestId) {
                        currentRequestId = null
                        ScanCompletion(
                            confirmation = currentScanConfirmation,
                            onFinished = currentScanFinishedHandler,
                        ).also {
                            currentScanConfirmation = null
                            currentScanMessageHandler = null
                            currentScanFinishedHandler = null
                            currentScanOutput.setLength(0)
                        }
                    } else {
                        null
                    }
                }
                if (completion != null) {
                    val exitCode = if (event.optString("type") == "finished") {
                        event.optInt("exitCode")
                    } else {
                        -1
                    }
                    if (completion.confirmation?.isDone == false) {
                        completion.confirmation.completeExceptionally(
                            IOException(
                                if (exitCode >= 0) {
                                    "扫描脚本退出前没有返回 start_scan_callback，退出码 $exitCode"
                                } else {
                                    event.optString("message", "扫描通信失败")
                                },
                            ),
                        )
                    }
                    runCatching { completion.onFinished?.invoke(exitCode) }
                    publish(event)
                }
            }
            "stopped" -> Unit
        }
    }

    private fun handleScanOutput(requestId: String, text: String) {
        val lines = synchronized(lock) {
            if (requestId != currentRequestId) return
            currentScanOutput.append(text)
            buildList {
                while (true) {
                    val newline = currentScanOutput.indexOf("\n")
                    if (newline < 0) break
                    add(currentScanOutput.substring(0, newline).trimEnd('\r'))
                    currentScanOutput.delete(0, newline + 1)
                }
            }
        }
        lines.filter(String::isNotBlank).forEach { line ->
            val payload = try {
                JSONObject(line)
            } catch (error: Throwable) {
                failCurrentScan(IOException("扫描脚本返回了无效 JSON: $line", error))
                return
            }
            val callback = synchronized(lock) {
                if (requestId == currentRequestId) currentScanMessageHandler else null
            } ?: return
            handleScanCallback(payload)
            runCatching { callback(payload) }
                .onFailure { failCurrentScan(it) }
        }
    }

    private fun handleScanCallback(payload: JSONObject) {
        if (payload.optString("action") != "start_scan_callback") return
        val confirmation = synchronized(lock) { currentScanConfirmation } ?: return
        if (payload.optBoolean("ok")) {
            confirmation.complete(Unit)
        } else {
            confirmation.completeExceptionally(
                IllegalStateException(
                    payload.optString("message", "扫描请求未被接受"),
                ),
            )
        }
    }

    private fun failCurrentScan(error: Throwable) {
        val confirmation = synchronized(lock) { currentScanConfirmation }
        confirmation?.completeExceptionally(error)
    }

    private fun onTerminalExited(exitedTerminalId: Long, exitCode: Int) {
        val unexpected = synchronized(lock) {
            terminalId == exitedTerminalId && !stopping
        }
        if (!unexpected) return
        executor.execute {
            fail("扫描终端已结束，退出码 $exitCode")
            stopInternal(
                publishStopped = false,
                stopReason = "扫描终端已经自行退出后的资源清理",
            )
        }
    }

    private fun writeRequest(request: JSONObject) {
        synchronized(lock) {
            val writer = commandWriter ?: throw IOException("命令管道尚未连接")
            writer.write(request.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun stopInternal(
        publishStopped: Boolean,
        stopReason: String,
    ) {
        val resources = synchronized(lock) {
            stopping = true
            runCatching {
                commandWriter?.apply {
                    write(JSONObject().put("type", "shutdown").toString())
                    newLine()
                    flush()
                }
            }
            Resources(
                terminalId = terminalId,
                writer = commandWriter,
                eventInput = eventInput,
                pipeDirectory = pipeDirectory,
                startupFuture = startupFuture,
                scanConfirmation = currentScanConfirmation,
                scanFinished = currentScanFinishedHandler,
            ).also {
                terminalId = null
                commandWriter = null
                eventInput = null
                pipeDirectory = null
                readySignal?.countDown()
                readySignal = null
                startupFuture = null
                currentRequestId = null
                currentScanConfirmation = null
                currentScanMessageHandler = null
                currentScanFinishedHandler = null
                currentScanOutput.setLength(0)
            }
        }

        val stoppedError = IOException(stopReason)
        resources.startupFuture?.completeExceptionally(stoppedError)
        resources.scanConfirmation?.completeExceptionally(stoppedError)
        runCatching { resources.scanFinished?.invoke(-1) }

        resources.terminalId?.let { terminalId ->
            terminalManager.stopTerminal(terminalId, reason = stopReason)
        }
        runCatching { resources.writer?.close() }
        runCatching { resources.eventInput?.close() }
        resources.pipeDirectory?.let { runCatching { it.deleteRecursively() } }

        synchronized(lock) {
            status = STATUS_STOPPED
            statusMessage = "扫描终端已停止"
            stopping = false
        }
        if (publishStopped) publishState()
    }

    private fun fail(message: String) {
        val startup = synchronized(lock) {
            status = STATUS_ERROR
            statusMessage = message
            readySignal?.countDown()
            startupFuture.also { startupFuture = null }
        }
        startup?.completeExceptionally(IOException(message))
        publishState()
    }

    private fun publishState() {
        val event = synchronized(lock) { stateEvent(status, statusMessage) }
        publish(event)
    }

    private fun stateEvent(status: String, message: String): JSONObject =
        JSONObject()
            .put("type", "state")
            .put("status", status)
            .put("message", message)

    private fun publish(event: JSONObject) {
        publishEvent(event.toString())
    }

    private data class PipeFiles(
        val commandPipe: File,
        val eventPipe: File,
    )

    private data class Resources(
        val terminalId: Long?,
        val writer: BufferedWriter?,
        val eventInput: FileInputStream?,
        val pipeDirectory: File?,
        val startupFuture: CompletableFuture<Unit>?,
        val scanConfirmation: CompletableFuture<Unit>?,
        val scanFinished: ((Int) -> Unit)?,
    )

    private data class ScanCompletion(
        val confirmation: CompletableFuture<Unit>?,
        val onFinished: ((Int) -> Unit)?,
    )

    private companion object {
        const val STATUS_STOPPED = "STOPPED"
        const val STATUS_STARTING = "STARTING"
        const val STATUS_READY = "READY"
        const val STATUS_STOPPING = "STOPPING"
        const val STATUS_ERROR = "ERROR"
        const val PIPE_DIRECTORY_NAME = "wlantool-ipc"
        const val COMMAND_PIPE_NAME = "commands.fifo"//TODO:以后要是多个管道怎么办？
        const val EVENT_PIPE_NAME = "events.fifo"
        const val SCAN_SCRIPT = "/wlantool/scan.py"
        const val HOST_TOOL_PATH = "/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/odm/bin:/apex/com.android.runtime/bin"
        const val BRIDGE_COMMAND = "/usr/bin/python3 -u /wlantool/terminal_bridge.py --command-pipe /tmp/wlantool-ipc/commands.fifo --event-pipe /tmp/wlantool-ipc/events.fifo"
        const val START_TIMEOUT_MILLIS = 20_000L
    }
}
