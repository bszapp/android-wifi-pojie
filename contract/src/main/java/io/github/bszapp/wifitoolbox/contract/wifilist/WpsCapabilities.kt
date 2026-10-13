package io.github.bszapp.wifitoolbox.contract.wifilist

import android.net.wifi.ScanResult
import java.nio.ByteBuffer

/** WPS 广播允许用户提交已知 PIN；广播未声明 PIN 方法时，由实际握手返回结果。 */
fun ScanResult.supportsWps(): Boolean = capabilities.orEmpty().let {
    it.contains("[WPS]") || it.contains("[WPS-PIN]")
}

/** 底层扫描直接标记 PIN 能力；系统扫描仅在提供 WPS 信息元素时判断。 */
fun ScanResult.supportsWpsPin(): Boolean {
    if (capabilities.orEmpty().contains("[WPS-PIN]")) return true
    return runCatching {
        val elements = ScanResult::class.java.getMethod("getInformationElements").invoke(this) as? List<*>
            ?: return@runCatching false
        val payload = java.io.ByteArrayOutputStream()
        for (element in elements.filterNotNull()) {
            val type = element.javaClass
            if (type.getMethod("getId").invoke(element) != 221) continue
            val buffer = (type.getMethod("getBytes").invoke(element) as ByteBuffer).duplicate()
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0xf2.toByte() && bytes[3] == 4.toByte()) {
                payload.write(bytes, 4, bytes.size - 4)
            }
        }
        val data = payload.toByteArray()
        var offset = 0
        fun unsigned(at: Int) = data[at].toInt() and 255
        while (offset + 4 <= data.size) {
            val attribute = (unsigned(offset) shl 8) or unsigned(offset + 1)
            val length = (unsigned(offset + 2) shl 8) or unsigned(offset + 3)
            offset += 4
            if (length > data.size - offset) return@runCatching false
            if (attribute == 0x1008 && length == 2) {
                val methods = (unsigned(offset) shl 8) or unsigned(offset + 1)
                if (methods and (0x0004 or 0x0008 or 0x0100) != 0) return@runCatching true
            }
            offset += length
        }
        false
    }.getOrDefault(false)
}
