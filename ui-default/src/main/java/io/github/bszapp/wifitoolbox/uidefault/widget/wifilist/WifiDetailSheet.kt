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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeCaptureQuality
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.uidefault.model.MergedWifiGroup
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

// ── 详情底部弹窗 ──────────────────────────────────────────────────────────────
//
// 三级结构：接入点列表 → 单个接入点的抓包数据 → 接入点与设备的详细信息。
// 动画状态只记录当前处于三个页面中的哪一个，页面内容（选中的接入点 / 设备 / 列表滚动位置）单独记录。

private enum class WifiDetailStage {
    /** 接入点列表（扫描结果与抓包数据合并展示）。 */
    LIST,

    /** 单个接入点下的抓包数据。 */
    ACCESS_POINT,

    /** 接入点与设备的详细信息。 */
    DEVICE,
}

/** 扫描结果 / 虚拟接入点 / 抓包数据按 BSSID 合并后的接入点条目。 */
private data class MergedAccessPoint(
    val key: String,
    val scanned: ScanResult? = null,
    val virtual: WifiInfo? = null,
    val captured: MonitorAccessPoint? = null,
)

/**
 * 稳定选择标识：优先 BSSID。
 * [key] 中的 `scan-$index` 依赖扫描结果下标，排序变化会位移，不能用于选中态。
 */
private val MergedAccessPoint.stableKey: String
    get() = scanned?.BSSID?.lowercase()?.takeIf { it.isNotBlank() }
        ?: virtual?.bssid?.lowercase()?.takeIf { it.isNotBlank() }
        ?: captured?.bssid?.lowercase()?.takeIf { it.isNotBlank() }
        ?: key

private fun mergeAccessPoints(
    group: MergedWifiGroup,
    capturedAccessPoints: List<MonitorAccessPoint>,
): List<MergedAccessPoint> {
    val capturedByBssid = capturedAccessPoints.associateBy { it.bssid.lowercase() }
    val used = HashSet<String>(capturedAccessPoints.size)
    val entries = ArrayList<MergedAccessPoint>(group.networks.size + capturedAccessPoints.size + 1)

    group.networks.forEachIndexed { index, network ->
        val bssid = network.BSSID?.lowercase() ?: ""
        val captured = capturedByBssid[bssid]?.takeIf { bssid.isNotEmpty() }
        if (captured != null) used.add(bssid)
        entries += MergedAccessPoint(key = "scan-$index", scanned = network, captured = captured)
    }

    group.virtualAccessPoint?.let { virtual ->
        val bssid = virtual.bssid?.lowercase() ?: ""
        val captured = capturedByBssid[bssid]?.takeIf { bssid.isNotEmpty() }
        if (captured != null) used.add(bssid)
        entries += MergedAccessPoint(key = "virtual", virtual = virtual, captured = captured)
    }

    capturedAccessPoints.forEach { captured ->
        if (used.add(captured.bssid.lowercase())) {
            entries += MergedAccessPoint(
                key = "captured-${captured.bssid.lowercase()}",
                captured = captured,
            )
        }
    }
    return entries
}

