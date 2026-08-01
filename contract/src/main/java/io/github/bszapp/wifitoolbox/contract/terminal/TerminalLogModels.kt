package io.github.bszapp.wifitoolbox.contract.terminal

data class TerminalLogEntry(
    val id: Long,
    val text: String,
)

data class TerminalLogBatch(
    val terminalId: Long,
    val generation: Long,
    val oldestAvailableId: Long,
    val latestId: Long,
    val lineCount: Int,
    val entries: List<TerminalLogEntry>,
)

data class TerminalLogState(
    val terminalId: Long,
    val generation: Long = 0L,
    val oldestAvailableId: Long = 1L,
    val latestId: Long = 0L,
    val lineCount: Int = 0,
    val entries: List<TerminalLogEntry> = emptyList(),
)

data class TerminalManagerState(
    val aliveGeneration: Long = 0L,
    val aliveTerminalIds: List<Long> = emptyList(),
    val terminals: Map<Long, TerminalLogState> = emptyMap(),
)
