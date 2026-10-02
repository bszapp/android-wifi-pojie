package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeSwitch
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 始终参与组合；服务保留的切换目标让退出动画期间的文字保持不变。 */
@Composable
internal fun WifiModeSwitchSheet(progress: WifiModeSwitch?) {
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val message = progress?.targetMode?.let { "正在切换到${it.displayName}" } ?: "正在切换网卡模式"
    SingleOverlayBottomSheet(
        show = progress?.isRunning == true,
        title = "切换网卡模式",
        allowDismiss = false,
        onDismissRequest = {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(message, style = MiuixTheme.textStyles.body2)
        }
    }
}