@Composable
fun WifiDetailSheet(
    show: Boolean,
    group: MergedWifiGroup,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    capturedAccessPoints: List<MonitorAccessPoint> = emptyList(),
    deviceDetailContent: @Composable (MonitorAccessPoint, MonitorDevice) -> Unit = { _, _ -> },
) {
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    // 动画状态：只记录当前是三个页面中的哪一个
    var stage by remember(group.ssid) { mutableStateOf(WifiDetailStage.LIST) }

    // 页面内容单独记录：只存选中标识，不持有对象引用（单一数据源）
    var selectedAccessPointKey by remember(group.ssid) { mutableStateOf<String?>(null) }
    var selectedDeviceMac by remember(group.ssid) { mutableStateOf<String?>(null) }

    val networkPagerState = rememberPagerState(pageCount = { 2 })
    val accessPointGridState = rememberLazyGridState()
    val deviceListState = rememberLazyListState()

    val mergedAccessPoints = remember(group, capturedAccessPoints) {
        mergeAccessPoints(group, capturedAccessPoints).sortedWith(
            compareByDescending<MergedAccessPoint> {
                it.captured?.let { captured ->
                    group.ssid.isBlank() && !captured.ssid.isNullOrBlank()
                } == true
            }.thenByDescending { successfulHandshakeDeviceCount(it.captured) },
        )
    }

    // 抓包数据刷新后选中项跟随最新数据重新查找，详情页因此保持实时
    val selectedAccessPoint = selectedAccessPointKey?.let { key ->
        mergedAccessPoints.find { it.stableKey == key }
    }

    val deviceTarget = selectedAccessPoint?.captured
        ?.takeIf { selectedDeviceMac != null }
        ?.let { accessPoint ->
            selectedDeviceMac?.let { mac -> accessPoint.devices.firstOrNull { it.mac == mac } }
                ?.let { device -> accessPoint to device }
        }

    LaunchedEffect(stage, selectedAccessPoint, deviceTarget) {
        if (stage == WifiDetailStage.DEVICE && deviceTarget == null) {
            stage = WifiDetailStage.ACCESS_POINT
        }
        if (stage == WifiDetailStage.ACCESS_POINT && selectedAccessPoint == null) {
            stage = WifiDetailStage.LIST
        }
    }

    // 标题栏交给 sheet 自带的 title/startAction，内容区不自绘标题行
    val backAction: (@Composable () -> Unit)? = when (stage) {
        WifiDetailStage.LIST -> null
        else -> {
            {
                IconButton(onClick = {
                    stage = if (stage == WifiDetailStage.DEVICE) {
                        WifiDetailStage.ACCESS_POINT
                    } else {
                        WifiDetailStage.LIST
                    }
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
    SingleOverlayBottomSheet(
        show = show,
        title = when (stage) {
            WifiDetailStage.LIST -> null
            WifiDetailStage.ACCESS_POINT -> accessPointTitle(selectedAccessPoint)
            WifiDetailStage.DEVICE -> deviceTarget?.second
                ?.name?.takeIf { it.isNotBlank() }
                ?: deviceTarget?.second?.mac
                ?: "设备详情"
        },
        startAction = backAction,
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column(Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = stage,
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    val movingForward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(260)) { width -> if (movingForward) width else -width } togetherWith
                        slideOutHorizontally(tween(260)) { width -> if (movingForward) -width else width })
                        .using(SizeTransform(clip = true) { _, _ -> tween(260) })
                },
                label = "wifi-detail-stage",
            ) { target ->
                when (target) {
                    WifiDetailStage.LIST -> WifiNetworkDetailContent(
                        group = group,
                        accessPoints = mergedAccessPoints,
                        pagerState = networkPagerState,
                        gridState = accessPointGridState,
                        onAccessPointClick = { entry ->
                            selectedAccessPointKey = entry.stableKey
                            selectedDeviceMac = null
                            stage = WifiDetailStage.ACCESS_POINT
                        },
                    )

                    WifiDetailStage.ACCESS_POINT -> AccessPointCaptureContent(
                        entry = selectedAccessPoint,
                        deviceListState = deviceListState,
                        onDeviceClick = { device ->
                            selectedDeviceMac = device.mac
                            stage = WifiDetailStage.DEVICE
                        },
                    )

                    WifiDetailStage.DEVICE -> DeviceDetailContent(
                        target = deviceTarget,
                        deviceDetailContent = deviceDetailContent,
                    )
                }
            }
            if (stage != WifiDetailStage.DEVICE) {
                Spacer(Modifier.height(safeBottom))
            }
        }
    }
}

// ── 第一级：接入点列表 ────────────────────────────────────────────────────────

@Composable
private fun WifiNetworkDetailContent(
    group: MergedWifiGroup,
    accessPoints: List<MergedAccessPoint>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onAccessPointClick: (MergedAccessPoint) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Wifi, contentDescription = null,
                        tint = MiuixTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(26.dp))
                }
                Column {
                    Text(group.displaySsid, style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${accessPoints.size} 个接入点" +
                            if (group.savedWifiList.isNotEmpty()) " · ${group.savedWifiList.size} 条已保存配置" else "",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        val connection = group.connection
        if (connection != null) {
            val scope = rememberCoroutineScope()
            TabRow(
                tabs = listOf("网络详情", "连接信息"),
                selectedTabIndex = pagerState.currentPage,
                onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                modifier = Modifier.padding(vertical = 6.dp),
            )
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { page ->
                when (page) {
                    0 -> WifiAccessPointGridPage(
                        group = group,
                        accessPoints = accessPoints,
                        gridState = gridState,
                        onAccessPointClick = onAccessPointClick,
                    )
                    else -> WifiConnectionInfoPage(connection)
                }
            }
        } else {
            WifiAccessPointGridPage(
                group = group,
                accessPoints = accessPoints,
                gridState = gridState,
                onAccessPointClick = onAccessPointClick,
            )
        }
    }
}

