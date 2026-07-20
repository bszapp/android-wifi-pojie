package io.github.bszapp.wifitoolbox.uidefault.model

import io.github.bszapp.wifitoolbox.contract.IAppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class StartupUiState(private val controller: IAppController, scope: CoroutineScope) {
//TODO:太散了！
    val uid = controller.startup.state
        .map { it.serviceUid }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val pid = controller.startup.state
        .map { it.servicePid }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val uidStr = controller.startup.state
        .map { it.serviceUidStr }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val mode = controller.startup.state
        .map { it.selectedMode }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val serviceVersionName = controller.startup.state
        .map { it.serviceVersionName }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val serviceVersionCode = controller.startup.state
        .map { it.serviceVersionCode }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun stop(exit: Boolean) = controller.startup.stop(exit)
}
