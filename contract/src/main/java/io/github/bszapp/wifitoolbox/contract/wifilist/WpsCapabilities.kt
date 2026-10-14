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
    return wpsDeviceInfo()?.configMethods?.let { it and (0x0004 or 0x0008 or 0x0100) != 0 } == true
}

/** 来自扫描 WPS 广播的原始设备字段；未知字段保留为空，不能用 MAC 厂商猜测型号。 */
data class WpsDeviceInfo(
    val model: String,
    val modelNumber: String,
    val deviceName: String,
    val manufacturer: String,
    val configMethods: Int?,
)

fun ScanResult.wpsDeviceInfo(): WpsDeviceInfo? {
    return runCatching {
        val elements = runCatching {
            ScanResult::class.java.getMethod("getInformationElements").invoke(this) as? List<*>
        }.getOrNull() ?: (ScanResult::class.java.getField("informationElements").get(this) as? Array<*>)?.toList()
            ?: return@runCatching null
        val payload = java.io.ByteArrayOutputStream()
        for (element in elements.filterNotNull()) {
            val type = element.javaClass
            val id = runCatching { type.getMethod("getId").invoke(element) }.getOrElse {
                type.getField("id").getInt(element)
            }
            if (id != 221) continue
            val bytes = runCatching {
                val buffer = (type.getMethod("getBytes").invoke(element) as ByteBuffer).duplicate()
                ByteArray(buffer.remaining()).also(buffer::get)
            }.getOrElse { type.getField("bytes").get(element) as ByteArray }
            if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0xf2.toByte() && bytes[3] == 4.toByte()) {
                payload.write(bytes, 4, bytes.size - 4)
            }
        }
        val data = payload.toByteArray()
        if (data.isEmpty()) return@runCatching null
        val attributes = linkedMapOf<Int, ByteArray>()
        var offset = 0
        fun unsigned(at: Int) = data[at].toInt() and 255
        while (offset + 4 <= data.size) {
            val attribute = (unsigned(offset) shl 8) or unsigned(offset + 1)
            val length = (unsigned(offset + 2) shl 8) or unsigned(offset + 3)
            offset += 4
            if (length > data.size - offset) break
            if (attribute !in attributes) attributes[attribute] = data.copyOfRange(offset, offset + length)
            offset += length
        }
        fun text(attribute: Int): String = attributes[attribute]?.let { bytes ->
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte()) end--
            String(bytes, 0, end, Charsets.UTF_8)
        }.orEmpty()
        val methods = attributes[0x1008]?.takeIf { it.size == 2 }?.let {
            ((it[0].toInt() and 255) shl 8) or (it[1].toInt() and 255)
        }
        WpsDeviceInfo(text(0x1023), text(0x1024), text(0x1011), text(0x1021), methods)
    }.getOrNull()
}
