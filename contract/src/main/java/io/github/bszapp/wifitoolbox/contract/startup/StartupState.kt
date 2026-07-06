package io.github.bszapp.wifitoolbox.contract.startup

data class StartupState(
    val status: StartupStatus = StartupStatus.IDLE,
    val selectedMode: StartupMode? = null,
    val errorException: Exception? = null,
    val serviceUid: Int? = null,
    val serviceUidStr: String? = null,
    val servicePid: Int? = null,
    val serviceVersionName: String? = null,
    val serviceVersionCode: Long? = null
)
