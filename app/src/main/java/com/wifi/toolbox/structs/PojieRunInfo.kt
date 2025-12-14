package com.wifi.toolbox.structs

data class PojieRunInfo(
    val ssid: String,
    val tryList: List<String>,
    val tryIndex: Int = 0,
    val lastTryTime: Long = 0,
    val status: Int = 0,
    val textTip: String = "正在准备"
){
    companion object {
        const val STATUS_WAITING = 0
        const val STATUS_RUNNING = 1
    }
}