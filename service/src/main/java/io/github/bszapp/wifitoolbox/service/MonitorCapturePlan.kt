package io.github.bszapp.wifitoolbox.service

import org.json.JSONObject

/** 由 Wi-Fi 控制器唯一持有的持续录制目标；扫描只能临时借用网卡。 */
internal sealed interface MonitorCapturePlan {
    data object Stopped : MonitorCapturePlan
    data class Fixed(val frequencyMhz: Int) : MonitorCapturePlan
    data object Hopping : MonitorCapturePlan

    fun toJson(): JSONObject = when (this) {
        Stopped -> JSONObject().put("type", "stopped")
        is Fixed -> JSONObject().put("type", "fixed").put("frequencyMhz", frequencyMhz)
        Hopping -> JSONObject().put("type", "hopping")
    }
}
