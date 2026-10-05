@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.uidefault.screen.MonitorAccessPointCard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bszapp.wifitoolbox.uidefault.model.MergedWifiGroup
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import kotlinx.coroutines.launch

// ── 详情底部弹窗 ──────────────────────────────────────────────────────────────

private enum class WifiDetailPage {
    NETWORK,
    DEVICE,
}

@Composable
fun WifiDetailSheet(
    group: MergedWifiGroup,
    onDismiss: () -> Unit,
    capturedAccessPoints: List<MonitorAccessPoint> = emptyList(),
    deviceDetailContent: @Composable (MonitorAccessPoint, MonitorDevice) -> Unit = { _, _ -> },
) {
    var selectedDevice by remember(group.ssid) { mutableStateOf<Pair<String, String>?>(null) }
    var selectedPage by remember(group.ssid) { mutableStateOf(WifiDetailPage.NETWORK) }
    val networkPagerState = rememberPagerState(pageCount = { 2 })
    val networkScrollState = rememberScrollState()
    val expandedCapture = remember(group.ssid) { mutableStateMapOf<String, Boolean>() }
    val deviceTarget = selectedDevice?.takeIf { (bssid, mac) ->
        capturedAccessPoints.any { accessPoint ->
            accessPoint.bssid == bssid && accessPoint.devices.any { device -> device.mac == mac }
        }
    }
    LaunchedEffect(selectedPage, deviceTarget != null) {
        if (selectedPage == WifiDetailPage.DEVICE && deviceTarget == null) {
            selectedPage = WifiDetailPage.NETWORK
        }
    }

    SingleOverlayBottomSheet(
        show = true,
        title = null,
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
    ) {
        AnimatedContent(
            targetState = selectedPage,
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                val movingToDevice = targetState == WifiDetailPage.DEVICE
                (slideInHorizontally(tween(260)) { width -> if (movingToDevice) width else -width } togetherWith
                    slideOutHorizontally(tween(260)) { width -> if (movingToDevice) -width else width })
                    .using(SizeTransform(clip = true) { _, _ -> tween(260) })
            },
            label = "wifi-network-device-detail",
        ) { target ->
            if (target == WifiDetailPage.NETWORK) {
                WifiNetworkDetailContent(
                    group = group,
                    capturedAccessPoints = capturedAccessPoints,
                    pagerState = networkPagerState,
                    scrollState = networkScrollState,
                    expandedCapture = expandedCapture,
                    onDeviceClick = { accessPoint, device ->
                        selectedDevice = accessPoint.bssid to device.mac
                        selectedPage = WifiDetailPage.DEVICE
                    },
                )
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        top.yukonga.miuix.kmp.basic.IconButton(onClick = { selectedPage = WifiDetailPage.NETWORK }) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回网络详情",
                                tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
                            )
                        }
                        top.yukonga.miuix.kmp.basic.Text("设备详情", style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.title3)
                    }
                    val target = deviceTarget
                    if (target != null) {
                        val (bssid, mac) = target
                        val targetAccessPoint = capturedAccessPoints.firstOrNull { it.bssid == bssid }
                        val targetDevice = targetAccessPoint?.devices?.firstOrNull { it.mac == mac }
                        if (targetAccessPoint != null && targetDevice != null) {
                            deviceDetailContent(targetAccessPoint, targetDevice)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WifiNetworkDetailContent(
    group: MergedWifiGroup,
    capturedAccessPoints: List<MonitorAccessPoint>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    scrollState: androidx.compose.foundation.ScrollState,
    expandedCapture: MutableMap<String, Boolean>,
    onDeviceClick: (MonitorAccessPoint, MonitorDevice) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Wifi, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
                }
                Column {
                    Text(group.displaySsid, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${group.accessPointCount} 个接入点" +
                            if (group.savedWifiList.isNotEmpty()) " · ${group.savedWifiList.size} 条已保存配置" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        val connection = group.connection
        if (connection != null) {
            val scope = rememberCoroutineScope()
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                listOf("网络详情", "连接信息").forEachIndexed { index, title ->
                    Tab(selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) })
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { page ->
                when (page) {
                    0 -> WifiNetworkDetailPage(group, capturedAccessPoints, onDeviceClick, scrollState, expandedCapture)
                    else -> WifiConnectionInfoPage(connection)
                }
            }
        } else {
            WifiNetworkDetailPage(group, capturedAccessPoints, onDeviceClick, scrollState, expandedCapture)
        }
    }
}

@Composable
private fun WifiNetworkDetailPage(
    group: MergedWifiGroup,
    capturedAccessPoints: List<MonitorAccessPoint>,
    onDeviceClick: (MonitorAccessPoint, MonitorDevice) -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    expandedCapture: MutableMap<String, Boolean>,
) {
    val accessPoints = buildList<DetailAccessPoint> {
        group.networks.forEach { add(DetailAccessPoint.Scanned(it)) }
        group.virtualAccessPoint?.let { add(DetailAccessPoint.Virtual(it)) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 16.dp)
            .padding(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 16.dp),
    ) {
        SectionHeader(
            icon = {
                Icon(
                    Icons.Rounded.Router,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            title = "接入点",
            badge = group.accessPointCount.toString(),
        )

        TwoColumnCardFlow(
            items = accessPoints,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { item ->
            when (item) {
                is DetailAccessPoint.Scanned -> ApCard(
                    ap = item.value,
                    isCurrent = group.isCurrentAccessPoint(item.value),
                )
                is DetailAccessPoint.Virtual -> VirtualApCard(item.value)
            }
        }

        io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
            targetState = capturedAccessPoints,
            contentKey = { it.isEmpty() },
            label = "wifi-captured-access-points",
        ) { visibleAccessPoints ->
        if (visibleAccessPoints.isNotEmpty()) {
            Column {
                SectionHeader(icon = { Icon(Icons.Rounded.Router, null) },
                    title = "抓包数据", badge = visibleAccessPoints.size.toString())
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    visibleAccessPoints.forEach { accessPoint ->
                        MonitorAccessPointCard(
                            accessPoint = accessPoint,
                            expanded = expandedCapture[accessPoint.bssid] == true,
                            onExpandedChange = { expandedCapture[accessPoint.bssid] = it },
                            onDeviceClick = { onDeviceClick(accessPoint, it) },
                        )
                    }
                }
            }
        }
        }

        io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
            targetState = group.savedWifiList,
            contentKey = { it.isEmpty() },
            label = "wifi-saved-configurations",
        ) { savedWifiConfigs ->
        if (savedWifiConfigs.isNotEmpty()) {
            Column {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    icon = {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    title = "已保存的配置",
                    badge = savedWifiConfigs.size.toString(),
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    savedWifiConfigs.forEach { config ->
                        SavedWifiConfigCard(
                            config = config,
                            isCurrent = group.isCurrentConfiguration(config),
                        )
                    }
                }
            }
        }
        }
    }
}

private sealed interface DetailAccessPoint {
    data class Scanned(val value: ScanResult) : DetailAccessPoint
    data class Virtual(val value: WifiInfo) : DetailAccessPoint
}

@Composable
private fun WifiConnectionInfoPage(info: WifiInfo) {
    val rows = buildList {
        add("状态" to "已连接")
        add("SSID" to info.ssid.removeSurrounding("\""))
        info.bssid?.takeIf { it.isNotBlank() }?.let { add("BSSID" to it) }
        add("配置 ID" to info.networkId.toString())
        info.macAddress?.takeIf { it.isNotBlank() }?.let { add("当前连接 MAC" to it) }
        add("信号强度" to "${info.rssi} dBm")
        add("频率" to "${info.frequency} MHz")
        add("信道" to (frequencyToChannel(info.frequency)?.toString() ?: "未知"))
        add("频段" to frequencyBand(info.frequency))
        wifiSecurityType(info)?.let { add("安全类型" to it) }
        add("Supplicant 状态" to info.supplicantState.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add("Wi-Fi 标准" to wifiStandardName(info.wifiStandard))
        }
        if (info.linkSpeed >= 0) add("当前链路速率" to "${info.linkSpeed} Mbps")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (info.txLinkSpeedMbps >= 0) add("发送链路速率" to "${info.txLinkSpeedMbps} Mbps")
            if (info.rxLinkSpeedMbps >= 0) add("接收链路速率" to "${info.rxLinkSpeedMbps} Mbps")
        }
        if (info.ipAddress != 0) add("IPv4 地址" to formatIpv4(info.ipAddress))
        add("隐藏网络" to if (info.hiddenSSID) "是" else "否")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info.apMldMacAddress?.let { add("AP MLD 地址" to it.toString()) }
            if (info.apMloLinkId >= 0) add("MLO Link ID" to info.apMloLinkId.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .padding(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                        )
                    }
                    ConnectionInfoRow(label, value)
                }
            }
        }
    }
}

