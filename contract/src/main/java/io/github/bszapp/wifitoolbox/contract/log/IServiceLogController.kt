package io.github.bszapp.wifitoolbox.contract.log

import kotlinx.coroutines.flow.StateFlow

interface IServiceLogController {
    val entries: StateFlow<List<ServiceLogEntry>>
    val latestId: StateFlow<Long>
    val rawViewEnabled: StateFlow<Boolean>
    val systemWifiEntries: StateFlow<List<ServiceLogEntry>>
    val systemWifiLatestId: StateFlow<Long>
    val systemWifiRawViewEnabled: StateFlow<Boolean>

    fun clear()
    fun setRawViewEnabled(enabled: Boolean)
    fun clearSystemWifi()
    fun setSystemWifiRawViewEnabled(enabled: Boolean)
}
