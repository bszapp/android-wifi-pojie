package io.github.bszapp.wifitoolbox.contract.startup

data class StartupState(
    val status: StartupStatus = StartupStatus.IDLE,
    val selectedMode: StartupMode? = null,
    val errorException: Exception? = null,
    val serviceInfo: StartupInfo? = null,
    val connectionId: Long = 0L,
)
