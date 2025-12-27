package com.wifi.toolbox.services

import android.util.Log
import com.wifi.toolbox.structs.WifiLogData
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class WifiLogcatService : AutoCloseable {
    var stopFunc: Runnable? = null

    private val _logFlow = MutableSharedFlow<WifiLogData>(extraBufferCapacity = 64)
    val logFlow = _logFlow.asSharedFlow()

    private var currentSsid: String? = null
    private var connectStartTime: Long = 0L
    private var handshakeStartTime: Long = 0L
    private var handshakeCount: Int = 0

    override fun close() {
        stopFunc?.run()
        stopFunc = null
    }

    init {
        stopFunc = ShizukuUtil.executeCommand(
            command = "sh -c \"logcat -c && logcat -s \\\"WifiService:D\\\" \\\"wpa_supplicant:D\\\" \\\"DhcpClient:D\\\"\"",
            onOutputReceived = { line ->
                Log.d("WifiLogcatService", line)
                when {
                    line.contains("Trying to associate with SSID") -> {
                        val match = Regex("SSID '(.*?)'").find(line)
                        if (match != null) {
                            currentSsid = match.groupValues[1]
                            connectStartTime = System.currentTimeMillis()
                            handshakeStartTime = 0L
                            handshakeCount = 0
                        }
                    }

                    line.contains("WifiService: enableNetwork") -> {
                        connectStartTime = System.currentTimeMillis()
                        handshakeStartTime = 0L
                        handshakeCount = 0
                    }

                    line.contains("WPA: RX message 1 of 4-Way Handshake from") -> {
                        if (handshakeStartTime == 0L)
                            handshakeStartTime = System.currentTimeMillis()
                    }

                    line.contains("WPA: Key negotiation completed with") -> {
                        Log.d("WifiLogcatService", "连接成功")
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_WIFI_CONNECTED,
                                connectStartTime,
                                currentSsid
                            )
                        )
                    }

                    line.contains("Sending EAPOL-Key 2/4") -> {
                        handshakeCount++
                        val useTime =
                            if (handshakeStartTime > 0L) (System.currentTimeMillis() - handshakeStartTime).toInt() else 0
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_HANDSHAKE,
                                connectStartTime,
                                currentSsid,
                                useTime,
                                handshakeCount
                            )
                        )
                    }

                    line.contains("WPA: 4-Way Handshake failed") -> {
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_CONNECT_FAILED,
                                connectStartTime,
                                currentSsid
                            )
                        )
                    }
                }
            },
            onCommandFinished = {
                //注：这里正常不应被执行
            }
        )
    }

}