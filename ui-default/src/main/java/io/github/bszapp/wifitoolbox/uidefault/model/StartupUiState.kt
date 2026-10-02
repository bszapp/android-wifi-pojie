package io.github.bszapp.wifitoolbox.uidefault.model

import io.github.bszapp.wifitoolbox.contract.IAppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class StartupUiState(private val controller: IAppController, scope: CoroutineScope) {
//TODO:太散了！
    val serviceInfo = controller.startup.state
        .map { it.serviceInfo }
        .stateIn(scope, SharingStarted.Eagerly, controller.startup.state.value.serviceInfo)

    fun stop(exit: Boolean) = controller.startup.stop(exit)
}
