package com.wifi.toolbox.structs

data class WifiLogData(
    val event: Int,
    val eventStartTime: Long,
    val ssid: String?,
    val handshakeUseTime: Int = 0,
    val handshakeCount: Int = 0
) {
    companion object {
        const val EVENT_WIFI_CONNECTED = 0
        const val EVENT_CONNECT_FAILED = 1
        const val EVENT_HANDSHAKE = 2
        const val EVENT_CONNECT_ERROR = 3
    }
}