@Composable
private fun ConnectionInfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            softWrap = true,
        )
    }
}

// ── Section 标题 ──────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    badge: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            icon()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 8.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── 双列可变高卡片流 ──────────────────────────────────────────────────────────

@Composable
private fun <T> TwoColumnCardFlow(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    // 使用 Row + 两列 Column 实现双列流式布局（奇偶分列）
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左列：偶数索引
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.filterIndexed { idx, _ -> idx % 2 == 0 }.forEach { item ->
                itemContent(item)
            }
        }
        // 右列：奇数索引
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.filterIndexed { idx, _ -> idx % 2 == 1 }.forEach { item ->
                itemContent(item)
            }
        }
    }
}

// ── 单个 AP 卡片 ──────────────────────────────────────────────────────────────

@Composable
private fun ApCard(
    ap: ScanResult,
    isCurrent: Boolean,
) {
    val isUnknownSignal = ap.level == 0
    val signalLevel = if (isUnknownSignal) 0 else WifiManager.calculateSignalLevel(ap.level, 5)
    val (signalLabel, signalColor) = if (isUnknownSignal) {
        "未知" to MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        signalInfo(signalLevel)
    }
    val isSecure = ap.capabilities.contains("WPA") || ap.capabilities.contains("WEP")
    val containerColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "AccessPointContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "AccessPointContent",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 顶部：信号强度 + 锁图标
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 信号条
                SignalBars(
                    level = signalLevel,
                    color = signalColor,
                    modifier = Modifier.size(width = 22.dp, height = 16.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = isCurrent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = contentColor.copy(alpha = 0.1f),
                            ) {
                                Text(
                                    text = "当前接入点",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Icon(
                        imageVector = if (isSecure) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // dBm + 信号等级
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (isUnknownSignal) "未知" else "${ap.level}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = signalColor,
                    fontFamily = FontFamily.Monospace,
                )
                io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = !isUnknownSignal) {
                    Text(
                        text = "dBm",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = signalLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = signalColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )

            // BSSID
            Text(
                text = ap.BSSID!!,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 频率
            ApChip(text = "${ap.frequency} MHz")

            // 安全能力（简化显示）
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = ap.capabilities.takeIf { it.isNotEmpty() },
                contentKey = { it == null },
                label = "wifi-capabilities",
            ) { capabilities ->
            if (capabilities != null) {
                val capShort = capabilities
                    .replace("[", "")
                    .replace("]", " ")
                    .trim()
                Text(
                    text = capShort,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor.copy(alpha = 0.7f),
                    lineHeight = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            }
        }
    }
}

@Composable
private fun VirtualApCard(info: WifiInfo) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalBars(
                    level = 0,
                    color = contentColor,
                    modifier = Modifier.size(width = 22.dp, height = 16.dp),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = contentColor.copy(alpha = 0.1f),
                ) {
                    Text(
                        text = "当前接入点",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = "未知",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = contentColor.copy(alpha = 0.3f),
            )
            info.bssid?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    softWrap = true,
                )
            }
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = info.frequency.takeIf { it > 0 },
                contentKey = { it == null },
                label = "wifi-frequency-detail",
            ) { frequency ->
            if (frequency != null) {
                Text(
                    text = "$frequency MHz · ${frequencyBand(frequency)} · " +
                        "信道 ${frequencyToChannel(frequency) ?: "未知"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.75f),
                    softWrap = true,
                )
            }
            }
            wifiSecurityType(info)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.75f),
                    softWrap = true,
                )
            }
        }
    }
}

