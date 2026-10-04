package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.runtime.Composable
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeSwitch
import io.github.bszapp.wifitoolbox.uidefault.component.BlockingLoadingDialog

/** 始终参与组合；服务保留的切换目标让退出动画期间的文字保持不变。 */
@Composable
internal fun WifiModeSwitchSheet(progress: WifiModeSwitch?, onInterrupt: (Long) -> Unit) {
    val message = progress?.targetMode?.let { "正在切换到${it.displayName}" } ?: "正在切换网卡模式"
    BlockingLoadingDialog(
        visible = progress?.isRunning == true,
        operationId = progress?.operationId ?: 0L,
        text = message,
        interruptionWarning = "强制结束模式切换终端及相关操作，并重新读取网卡实际模式。已执行的命令不会自动回滚。",
        onInterrupt = onInterrupt,
    )
}
