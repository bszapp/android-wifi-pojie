package io.github.bszapp.wifitoolbox.contract

import io.github.bszapp.wifitoolbox.contract.settings.ISettingsManager
import io.github.bszapp.wifitoolbox.contract.startup.IStartupController
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import kotlinx.coroutines.flow.StateFlow

interface IAppController {
    val startup: IStartupController
    val wifiList: IWifiListController
    val settings: ISettingsManager
    val isExiting: StateFlow<Boolean>
}