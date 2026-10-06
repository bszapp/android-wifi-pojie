package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import android.os.Build
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun MonitorModeSheet(show: Boolean, onDismiss: () -> Unit, onExecute: (String) -> Unit) {
    var preset by rememberSaveable {
        mutableStateOf(if (DeviceModelInfo() == DeviceModel.QUALCOMM) MonitorCommandPreset.QUALCOMM else if (DeviceModelInfo() == DeviceModel.MEDIATEK) MonitorCommandPreset.MEDIATEK else MonitorCommandPreset.GENERAL)
    }
    var command by rememberSaveable { mutableStateOf(preset.command) }
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
                            "一般命令已在 Redmi Note 12T Pro 测试，高通专用命令已在小米6测试。监听模式支持情况依赖设备驱动，可选择命令并按设备情况编辑。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                item {
                    Card {
                        MonitorCommandPreset.entries.forEach { option ->
                            BasicComponent(
                                title = option.title,
                                role = Role.RadioButton,
                                onClick = {
                                    preset = option
                                    command = option.command
                                },
                                endActions = { RadioButton(selected = preset == option, onClick = null) },
                            )
                        }
                    }
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

enum class DeviceModel {
    QUALCOMM,
    MEDIATEK,
    GENERAL
}

private fun DeviceModelInfo(): DeviceModel
{
    if (Build.HARDWARE.startsWith("qcom", ignoreCase = true) || Build.HARDWARE.contains("qualcomm", ignoreCase = true) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MANUFACTURER.contains("qualcomm", ignoreCase = true))
    {
        return DeviceModel.QUALCOMM;
    }
    else if (Build.HARDWARE.startsWith("mt", ignoreCase = true))
    {
        return DeviceModel.MEDIATEK;
    }
    else
    {
        return DeviceModel.GENERAL;
    }
}

private enum class MonitorCommandPreset(val title: String, val command: String) {
    GENERAL("一般命令", DEFAULT_MONITOR_COMMAND),
    QUALCOMM("高通专用", QUALCOMM_MONITOR_COMMAND),
    MEDIATEK("天玑专用", MEDIATEK_MONITOR_COMMAND)
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

private const val QUALCOMM_MONITOR_COMMAND = """stop wpa_supplicant
stop vendor.wifi_hal_legacy
stop wificond
ip link set wlan0 down
if ip link show wlan1 >/dev/null 2>&1; then
    ip link set wlan1 down
fi
if ip link show p2p0 >/dev/null 2>&1; then
    ip link set p2p0 down
fi
printf '4\n' > /sys/module/wlan/parameters/con_mode
ip link set wlan0 up"""

private const val MEDIATEK_MONITOR_COMMAND = """svc wifi disable
setprop vendor.hardware.wlan.runtcpdump stop
ifconfig wlan0 down
/vendor/bin/iwpriv wlan0 driver "set_chip KeepFullPwr 1"
/vendor/bin/iw-vendor dev wlan0 set type monitor
/vendor/bin/iwpriv wlan0 driver monitor=0-1-1-0-0-0-0-1-0
/vendor/bin/iwpriv wlan0 driver "set_chip KeepFullPwr 0"
ifconfig wlan0 up
"""