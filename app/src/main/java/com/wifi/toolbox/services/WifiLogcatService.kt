package com.wifi.toolbox.services

import android.util.Log
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.structs.WifiLogData
import com.wifi.toolbox.utils.CommandRunner
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.function.Consumer

class WifiLogcatService(private val pojieSettings: PojieSettings) : AutoCloseable {
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

    private fun decodeHexSsid(hexSsid: String): String {
        if (!hexSsid.contains("\\x")) return hexSsid
        return try {
            val bais = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < hexSsid.length) {
                if (hexSsid[i] == '\\' && i + 3 < hexSsid.length && hexSsid[i + 1] == 'x') {
                    val hex = hexSsid.substring(i + 2, i + 4)
                    bais.write(hex.toInt(16))
                    i += 4
                } else {
                    bais.write(hexSsid[i].code)
                    i++
                }
            }
            bais.toString("UTF-8")
        } catch (_: Exception) {
            hexSsid
        }
    }

    fun executeCommand(
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        var stopFunc = Runnable {}
        when (pojieSettings.commandMethod) {
            0 -> throw Exception("命令行实现方式为空，请先去设置中选择")
            1 -> stopFunc = ShizukuUtil.executeCommand(command, onOutputReceived, onCommandFinished)
            2 -> throw Exception("前面的区域，以后再来探索吧(commandMethod=2)")
        }
        return stopFunc
    }

    init {
        stopFunc = executeCommand(
            command = "sh -c \"logcat -c && logcat -s \\\"WifiService:D\\\" \\\"wpa_supplicant:D\\\" \\\"DhcpClient:D\\\"\"",
            onOutputReceived = { line ->
                Log.d("WifiLogcatService", line)
                when {
                    line.contains("Trying to associate with SSID") -> {
                        val match = Regex("SSID '(.*?)'").find(line)
                        if (match != null) {
                            currentSsid = decodeHexSsid(match.groupValues[1])
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

                    line.contains("CTRL-EVENT-ASSOC-REJECT") -> {
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_CONNECT_ERROR,
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