@Composable
private fun ApChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

// ── 信号条图形 ────────────────────────────────────────────────────────────────

@Composable
private fun SignalBars(
    level: Int,        // 0-4
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val totalBars = 4
        for (i in 1..totalBars) {
            val fraction = i / totalBars.toFloat()
            val active = i <= level
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (active) color
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
            )
        }
    }
}

// ── 已保存配置卡片 ────────────────────────────────────────────────────────────

@Suppress("DEPRECATION")
@Composable
private fun SavedWifiConfigCard(
    config: WifiConfiguration,
    isCurrent: Boolean,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "SavedConfigContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "SavedConfigContent",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = config.SSID?.trim('"') ?: "<未知>",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = isCurrent) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = contentColor.copy(alpha = 0.1f),
                        modifier = Modifier.padding(horizontal = 6.dp),
                    ) {
                        Text(
                            text = "正在使用",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "ID ${config.networkId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(6.dp))

            // 密码
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = config.preSharedKey?.takeIf { it.isNotEmpty() },
                contentKey = { it == null },
                label = "saved-wifi-password",
            ) { password ->
            if (password != null) {
                SavedInfoRow("密码", password.trim('"'))
            }
            }

            // WEP 密钥
            config.wepKeys?.forEachIndexed { i, key ->
                io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                    targetState = key?.takeIf { it.isNotEmpty() },
                    contentKey = { it == null },
                    label = "saved-wep-key-$i",
                ) { visibleKey ->
                if (visibleKey != null) {
                    SavedInfoRow("WEP Key $i", visibleKey)
                }
                }
            }

            // BSSID
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = config.BSSID?.takeIf { it.isNotEmpty() },
                contentKey = { it == null },
                label = "saved-wifi-bssid",
            ) { bssid ->
            if (bssid != null) {
                SavedInfoRow("BSSID", bssid)
            }
            }

            // 认证方式
            val keyMgmtBits = buildList {
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) add("NONE")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) add("WPA_PSK")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) add("WPA_EAP")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) add("IEEE8021X")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)) add("WPA2_PSK")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) add("SAE(WPA3)")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) add("OWE")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) add("SUITE_B_192")
            }
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = keyMgmtBits,
                contentKey = { it.isEmpty() },
                label = "saved-wifi-key-management",
            ) { visibleKeyMgmtBits ->
            if (visibleKeyMgmtBits.isNotEmpty()) {
                SavedInfoRow("认证方式", visibleKeyMgmtBits.joinToString(" / "))
            }
            }

            // 协议
            val protocols = buildList {
                if (config.allowedProtocols.get(WifiConfiguration.Protocol.WPA)) add("WPA")
                if (config.allowedProtocols.get(WifiConfiguration.Protocol.RSN)) add("RSN(WPA2/3)")
            }
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = protocols,
                contentKey = { it.isEmpty() },
                label = "saved-wifi-protocols",
            ) { visibleProtocols ->
            if (visibleProtocols.isNotEmpty()) {
                SavedInfoRow("协议", visibleProtocols.joinToString(" / "))
            }
            }

            // 单播加密
            val pairwise = buildList {
                if (config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.TKIP)) add("TKIP")
                if (config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.CCMP)) add("CCMP(AES)")
                if (config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.GCMP_256)) add(
                    "GCMP-256"
                )
            }
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = pairwise,
                contentKey = { it.isEmpty() },
                label = "saved-wifi-pairwise-ciphers",
            ) { visiblePairwise ->
            if (visiblePairwise.isNotEmpty()) {
                SavedInfoRow("单播加密", visiblePairwise.joinToString(" / "))
            }
            }

            // 组播加密
            val groupCiphers = buildList {
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.TKIP)) add("TKIP")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.CCMP)) add("CCMP")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.WEP40)) add("WEP40")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.WEP104)) add("WEP104")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.GCMP_256)) add("GCMP-256")
            }
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = groupCiphers,
                contentKey = { it.isEmpty() },
                label = "saved-wifi-group-ciphers",
            ) { visibleGroupCiphers ->
            if (visibleGroupCiphers.isNotEmpty()) {
                SavedInfoRow("组播加密", visibleGroupCiphers.joinToString(" / "))
            }
            }

            // 隐藏网络
            SavedInfoRow("隐藏网络", if (config.hiddenSSID) "是" else "否")

            // MAC 随机化
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SavedInfoRow(
                    "MAC 随机化", when (config.macRandomizationSetting) {
                        WifiConfiguration.RANDOMIZATION_NONE -> "使用设备MAC"
                        WifiConfiguration.RANDOMIZATION_PERSISTENT -> "指定随机地址"
                        WifiConfiguration.RANDOMIZATION_NON_PERSISTENT -> "每次随机"
                        WifiConfiguration.RANDOMIZATION_AUTO -> "自动"
                        else -> "未知(${config.macRandomizationSetting})"
                    }
                )
                SavedInfoRow("随机 MAC", config.randomizedMacAddress.toString())
            }
        }
    }
}

