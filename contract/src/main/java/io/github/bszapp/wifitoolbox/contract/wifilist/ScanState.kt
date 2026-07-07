package io.github.bszapp.wifitoolbox.contract.wifilist

import android.net.wifi.ScanResult

data class ScanState(
    val status: ScanStatus? = null,
    val scanResults: List<ScanResult> = emptyList(),
    val isScanning: Boolean = false,
    val errorException: Exception? = null
)