@Composable
private fun WifiAccessPointGridPage(
    group: MergedWifiGroup,
    accessPoints: List<MergedAccessPoint>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onAccessPointClick: (MergedAccessPoint) -> Unit,
) {
    val savedWifiConfigs = group.savedWifiList
    val bottomPadding = 16.dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header-access-points", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                icon = {
                    Icon(
                        Icons.Rounded.Router,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                title = "接入点",
                badge = accessPoints.size.toString(),
            )
        }

        items(
            count = accessPoints.size,
            key = { index -> accessPoints[index].key },
        ) { index ->
            val entry = accessPoints[index]
            when {
                entry.scanned != null -> ApCard(
                    ap = entry.scanned,
                    isCurrent = group.isCurrentAccessPoint(entry.scanned),
                    captured = entry.captured,
                    onClick = { onAccessPointClick(entry) },
                )

                entry.virtual != null -> VirtualApCard(
                    info = entry.virtual,
                    captured = entry.captured,
                    onClick = { onAccessPointClick(entry) },
                )

                else -> CapturedApCard(
                    captured = entry.captured,
                    onClick = { onAccessPointClick(entry) },
                )
            }
        }

        if (savedWifiConfigs.isNotEmpty()) {
            item(key = "header-saved-configurations", span = { GridItemSpan(maxLineSpan) }) {
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
                }
            }
            items(
                count = savedWifiConfigs.size,
                key = { index -> "saved-${savedWifiConfigs[index].networkId}-$index" },
                span = { GridItemSpan(maxLineSpan) },
            ) { index ->
                SavedWifiConfigCard(
                    config = savedWifiConfigs[index],
                    isCurrent = group.isCurrentConfiguration(savedWifiConfigs[index]),
                )
            }
        }
    }
}

// ── 第二级：单个接入点的抓包数据 ──────────────────────────────────────────────

