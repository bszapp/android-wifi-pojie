package io.github.bszapp.wifitoolbox.ui.startup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.startup.StartupState
import kotlinx.coroutines.flow.StateFlow

class StartupViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: IAppController = AppControllerProvider.get()

    val state: StateFlow<StartupState> = controller.startup.state

    fun launch(mode: StartupMode) = controller.startup.launch(mode)
    fun buildShellStartupCommand(): String = controller.startup.buildShellStartupCommand()
    fun prepareForManualStartup() = controller.startup.prepareForManualStartup()
    fun cancel() = controller.startup.cancel()
}
