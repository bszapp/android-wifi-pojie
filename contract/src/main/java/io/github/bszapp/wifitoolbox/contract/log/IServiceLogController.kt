package io.github.bszapp.wifitoolbox.contract.log

import kotlinx.coroutines.flow.StateFlow

interface IServiceLogController {
    val entries: StateFlow<List<ServiceLogEntry>>
    val latestId: StateFlow<Long>
    val rawViewEnabled: StateFlow<Boolean>

    fun clear()
    fun setRawViewEnabled(enabled: Boolean)
}
