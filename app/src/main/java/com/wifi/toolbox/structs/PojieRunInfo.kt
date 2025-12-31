package com.wifi.toolbox.structs

data class PojieRunInfo(
    val ssid: String,
    var tryList: List<String>,
    var tryIndex: Int = 0,
    var lastTryTime: Long = 0,
    var retryCount: Int = 0,
    var textTip: String = "正在准备"
)