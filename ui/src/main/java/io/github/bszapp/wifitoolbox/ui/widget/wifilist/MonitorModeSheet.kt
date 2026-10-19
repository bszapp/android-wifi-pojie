package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.ui.component.PreviewSheetHost
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 精确匹配使用设备代号，处理器条件只用于未经验证设备的推荐。 */
private val MONITOR_COMMAND_PRESETS = listOf(
    MonitorCommandPreset(
        id = "general",
        title = "一般命令",
        testedDevices = listOf(MonitorTestedDevice("pearl", "Redmi Note 12T Pro")),
        processorPattern = Regex("dimensity\\s*8200|\\bmt6896", RegexOption.IGNORE_CASE),
        command = """svc wifi disable
setprop ctl.restart wificond
setprop ctl.restart vendor.wifi_hal_legacy
start wificond
start vendor.wifi_hal_legacy
pkill wpa_supplicant
ip link set wlan0 down
iw wlan0 set type monitor
ip link set wlan0 up""",
    ),
    MonitorCommandPreset(
        id = "qualcomm",
        title = "高通",
        testedDevices = listOf(
            MonitorTestedDevice("raphael", "Redmi K20 Pro"),
            MonitorTestedDevice("sagit", "MI 6"),
        ),
        processorPattern = Regex("qualcomm|qcom|snapdragon|\\b(?:msm|sdm|sm)\\d+", RegexOption.IGNORE_CASE),
        command = """stop wpa_supplicant
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
ip link set wlan0 up""",
    ),
    MonitorCommandPreset(
        id = "mediatek",
        title = "天玑",
        testedDevices = listOf(MonitorTestedDevice("rothko", "Redmi K70 Ultra")),
        processorPattern = Regex("mediatek|dimensity|\\bmt\\d+", RegexOption.IGNORE_CASE),
        command = """svc wifi disable
setprop vendor.hardware.wlan.runtcpdump stop
ifconfig wlan0 down
/vendor/bin/iwpriv wlan0 driver "set_chip KeepFullPwr 1"
/vendor/bin/iw-vendor dev wlan0 set type monitor
/vendor/bin/iwpriv wlan0 driver monitor=0-1-1-0-0-0-0-1-0
/vendor/bin/iwpriv wlan0 driver "set_chip KeepFullPwr 0"
ifconfig wlan0 up
""",
    ),
)

private data class MonitorTestedDevice(val certifiedModel: String, val modelName: String)

private data class MonitorCommandPreset(
    val id: String,
    val title: String,
    val command: String,
    val testedDevices: List<MonitorTestedDevice>,
    val processorPattern: Regex,
) {
    fun testedDevice(device: MonitorDeviceInfo): MonitorTestedDevice? =
        testedDevices.firstOrNull { it.certifiedModel.equals(device.certifiedModel, ignoreCase = true) }

    fun matchesProcessor(device: MonitorDeviceInfo): Boolean =
        device.processorNames.any(processorPattern::containsMatchIn)
}

private data class MonitorDeviceInfo(
    val certifiedModel: String,
    val modelName: String,
    val processorNames: List<String>,
) {
    companion object {
        fun current(): MonitorDeviceInfo = MonitorDeviceInfo(
            certifiedModel = Build.DEVICE,
            modelName = Build.MODEL,
            processorNames = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Build.SOC_MODEL)
                    add(Build.SOC_MANUFACTURER)
                }
                add(Build.HARDWARE)
            }.filter { it.isNotBlank() && !it.equals(Build.UNKNOWN, ignoreCase = true) }.distinct(),
        )
    }
}

