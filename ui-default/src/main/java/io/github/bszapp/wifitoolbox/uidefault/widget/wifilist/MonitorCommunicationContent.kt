package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDecryptionStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDeviceProtocol

internal fun decryptionStatusText(value: MonitorDecryptionStatus): String = when (value) {
    MonitorDecryptionStatus.PROTOCOL_UNKNOWN -> "尚未确定设备实际使用的协议，不能据此判断通信安全"
    MonitorDecryptionStatus.NO_PASSWORD -> "无目标接入点密码"
    MonitorDecryptionStatus.PASSWORD_MISMATCH -> "本机存储的目标接入点密码有误"
    MonitorDecryptionStatus.INCOMPLETE_HANDSHAKE -> "未捕获到完整握手数据"
    MonitorDecryptionStatus.READY -> "已核验本机保存的密码，可解析捕获的通信数据"
    MonitorDecryptionStatus.SECURE -> "该网络传输的数据是安全的"
    MonitorDecryptionStatus.UNSUPPORTED -> "当前不支持解密设备实际使用的协议或密码套件"
    MonitorDecryptionStatus.LOADING -> "加载中"
}

internal fun deviceProtocolText(value: MonitorDeviceProtocol): String = when (value) {
    MonitorDeviceProtocol.UNKNOWN -> "设备实际协议尚未确定"
    MonitorDeviceProtocol.WPA_PSK -> "设备实际协议：WPA-PSK"
    MonitorDeviceProtocol.WPA2_PSK -> "设备实际协议：WPA2-PSK"
    MonitorDeviceProtocol.WPA2_PSK_SHA256 -> "设备实际协议：WPA2-PSK-SHA256"
    MonitorDeviceProtocol.SAE -> "设备实际协议：SAE（WPA3）"
    MonitorDeviceProtocol.OWE -> "设备实际协议：OWE"
    MonitorDeviceProtocol.OTHER -> "设备实际使用其他认证协议"
}
