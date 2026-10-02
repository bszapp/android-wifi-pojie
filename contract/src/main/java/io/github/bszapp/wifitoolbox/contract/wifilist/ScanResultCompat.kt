package io.github.bszapp.wifitoolbox.contract.wifilist

import android.net.wifi.ScanResult
import android.os.Build

/** 供服务解析结果与 UI 补全未知信号接入点共用的兼容创建入口。 */
fun createScanResultCompat(): ScanResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return ScanResult()
    return runCatching {
        ScanResult::class.java.getDeclaredConstructor().newInstance()
    }.getOrElse { error ->
        throw IllegalStateException("当前系统无法创建兼容的 ScanResult", error)
    }
}
