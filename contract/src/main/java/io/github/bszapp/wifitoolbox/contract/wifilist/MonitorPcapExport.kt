package io.github.bszapp.wifitoolbox.contract.wifilist

data class MonitorPcapExportResult(
    val requestId: String,
    val path: String,
    val fileName: String,
)
