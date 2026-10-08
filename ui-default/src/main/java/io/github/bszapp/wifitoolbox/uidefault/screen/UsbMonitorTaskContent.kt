package io.github.bszapp.wifitoolbox.uidefault.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 所有连接信息均来自当前服务任务快照，页面不独立保存任务或连接状态。 */
@Composable
internal fun UsbMonitorTaskContent(
    progress: TaskProgress.UsbMonitor?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            BasicComponent(
                title = "正在使用电脑控制",
                summary = "Wifitoolbox Network Driver",
                startAction = {
                    Icon(Icons.Rounded.Usb, null, tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp))
                },
            )
            BasicComponent(
                title = "电脑设备连接状态",
                summary = when {
                    progress == null -> "加载中"
                    !progress.usbConnected -> "等待 USB 连接"
                    !progress.clientConnected -> "USB 已连接，等待电脑采集程序"
                    progress.capturing -> "电脑已连接，正在采集"
                    else -> "电脑已连接，采集已暂停"
                },
            )
        }
        TextButton(
            text = "停止",
            onClick = onStop,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
