package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.service.TerminalManager
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 任务及网卡归服务所有，USB 生命周期与恢复确认由脚本负责。 */
internal class UsbMonitorTask(
    private val terminals: TerminalManager,
    private val acquireRadio: () -> AutoCloseable,
) : ServiceTask {
    override fun run(context: TaskContext) {
        context.ensureRunning()
        val radio = acquireRadio()
        val events = LinkedBlockingQueue<Event>(512)
        var terminalId: Long? = null
        var exited = false
        var changedUsb = false
        var restored = false
        var failure: String? = null
        var interrupted = false
        fun handle(line: String) {
            context.log(line)
            val event = runCatching { JSONObject(line.substringAfter("{", "").let { "{$it" }) }
                .getOrNull() ?: return
            when (event.optString("event")) {
                "usb_bound" -> changedUsb = true
                "peer_state" -> context.updateProgress(TaskProgress.UsbMonitor(
                    usbConnected = event.getBoolean("usbConnected"),
                    clientConnected = event.getBoolean("clientConnected"),
                    capturing = event.getBoolean("capturing"),
                ))
                "session_error", "fatal", "reader_error", "writer_error", "packet_error" ->
                    failure = event.optString("error")
                "usb_restored" -> restored = event.optBoolean("verified") &&
                    event.optJSONArray("errors")?.length() == 0
            }
        }
        try {
            context.ensureRunning()
            context.log("任务类型：电脑控制；USB 设备名称：Wifitoolbox Network Driver")
            context.log("应用扫描、跳频和录制已暂停，原有抓包数据保留")
            terminalId = terminals.createChrootTerminal(
                onOutputLines = { _, lines -> lines.forEach { events.put(Event.Line(it)) } },
                onExit = { _, code -> events.put(Event.Exit(code)) },
            )
            terminals.writeInput(terminalId,
                "stty -echo -icanon min 1 time 0 && exec python3 /wlantool/usb_monitor.py --service --seconds 0")
            while (true) {
                context.ensureRunning()
                when (val event = events.poll(100, TimeUnit.MILLISECONDS)) {
                    null -> Unit
                    is Event.Line -> handle(event.text)
                    is Event.Exit -> {
                        exited = true
                        check(event.code == 0 && failure == null && (!changedUsb || restored)) {
                            failure ?: "电脑控制进程退出：${event.code}；USB 恢复确认=$restored"
                        }
                        context.log("电脑控制已结束，USB 已恢复")
                        return
                    }
                }
            }
        } catch (stop: InterruptedException) {
            interrupted = true
            Thread.interrupted()
            throw stop
        } finally {
            try {
                terminalId?.let { id ->
                    if (!exited) {
                        context.log("正在停止电脑控制并恢复原 USB 配置")
                        runCatching { terminals.writeInput(id, "{\"command\":\"stop\"}") }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (!exited && System.nanoTime() < deadline) {
                            val event = try { events.poll(100, TimeUnit.MILLISECONDS) }
                            catch (_: InterruptedException) {
                                interrupted = true
                                Thread.interrupted()
                                continue
                            }
                            when (event) {
                                null -> Unit
                                is Event.Line -> handle(event.text)
                                is Event.Exit -> exited = true
                            }
                        }
                        if (!exited) {
                            // 结束监督终端会关闭停止管道，独立恢复进程仍能执行还原。
                            terminals.stopTerminal(id, "电脑控制监督终端未响应停止")
                        }
                        check(exited && (!changedUsb || restored)) {
                            "电脑控制未能确认 USB 恢复，请检查 USB 连接及任务日志"
                        }
                        context.log("原 USB 配置已恢复")
                    }
                }
            } finally {
                radio.close()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private sealed interface Event {
        data class Line(val text: String) : Event
        data class Exit(val code: Int) : Event
    }
}
