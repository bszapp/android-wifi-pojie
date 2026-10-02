package io.github.bszapp.wifitoolbox.contract.log

import kotlinx.coroutines.flow.StateFlow

interface IServiceLogController : ILogController {
    val systemWifiEntries: StateFlow<List<ServiceLogEntry>>
    val systemWifiLatestId: StateFlow<Long>
    val systemWifiRawViewEnabled: StateFlow<Boolean>

    fun clearSystemWifi()
    fun setSystemWifiRawViewEnabled(enabled: Boolean)
}
