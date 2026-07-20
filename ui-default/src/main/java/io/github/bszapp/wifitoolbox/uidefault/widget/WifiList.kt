@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.widget

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.uidefault.component.TagItem
import io.github.bszapp.wifitoolbox.uidefault.component.TagStyle
import io.github.bszapp.wifitoolbox.uidefault.component.WifiIcon
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.model.MergedWifiGroup
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WifiDetailSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WifiGroupCardActions

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WifiList(
    modifier: Modifier = Modifier,
    vm: DefaultViewModel = viewModel(),
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
) {
    val wifiState by vm.wifiList.state.collectAsStateWithLifecycle()
    val savedWifiList by vm.wifiList.savedWifiList.collectAsStateWithLifecycle()
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    // WifiState 与 SavedWifiList 是两种同级数据：
    // 扫描结果只来自 Enabled，已保存配置只来自独立的 SavedWifiList。
    val scanResults: List<ScanResult> = when (val state = wifiState) {
        is WifiState.Data.Enabled -> state.scanResults
        else -> emptyList()
    }
    val savedNetworks: List<WifiConfiguration> =
        savedWifiList?.networks ?: emptyList()

    val groups = remember(scanResults, savedNetworks) {
        MergedWifiGroup.buildFrom(
            results = scanResults,
            savedWifiList = savedNetworks,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val state = wifiState) {
            null -> Box(modifier = Modifier.fillMaxSize())

            is WifiState.Data.Disabled -> WifiDisabledContent(
                onEnableWifi = { vm.wifiList.setWifiEnabled(true) },
            )

            is WifiState.Error -> WifiErrorContent(
                message = state.exception.message ?: "未知错误",
            )

            is WifiState.Data.Enabled -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (groups.isEmpty()) {
                    item(key = "wifi-empty") {
                        // 空状态仍放在可滚动容器中，保证 PullToRefresh 能收到 nested scroll。
                        WifiEmptyContent(
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    itemsIndexed(
                        items = groups,
                        key = { _, item -> item.strongest.BSSID!! },
                    ) { index, group ->
                        WifiGroupCard(
                            vm = vm,
                            group = group,
                            modifier = Modifier.animateItem(),
                            onClick = { selectedIndex = index },
                        )
                    }
                }
            }
        }
    }

    selectedIndex?.let { index ->
        groups.getOrNull(index)?.let { group ->
            WifiDetailSheet(group = group, onDismiss = { selectedIndex = null })
        }
    }
}

@Composable
private fun WifiEmptyContent(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Inbox,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "空空如也",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WifiDisabledContent(onEnableWifi: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(144.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)),
                )
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "Wi-Fi 未开启",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "开启 Wi-Fi 后即可扫描附近的无线网络",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onEnableWifi,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "开启 Wi-Fi",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WifiErrorContent(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

// ── 单个 Wi-Fi 卡片 ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WifiGroupCard(
    vm: DefaultViewModel,
    group: MergedWifiGroup,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val levelIndex = if (group.strongest.level == 0) 0
    else WifiManager.calculateSignalLevel(group.strongest.level, 5)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            WifiIcon(
                modifier = Modifier.size(28.dp),
                level = levelIndex
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = group.displaySsid,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                    )

                    val hasTags = group.networks.size > 1 || group.savedWifiList.isNotEmpty()
                    if (hasTags) {
                        Spacer(Modifier.width(4.dp))
                        if (group.networks.size > 1) {
                            TagItem(
                                text = group.networks.size.toString(),
                                icon = Icons.Default.Layers,
                                style = TagStyle.Tertiary
                            )
                        }
                        if (group.savedWifiList.isNotEmpty()) {
                            TagItem(
                                text = "已保存",
                                style = TagStyle.Primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = group.signalDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(12.dp))
            WifiGroupCardActions(
                group = group,
                onConnect = { /* TODO */ },
                onOpenDetail = { onClick() },
                onConnectWithConfig = { /* TODO */ },
                onUpdateConfig = { networkId, patch ->
                    vm.wifiList.updateWifiConfig(networkId, patch)
                },
            )

        }
    }
}

