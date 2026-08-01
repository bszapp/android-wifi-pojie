package io.github.bszapp.wifitoolbox.contract.terminal

import kotlinx.coroutines.flow.StateFlow

interface ITerminalController {
    val state: StateFlow<TerminalManagerState>

    fun clearLogs(terminalId: Long)
}
