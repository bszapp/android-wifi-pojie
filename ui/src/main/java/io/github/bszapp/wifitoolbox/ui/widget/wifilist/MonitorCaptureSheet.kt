@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import android.net.wifi.ScanResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChannel
import io.github.bszapp.wifitoolbox.contract.wifilist.createScanResultCompat
import io.github.bszapp.wifitoolbox.ui.component.PreviewSheetHost
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Preview(name = "监听频率", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
internal fun MonitorCaptureSheetPreview() {
    PreviewSheetHost {
        MonitorCaptureSheet(
            show = true,
            channels = listOf(
                MonitorChannel(1, 2412),
                MonitorChannel(6, 2437),
                MonitorChannel(11, 2462),
                MonitorChannel(36, 5180),
                MonitorChannel(44, 5220),
                MonitorChannel(149, 5745),
            ),
            scanResults = listOf(
                createScanResultCompat().apply {
                    SSID = "ChinaNet-Home"
                    BSSID = "AA:BB:CC:11:22:01"
                    capabilities = "[WPA2-PSK-CCMP][ESS]"
                    level = -48
                    frequency = 2412
                },
                createScanResultCompat().apply {
                    SSID = "CMCC-5G"
                    BSSID = "AA:BB:CC:11:22:02"
                    capabilities = "[WPA2-PSK-CCMP+TKIP][ESS]"
                    level = -67
                    frequency = 5745
                },
                createScanResultCompat().apply {
                    SSID = "eduroam"
                    BSSID = "AA:BB:CC:11:22:03"
                    capabilities = "[WPA2-EAP-CCMP][ESS]"
                    level = -75
                    frequency = 5180
                },
            ),
            frequencyMhz = 2412,
            configuredFrequencyMhz = 2412,
            hopping = false,
            capturing = false,
            onDismiss = {},
            onChange = { _, _ -> },
        )
    }
}

private sealed interface CaptureSelectionItem {
    val key: String
    data object Empty : CaptureSelectionItem { override val key = "channels-empty" }
    data object Hopping : CaptureSelectionItem { override val key = "hopping" }
    data class Band(val band: MonitorChartBand) : CaptureSelectionItem { override val key = band.name }
}

@Composable
internal fun MonitorCaptureSheet(
    show: Boolean,
    channels: List<MonitorChannel>,
    scanResults: List<ScanResult>,
    frequencyMhz: Int,
    configuredFrequencyMhz: Int,
    hopping: Boolean,
    capturing: Boolean,
    enabled: Boolean = true,
    onDismiss: () -> Unit,
    onChange: (frequencyMhz: Int, hopping: Boolean) -> Unit,
) {
    val selectedFrequency = (if (hopping) frequencyMhz else configuredFrequencyMhz.takeIf { it > 0 }
        ?: frequencyMhz).takeIf { it > 0 }
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val maxHeight = LocalWindowInfo.current.containerDpSize.height * 0.7f
    val selectedChannel = channels.firstOrNull { it.frequencyMhz == selectedFrequency }
    val currentChannel = channels.firstOrNull { it.frequencyMhz == frequencyMhz }
    val hitNetworks = remember(scanResults, selectedFrequency) {
        selectedFrequency?.let { chartHitNetworks(scanResults, it) }.orEmpty()
    }
    val selectionItems = buildList<CaptureSelectionItem> {
        add(if (channels.isEmpty()) CaptureSelectionItem.Empty else CaptureSelectionItem.Hopping)
        MonitorChartBand.entries.forEach { add(CaptureSelectionItem.Band(it)) }
    }
    SingleOverlayBottomSheet(
        show = show,
        title = "监听频率",
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).scrollEndHaptic().overScrollVertical(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(selectionItems, key = { it.key }) { item ->
                    when (item) {
                        CaptureSelectionItem.Empty -> Text("尚未获取到可用信道")
                        CaptureSelectionItem.Hopping -> Card {
                            SwitchPreference(
                                title = "自动跳频",
                                summary = "按上次扫描的信号指数分配停留时间，指数越高停留越久",
                                checked = hopping,
                                enabled = enabled,
                                onCheckedChange = { automatic ->
                                    onChange(frequencyMhz.takeIf { it > 0 } ?: selectedFrequency ?: 0, automatic)
                                },
                            )
                        }
                        is CaptureSelectionItem.Band -> MonitorChannelChart(
                            band = item.band,
                            channels = channels,
                            scanResults = scanResults,
                            selectedFrequency = selectedFrequency,
                            enabled = enabled && !hopping,
                            onSelect = { frequency -> onChange(frequency, false) },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = currentChannel?.let {
                        "当前信道：${it.channel}，${frequencyBand(it.frequencyMhz)}，${it.frequencyMhz}MHz"
                    } ?: "尚未获取到当前信道",
                    style = MiuixTheme.textStyles.body2,
                )
                Text(
                    text = when {
                        hopping && capturing -> "自动跳频中，手动信道选择已禁用"
                        hopping -> "自动跳频已开启，开始抓包后生效"
                        else -> "点击图表选择信道，修改立即生效"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
                    targetState = selectedChannel?.takeUnless { hopping }?.let { it to hitNetworks.toList() },
                    contentKey = { it?.first?.frequencyMhz },
                    label = "monitor-capture-hit-networks",
                ) { channelAndNetworks ->
                if (channelAndNetworks != null) {
                    val (_, visibleNetworks) = channelAndNetworks
                    Text(
                        text = if (visibleNetworks.isEmpty()) "命中：暂无网络" else "命中：" + visibleNetworks.joinToString("、") {
                            it.SSID?.takeIf(String::isNotBlank) ?: "<隐藏的网络>（${it.BSSID}）"
                        },
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        maxLines = 1,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                }
            }
            TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
