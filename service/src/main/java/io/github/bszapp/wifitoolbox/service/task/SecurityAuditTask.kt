package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock
import io.github.bszapp.wifitoolbox.contract.task.*
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/** 旧版多网络轮询、冷却、重试调度；单次连接由安全测试专用执行器完成。 */
internal class SecurityAuditTask(
    input: SecurityAuditTaskInput,
    private val dictionary: (String) -> List<String>,
    private val worker: SecurityAuditConnectWorker,
) : ServiceTask {
    @Volatile private var config = input.config
    private val updates = LinkedBlockingQueue<TaskUpdatePayload>()
    private val attemptRevision = AtomicLong()
    private val entries = mutableListOf<Entry>()
    @Volatile private var accepting = true
    @Volatile private var attemptingSsid: String? = null

    init { addOrEdit(input.network) }

    override fun update(update: TaskUpdateRequest): Boolean {
        synchronized(updates) {
            if (!accepting) return false
            when (val payload = update.payload) {
                is TaskUpdatePayload.SecurityAuditUpsert -> attemptRevision.incrementAndGet()
                is TaskUpdatePayload.SecurityAuditRemove -> if (payload.ssid == attemptingSsid) attemptRevision.incrementAndGet()
                is TaskUpdatePayload.SecurityAuditConfigure -> config = payload.config
                else -> return false
            }
            updates.offer(update.payload)
            return true
        }
    }

    override fun run(context: TaskContext) {
        context.log("授权网络安全测试开始：多网络排队，单次连接串行执行")
        try {
            while (true) {
                try {
                context.ensureRunning()
                drainUpdates(context)
                val active = entries.filter { it.record.active }
                if (active.isEmpty()) {
                    synchronized(updates) {
                        if (updates.isNotEmpty()) return@synchronized
                        accepting = false
                    }
                    if (accepting) continue
                    context.log("运行队列为空，本批安全测试结束")
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val next = active.filter { it.readyAt <= now }.minByOrNull { it.lastAttemptAt }
                if (next == null) {
                    publish(context)
                    val wait = (active.minOf { it.readyAt } - now).coerceIn(1, 100)
                    updates.poll(wait, TimeUnit.MILLISECONDS)?.let { applyUpdate(it, context) }
                    continue
                }
                try {
                    attemptingSsid = next.record.input.ssid
                    drainUpdates(context)
                    if (next.record.active && next.readyAt <= SystemClock.elapsedRealtime()) attempt(next, context)
                } finally { attemptingSsid = null }
                } catch (stop: InterruptedException) {
                    throw stop
                } catch (error: ConnectionCleanupException) {
                    context.log("上一轮断开未确认，停止提交后续连接：${error.cause?.message ?: error.message}")
                    throw error
                } catch (error: Exception) {
                    context.log("本轮异常，保留当前位置继续：${error.message ?: error.javaClass.name}")
                    Thread.sleep(100)
                }
            }
        } finally {
            accepting = false
            runCatching { worker.closeNetworkCardSession(context) }
                .onFailure { context.log("关闭持久网卡脚本会话失败：${it.message ?: it.javaClass.name}") }
            entries.filter { it.record.active }.forEach {
                it.record = it.record.copy(state = SecurityAuditNetworkState.STOPPED,
                    stage = null, nextAttemptAt = null, updatedAt = System.currentTimeMillis(), message = "已停止")
            }
            entries.filter { it.record.state != SecurityAuditNetworkState.SUCCEEDED }.forEach {
                forgetNetwork(it, context)
            }
            publish(context)
        }
    }

    private fun attempt(entry: Entry, context: TaskContext) {
        val password = entry.passwords.getOrNull(entry.record.completed)
        if (password == null) {
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.EXHAUSTED, message = "给定字典未发现密码")
            forgetNetwork(entry, context)
            publish(context)
            return
        }
        val revision = attemptRevision.get()
        val selectedConfig = config
        entry.lastAttemptAt = SystemClock.elapsedRealtime()
        val started = entry.lastAttemptAt
        // 网卡方式的随机策略在本轮连接前决定并保持；返回 null 即使用设备原 MAC。
        val mac = selectMac(entry, selectedConfig)
        val macLabel = mac
        entry.record = entry.record.copy(state = SecurityAuditNetworkState.CONNECTING, config = selectedConfig,
            attempts = entry.record.attempts + 1, nextAttemptAt = null,
            updatedAt = System.currentTimeMillis(),
            message = if (macLabel != null) "正在尝试：$password\nMAC：$macLabel" else "正在尝试：$password",
            stage = ConnectWifiStage.ROUTER_COMMUNICATION)
        context.log("[${entry.record.input.ssid}] 尝试字典第 ${entry.record.completed + 1}/${entry.passwords.size} 位" +
            (macLabel?.let { " mac=$it" } ?: ""))
        publish(context)
        val child = context.forAttempt(entry.record.input.ssid, { progress ->
            if (progress is TaskProgress.ConnectWifi) {
                entry.record = entry.record.copy(stage = progress.stage, updatedAt = System.currentTimeMillis())
                publish(context)
            }
        }, { if (revision != attemptRevision.get()) throw AttemptChanged() }, { config })
        var duration = 0L
        val result = try {
            if (selectedConfig.connectionMethod == SecurityAuditConnectionMethod.SYSTEM) {
                entry.systemConfiguration = true
                entry.configurationCleanupAttempted = false
            }
            try {
                worker.performTaskLogic(entry.record.input.ssid, password, mac, selectedConfig, child)
            } finally {
                // 旧版先记录连接耗时，再清理连接；清理不计入密码尝试耗时。
                duration = SystemClock.elapsedRealtime() - started
                try { worker.cleanConnection(selectedConfig.connectionMethod, child) }
                catch (error: Exception) { throw ConnectionCleanupException(error) }
            }
        } catch (_: AttemptChanged) {
            // 配置/队列变更只撤销本次连接；通用任务的停止仍由父 Context 处理。
            Thread.interrupted()
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.QUEUED, stage = null,
                message = "排队中", attempts = entry.record.attempts - 1)
            drainUpdates(context)
            publish(context)
            return
        } catch (stop: InterruptedException) {
            throw stop
        } catch (error: ConnectionCleanupException) {
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.ERROR, stage = null,
                updatedAt = System.currentTimeMillis(), message = "上一轮断开未确认，已停止继续连接")
            publish(context)
            throw error
        } catch (error: Exception) {
            context.log("[${entry.record.input.ssid}] 执行异常：${error.message ?: error.javaClass.name}")
            ConnectionAttemptResult.ERROR
        }
        val totalDuration = entry.record.elapsedMillis + duration
        when (result) {
            ConnectionAttemptResult.FAILED -> {
                entry.costs += duration
                if (entry.costs.size > 20) entry.costs.removeAt(0)
            }
            ConnectionAttemptResult.TIMEOUT, ConnectionAttemptResult.REJECTED -> {
                if (entry.costs.isNotEmpty()) entry.costs[entry.costs.lastIndex] += duration
            }
            else -> Unit
        }
        entry.record = entry.record.copy(elapsedMillis = totalDuration, stage = null,
            updatedAt = System.currentTimeMillis(), averageAttemptMillis =
                if (entry.costs.size >= 5) entry.costs.sum() / entry.costs.size else null)
        context.log("[${entry.record.input.ssid}] 本次结果=$result，耗时=${duration}ms")
        when (result) {
            ConnectionAttemptResult.SUCCESS -> {
                entry.record = entry.record.copy(state = SecurityAuditNetworkState.SUCCEEDED,
                    password = password, dictionaryPosition = entry.record.completed + 1,
                    completed = entry.record.completed + 1, message = "发现弱密码，建议立即更换")
                context.log("[${entry.record.input.ssid}] 已在字典第 ${entry.record.dictionaryPosition} 位发现弱密码")
            }
            ConnectionAttemptResult.FAILED -> advance(entry)
            ConnectionAttemptResult.TIMEOUT, ConnectionAttemptResult.REJECTED -> {
                val count = entry.record.retryCount + 1
                if (config.retryCount < 6 && count > config.retryCount) advance(entry)
                else {
                    val delay = cooldownMillis(count)
                    // 时间使用单调钟；仅用于展示的下次时间转换成墙上时间。
                    entry.readyAt = entry.lastAttemptAt + delay
                    entry.record = entry.record.copy(retryCount = count,
                        state = if (delay > 0) SecurityAuditNetworkState.COOLDOWN else SecurityAuditNetworkState.QUEUED,
                        message = "${if (result == ConnectionAttemptResult.TIMEOUT) "超时" else worker.rejectionDescription ?: "路由器拒绝接入"}，第 $count 次重试",
                        nextAttemptAt = System.currentTimeMillis() + (entry.readyAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                }
            }
            ConnectionAttemptResult.ERROR -> entry.record = entry.record.copy(
                state = SecurityAuditNetworkState.ERROR, message = "连接执行异常，请查看任务日志")
        }
        if (!entry.record.active && entry.record.state != SecurityAuditNetworkState.SUCCEEDED) {
            forgetNetwork(entry, context)
        }
        publish(context)
    }

    private fun advance(entry: Entry) {
        val completed = entry.record.completed + 1
        entry.readyAt = 0
        entry.record = entry.record.copy(completed = completed, retryCount = 0, nextAttemptAt = null,
            state = if (completed >= entry.passwords.size) SecurityAuditNetworkState.EXHAUSTED else SecurityAuditNetworkState.QUEUED,
            message = if (completed >= entry.passwords.size) "给定字典未发现密码" else "排队中")
    }

    private fun drainUpdates(context: TaskContext) {
        while (true) applyUpdate(updates.poll() ?: break, context)
        publish(context)
    }

    private fun applyUpdate(update: TaskUpdatePayload, context: TaskContext) {
        when (update) {
            is TaskUpdatePayload.SecurityAuditUpsert -> {
                addOrEdit(update.network)
                context.log("[${update.network.ssid}] 已添加或编辑运行字典")
            }
            is TaskUpdatePayload.SecurityAuditRemove -> {
                entries.filter { it.record.active && it.record.input.ssid == update.ssid }.forEach {
                    it.record = it.record.copy(state = SecurityAuditNetworkState.STOPPED,
                        stage = null, nextAttemptAt = null, updatedAt = System.currentTimeMillis(), message = "用户停止此网络")
                    forgetNetwork(it, context)
                }
                context.log("[${update.ssid}] 已移出运行队列")
            }
            is TaskUpdatePayload.SecurityAuditConfigure -> {
                config = update.config
                entries.filter { it.record.active }.forEach {
                    it.record = it.record.copy(config = config)
                    if (it.record.retryCount > 0) {
                        it.readyAt = it.lastAttemptAt + cooldownMillis(it.record.retryCount)
                        it.record = it.record.copy(nextAttemptAt = System.currentTimeMillis() +
                            (it.readyAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    }
                }
                context.log("安全测试运行配置已更新")
            }
            else -> error("安全测试不支持此更新")
        }
    }

    private fun addOrEdit(input: SecurityAuditNetworkInput) {
        val passwords = dictionary(input.dictionaryId)
        val existing = entries.firstOrNull { it.record.active && it.record.input.ssid == input.ssid }
        if (existing != null) {
            val same = existing.passwords == passwords
            existing.passwords = passwords
            existing.readyAt = 0
            existing.record = existing.record.copy(input = input, total = passwords.size,
                completed = if (same) existing.record.completed else 0, retryCount = 0,
                nextAttemptAt = null, stage = null, state = SecurityAuditNetworkState.QUEUED,
                updatedAt = System.currentTimeMillis(), message = "排队中")
        } else {
            val now = System.currentTimeMillis()
            entries += Entry(SecurityAuditNetworkRecord(UUID.randomUUID().toString(), input, config,
                now, now, SecurityAuditNetworkState.QUEUED, passwords.size,
                completed = input.startIndex.coerceIn(0, passwords.size)), passwords)
        }
    }

    private fun publish(context: TaskContext) = context.publishAuditNetworks(config, entries.map { it.record })
    private fun forgetNetwork(entry: Entry, context: TaskContext) {
        if (!entry.systemConfiguration || entry.configurationCleanupAttempted) return
        entry.configurationCleanupAttempted = true
        try {
            if (worker.forgetNetwork(entry.record.input.ssid)) context.log("[${entry.record.input.ssid}] 已遗忘网络配置")
            else context.log("[${entry.record.input.ssid}] 未找到可遗忘的网络配置")
        } catch (error: Exception) {
            context.log("[${entry.record.input.ssid}] 遗忘网络失败：${error.message ?: error.javaClass.name}")
        }
    }
    /**
     * 决定本次连接使用的 MAC；null 表示使用设备原 MAC。
     *
     * 异常时更换/每次更换在首次尝试就使用随机地址，并在该网络的后续尝试中一直保持，
     * 不再回退到设备地址；同一密码连续重试次数达到阈值时即换成新的随机地址。
     * 每次更换等价于阈值 0，即每次尝试都换新。
     */
    private fun selectMac(entry: Entry, config: SecurityAuditConfig): String? {
        if (config.connectionMethod != SecurityAuditConnectionMethod.NETWORK_CARD) {
            entry.mac = null
            return null
        }
        return when (config.macMode) {
            SecurityAuditMacMode.DEVICE -> { entry.mac = null; null }
            SecurityAuditMacMode.EVERY_ATTEMPT -> randomMacAddress().also { entry.mac = it }
            SecurityAuditMacMode.ON_FAILURE ->
                if (entry.mac == null || entry.record.retryCount >= config.macChangeThreshold) {
                    randomMacAddress().also { entry.mac = it }
                } else entry.mac
        }
    }

    /** 本地管理的单播随机 MAC，避开多播位。 */
    private fun randomMacAddress(): String =
        (1..6).joinToString(":") { "%02x".format(SECURE_RANDOM.nextInt(256) and 0xfc or 0x02) }

    private fun cooldownMillis(retries: Int): Long =
        if (retries > 0) 2.0.pow(retries.toDouble() - 1).toLong() * config.cooldownBaseMillis else 0
    private class AttemptChanged : InterruptedException()
    private companion object {
        val SECURE_RANDOM = java.security.SecureRandom()
    }
    private data class Entry(var record: SecurityAuditNetworkRecord, var passwords: List<String>,
        var lastAttemptAt: Long = 0, var readyAt: Long = 0, val costs: MutableList<Long> = mutableListOf(),
        /** 随机 MAC 策略下该网络当前保持的地址；null 表示使用设备原 MAC。 */
        var mac: String? = null,
        var systemConfiguration: Boolean = record.config.connectionMethod == SecurityAuditConnectionMethod.SYSTEM,
        var configurationCleanupAttempted: Boolean = false)
}