// ── SavedInfoRow ──────────────────────────────────────────────────────────────

@Composable
private fun SavedInfoRow(label: String, value: String) {
    val contentColor = LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = contentColor,
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End
        )
    }
}

// ── 工具函数 ──────────────────────────────────────────────────────────────────

@Composable
private fun signalInfo(level: Int): Pair<String, Color> = when (level) {
    4 -> "优秀" to MaterialTheme.colorScheme.primary
    3 -> "良好" to MaterialTheme.colorScheme.tertiary
    2 -> "一般" to MaterialTheme.colorScheme.secondary
    1 -> "较弱" to MaterialTheme.colorScheme.onSurfaceVariant
    else -> "很弱" to MaterialTheme.colorScheme.error
}

private fun wifiSecurityType(info: WifiInfo): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return when (info.currentSecurityType) {
        WifiInfo.SECURITY_TYPE_OPEN -> "开放网络"
        WifiInfo.SECURITY_TYPE_WEP -> "WEP"
        WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2-PSK"
        WifiInfo.SECURITY_TYPE_EAP -> "WPA/WPA2-EAP"
        WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE"
        WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "WPA3-Enterprise 192-bit"
        WifiInfo.SECURITY_TYPE_OWE -> "OWE"
        WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
        WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
        WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
        WifiInfo.SECURITY_TYPE_OSEN -> "OSEN"
        WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
        WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
        WifiInfo.SECURITY_TYPE_DPP -> "DPP"
        else -> null
    }
}

private fun wifiStandardName(standard: Int): String = when (standard) {
    ScanResult.WIFI_STANDARD_LEGACY -> "Legacy"
    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6/6E (802.11ax)"
    ScanResult.WIFI_STANDARD_11AD -> "WiGig (802.11ad)"
    ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
    else -> "未知"
}

private fun formatIpv4(address: Int): String = listOf(
    address and 0xff,
    address shr 8 and 0xff,
    address shr 16 and 0xff,
    address shr 24 and 0xff,
).joinToString(".")
