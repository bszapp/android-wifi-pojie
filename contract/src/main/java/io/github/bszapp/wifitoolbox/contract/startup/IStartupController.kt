package io.github.bszapp.wifitoolbox.contract.startup

import kotlinx.coroutines.flow.StateFlow

interface IStartupController {
    val state: StateFlow<StartupState>
    fun launch(mode: StartupMode)
    fun buildShellStartupCommand(): String
    fun prepareForManualStartup()
    fun cancel()
    suspend fun stop(exit: Boolean)
    fun disconnect(exit: Boolean)
}
