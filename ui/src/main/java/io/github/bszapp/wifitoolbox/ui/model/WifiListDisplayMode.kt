package io.github.bszapp.wifitoolbox.ui.model

import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode

/** 仅供界面使用的有效模式，不写回各ViewModel保存的子模式。 */
internal data class WifiListDisplayMode(
    val mode: WifiMode,
    val submode: NormalWifiSubmode,
    val taskRunning: Boolean,
)

internal fun resolveWifiListDisplayMode(
    mode: WifiMode?,
    submode: NormalWifiSubmode,
    currentTask: TaskSnapshot?,
): WifiListDisplayMode {
    val running = currentTask?.takeIf { it.state == TaskExecutionState.RUNNING }
        ?: return WifiListDisplayMode(mode ?: WifiMode.NORMAL, submode, taskRunning = false)
    return when (running.request.payload) {
        is TaskRequestPayload.PskSecurityAudit -> WifiListDisplayMode(
            WifiMode.NORMAL, NormalWifiSubmode.PSK_SECURITY_AUDIT, taskRunning = true,
        )
        is TaskRequestPayload.WpsSecurityAudit -> WifiListDisplayMode(
            WifiMode.NORMAL, NormalWifiSubmode.WPS_SECURITY_AUDIT, taskRunning = true,
        )
        is TaskRequestPayload.ConnectWifi,
        is TaskRequestPayload.WpsPbc,
        is TaskRequestPayload.WpsPin -> WifiListDisplayMode(
            WifiMode.NORMAL, NormalWifiSubmode.SCAN, taskRunning = true,
        )
        TaskRequestPayload.UsbMonitor -> WifiListDisplayMode(
            WifiMode.MONITOR, submode, taskRunning = true,
        )
    }
}
