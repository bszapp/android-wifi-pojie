package io.github.bszapp.wifitoolbox.uidefault.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.IAppController

class DefaultViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: IAppController = AppControllerProvider.get()

    /** App 内唯一错误广播源，UI 不再监听任何 Wi-Fi 专用错误流。 */
    val errors = controller.errors
    val serviceLogs = controller.serviceLogs

    val startup = StartupUiState(controller, viewModelScope)
    val wifiList = WifiListUiState(
        controller = controller,
        scope = viewModelScope,
    )
}
