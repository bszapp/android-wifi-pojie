package io.github.bszapp.wifitoolbox.contract.log

import kotlinx.coroutines.flow.StateFlow

/** 单个日志来源的只读显示数据与用户操作。 */
interface ILogController {
    val entries: StateFlow<List<ServiceLogEntry>>
    val latestId: StateFlow<Long>
    val rawViewEnabled: StateFlow<Boolean>

    fun clear()
    fun setRawViewEnabled(enabled: Boolean)
}
