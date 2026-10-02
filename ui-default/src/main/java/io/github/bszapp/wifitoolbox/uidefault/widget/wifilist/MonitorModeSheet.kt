package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun MonitorModeSheet(show: Boolean, onDismiss: () -> Unit, onExecute: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf(DEFAULT_MONITOR_COMMAND) }
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    SingleOverlayBottomSheet(
        show = show,
        title = "进入监听模式",
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).scrollEndHaptic().overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "此命令只切换网卡模式，持续抓取的信道在开始抓取时选择。\n" +
                            "支持监听模式的网卡较少，可能需要自定义内核。以下命令已在 Redmi Note 12T Pro 测试，其他设备支持情况未知，可按设备情况修改。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                item {
                    TextField(value = command, onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth(), label = "进入命令", minLines = 9, maxLines = 16)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(text = "执行", onClick = { onExecute(command) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

private const val DEFAULT_MONITOR_COMMAND = """svc wifi disable
setprop ctl.restart wificond
setprop ctl.restart vendor.wifi_hal_legacy
start wificond
start vendor.wifi_hal_legacy
pkill wpa_supplicant
ip link set wlan0 down
iw wlan0 set type monitor
ip link set wlan0 up"""
