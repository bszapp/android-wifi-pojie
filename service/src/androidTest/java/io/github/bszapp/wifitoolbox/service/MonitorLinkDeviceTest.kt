package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.os.SystemClock
import android.util.Base64
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.contract.container.ContainerEnvironment
import io.github.bszapp.wifitoolbox.service.container.ContainerMountManager
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** app_process 设备测试：复用服务执行器与接收开关，不启动统计或清理已有抓包数据。 */
object MonitorLinkDeviceTest {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 5) { "rootfs runtime libterminal.so base64进入预设 测试组" }
        require(Process.myUid() == 0)
        lateinit var terminals: TerminalManager
        val mounts = ContainerMountManager({ pid -> terminals.stopChrootTerminals(pid) },
            { error -> println("MOUNT_ERROR $error") })
        mounts.refresh(ContainerEnvironment(File(args[0]).parentFile!!.absolutePath, args[2]))
        terminals = TerminalManager(mounts, {}, { range ->
            if (range.lineCount > 0) runCatching {
                terminals.getLogs(range.terminalId, range.latestId, range.latestId).entries
                    .forEach { println("TERMINAL ${range.terminalId}: ${it.text}") }
            }
        })
        val hybrid = HybridWifiScanner(terminals)
        val controller = WifiListController(
            { null }, hybrid, terminals, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
            { operation, error -> println("SERVICE_ERROR $operation: $error") },
        )
        val script = WifiListController::class.java.declaredMethods.single { it.name == "runChrootTerminalScript" }
            .apply { isAccessible = true }
        val link = WifiListController::class.java.getDeclaredMethod("setInterfaceUp", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val abort = WifiListController::class.java.getDeclaredMethod("stopModeTransition")
            .apply { isAccessible = true }
        val exitCommand = WifiListController::class.java.getDeclaredField("MONITOR_EXIT_COMMAND")
            .apply { isAccessible = true }.get(null) as String
        val entryCommand = String(Base64.decode(args[3], Base64.DEFAULT), Charsets.UTF_8)
        val start = SystemClock.elapsedRealtime()
        fun report(text: String) = println("TEST +${SystemClock.elapsedRealtime() - start}ms $text")
        fun snapshot(): String {
            val mode = runCatching { WirelessInterfaceNetlink.readMode().name }
                .getOrElse { "ERROR:${it.message}" }
            val flags = runCatching { File("/sys/class/net/wlan0/flags").readText().trim() }.getOrElse { "missing" }
            val index = runCatching { File("/sys/class/net/wlan0/ifindex").readText().trim() }.getOrElse { "missing" }
            return "mode=$mode flags=$flags ifindex=$index"
        }
        fun sample(duration: Long) {
            val deadline = SystemClock.elapsedRealtime() + duration
            var previous: String? = null
            var logged = 0L
            while (true) {
                val value = snapshot()
                val now = SystemClock.elapsedRealtime()
                if (previous != value || now - logged >= 1000L) {
                    report(value)
                    previous = value
                    logged = now
                }
                if (now >= deadline) return
                Thread.sleep(200)
            }
        }
        fun runScript(command: String, label: String): Int {
            val result = CompletableFuture<Int>()
            val callback: (Int) -> Unit = { result.complete(it) }
            report("SCRIPT_BEGIN $label")
            script.invoke(controller, command, args[0], args[1], args[2], callback)
            try {
                val code = result.get(30, TimeUnit.SECONDS)
                report("SCRIPT_END $label code=$code ${snapshot()}")
                return code
            } finally {
                if (!result.isDone) abort.invoke(controller)
            }
        }
        fun setUp(up: Boolean): Boolean {
            val began = SystemClock.elapsedRealtime()
            return try {
                link.invoke(controller, up)
                report("LINK up=$up OK elapsed=${SystemClock.elapsedRealtime() - began}ms ${snapshot()}")
                true
            } catch (error: InvocationTargetException) {
                report("LINK up=$up FAILED elapsed=${SystemClock.elapsedRealtime() - began}ms cause=${error.cause} ${snapshot()}")
                false
            }
        }
        val kernelBefore = kernelLog()
        var failed = false
        try {
            report("BEGIN uid=${Process.myUid()} pid=${Process.myPid()} case=${args[4]} ${snapshot()}")
            if (args[4] == "recover") {
                AndroidApi().setWifiEnabled(true)
                sample(10_000)
                check(WirelessInterfaceNetlink.readMode() == WifiMode.NORMAL)
                check(setUp(true))
                report("RESTORED_BY_ANDROID_API")
                exitProcess(0)
            }
            check(runScript(entryCommand, "full-entry") == 0)
            check(WirelessInterfaceNetlink.readMode() == WifiMode.MONITOR)
            when (args[4]) {
                "keep-up" -> sample(10_000)
                "immediate-down" -> {
                    check(setUp(false))
                    sample(7000)
                    check(setUp(true))
                    sample(10_000)
                }
                "delayed-down" -> {
                    sample(7000)
                    check(setUp(false))
                    sample(1000)
                    check(setUp(true))
                    sample(10_000)
                }
                "repeat-down" -> repeat(5) {
                    report("CYCLE ${it + 1}/5")
                    check(setUp(false))
                    sample(1000)
                    check(setUp(true))
                    sample(1000)
                }
                else -> error("未知测试组 ${args[4]}")
            }
            check(WirelessInterfaceNetlink.readMode() == WifiMode.MONITOR) { "monitor 类型已被改变" }
            report("PASS ${args[4]}")
        } catch (error: Throwable) {
            failed = true
            report("FAIL ${args[4]} $error")
            error.printStackTrace()
        } finally {
            try {
                runScript(exitCommand, "service-exit")
                AndroidApi().setWifiEnabled(true)
                sample(10_000)
                check(WirelessInterfaceNetlink.readMode() == WifiMode.NORMAL) { "恢复普通模式失败" }
                check(setUp(true))
                report("RESTORED")
            } catch (error: Throwable) {
                failed = true
                report("RESTORE_FAILED $error")
                error.printStackTrace()
            }
            val beforeLines = kernelBefore.lineSequence().toHashSet()
            kernelLog().lineSequence().filter { it !in beforeLines }
                .filter { it.contains("wlan", true) || it.contains("cfg80211", true) }
                .forEach { report("KERNEL $it") }
            controller.stop()
            hybrid.close()
            terminals.close()
            mounts.close()
        }
        exitProcess(if (failed) 1 else 0)
    }

    private fun kernelLog(): String = runCatching {
        val process = ProcessBuilder("/system/bin/dmesg").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }.getOrElse { "dmesg: $it" }
}

