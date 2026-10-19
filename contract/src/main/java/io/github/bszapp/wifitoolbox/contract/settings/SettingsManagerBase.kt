package io.github.bszapp.wifitoolbox.contract.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

abstract class SettingsManagerBase(
    final override val settings: ApplicationSettings = ApplicationSettings(),
) : ISettingsManager {
    private val _revision = MutableStateFlow(0L)
    //TODO:这是什么东西
    final override val revision: StateFlow<Long> = _revision.asStateFlow()

    protected fun bindAutoSave() {
        settings.forEachValueSetting { item ->
            item.onChanged = {
                save()
                _revision.value = _revision.value + 1L
            }
        }
    }

    protected fun loadFromJson(json: JSONObject?) {
        val migrated = json?.let { JSONObject(it.toString()) }
        // 旧名称与旧版分模式日志设置只在读取时兼容，后续保存统一使用新结构。
        if (migrated != null) {
            if (!migrated.has("pskSecurityAudit")) {
                migrated.optJSONObject("securityAudit")?.let { migrated.put("pskSecurityAudit", it) }
            }
            if (!migrated.has("logPanel")) {
                val previous = migrated.optJSONObject("pskSecurityAudit")
                val panel = previous?.optJSONObject("password")?.optJSONObject("logPanel")
                    ?: previous?.optJSONObject("wps")?.optJSONObject("logPanel")
                panel?.let { migrated.put("logPanel", it) }
            }
        }
        settings.loadRootJson(migrated)
        _revision.value = _revision.value + 1L
    }

    protected fun exportJson(lastVersion: Long): JSONObject = settings.toRootJson(lastVersion)
}