@Composable
private fun AccessPointCaptureContent(
    entry: MergedAccessPoint?,
    deviceListState: androidx.compose.foundation.lazy.LazyListState,
    onDeviceClick: (MonitorDevice) -> Unit,
) {
    val captured = entry?.captured
    val devices = captured?.devices.orEmpty().sortedWith(
        compareByDescending<MonitorDevice> { successfulHandshakeCount(it) > 0 }
            .thenByDescending(::successfulHandshakeCount),
    )
    val bottomPadding = 16.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = deviceListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "access-point-overview") {
                AccessPointOverviewCard(entry = entry, captured = captured)
            }

            if (captured == null) {
                item(key = "access-point-empty-capture") {
                    CaptureHintCard(text = "该接入点暂无抓包数据")
                }
            } else {
                item(key = "header-devices") {
                    SectionHeader(
                        icon = {
                            Icon(
                                Icons.Rounded.Smartphone,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        title = "设备",
                        badge = devices.size.toString(),
                    )
                }
                if (devices.isEmpty()) {
                    item(key = "access-point-empty-devices") {
                        CaptureHintCard(text = "尚未在此接入点下发现设备")
                    }
                } else {
                    items(
                        count = devices.size,
                        key = { index -> "device-${devices[index].mac}" },
                    ) { index ->
                        DeviceCard(
                            device = devices[index],
                            onClick = { onDeviceClick(devices[index]) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessPointOverviewCard(
    entry: MergedAccessPoint?,
    captured: MonitorAccessPoint?,
) {
    val scanned = entry?.scanned
    val virtual = entry?.virtual
    val bssid = scanned?.BSSID ?: captured?.bssid ?: virtual?.bssid
    val signalDbm = scanned?.level?.takeIf { it != 0 }
        ?: captured?.signal?.latestDbm
    val rows = buildList {
        scanned?.SSID?.takeIf { it.isNotBlank() }?.let { add("网络名称" to it) }
        captured?.ssid?.takeIf { it.isNotBlank() }?.let { add("抓包网络名称" to it) }
        bssid?.takeIf { it.isNotBlank() }?.let { add("接入点 MAC" to it) }
        scanned?.frequency?.takeIf { it > 0 }?.let { frequency ->
            add("频率" to "$frequency MHz · ${frequencyBand(frequency)} · 信道 ${frequencyToChannel(frequency) ?: "未知"}")
        }
        if (scanned != null) {
            add("安全能力" to (scanned.capabilities.takeIf { it.isNotBlank() }
                ?.replace("[", "")?.replace("]", " ")?.trim()
                ?: "未知"))
        }
        if (captured != null && captured.securityProtocols.isNotEmpty()) {
            add("安全类型" to captured.securityProtocols.joinToString(" / "))
        }
        signalDbm?.let { add("信号强度" to "$it dBm") }
        if (captured != null) {
            add("设备数量" to "${captured.devices.size} 台")
            add("成功握手" to "${captured.devices.sumOf(::successfulHandshakeCount)} 次")
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.6f),
                    )
                }
                InfoRow(label = label, value = value)
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: MonitorDevice,
    onClick: () -> Unit,
) {
    val handshakeCount = successfulHandshakeCount(device)
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        BasicComponent(
            title = device.name ?: device.mac,
            summary = device.name?.let { device.mac },
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Smartphone,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            },
            endActions = {
                if (handshakeCount > 0) {
                    Badge(
                        containerColor = MiuixTheme.colorScheme.primary,
                        contentColor = MiuixTheme.colorScheme.onPrimary,
                    ) {
                        Text("成功握手${handshakeCount}次", softWrap = true)
                    }
                }
            },
            onClick = onClick,
        )
    }
}

@Composable
private fun CaptureHintCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

// ── 第三级：设备详情 ──────────────────────────────────────────────────────────

@Composable
private fun DeviceDetailContent(
    target: Pair<MonitorAccessPoint, MonitorDevice>?,
    deviceDetailContent: @Composable (MonitorAccessPoint, MonitorDevice) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (target != null) {
            val (accessPoint, device) = target
            deviceDetailContent(accessPoint, device)
        }
    }
}

// ── 连接信息页 ────────────────────────────────────────────────────────────────

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
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.tertiaryContainer,
                contentColor = MiuixTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f),
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
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onTertiaryContainer,
            softWrap = true,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(84.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
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
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onBackgroundVariant) {
            icon()
        }
        Text(
            text = title,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MiuixTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 8.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badge,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── 单个 AP 卡片 ──────────────────────────────────────────────────────────────

@Composable
private fun ApCard(
    ap: ScanResult,
    isCurrent: Boolean,
    captured: MonitorAccessPoint?,
    onClick: () -> Unit,
) {
    val handshakeDeviceCount = successfulHandshakeDeviceCount(captured)
    val isUnknownSignal = ap.level == 0
    val signalLevel = if (isUnknownSignal) 0 else WifiManager.calculateSignalLevel(ap.level, 5)
    val (signalLabel, signalColor) = if (isUnknownSignal) {
        "未知" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        signalInfo(signalLevel)
    }
    val isSecure = ap.capabilities.contains("WPA") || ap.capabilities.contains("WEP")
    val containerColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MiuixTheme.colorScheme.tertiaryContainer
        } else {
            MiuixTheme.colorScheme.surfaceContainerHigh
        },
        label = "AccessPointContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MiuixTheme.colorScheme.onTertiaryContainer
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        label = "AccessPointContent",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = containerColor,
            contentColor = contentColor,
        ),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
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
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = contentColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = captured != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = contentColor.copy(alpha = 0.1f),
                            ) {
                                Text(
                                    text = "抓包 ${captured?.devices?.size ?: 0} 台",
                                    style = MiuixTheme.textStyles.footnote2,
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
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.Bold,
                    color = signalColor,
                    fontFamily = FontFamily.Monospace,
                )
                io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = !isUnknownSignal) {
                    Text(
                        text = "dBm",
                        style = MiuixTheme.textStyles.footnote2,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = signalLabel,
                    style = MiuixTheme.textStyles.footnote2,
                    color = signalColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.6f)
            )

            // BSSID
            Text(
                text = ap.BSSID!!,
                style = MiuixTheme.textStyles.footnote1,
                fontFamily = FontFamily.Monospace,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            captured?.ssid?.takeIf { it.isNotBlank() }?.let { ssid ->
                Text(
                    text = "SSID：$ssid",
                    style = MiuixTheme.textStyles.footnote2,
                    color = contentColor.copy(alpha = 0.75f),
                    softWrap = true,
                )
            }

            // 频率
            ApChip(text = "${ap.frequency} MHz")
            if (handshakeDeviceCount > 0) {
                ApChip(text = "成功握手${handshakeDeviceCount}台")
            }

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
                    style = MiuixTheme.textStyles.footnote2,
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
private fun VirtualApCard(
    info: WifiInfo,
    captured: MonitorAccessPoint?,
    onClick: () -> Unit,
) {
    val contentColor = MiuixTheme.colorScheme.onTertiaryContainer
    val handshakeDeviceCount = successfulHandshakeDeviceCount(captured)
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.tertiaryContainer,
            contentColor = contentColor,
        ),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
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
                        style = MiuixTheme.textStyles.footnote2,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = "未知",
                style = MiuixTheme.textStyles.headline2,
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
                    style = MiuixTheme.textStyles.footnote1,
                    color = contentColor,
                    softWrap = true,
                )
            }
            captured?.ssid?.takeIf { it.isNotBlank() }?.let { ssid ->
                Text(
                    text = "SSID：$ssid",
                    style = MiuixTheme.textStyles.footnote2,
                    color = contentColor.copy(alpha = 0.75f),
                    softWrap = true,
                )
            }
            if (handshakeDeviceCount > 0) {
                ApChip(text = "成功握手${handshakeDeviceCount}台")
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
                    style = MiuixTheme.textStyles.footnote2,
                    color = contentColor.copy(alpha = 0.75f),
                    softWrap = true,
                )
            }
            }
            wifiSecurityType(info)?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote2,
                    color = contentColor.copy(alpha = 0.75f),
                    softWrap = true,
                )
            }
        }
    }
}

