package io.github.bszapp.wifitoolbox.contract.terminal

import kotlinx.coroutines.flow.StateFlow

interface ITerminalController {
    val state: StateFlow<TerminalManagerState>
    val appState: StateFlow<TerminalManagerState>

    fun createServiceTerminal()

    fun createAppTerminal()

    fun clearLogs(terminalId: Long)

    fun clearAppLogs(terminalId: Long)

    fun sendInput(terminalId: Long, text: String)

    fun sendAppInput(terminalId: Long, text: String)

    fun closeTerminal(terminalId: Long)

    fun closeAppTerminal(terminalId: Long)
}
