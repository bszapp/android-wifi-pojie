package io.github.bszapp.wifitoolbox.contract.settings

import kotlinx.coroutines.flow.StateFlow

interface ISettingsManager {
    val settings: ApplicationSettings
    val revision: StateFlow<Long>

    fun save()
}
