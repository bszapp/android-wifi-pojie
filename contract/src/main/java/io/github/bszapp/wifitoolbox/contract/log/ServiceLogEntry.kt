package io.github.bszapp.wifitoolbox.contract.log

data class ServiceLogEntry(
    val id: Long,
    val tag: String,
    val rawLine: String,
)

data class ServiceLogBatch(
    val oldestAvailableId: Long,
    val latestId: Long,
    val entries: List<ServiceLogEntry>,
)