@Preview(name = "进入监听模式 · 命令确认", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
internal fun MonitorModeSheetPreview() {
    PreviewSheetHost {
        MonitorModeSheet(
            show = true,
            onDismiss = {},
            onExecute = {},
        )
    }
}

@Composable
internal fun MonitorModeSheet(show: Boolean, onDismiss: () -> Unit, onExecute: (String) -> Unit) {
    val device = remember { MonitorDeviceInfo.current() }
    val defaultPreset = remember(device) {
        MONITOR_COMMAND_PRESETS.firstOrNull { it.testedDevice(device) != null }
            ?: MONITOR_COMMAND_PRESETS.firstOrNull { it.matchesProcessor(device) }
            ?: MONITOR_COMMAND_PRESETS.first()
    }
    var selectedId by rememberSaveable { mutableStateOf(defaultPreset.id) }
    var command by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(defaultPreset.command))
    }
    var wasOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(show) {
        // 每次重新打开自动推荐；旋转重建时保留正在编辑的内容和选中项。
        if (show && !wasOpen) {
            selectedId = defaultPreset.id
            command = TextFieldValue(defaultPreset.command)
        }
        wasOpen = show
    }
    val selectedPreset = MONITOR_COMMAND_PRESETS.firstOrNull { it.id == selectedId } ?: defaultPreset
    val testedDevice = selectedPreset.testedDevice(device)
    val isModified = command.text != selectedPreset.command
    val displayName = MONITOR_COMMAND_PRESETS.firstNotNullOfOrNull { it.testedDevice(device) }?.modelName
        ?: device.modelName
    val compatibilityMessage = when {
        isModified -> "命令已修改，将按编辑后的内容在容器中执行，请确认适用于当前设备。"
        testedDevice != null -> "您的设备（${testedDevice.modelName}）经测试已确认可用"
        selectedPreset.matchesProcessor(device) -> "您的设备未经测试，可尝试此处理器品牌的通用命令"
        else -> "此命令尚未在您的设备上确认可用，请改选匹配的预设，或直接修改命令。"
    }
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
                item(key = "device") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Smartphone,
                                null,
                                Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onTertiaryContainer,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(displayName, style = MiuixTheme.textStyles.headline1)
                                Text(
                                    device.certifiedModel + device.processorNames.firstOrNull()?.let { " · $it" }.orEmpty(),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                when {
                                    isModified -> Icons.Rounded.Tune
                                    testedDevice != null -> Icons.Rounded.Verified
                                    else -> Icons.Rounded.Info
                                },
                                null,
                                Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                compatibilityMessage,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            )
                        }
                    }
                }
                item(key = "description") {
                    Text(
                        text = "此操作只切换网卡模式，不会开始抓包。请先选择匹配的预设并核对命令，录制信道在开始抓取时再选。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                item(key = "presets") {
                    Column {
                        SmallTitle(
                            text = "命令预设",
                            insideMargin = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
                        )
                        Card {
                            MONITOR_COMMAND_PRESETS.forEach { option ->
                                val optionTested = option.testedDevice(device)
                                RadioButtonPreference(
                                    title = option.title,
                                    summary = "已测试：${option.testedDevices.joinToString("、") { it.modelName }}",
                                    selected = selectedId == option.id,
                                    onClick = {
                                        selectedId = option.id
                                        command = TextFieldValue(option.command)
                                    },
                                    endActions = {
                                        val badge = when {
                                            optionTested != null -> "已验证"
                                            option.id == defaultPreset.id && option.matchesProcessor(device) -> "推荐"
                                            else -> null
                                        }
                                        if (badge != null) {
                                            Text(
                                                text = badge,
                                                style = MiuixTheme.textStyles.footnote1,
                                                color = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("将要执行的命令", style = MiuixTheme.textStyles.subtitle)
                        Text(
                            text = if (isModified) "已在「${selectedPreset.title}」基础上修改" else "来自「${selectedPreset.title}」预设",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (isModified) {
                        TextButton(
                            text = "恢复预设",
                            onClick = { command = TextFieldValue(selectedPreset.command) },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                TextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "进入命令",
                    textStyle = MiuixTheme.textStyles.body2,
                    minLines = 6,
                    maxLines = 12,
                )
                Text(
                    text = "可直接改命令；改错了点「恢复预设」，或重新点上方预设。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "执行命令",
                    onClick = { onExecute(command.text) },
                    modifier = Modifier.weight(1f),
                    enabled = command.text.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}