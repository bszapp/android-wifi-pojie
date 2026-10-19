@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import android.net.wifi.ScanResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChannel
import io.github.bszapp.wifitoolbox.contract.wifilist.createScanResultCompat
import io.github.bszapp.wifitoolbox.ui.component.PreviewSheetHost
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Preview(name = "选择录制信道", showBackground = true, widthDp = 411, heightDp = 891)
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
            onDismiss = {},
            onStart = { _, _ -> },
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
    onDismiss: () -> Unit,
    onStart: (frequencyMhz: Int, hopping: Boolean) -> Unit,
) {
    var selectedFrequency by rememberSaveable(show) { mutableStateOf<Int?>(null) }
    var hopping by rememberSaveable(show) { mutableStateOf(false) }
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val maxHeight = LocalWindowInfo.current.containerDpSize.height * 0.7f
    val selectedChannel = channels.firstOrNull { it.frequencyMhz == selectedFrequency }
    val hitNetworks = remember(scanResults, selectedFrequency) {
        selectedFrequency?.let { chartHitNetworks(scanResults, it) }.orEmpty()
    }
    val selectionItems = buildList<CaptureSelectionItem> {
        add(if (channels.isEmpty()) CaptureSelectionItem.Empty else CaptureSelectionItem.Hopping)
        MonitorChartBand.entries.forEach { add(CaptureSelectionItem.Band(it)) }
    }
    SingleOverlayBottomSheet(
        show = show,
        title = "选择录制信道",
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
                            BasicComponent(
                                title = "跳频录制",
                                summary = "按上次扫描的信号指数分配停留时间，指数越高停留越久",
                                role = Role.RadioButton,
                                onClick = { hopping = true },
                                endActions = { RadioButton(selected = hopping, onClick = null) },
                            )
                        }
                        is CaptureSelectionItem.Band -> MonitorChannelChart(
                            band = item.band,
                            channels = channels,
                            scanResults = scanResults,
                            selectedFrequency = selectedFrequency.takeUnless { hopping },
                            onSelect = { frequency -> hopping = false; selectedFrequency = frequency },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (hopping) "跳频录制 · ${channels.size} 个可用信道" else selectedChannel?.let {
                        "信道 ${it.channel} · ${frequencyBand(it.frequencyMhz)} · ${it.frequencyMhz} MHz"
                    } ?: "点击图表选择信道，或选择跳频录制",
                    style = MiuixTheme.textStyles.body2,
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
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(text = "开始录制", enabled = channels.isNotEmpty() && (hopping || selectedChannel != null),
                    onClick = { if (hopping) onStart(0, true) else selectedFrequency?.let { onStart(it, false) } }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
