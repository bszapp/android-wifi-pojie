package com.wifi.toolbox.structs

data class PojieRunInfo(
    val ssid: String,
    val tryList: List<String>,
    val tryIndex: Int = 0,
    val lastTryTime: Long = 0,
    val retryCount: Int = 0,
    val textTip: String = "正在准备"
)