/** 同一个长期终端接受测试指令；不调用 exit、close、stopTerminal 或挂载清理。 */
object PersistentMonitorLinkDeviceTest {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "rootfs runtime libterminal.so" }
        require(Process.myUid() == 0)
        val ownNamespace = File("/proc/self/ns/mnt").canonicalPath
        val initNamespace = File("/proc/1/ns/mnt").canonicalPath
        check(ownNamespace != initNamespace) { "测试拒绝在系统挂载命名空间启动终端" }
        check(File("/proc/self/mountinfo").readLines().none {
            it.substringBefore(" - ").contains("shared:")
        }) { "测试挂载仍有 shared 传播关系" }
        println("ISOLATED own=$ownNamespace init=$initNamespace")
        val started = SystemClock.elapsedRealtime()
        fun report(text: String) = println("TEST +${SystemClock.elapsedRealtime() - started}ms $text")
        val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Int>>()
        lateinit var terminals: TerminalManager
        val mounts = ContainerMountManager({ pid -> terminals.stopChrootTerminals(pid) },
            { error -> report("MOUNT_ERROR $error") })
        mounts.refresh(ContainerEnvironment(File(args[0]).parentFile!!.absolutePath, args[2]))
        terminals = TerminalManager(mounts, {}, {})
        val hybrid = HybridWifiScanner(terminals)
        val api = AndroidApi()
        val controller = WifiListController(
            { api }, hybrid, terminals, {}, {}, {}, { _, _ -> }, { _, _, _ -> },
            { operation, error -> report("SERVICE_ERROR $operation: $error") },
        )
        val setLink = WifiListController::class.java.getDeclaredMethod("setInterfaceUp", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val exitCommand = WifiListController::class.java.getDeclaredField("MONITOR_EXIT_COMMAND")
            .apply { isAccessible = true }.get(null) as String
        fun snapshot(): String {
            val mode = runCatching { WirelessInterfaceNetlink.readMode().name }.getOrElse { "ERROR:${it.message}" }
            val flags = runCatching { File("/sys/class/net/wlan0/flags").readText().trim() }.getOrElse { "missing" }
            val index = runCatching { File("/sys/class/net/wlan0/ifindex").readText().trim() }.getOrElse { "missing" }
            return "mode=$mode flags=$flags ifindex=$index binder=${File("/dev/binder").exists()} pts=${File("/dev/pts/ptmx").exists()}"
        }
        fun sample(duration: Long) {
            val deadline = SystemClock.elapsedRealtime() + duration
            var last: String? = null
            var printedAt = 0L
            do {
                val value = snapshot()
                val now = SystemClock.elapsedRealtime()
                if (last != value || now - printedAt >= 1000) {
                    report(value)
                    last = value
                    printedAt = now
                }
                if (now >= deadline) break
                Thread.sleep(200)
            } while (true)
        }
        api.setWifiEnabled(true)
        sample(5000)
        val id = terminals.createChrootTerminal(args[0], args[1], args[2],
            onOutputLines = { terminalId, lines ->
                lines.forEach { line ->
                    report("TERMINAL $terminalId: $line")
                    if (line.startsWith("__MONITOR_DONE_")) {
                        val marker = line.substringBefore('=')
                        line.substringAfter('=', "").trim().toIntOrNull()?.let { pending.remove(marker)?.complete(it) }
                    }
                }
            },
            onExit = { terminalId, code ->
                report("UNEXPECTED_TERMINAL_EXIT id=$terminalId code=$code")
                pending.values.forEach { it.completeExceptionally(IllegalStateException("终端意外退出")) }
            },
        )
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun run(command: String) {
            val marker = "__MONITOR_DONE_${java.util.UUID.randomUUID().toString().replace("-", "")}"
            val done = CompletableFuture<Int>()
            pending[marker] = done
            val script = command.trimEnd() + "\nprintf '\\n$marker=%s\\n' \"${'$'}?\""
            terminals.writeInput(id, "sh -c ${quote(script)}")
            val code = done.get(35, TimeUnit.SECONDS)
            report("SCRIPT_RESULT code=$code ${snapshot()}")
        }
        report("READY pid=${Process.myPid()} terminalId=$id; commands: script BASE64 | up | down | sample MS | restore | kernel")
        val input = System.`in`.bufferedReader()
        while (true) {
            val request = input.readLine()
            if (request == null) {
                // 输入断开时也保留终端，避免退出触发挂载清理。
                Thread.sleep(1000)
                continue
            }
            try {
                when {
                    request.startsWith("script ") -> run(String(Base64.decode(request.substringAfter(' '), Base64.DEFAULT), Charsets.UTF_8))
                    request == "up" || request == "down" -> {
                        val begin = SystemClock.elapsedRealtime()
                        try {
                            setLink.invoke(controller, request == "up")
                            report("LINK $request OK elapsed=${SystemClock.elapsedRealtime() - begin}ms ${snapshot()}")
                        } catch (error: InvocationTargetException) {
                            report("LINK $request FAILED cause=${error.cause} ${snapshot()}")
                        }
                    }
                    request.startsWith("sample ") -> sample(request.substringAfter(' ').toLong())
                    request == "restore" -> {
                        run(exitCommand)
                        api.setWifiEnabled(true)
                        sample(8000)
                    }
                    request == "kernel" -> {
                        val process = ProcessBuilder("/system/bin/dmesg").redirectErrorStream(true).start()
                        process.inputStream.bufferedReader().lineSequence().filter {
                            it.contains("wlanRemove") || it.contains("wlanProbe") || it.contains("mtk_cfg_change_iface") ||
                                it.contains("driver is not ready") || it.contains("Power off Wi-Fi") || it.contains("wlanOpen")
                        }.toList().takeLast(60).forEach { report("KERNEL $it") }
                        process.waitFor()
                    }
                    else -> report("UNKNOWN_COMMAND")
                }
            } catch (error: Throwable) {
                report("COMMAND_FAILED $error")
                error.printStackTrace()
            }
            report("COMMAND_DONE")
        }
    }
}
