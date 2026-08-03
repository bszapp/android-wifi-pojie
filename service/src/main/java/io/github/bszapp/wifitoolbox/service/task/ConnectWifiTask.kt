package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.service.AndroidApi
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
    override fun run(context: TaskContext) {
        val networkId = request.input.networkId
        val config = request.config
        val handshakeAttempts = config.failureFlags.handshakeAttemptsExceeded
        val handshakeTimeout = config.failureFlags.handshakeTimeout
        val events = LinkedBlockingQueue<WifiLogEvent>()
        val overallDeadline = SystemClock.elapsedRealtime() + config.timeoutMillis
        context.log("开始使用 Android networkId=$networkId 连接 Wi-Fi：$expectedSsid")
        context.log("进入阶段：与路由器建立通信")
        context.updateProgress(TaskProgress.ConnectWifi(ConnectWifiStage.ROUTER_COMMUNICATION))
        context.ensureRunning()

        val subscription = wifiLogAnalyzer.subscribe(events::offer)
        try {
            androidApi.connectWifiByNetworkIdDirect(networkId)

            var handshakeDeadline: Long? = null
            var handshakeCount = 0

            while (true) {
                context.ensureRunning()
                val now = SystemClock.elapsedRealtime()
                if (now >= overallDeadline) {
                    context.log("连接失败：任务总超时 ${config.timeoutMillis}ms")
                    return
                }
                val activeHandshakeDeadline = handshakeDeadline
                if (
                    handshakeTimeout != null &&
                    activeHandshakeDeadline != null &&
                    now >= activeHandshakeDeadline
                ) {
                    context.log(
                        "连接失败：WPA 握手超时 " +
                            "${handshakeTimeout.handshakeStepTimeoutMillis}ms",
                    )
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
                val event = events.poll(
                    (nextDeadline - now).coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS,
                ) ?: continue

                context.log("WPA 原文：${event.rawLine}")
                if (event.ssid != expectedSsid) {
                    context.log(
                        "连接失败：收到其他 Wi-Fi 的分析事件，" +
                            "目标=$expectedSsid，实际=${event.ssid}",
                    )
                    return
                }

                when (event) {
                    is WifiLogEvent.AssociationStarted -> {}

                    is WifiLogEvent.RouterAssociated -> {
                        context.log("已与路由器建立连接，MAC=${event.bssid}")
                    }

                    is WifiLogEvent.Handshake1Of4 -> {
                        context.updateProgress(
                            TaskProgress.ConnectWifi(ConnectWifiStage.WPA_HANDSHAKE_1_OF_4),
                        )
                        context.log("进入阶段：WPA 握手 1/4")
                        if (handshakeTimeout != null && handshakeDeadline == null) {
                            handshakeDeadline = SystemClock.elapsedRealtime() +
                                handshakeTimeout.handshakeStepTimeoutMillis
                        }
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
                        return
                    }

                    is WifiLogEvent.PasswordError -> {
                        if (config.failureFlags.passwordError) {
                            context.log("连接失败：密码错误")
                            return
                        }
                        context.log("检测到密码错误，当前配置未将其设为终止条件")
                    }

                    is WifiLogEvent.HandshakeFailed -> {
                        context.log("连接失败：WPA 四次握手失败")
                        return
                    }

                    is WifiLogEvent.AssociationRejected -> {
                        context.log(
                            "连接失败：路由器拒绝接入" +
                                (event.reason?.let { "，原因=$it" } ?: ""),
                        )
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
        }
    }
}
