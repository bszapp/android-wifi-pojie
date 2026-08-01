package io.github.bszapp.wifitoolbox.contract

import io.github.bszapp.wifitoolbox.contract.error.AppError
import io.github.bszapp.wifitoolbox.contract.container.IContainerController
import io.github.bszapp.wifitoolbox.contract.log.IServiceLogController
import io.github.bszapp.wifitoolbox.contract.settings.ISettingsManager
import io.github.bszapp.wifitoolbox.contract.startup.IStartupController
import io.github.bszapp.wifitoolbox.contract.terminal.ITerminalController
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface IAppController {
    val startup: IStartupController
    val wifiList: IWifiListController
    val serviceLogs: IServiceLogController
    val terminals: ITerminalController
    val settings: ISettingsManager
    val containers: IContainerController
    val errors: SharedFlow<AppError>
    val isExiting: StateFlow<Boolean>//TODO:未免显得有些突兀的分类？
}
