package io.github.bszapp.wifitoolbox.contract.settings

import org.json.JSONObject

/** App本地的日志阅读设置，不进入服务任务配置或跨进程传输。 */
data class AuditLogPanelConfig(
    val softWrap: Boolean = true,
    val autoScroll: Boolean = true,
    val fontSizeSp: Float = 13f,
)

class AuditLogPanelConfigSetting : ValueSetting<AuditLogPanelConfig>("logPanel", AuditLogPanelConfig()) {
    override fun toJsonValue(): JSONObject = JSONObject()
        .put("softWrap", current.softWrap)
        .put("autoScroll", current.autoScroll)
        .put("fontSizeSp", current.fontSizeSp.toDouble())

    override fun loadFromJsonValue(value: Any?) {
        val json = value as? JSONObject
        val defaults = AuditLogPanelConfig()
        setLoadedValue(AuditLogPanelConfig(
            softWrap = json?.optBoolean("softWrap", defaults.softWrap) ?: defaults.softWrap,
            autoScroll = json?.optBoolean("autoScroll", defaults.autoScroll) ?: defaults.autoScroll,
            fontSizeSp = json?.optDouble("fontSizeSp", defaults.fontSizeSp.toDouble())?.toFloat()
                ?.takeIf { it.isFinite() && it in 6f..32f } ?: defaults.fontSizeSp,
        ))
    }
}
