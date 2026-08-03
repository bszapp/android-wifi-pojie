package io.github.bszapp.wifitoolbox.uidefault.screen

import android.net.wifi.WifiConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Smartphone
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.wifilist.isScanning
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSource
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestResult
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorPcapExportResult
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.uidefault.component.ListPopupDefaults
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import io.github.bszapp.wifitoolbox.uidefault.widget.WifiList
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorDeviceDetailSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.formatMonitorByteCount
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.frequencyBand
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharedFlow
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ListScreen(
    viewModel: DefaultViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val wifiState by viewModel.wifiList.state.collectAsStateWithLifecycle()
    val isSendingScanRequest by
        viewModel.wifiList.isSendingScanRequest.collectAsStateWithLifecycle()
    val informationSourceState by
        viewModel.wifiList.informationSourceState.collectAsStateWithLifecycle()
    val savedWifiList by viewModel.wifiList.savedWifiList.collectAsStateWithLifecycle()
    val selectedSource = informationSourceState?.source ?: WifiInformationSource.SYSTEM
    val isInitializing = informationSourceState?.initializing == true
    val isScanning = wifiState.isScanning || isSendingScanRequest
    val controlsBusy = isScanning || isInitializing
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    var pendingMonitorExport by remember { mutableStateOf<MonitorPcapExportResult?>(null) }
    val monitorExportSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap"),
    ) { uri ->
        val export = pendingMonitorExport ?: return@rememberLauncherForActivityResult
        pendingMonitorExport = null
        if (uri == null) {
            viewModel.wifiList.releaseMonitorPcapExport(export.path)
        } else {
            viewModel.wifiList.saveMonitorPcapExport(export.path, uri)
        }
    }

    LaunchedEffect(viewModel.wifiList) {
        viewModel.wifiList.monitorPcapExports.collect { export ->
            pendingMonitorExport?.let { previous ->
                viewModel.wifiList.releaseMonitorPcapExport(previous.path)
            }
            pendingMonitorExport = export
            monitorExportSaveLauncher.launch(export.fileName)
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = selectedSource.displayName,
                    navigationIcon = {
                        Box {
                            val showTopPopup = remember { mutableStateOf(false) }
                            OverlayListPopup(
                                show = showTopPopup.value,
                                popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                alignment = PopupPositionProvider.Align.TopEnd,
                                onDismissRequest = { showTopPopup.value = false },
                                content = {
                                    ListPopupColumn {
                                        SmallTitle(text = "系统模式")
                                        DropdownImpl(
                                            text = "扫描",
                                            isSelected = selectedSource == WifiInformationSource.SYSTEM,
                                            optionSize = 3,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                viewModel.wifiList.setInformationSource(
                                                    WifiInformationSource.SYSTEM,
                                                )
                                            },
                                            index = 1,
                                        )
                                        SmallTitle(text = "网卡模式")
                                        DropdownImpl(
                                            text = "混合扫描",
                                            isSelected = selectedSource == WifiInformationSource.HYBRID,
                                            optionSize = 3,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                viewModel.wifiList.setInformationSource(
                                                    WifiInformationSource.HYBRID,
                                                )
                                            },
                                            index = 1,
                                        )
                                        DropdownImpl(
                                            text = "监听模式",
                                            isSelected = selectedSource == WifiInformationSource.MONITOR,
                                            optionSize = 3,
                                            onSelectedIndexChange = {},
                                            index = 2,
                                        )
                                    }
                                },
                            )
                            IconButton(
                                onClick = { showTopPopup.value = true },
                                holdDownState = showTopPopup.value,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.MoreCircle,
                                    tint = colorScheme.onSurface,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    actions = {
                        if (selectedSource == WifiInformationSource.MONITOR) {
                            IconButton(
                                onClick = { viewModel.wifiList.exportAllMonitorPcap() },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    tint = colorScheme.onSurface,
                                    contentDescription = "导出全部 PCAP",
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { viewModel.wifiList.startScan() },
                                enabled = !controlsBusy,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Refresh,
                                    tint = colorScheme.onSurface,
                                    contentDescription = "刷新",
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        if (selectedSource == WifiInformationSource.MONITOR) {
            Box(
                modifier = Modifier.fillMaxSize().let {
                    if (backdrop != null) it.layerBackdrop(backdrop) else it
                },
            ) {
                when {
                    isInitializing -> InitializingContent(bottomInnerPadding)
                    wifiState is WifiState.Error -> WifiList(
                        modifier = Modifier.fillMaxHeight(),
                        vm = viewModel,
                        listState = listState,
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 14.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                            end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                            bottom = bottomInnerPadding + 8.dp,
                        ),
                    )
                    else -> MonitorModeContent(
                        statistics = informationSourceState?.monitorStatistics,
                        savedNetworks = savedWifiList?.networks.orEmpty(),
                        handshakeTestResults = viewModel.wifiList.monitorHandshakeTestResults,
                        onExportDevicePcap = { bssid, deviceMac, subtypeIds ->
                            viewModel.wifiList.exportMonitorDevicePcap(
                                bssid = bssid,
                                deviceMac = deviceMac,
                                subtypeIds = subtypeIds,
                            )
                        },
                        onTestHandshake = { bssid, deviceMac, handshakeId, password ->
                            viewModel.wifiList.testMonitorHandshake(
                                bssid = bssid,
                                deviceMac = deviceMac,
                                handshakeId = handshakeId,
                                password = password,
                            )
                        },
                        onExportHandshake = { bssid, deviceMac, handshakeId ->
                            viewModel.wifiList.exportMonitorHandshakePcap(
                                bssid = bssid,
                                deviceMac = deviceMac,
                                handshakeId = handshakeId,
                            )
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 14.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                            end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                            bottom = bottomInnerPadding + 8.dp,
                        ),
                    )
                }
            }
            return@Scaffold
        }
        val refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")
        PullToRefresh(//TODO:这个列表在混合模式下刷新中随着刷新次数增加越来越卡，重启app即可重置卡顿情况，系统模式无此问题
            isRefreshing = isScanning,
            pullToRefreshState = pullState,
            onRefresh = {
                if (!isInitializing) viewModel.wifiList.startScan()
            },
            refreshTexts = refreshTexts,
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 6.dp,
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = bottomInnerPadding,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().let {
                    if (backdrop != null) it.layerBackdrop(backdrop) else it
                },
            ) {
                if (isInitializing) {
                    InitializingContent(bottomInnerPadding)
                } else {
                    WifiList(
                        modifier = Modifier
                            .fillMaxHeight()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        vm = viewModel,
                        listState = listState,
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 14.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                            end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                            bottom = bottomInnerPadding + 8.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun InitializingContent(bottomInnerPadding: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomInnerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "初始化",
            modifier = Modifier.padding(top = 12.dp),
            color = colorScheme.onSurface,
        )
    }
}

@Composable
private fun MonitorModeContent(
    statistics: MonitorModeStatistics?,
    savedNetworks: List<WifiConfiguration>,
    handshakeTestResults: SharedFlow<MonitorHandshakeTestResult>,
    onExportDevicePcap: (
        bssid: String,
        deviceMac: String,
        subtypeIds: Set<String>,
    ) -> Unit,
    onTestHandshake: (
        bssid: String,
        deviceMac: String,
        handshakeId: String,
        password: String,
    ) -> String,
    onExportHandshake: (
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ) -> String,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val expandedAccessPoints = remember { mutableStateMapOf<String, Boolean>() }
    var selectedDevice by remember { mutableStateOf<MonitorDeviceSelection?>(null) }
    val accessPoints = statistics?.accessPoints.orEmpty()

    LazyColumn(
        modifier = modifier
            .scrollEndHaptic()
            .overScrollVertical(),
        contentPadding = contentPadding,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        if (statistics != null) {
            item {
                SmallTitle(text = "数据总览")
            }
            item {
                MonitorModeOverviewCard(statistics = statistics)
            }
        }
        item {
            SmallTitle(text = "接入点地图")
        }
        if (accessPoints.isNotEmpty()) {
            items(
                count = accessPoints.size,
                key = { index -> accessPoints[index].bssid },
            ) { index ->
                val accessPoint = accessPoints[index]
                MonitorAccessPointCard(
                    accessPoint = accessPoint,
                    expanded = expandedAccessPoints[accessPoint.bssid] == true,
                    onExpandedChange = { expanded ->
                        expandedAccessPoints[accessPoint.bssid] = expanded
                    },
                    onDeviceClick = { device ->
                        selectedDevice = MonitorDeviceSelection(
                            bssid = accessPoint.bssid,
                            deviceMac = device.mac,
                        )
                    },
                )
            }
        }
    }

    selectedDevice?.let { selection ->
        val accessPoint = accessPoints.firstOrNull { it.bssid == selection.bssid }
        val device = accessPoint?.devices?.firstOrNull { it.mac == selection.deviceMac }
        if (accessPoint != null && device != null) {
            MonitorDeviceDetailSheet(
                accessPoint = accessPoint,
                device = device,
                savedNetworks = savedNetworks,
                handshakeTestResults = handshakeTestResults,
                onDismiss = { selectedDevice = null },
                onExport = { subtypeIds ->
                    selectedDevice = null
                    onExportDevicePcap(accessPoint.bssid, device.mac, subtypeIds)
                },
                onTestHandshake = { handshakeId, password ->
                    onTestHandshake(
                        accessPoint.bssid,
                        device.mac,
                        handshakeId,
                        password,
                    )
                },
                onExportHandshake = { handshakeId ->
                    onExportHandshake(
                        accessPoint.bssid,
                        device.mac,
                        handshakeId,
                    )
                },
            )
        }
    }
}

@Composable
private fun MonitorModeOverviewCard(statistics: MonitorModeStatistics) {
    Card {
        BasicComponent(
            title = "已进入监听模式",
            summary = "信道 ${statistics.channel} · " +
                "${frequencyBand(statistics.frequencyMhz)} · " +
                "${statistics.frequencyMhz} MHz",
        )
        BasicComponent(
            title = "已录制的大小",
            summary = formatMonitorByteCount(statistics.recordedBytes),
        )
        Text(
            text = "当前处于监听模式，如需退出请在左上角切换为其他模式。" +
                "请勿操作系统WiFi以免打断监听过程。",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            color = colorScheme.onSurfaceVariantSummary,
            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote1,
        )
    }
}

private data class MonitorDeviceSelection(
    val bssid: String,
    val deviceMac: String,
)

@Composable
private fun MonitorAccessPointCard(
    accessPoint: MonitorAccessPoint,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDeviceClick: (MonitorDevice) -> Unit,
) {
    val handshakeDeviceCount = accessPoint.devices
        .asSequence()
        .filter { it.handshakes.isNotEmpty() }
        .distinctBy { it.mac.lowercase() }
        .count()
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "MonitorAccessPointArrow",
    )
    Card {
        BasicComponent(
            startAction = {
                Icon(
                    imageVector = Icons.Rounded.Router,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(24.dp),
                    tint = colorScheme.onBackground,
                )
            },
            endActions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${accessPoint.devices.size} 台设备",
                        color = colorScheme.onSurfaceVariantActions,
                        style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "折叠" else "展开",
                        modifier = Modifier.size(20.dp).rotate(arrowRotation),
                        tint = colorScheme.onSurfaceVariantActions,
                    )
                }
            },
            onClick = { onExpandedChange(!expanded) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = accessPoint.ssid ?: "<未知网络>",
                    fontSize = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                if (accessPoint.ssidVisibility == MonitorSsidVisibility.HIDDEN) {
                    MonitorMapBadge(text = "隐藏网络")
                }
                if (handshakeDeviceCount > 0) {
                    MonitorMapBadge(text = "${handshakeDeviceCount}个设备握手")
                }
            }
            Text(
                text = accessPoint.bssid,
                fontSize = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                accessPoint.devices.forEach { device ->
                    val handshakeCount = device.handshakes.size
                    if (device.name == null) {
                        BasicComponent(
                            title = device.mac,
                            startAction = {
                                MonitorDeviceIcon()
                            },
                            endActions = if (handshakeCount > 0) {
                                {
                                    MonitorMapBadge(
                                        text = "${handshakeCount}个握手包",
                                        withStartPadding = false,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = { onDeviceClick(device) },
                        )
                    } else {
                        BasicComponent(
                            title = device.name,
                            summary = device.mac,
                            startAction = {
                                MonitorDeviceIcon()
                            },
                            endActions = if (handshakeCount > 0) {
                                {
                                    MonitorMapBadge(
                                        text = "${handshakeCount}个握手包",
                                        withStartPadding = false,
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = { onDeviceClick(device) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitorMapBadge(
    text: String,
    withStartPadding: Boolean = true,
) {
    Badge(
        modifier = if (withStartPadding) Modifier.padding(start = 8.dp) else Modifier,
        containerColor = colorScheme.primary,
        contentColor = colorScheme.onPrimary,
    ) {
        Text(text = text)
    }
}

@Composable
private fun MonitorDeviceIcon() {
    Icon(
        imageVector = Icons.Rounded.Smartphone,
        contentDescription = null,
        modifier = Modifier.padding(end = 8.dp).size(24.dp),
        tint = colorScheme.onBackground,
    )
}
