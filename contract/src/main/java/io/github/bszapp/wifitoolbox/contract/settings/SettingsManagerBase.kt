package io.github.bszapp.wifitoolbox.contract.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

abstract class SettingsManagerBase(
    final override val settings: ApplicationSettings = ApplicationSettings(),
) : ISettingsManager {
    private val _revision = MutableStateFlow(0L)
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
        settings.loadRootJson(json)
        _revision.value = _revision.value + 1L
    }

    protected fun exportJson(lastVersion: Long): JSONObject = settings.toRootJson(lastVersion)
}
