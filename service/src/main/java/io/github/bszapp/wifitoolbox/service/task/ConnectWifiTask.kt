package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTarget
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskRequest
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.service.AndroidApi
import io.github.bszapp.wifitoolbox.service.TemporaryWifiNetworkRequest
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogAnalyzer
import io.github.bszapp.wifitoolbox.service.wifilog.WifiLogEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal class ConnectWifiTask(
    private val request: ConnectWifiTaskRequest,
    private val expectedSsid: String,
    private val androidApi: AndroidApi,
    private val wifiLogAnalyzer: WifiLogAnalyzer,
) : ServiceTask {
    var attemptResult: ConnectionAttemptResult = ConnectionAttemptResult.ERROR
        private set

    override fun run(context: TaskContext) {
        val config = request.config
        val signals = LinkedBlockingQueue<ConnectSignal>()
        val attemptStarted = SystemClock.elapsedRealtime()
        val overallDeadline = attemptStarted + config.timeoutMillis
        context.log("任务类型：${request.input.type.displayName}")
        context.log("进入阶段：与路由器建立通信")
        context.updateProgress(TaskProgress.ConnectWifi(ConnectWifiStage.ROUTER_COMMUNICATION))
        context.ensureRunning()

        val listener: (WifiLogEvent) -> Unit = { event -> signals.offer(ConnectSignal(event, SystemClock.elapsedRealtime())) }
        val subscription = wifiLogAnalyzer.subscribe(listener)
        var temporaryRequest: TemporaryWifiNetworkRequest? = null
        try {
            when (val target = request.input.target) {
                is ConnectWifiTarget.SavedNetwork -> {
                    context.log(
                        "开始使用 Android networkId=${target.networkId} " +
                            "连接 Wi-Fi：$expectedSsid",
                    )
                    androidApi.connectWifiByNetworkId(target.networkId)
                }

                is ConnectWifiTarget.TemporaryNetwork -> {
                    temporaryRequest = androidApi.requestTemporaryWifiNetwork(
                        ssid = target.ssid,
                        password = target.password,
                    ).also { temporary ->
                        context.log(
                            if (temporary.removedExistingConfiguration) {
                                "已删除原 Wi-Fi 配置并新建测试配置，" +
                                    "networkId=${temporary.networkId}"
                            } else {
                                "未找到原 Wi-Fi 配置，已新建测试配置，" +
                                    "networkId=${temporary.networkId}"
                            },
                        )
                    }
                }
                is ConnectWifiTarget.NetworkCard -> error("网卡测试应使用 NetworkCardConnectTask")
            }

            var handshakeStarted: Long? = null
            var handshakeCount = 0

            while (true) {
                context.ensureRunning()
                val flags = config.failureFlags
                val handshakeAttempts = flags.handshakeAttemptsExceeded
                val handshakeTimeout = flags.handshakeTimeout
                val now = SystemClock.elapsedRealtime()
                if (now >= overallDeadline) {
                    context.log("连接失败：任务总超时 ${config.timeoutMillis}ms")
                    attemptResult = ConnectionAttemptResult.TIMEOUT
                    return
                }
                val activeHandshakeDeadline = handshakeStarted?.let { it + (handshakeTimeout?.handshakeStepTimeoutMillis ?: 0) }
                if (
                    handshakeTimeout != null &&
                    activeHandshakeDeadline != null &&
                    now >= activeHandshakeDeadline
                ) {
                    context.log(
                        "连接失败：WPA 握手超时 " +
                            "${handshakeTimeout.handshakeStepTimeoutMillis}ms",
                    )
                    attemptResult = ConnectionAttemptResult.FAILED
                    return
                }

                val nextDeadline = if (
                    handshakeTimeout != null &&
                    activeHandshakeDeadline != null
                ) {
                    min(overallDeadline, activeHandshakeDeadline)
                } else {
                    overallDeadline
                }
                val signal = signals.poll(
                    (nextDeadline - now).coerceIn(1L, 100L),
                    TimeUnit.MILLISECONDS,
                ) ?: continue
                val event = signal.event

                if (event.ssid != expectedSsid) {
                    if (request.input.target is ConnectWifiTarget.TemporaryNetwork) continue
                    context.log("WPA 原文：${event.rawLine}")
                    context.log("连接失败：收到其他 Wi-Fi 的分析事件，目标=$expectedSsid，实际=${event.ssid}")
                    return
                }
                context.log("WPA 原文：${event.rawLine}")

                when (event) {
                    is WifiLogEvent.AssociationStarted -> Unit

                    is WifiLogEvent.RouterAssociated -> {
                        context.log("已与路由器建立连接，MAC=${event.bssid}")
                    }

                    is WifiLogEvent.Handshake1Of4 -> {
                        context.updateProgress(
                            TaskProgress.ConnectWifi(ConnectWifiStage.WPA_HANDSHAKE_1_OF_4),
                        )
                        context.log("进入阶段：WPA 握手 1/4")
                        if (handshakeStarted == null) handshakeStarted = signal.receivedAt
                    }

                    is WifiLogEvent.Handshake2Of4 -> {
                        handshakeCount++
                        context.updateProgress(
                            TaskProgress.ConnectWifi(ConnectWifiStage.WPA_HANDSHAKE_2_OF_4),
                        )
                        context.log("进入阶段：WPA 握手 2/4")
                        val limit = handshakeAttempts?.maxHandshakeAttempts
                        context.log("握手次数：$handshakeCount/${limit ?: "null"}")
                        if (limit != null && handshakeCount > limit) {
                            context.log("连接失败：WPA 握手次数超过最大次数 $limit")
                            attemptResult = ConnectionAttemptResult.FAILED
                            return
                        }
                    }

                    is WifiLogEvent.Handshake3Of4 -> {
                        context.updateProgress(
                            TaskProgress.ConnectWifi(ConnectWifiStage.WPA_HANDSHAKE_3_OF_4),
                        )
                        context.log("进入阶段：WPA 握手 3/4")
                    }

                    is WifiLogEvent.Handshake4Of4 -> {
                        context.updateProgress(
                            TaskProgress.ConnectWifi(ConnectWifiStage.WPA_HANDSHAKE_4_OF_4),
                        )
                        context.log("进入阶段：WPA 握手 4/4")
                    }

                    is WifiLogEvent.KeyNegotiationCompleted -> {
                        context.log(
                            "连接成功：密钥协商完成，" +
                                "MAC=${event.bssid}，SSID=$expectedSsid",
                        )
                        if (temporaryRequest != null) {
                            context.log("测试连通性成功，立即断开临时网络")
                        }
                        attemptResult = ConnectionAttemptResult.SUCCESS
                        return
                    }

                    is WifiLogEvent.PasswordError -> {
                        if (flags.passwordError) {
                            context.log("连接失败：密码错误")
                            attemptResult = ConnectionAttemptResult.FAILED
                            return
                        }
                        context.log("检测到密码错误，当前配置未将其设为终止条件")
                    }

                    is WifiLogEvent.HandshakeFailed -> {
                        context.log("连接失败：WPA 四次握手失败")
                        attemptResult = ConnectionAttemptResult.FAILED
                        return
                    }

                    is WifiLogEvent.AssociationRejected -> {
                        context.log(
                            "连接失败：路由器拒绝接入" +
                                (event.reason?.let { "，原因=$it" } ?: ""),
                        )
                        attemptResult = ConnectionAttemptResult.REJECTED
                        return
                    }

                    is WifiLogEvent.Disconnected -> {
                        context.log(
                            "连接过程中发生断开" +
                                (event.bssid?.let { "，MAC=$it" } ?: "") +
                                (event.reason?.let { "，原因=$it" } ?: ""),
                        )
                    }
                }
            }
        } finally {
            subscription.close()
            temporaryRequest?.let { requestHandle ->
                runCatching { requestHandle.close() }
                    .onSuccess {
                        context.log("已断开测试连接并删除测试配置")
                    }
                    .onFailure { error ->
                        attemptResult = ConnectionAttemptResult.ERROR
                        context.log(
                            "清理测试连接配置失败：${error.message ?: error.javaClass.name}",
                        )
                    }
            }
        }
    }

    private val ConnectWifiTaskType.displayName: String
        get() = when (this) {
            ConnectWifiTaskType.USE_SAVED_NETWORK -> "使用已保存的网络连接"
            ConnectWifiTaskType.CONNECT_TO_NETWORK -> "连接到网络"
        }

    private data class ConnectSignal(val event: WifiLogEvent, val receivedAt: Long)
}
