package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal data class MonitorModeSheetTarget(
    val channel: Int,
    val frequencyMhz: Int,
    val accessPoints: List<MonitorModeAccessPoint>,
)

internal data class MonitorModeAccessPoint(
    val name: String,
    val mac: String,
)

private enum class MonitorModeSheetStep {
    CONFIRMATION,
    COMMAND,
}

@Composable
internal fun MonitorModeSheet(
    target: MonitorModeSheetTarget,
    onDismiss: () -> Unit,
    onExecute: (command: String, channel: Int, frequencyMhz: Int) -> Unit,
) {
    var step by remember(target.channel, target.frequencyMhz) {
        mutableStateOf(MonitorModeSheetStep.CONFIRMATION)
    }
    var permissionConfirmed by remember(target.channel, target.frequencyMhz) {
        mutableStateOf(false)
    }
    var command by remember(target.channel, target.frequencyMhz) {
        mutableStateOf(DEFAULT_MONITOR_COMMAND)
    }
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    OverlayBottomSheet(
        show = true,
        title = "进入监听模式",
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(bottom = bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "信道 ${target.channel} · " +
                        "${frequencyBand(target.frequencyMhz)} · ${target.frequencyMhz} MHz",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }

            when (step) {
                MonitorModeSheetStep.CONFIRMATION -> {
                    item {
                        Text(
                            text = "进入监听模式可能会录制到以下接入点的信号：",
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    item {
                        Card {
                            target.accessPoints.forEach { accessPoint ->
                                BasicComponent(
                                    title = accessPoint.name,
                                    summary = accessPoint.mac,
                                )
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { permissionConfirmed = !permissionConfirmed }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                state = if (permissionConfirmed) {
                                    ToggleableState.On
                                } else {
                                    ToggleableState.Off
                                },
                                onClick = { permissionConfirmed = !permissionConfirmed },
                            )
                            Text(
                                text = "我已拥有以上网络的测试权限，且知晓监听模式只用来诊断信号使用",
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .weight(1f),
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                text = "取消",
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "下一步",
                                onClick = { step = MonitorModeSheetStep.COMMAND },
                                enabled = permissionConfirmed,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }

                MonitorModeSheetStep.COMMAND -> {
                    item {
                        Text(
                            text = "请将信道留为【信道】。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    item {
                        TextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "进入命令",
                            minLines = 11,
                            maxLines = 16,
                        )
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                text = "上一步",
                                onClick = { step = MonitorModeSheetStep.CONFIRMATION },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "执行",
                                onClick = {
                                    onExecute(command, target.channel, target.frequencyMhz)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
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
ip link set wlan0 up
iw dev wlan0 set channel 【信道】 HT20"""
