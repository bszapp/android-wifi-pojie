package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock
import android.net.wifi.SupplicantState
import io.github.bszapp.wifitoolbox.contract.task.*
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.TerminalManager
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogAnalyzer
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogEvent
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogObservation
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 安全测试专用单次执行器，移植旧版 ConnectWorker 的系统连接及结果判定。
 * 调度器在取得结果后调用 cleanConnection；只有网络移出队列才调用 forgetNetwork。
 * 网卡方式拥有独立执行器，两个分支均不创建普通连通性测试任务。
 */
internal class SecurityAuditConnectWorker(
    private val androidApi: AndroidApi,
    private val wifiLogAnalyzer: WifiLogAnalyzer,
    terminalManager: TerminalManager,
) {
    private val networkCardWorker = SecurityAuditNetworkCardWorker(terminalManager, androidApi::getDeviceName)
    private var systemAttempt: SystemAttempt? = null
    var rejectionDescription: String? = null
        private set

    fun performTaskLogic(
        ssid: String,
        password: String,
        mac: String?,
        config: SecurityAuditConfig,
        context: TaskContext,
    ): ConnectionAttemptResult {
        rejectionDescription = null
        return when (config.connectionMethod) {
            SecurityAuditConnectionMethod.SYSTEM -> {
                // 系统连接开始前结束可能仍存活的网卡脚本会话，确保其先恢复系统 supplicant。
                networkCardWorker.closeSession(context)
                performSystemConnection(ssid, password, config, context)
            }
            SecurityAuditConnectionMethod.NETWORK_CARD -> networkCardWorker.performTaskLogic(ssid, password, mac, config, context)
        }
    }

    fun closeNetworkCardSession(context: TaskContext) = networkCardWorker.closeSession(context)

    fun cleanConnection(method: SecurityAuditConnectionMethod, context: TaskContext) {
        if (method == SecurityAuditConnectionMethod.SYSTEM) {
            val attempt = systemAttempt ?: return
            // 停止指令只中断判定，实际断开确认仍须完成，才能交还连接执行权。
            var interrupted = Thread.interrupted()
            val started = SystemClock.elapsedRealtime()
            try {
                val before = androidApi.readSecurityAuditConnectionState()
                var sawActiveState = before.isConnecting()
                androidApi.disconnectSecurityAuditNetwork()
                context.log("断开请求已接受：networkId=${attempt.networkId}，等待实际断开")
                val deadline = started + attempt.timeoutMillis
                while (true) {
                    val signal = attempt.signals.poll()
                    if (signal != null && attempt.belongsToSubmission(signal)) {
                        // 本轮结果已经锁定，清理阶段的密码错误等日志仅记录原文。
                        context.log("清理 WPA 原文：${signal.event.rawLine}")
                        if (signal.event is WifiLogEvent.Disconnected) attempt.disconnected = true
                    }
                    if (attempt.disconnected) {
                        context.log("实际断开已确认：WPA 断开事件，耗时=${SystemClock.elapsedRealtime() - started}ms")
                        return
                    }
                    val state = androidApi.readSecurityAuditConnectionState()
                    if (state.isConnecting()) sawActiveState = true
                    if (state.isDisconnected() && (sawActiveState || !attempt.linkObserved)) {
                        context.log("实际断开已确认：supplicantState=$state，耗时=${SystemClock.elapsedRealtime() - started}ms")
                        return
                    }
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "无法确认上一轮实际断开：networkId=${attempt.networkId} state=$state" }
                    try {
                        attempt.signals.poll(remaining.coerceAtMost(20L), TimeUnit.MILLISECONDS)?.let {
                            if (attempt.belongsToSubmission(it)) {
                                context.log("清理 WPA 原文：${it.event.rawLine}")
                                if (it.event is WifiLogEvent.Disconnected) attempt.disconnected = true
                            }
                        }
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                attempt.subscription.close()
                systemAttempt = null
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        if (method == SecurityAuditConnectionMethod.NETWORK_CARD) {
            // 完成或撤销本轮连接后仍保留脚本会话，直到任务生命周期结束。
            networkCardWorker.cleanConnection(context)
        }
    }

    fun forgetNetwork(ssid: String): Boolean = androidApi.forgetSecurityAuditNetwork(ssid)

    private fun performSystemConnection(
        ssid: String,
        password: String,
        config: SecurityAuditConfig,
        context: TaskContext,
    ): ConnectionAttemptResult {
        context.ensureRunning()
        check(systemAttempt == null) { "上一轮连接尚未清理" }
        val attempt = SystemAttempt(ssid, config.connection.timeoutMillis)
        attempt.subscription = wifiLogAnalyzer.subscribeAttempt { attempt.signals.offer(it) }
        systemAttempt = attempt
        context.updateProgress(TaskProgress.ConnectWifi(ConnectWifiStage.ROUTER_COMMUNICATION))
        context.log("进入阶段：与路由器建立通信")
        val apiStarted = SystemClock.elapsedRealtime()
        val submission = androidApi.connectSecurityAuditNetwork(ssid, password) { networkId ->
            context.ensureRunning()
            attempt.networkId = networkId
            attempt.submittedAfterId = wifiLogAnalyzer.latestId()
            attempt.submittedAtMillis = System.currentTimeMillis()
            attempt.submittedAtElapsed = SystemClock.elapsedRealtime()
        }
        context.log("连接请求返回：networkId=${submission.networkId} accepted=${submission.accepted} " +
            "apiElapsed=${SystemClock.elapsedRealtime() - apiStarted}ms，单次结束保留配置")
        if (!submission.accepted) {
            rejectionDescription = "系统未接受连接请求"
            context.log("连接请求未被接受，按现有重试规则处理，不等待握手超时")
            return ConnectionAttemptResult.REJECTED
        }
        // 保留原先 API 返回后启动总超时的规则。
        val timeoutMillis = (context.auditConfiguration?.invoke() ?: config).connection.timeoutMillis
        attempt.timeoutMillis = timeoutMillis
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var handshakeStarted: Long? = null
        var handshakeCount = 0
        var associationId: Long? = null
        while (true) {
            context.ensureRunning()
            val flags = (context.auditConfiguration?.invoke() ?: config).connection.failureFlags
            val handshakeDeadline = handshakeStarted?.let { start ->
                flags.handshakeTimeout?.let { start + it.handshakeStepTimeoutMillis }
            }
            val nextDeadline = minOf(deadline, handshakeDeadline ?: deadline)
            val now = SystemClock.elapsedRealtime()
            // 优先取已到达的事件，避免队列中的及时失败/成功事件被消费时刻误判为超时。
            val signal = attempt.signals.poll() ?: if (now < nextDeadline) {
                attempt.signals.poll((nextDeadline - now).coerceIn(1L, 100L), TimeUnit.MILLISECONDS)
            } else null
            if (signal != null && !attempt.belongsToSubmission(signal)) continue
            val sourceAt = signal?.let { attempt.sourceElapsed(it) }
            if (signal == null || sourceAt == null || sourceAt > nextDeadline) {
                val current = SystemClock.elapsedRealtime()
                if (current >= nextDeadline) {
                    if (handshakeDeadline != null && handshakeDeadline <= deadline) {
                        context.log("连接失败：握手超时，用时=${flags.handshakeTimeout!!.handshakeStepTimeoutMillis}ms")
                        return ConnectionAttemptResult.FAILED
                    }
                    context.log("连接超时：${timeoutMillis}ms；" +
                        if (associationId == null) "未观察到本轮关联开始" else "未观察到本轮连接结果")
                    return ConnectionAttemptResult.TIMEOUT
                }
                continue
            }
            val event = signal.event
            if (event is WifiLogEvent.AssociationStarted) {
                associationId = signal.associationStartId
                handshakeStarted = null
                handshakeCount = 0
                attempt.disconnected = false
                attempt.linkObserved = false
            } else if (associationId != signal.associationStartId) continue
            val decisionAt = SystemClock.elapsedRealtime()
            var result: ConnectionAttemptResult? = null
            var message: String? = null
            var nextStage: ConnectWifiStage? = null
            when (event) {
                is WifiLogEvent.AssociationStarted -> message = "本轮关联已开始"
                is WifiLogEvent.RouterAssociated -> {
                    attempt.linkObserved = true
                    message = "已与路由器建立连接，MAC=${event.bssid}"
                }
                is WifiLogEvent.Handshake1Of4 -> {
                    attempt.linkObserved = true
                    if (handshakeStarted == null) handshakeStarted = sourceAt
                    nextStage = ConnectWifiStage.WPA_HANDSHAKE_1_OF_4
                }
                is WifiLogEvent.Handshake2Of4 -> {
                    attempt.linkObserved = true
                    handshakeCount++
                    nextStage = ConnectWifiStage.WPA_HANDSHAKE_2_OF_4
                    val limit = flags.handshakeAttemptsExceeded?.maxHandshakeAttempts
                    message = "握手次数：$handshakeCount/${limit ?: "null"}"
                    if (limit != null && handshakeCount > limit) {
                        result = ConnectionAttemptResult.FAILED
                        message += "；连接失败：握手超次"
                    }
                }
                is WifiLogEvent.Handshake3Of4 -> nextStage = ConnectWifiStage.WPA_HANDSHAKE_3_OF_4
                is WifiLogEvent.Handshake4Of4 -> nextStage = ConnectWifiStage.WPA_HANDSHAKE_4_OF_4
                is WifiLogEvent.KeyNegotiationCompleted -> {
                    attempt.linkObserved = true
                    message = "连接成功：密钥协商完成，MAC=${event.bssid}"
                    result = ConnectionAttemptResult.SUCCESS
                }
                is WifiLogEvent.PasswordError, is WifiLogEvent.HandshakeFailed -> {
                    if (event.rawLine.contains("WPA: 4-Way Handshake failed")) {
                        message = "连接失败：WPA 四次握手失败"
                        result = ConnectionAttemptResult.FAILED
                    } else if (flags.passwordError) {
                        message = "连接失败：密码错误"
                        result = ConnectionAttemptResult.FAILED
                    } else {
                        message = "检测到密码错误，当前配置未将其设为终止条件"
                    }
                }
                is WifiLogEvent.AssociationRejected -> {
                    rejectionDescription = "路由器拒绝接入"
                    message = "关联失败：status_code=${event.reason ?: "未知"}"
                    result = ConnectionAttemptResult.REJECTED
                }
                is WifiLogEvent.Disconnected -> {
                    attempt.disconnected = true
                    message = "本轮连接已断开：reason=${event.reason ?: "未知"}"
                    // 断开原文后可能紧接四次握手失败/WRONG_KEY，继续按明确失败标志判定。
                }
            }
            // 先完成判定，再写日志及发布进度；日志 ID 去重不合并真实的 M2 重试。
            context.log("WPA 原文：${event.rawLine}")
            message?.let(context::log)
            nextStage?.let { stage(context, it) }
            if (result != null) {
                context.log("判定=$result logId=${signal.logId} " +
                    "sourceToReceive=${signal.receivedAtElapsed - sourceAt}ms " +
                    "receiveToDecision=${decisionAt - signal.receivedAtElapsed}ms")
                return result
            }
        }
    }

    private fun stage(context: TaskContext, stage: ConnectWifiStage) {
        context.updateProgress(TaskProgress.ConnectWifi(stage))
        context.log("进入阶段：WPA 握手 ${when (stage) {
            ConnectWifiStage.WPA_HANDSHAKE_1_OF_4 -> "1/4"
            ConnectWifiStage.WPA_HANDSHAKE_2_OF_4 -> "2/4"
            ConnectWifiStage.WPA_HANDSHAKE_3_OF_4 -> "3/4"
            ConnectWifiStage.WPA_HANDSHAKE_4_OF_4 -> "4/4"
            else -> error("非握手阶段")
        }}")
    }

    private class SystemAttempt(val ssid: String, var timeoutMillis: Long) {
        val signals = LinkedBlockingQueue<WifiLogObservation>()
        lateinit var subscription: AutoCloseable
        var networkId = -1
        var submittedAfterId = Long.MAX_VALUE
        var submittedAtMillis = Long.MAX_VALUE
        var submittedAtElapsed = 0L
        var lastHandledId = -1L
        var linkObserved = false
        var disconnected = false

        fun belongsToSubmission(signal: WifiLogObservation): Boolean {
            if (signal.event.ssid != ssid || signal.logId <= lastHandledId ||
                signal.logId <= submittedAfterId || signal.timestampMillis < submittedAtMillis ||
                signal.associationStartId <= submittedAfterId ||
                signal.associationStartedAtMillis < submittedAtMillis) return false
            lastHandledId = signal.logId
            return true
        }

        fun sourceElapsed(signal: WifiLogObservation): Long =
            submittedAtElapsed + (signal.timestampMillis - submittedAtMillis)
    }

    private fun SupplicantState.isConnecting(): Boolean = when (this) {
        SupplicantState.AUTHENTICATING, SupplicantState.ASSOCIATING, SupplicantState.ASSOCIATED,
        SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE, SupplicantState.COMPLETED -> true
        else -> false
    }

    private fun SupplicantState.isDisconnected(): Boolean = when (this) {
        SupplicantState.DISCONNECTED, SupplicantState.INACTIVE, SupplicantState.INTERFACE_DISABLED,
        SupplicantState.UNINITIALIZED -> true
        else -> false
    }
}