/** 仅存在于抓包数据中的接入点（扫描列表里没有对应的 ScanResult）。 */
@Composable
private fun CapturedApCard(
    captured: MonitorAccessPoint?,
    onClick: () -> Unit,
) {
    if (captured == null) return

    val signalDbm = captured.signal?.latestDbm
    val signalLevel = signalDbm?.let { WifiManager.calculateSignalLevel(it, 5) } ?: 0
    val (signalLabel, signalColor) = if (signalDbm == null) {
        "未知" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        signalInfo(signalLevel)
    }
    val handshakeDeviceCount = captured.devices
        .asSequence()
        .filter { successfulHandshakeCount(it) > 0 }
        .distinctBy { it.mac.lowercase() }
        .count()

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
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
                    level = signalLevel,
                    color = signalColor,
                    modifier = Modifier.size(width = 22.dp, height = 16.dp),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = "抓包数据",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = signalDbm?.let { "$it" } ?: "未知",
                style = MiuixTheme.textStyles.headline2,
                fontWeight = FontWeight.Bold,
                color = signalColor,
                fontFamily = FontFamily.Monospace,
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.6f),
            )
            Text(
                text = captured.ssid?.takeIf { it.isNotBlank() }?.let { "SSID：$it" } ?: "<未知网络>",
                style = MiuixTheme.textStyles.body2,
                softWrap = true,
            )
            Text(
                text = captured.bssid,
                style = MiuixTheme.textStyles.footnote1,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ApChip(text = "${captured.devices.size} 台设备")
                if (handshakeDeviceCount > 0) {
                    ApChip(text = "成功握手${handshakeDeviceCount}台")
                }
            }
        }
    }
}

@Composable
private fun ApChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSecondaryContainer,
            softWrap = true,
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
                        else MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.5f)
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
            MiuixTheme.colorScheme.tertiaryContainer
        } else {
            MiuixTheme.colorScheme.surfaceContainerHigh
        },
        label = "SavedConfigContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MiuixTheme.colorScheme.onTertiaryContainer
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        label = "SavedConfigContent",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = containerColor,
            contentColor = contentColor,
        ),
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
                    style = MiuixTheme.textStyles.subtitle,
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
                            style = MiuixTheme.textStyles.footnote2,
                            color = contentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "ID ${config.networkId}",
                        style = MiuixTheme.textStyles.footnote2,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.6f)
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
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
            color = contentColor,
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End
        )
    }
}

// ── 工具函数 ──────────────────────────────────────────────────────────────────

private fun successfulHandshakeCount(device: MonitorDevice): Int = device.handshakes.count { record ->
    record.status == MonitorHandshakeStatus.SUCCESS &&
        record.captureQuality == MonitorHandshakeCaptureQuality.COMPLETE
}

private fun successfulHandshakeDeviceCount(accessPoint: MonitorAccessPoint?): Int =
    accessPoint?.devices
        ?.asSequence()
        ?.filter { successfulHandshakeCount(it) > 0 }
        ?.distinctBy { it.mac.lowercase() }
        ?.count()
        ?: 0

private fun accessPointTitle(entry: MergedAccessPoint?): String =
    entry?.scanned?.SSID?.takeIf { it.isNotBlank() }
        ?: entry?.captured?.ssid?.takeIf { it.isNotBlank() }
        ?: entry?.virtual?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
        ?: entry?.captured?.bssid
        ?: entry?.scanned?.BSSID
        ?: entry?.virtual?.bssid
        ?: "接入点"

@Composable
private fun signalInfo(level: Int): Pair<String, Color> = when (level) {
    4 -> "优秀" to MiuixTheme.colorScheme.primary
    3 -> "良好" to MiuixTheme.colorScheme.secondary
    2 -> "一般" to MiuixTheme.colorScheme.onSurfaceVariantActions
    1 -> "较弱" to MiuixTheme.colorScheme.onSurfaceVariantSummary
    else -> "很弱" to MiuixTheme.colorScheme.error
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
