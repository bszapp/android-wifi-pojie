package io.github.bszapp.wifitoolbox.service

import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChannel
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** monitor 扫描专用进程；空闲只等待命令，结果仍由 Wi-Fi 控制器发布。 */
internal class MonitorWifiScanner(
    private val terminals: TerminalManager,
    private val onHoppingFailed: (Throwable) -> Unit,
) {
    private var terminalId: Long? = null
    private var ready: CompletableFuture<Unit>? = null
    private var pending: Scan? = null
    private var resumeRequest: ResumeRequest? = null
    private var nextRequestId = 0L
    private var hopping = false
    private var channels: List<MonitorChannel> = emptyList()

    @Synchronized
    fun availableChannels(): List<MonitorChannel> = channels

    @Synchronized
    fun initialize(): CompletableFuture<Unit> {
        if (terminalId != null) return requireNotNull(ready)
        val boot = CompletableFuture<Unit>()
        ready = boot
        val id = terminals.createChrootTerminal(
            onOutputLines = output@{ sourceId, lines ->
                if (synchronized(this) { terminalId != sourceId }) return@output
                lines.forEach { line ->
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    if (event.optString("action") == "ready") {
                        val list = event.getJSONArray("channels")
                        synchronized(this) {
                            channels = List(list.length()) { index ->
                                val item = list.getJSONObject(index)
                                MonitorChannel(item.getInt("channel"), item.getInt("frequencyMhz"))
                            }
                        }
                        boot.complete(Unit)
                        return@forEach
                    }
                    if (event.optString("action") == "resume_completed") {
                        val request = synchronized(this) {
                            resumeRequest?.takeIf { it.id == event.optLong("requestId", -1L) }?.also {
                                resumeRequest = null
                                if (event.optBoolean("ok")) hopping = it.plan == MonitorCapturePlan.Hopping
                            }
                        } ?: return@forEach
                        if (event.optBoolean("ok")) request.completion.complete(Unit)
                        else request.completion.completeExceptionally(IllegalStateException(event.optString("message")))
                        return@forEach
                    }
                    if (event.optString("action") == "hopping_failed") {
                        synchronized(this) { hopping = false }
                        onHoppingFailed(IllegalStateException(event.optString("message")))
                        return@forEach
                    }
                    val current = synchronized(this) { pending } ?: return@forEach
                    when (event.optString("action")) {
                        "start_scan_callback" -> {
                            if (event.optLong("requestId", -1L) != current.id) return@forEach
                            if (event.optBoolean("ok")) current.confirmation.complete(Unit)
                            else current.confirmation.completeExceptionally(IllegalStateException(event.optString("message")))
                        }
                        "scan_completed" -> {
                            if (event.optLong("requestId", -1L) != current.id) return@forEach
                            val restore = synchronized(this) {
                                if (pending !== current || current.scanCode != null) false
                                else { current.scanCode = event.getInt("code"); true }
                            }
                            if (restore) {
                                try {
                                    current.restore().whenComplete { _, error ->
                                        finish(current, requireNotNull(current.scanCode), error)
                                    }
                                } catch (error: Throwable) {
                                    finish(current, requireNotNull(current.scanCode), error)
                                }
                            }
                        }
                        else -> current.onMessage(event)
                    }
                }
            },
            onExit = { exited, code ->
                val current = synchronized(this) {
                    if (terminalId == exited) { terminalId = null; ready = null; true } else false
                }
                if (current) {
                    boot.completeExceptionally(IllegalStateException("monitor 扫描进程已退出，退出码 $code"))
                    val request = synchronized(this) {
                        channels = emptyList()
                        resumeRequest.also { resumeRequest = null }
                    }
                    val error = IllegalStateException("monitor 扫描进程已退出，退出码 $code")
                    request?.completion?.completeExceptionally(error)
                    val wasHopping = synchronized(this) { hopping.also { hopping = false } }
                    val scan = synchronized(this) { pending }
                    // 执行中的扫描或恢复请求各自负责错误收尾，避免重复触发停止流程。
                    if (wasHopping && scan == null && request == null) onHoppingFailed(error)
                    scan?.let { finish(it, it.scanCode ?: code, error) }
                }
            },
        )
        terminalId = id
        try {
            terminals.writeInput(id, "exec python3 /wlantool/monitor_scan.py -i wlan0 --duration 3 --listen")
        } catch (error: Throwable) {
            terminalId = null
            ready = null
            boot.completeExceptionally(error)
            terminals.stopTerminal(id, reason = "monitor 扫描进程初始化失败")
            throw error
        }
        return boot
    }

    @Synchronized
    fun start(
        onMessage: (JSONObject) -> Unit,
        restore: () -> CompletableFuture<Unit>,
        onFinished: (Int, Throwable?) -> Unit,
    ): CompletableFuture<Unit> {
        check(pending == null && resumeRequest == null) { "monitor 操作正在运行" }
        val scan = Scan(++nextRequestId, CompletableFuture(), onMessage, restore, onFinished)
        pending = scan
        try {
            initialize()
            terminals.writeInput(requireNotNull(terminalId), JSONObject()
                .put("action", "scan").put("requestId", scan.id).toString())
        } catch (error: Throwable) {
            pending = null
            scan.confirmation.completeExceptionally(error)
            throw error
        }
        return scan.confirmation
    }

    @Synchronized
    fun resume(plan: MonitorCapturePlan): CompletableFuture<Unit> {
        check((pending == null || pending?.scanCode != null) && resumeRequest == null) { "monitor 操作正在执行" }
        if (plan is MonitorCapturePlan.Fixed) {
            require(channels.any { it.frequencyMhz == plan.frequencyMhz }) { "所选信道不可用" }
        }
        val request = ResumeRequest(++nextRequestId, plan, CompletableFuture())
        resumeRequest = request
        try {
            terminals.writeInput(requireNotNull(terminalId), JSONObject()
                .put("action", "resume").put("requestId", request.id)
                .put("capturePlan", plan.toJson()).toString())
        } catch (error: Throwable) {
            resumeRequest = null
            request.completion.completeExceptionally(error)
        }
        return request.completion
    }

    private fun finish(scan: Scan, code: Int, error: Throwable?) {
        synchronized(this) {
            if (pending !== scan) return
            pending = null
        }
        if (!scan.confirmation.isDone) {
            scan.confirmation.completeExceptionally(IllegalStateException("monitor 扫描未能启动，退出码 $code"))
        }
        scan.onFinished(code, error)
    }

    fun stop() {
        val (id, futures, scan) = synchronized(this) {
            Triple(terminalId, listOfNotNull(ready, resumeRequest?.completion), pending).also {
                terminalId = null
                ready = null
                resumeRequest = null
                pending = null
                hopping = false
                channels = emptyList()
            }
        }
        val error = IllegalStateException("monitor 扫描进程已停止")
        futures.forEach { it.completeExceptionally(error) }
        scan?.let {
            it.confirmation.completeExceptionally(error)
            it.onFinished(it.scanCode ?: 1, error)
        }
        if (id != null) terminals.stopTerminal(id, reason = "退出 monitor 模式，停止扫描进程")
    }

    private data class Scan(
        val id: Long,
        val confirmation: CompletableFuture<Unit>,
        val onMessage: (JSONObject) -> Unit,
        val restore: () -> CompletableFuture<Unit>,
        val onFinished: (Int, Throwable?) -> Unit,
        var scanCode: Int? = null,
    )

    private data class ResumeRequest(
        val id: Long,
        val plan: MonitorCapturePlan,
        val completion: CompletableFuture<Unit>,
    )
}
