package io.github.bszapp.wifitoolbox.uidefault.screen

import android.net.wifi.WifiConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WifiProtectedSetup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdatePayload
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.wifilist.isScanning
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSource
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDisconnectionRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDisconnectionType
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeCaptureQuality
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorMapFilterState
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorPcapExportResult
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.uidefault.component.ListPopupDefaults
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.model.MonitorHandshakeTestUiState
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import io.github.bszapp.wifitoolbox.uidefault.widget.WifiList
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiSheetContent
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiTaskSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorDeviceDetailSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorHandshakeAction
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WpsPbcTaskSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.formatHandshakeDuration
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.formatHandshakeStartTime
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.handshakeStatusText
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.handshakeStepText
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.formatMonitorByteCount
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.frequencyBand
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
    val monitorMapFilterState by
        viewModel.wifiList.monitorMapFilterState.collectAsStateWithLifecycle()
    val monitorHandshakeTest by
        viewModel.wifiList.monitorHandshakeTest.collectAsStateWithLifecycle()
    val displayedTask by viewModel.displayedTask.collectAsStateWithLifecycle()
    val selectedSource = informationSourceState?.source ?: WifiInformationSource.SYSTEM
    val isInitializing = informationSourceState?.initializing == true
    val isScanning = wifiState.isScanning || isSendingScanRequest
    val controlsBusy = isScanning || isInitializing
    val taskActionScope = rememberCoroutineScope()
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
    var pendingHc22000Export by remember { mutableStateOf<PendingHc22000Export?>(null) }
    val hc22000SaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val export = pendingHc22000Export ?: return@rememberLauncherForActivityResult
        pendingHc22000Export = null
        if (uri != null) {
            viewModel.wifiList.saveMonitorHc22000(export.content, uri)
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
                            if (selectedSource == WifiInformationSource.HYBRID) {
                                IconButton(
                                    onClick = {
                                        taskActionScope.launch {
                                            runCatching { viewModel.startWpsPbcTask() }
                                        }
                                    },
                                    enabled = !isInitializing,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WifiProtectedSetup,
                                        tint = colorScheme.onSurface,
                                        contentDescription = "启动 WPS-PBC",
                                    )
                                }
                            }
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
                        handshakeTest = monitorHandshakeTest,
                        onClearHandshakeTestResult =
                            viewModel.wifiList::clearMonitorHandshakeTestResult,
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
                        onExportDisconnection = { bssid, deviceMac, disconnectionId ->
                            viewModel.wifiList.exportMonitorDisconnectionPcap(
                                bssid = bssid,
                                deviceMac = deviceMac,
                                disconnectionId = disconnectionId,
                            )
                        },
                        filterState = monitorMapFilterState,
                        onFilterStateChange = viewModel.wifiList::updateMonitorMapFilterState,
                        onSaveHc22000 = { content, fileName ->
                            pendingHc22000Export = PendingHc22000Export(content, fileName)
                            hc22000SaveLauncher.launch(fileName)
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
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
        PullToRefresh(
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

    displayedTask?.let { task ->
        DisplayedTaskSheet(viewModel = viewModel, task = task)
    }
}

@Composable
private fun DisplayedTaskSheet(
    viewModel: DefaultViewModel,
    task: TrackedTaskState,
) {
    when (task.snapshot.request.payload) {
        is TaskRequestPayload.ConnectWifi -> ConnectWifiTaskSheet(
            content = ConnectWifiSheetContent.Task,
            trackedTask = task,
            isSubmitting = false,
            dismissRequested = false,
            onSubmit = {},
            onStop = viewModel::stopDisplayedTask,
            onDismiss = viewModel::closeDisplayedTask,
        )

        is TaskRequestPayload.WpsPbc -> WpsPbcTaskSheet(
            trackedTask = task,
            isStarting = false,
            onContinuousCaptureChange = { enabled ->
                viewModel.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcContinuousCapture(enabled)),
                )
            },
            onAutoSaveToDeviceChange = { enabled ->
                viewModel.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcAutoSaveToDevice(enabled)),
                )
            },
            onUseIncompleteProtocolChange = { enabled ->
                viewModel.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcUseIncompleteProtocol(enabled)),
                )
            },
            onIgnoreRepeatedDevicesChange = { enabled ->
                viewModel.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcIgnoreRepeatedDevices(enabled)),
                )
            },
            onStop = viewModel::stopDisplayedTask,
            onDismiss = viewModel::closeDisplayedTask,
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonitorModeContent(
    statistics: MonitorModeStatistics?,
    savedNetworks: List<WifiConfiguration>,
    handshakeTest: MonitorHandshakeTestUiState,
    onClearHandshakeTestResult: () -> Unit,
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
    ) -> Unit,
    onExportHandshake: (
        bssid: String,
        deviceMac: String,
        handshakeId: String,
    ) -> String,
    onExportDisconnection: (
        bssid: String,
        deviceMac: String,
        disconnectionId: String,
    ) -> String,
    filterState: MonitorMapFilterState,
    onFilterStateChange: (MonitorMapFilterState) -> Unit,
    onSaveHc22000: (content: String, fileName: String) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val expandedAccessPoints = remember { mutableStateMapOf<String, Boolean>() }
    var selectedDevice by remember { mutableStateOf<MonitorDeviceSelection?>(null) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    var showFilterSheet by remember { mutableStateOf(false) }
    var handshakeAction by remember { mutableStateOf<MonitorHandshakeActionSelection?>(null) }
    var disconnectionExportTarget by remember {
        mutableStateOf<MonitorDisconnectionRecord?>(null)
    }
    var disconnectionExportConsent by remember { mutableStateOf(false) }
    val accessPoints = statistics?.accessPoints.orEmpty()
    val filteredAccessPoints = accessPoints
        .asSequence()
        .filter { filterState.showUnknownNetworks || !it.ssid.isNullOrBlank() }
        .map { accessPoint ->
            accessPoint.copy(
                devices = accessPoint.devices.filter { device ->
                    filterState.showProbeOnlyDevices || !device.probeOnly
                },
            )
        }
        .toList()
    val connectionLogItems = buildMonitorConnectionLogItems(
        accessPoints = accessPoints,
        disconnections = statistics?.disconnections.orEmpty(),
    )
    val layoutDirection = LocalLayoutDirection.current
    val topBarPadding = contentPadding.calculateTopPadding()

    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val listContentPadding = PaddingValues(
                top = 14.dp,
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding() + if (page == 0) 76.dp else 0.dp,
            )
            LazyColumn(
                modifier = Modifier
                    .padding(top = topBarPadding)
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = listContentPadding,
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
                stickyHeader(key = "monitor-mode-tabs-$page") {
                    MonitorModeTabRow(
                        selectedTab = pagerState.currentPage,
                        onSelectedTabChange = { targetPage ->
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(targetPage)
                            }
                        },
                    )
                }
                if (page == 0) {
                    items(
                        count = filteredAccessPoints.size,
                        key = { index -> filteredAccessPoints[index].bssid },
                    ) { index ->
                        val accessPoint = filteredAccessPoints[index]
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
                } else {
                    if (connectionLogItems.isEmpty()) {
                        item { Card { BasicComponent(title = "暂无连接日志") } }
                    } else {
                        items(
                            count = connectionLogItems.size,
                            key = { index -> connectionLogItems[index].stableKey },
                        ) { index ->
                            MonitorConnectionLogCard(
                                item = connectionLogItems[index],
                                onTestHandshake = { accessPoint, device, record ->
                                    handshakeAction = MonitorHandshakeActionSelection(
                                        bssid = accessPoint.bssid,
                                        deviceMac = device.mac,
                                        handshakeId = record.id,
                                        action = MonitorHandshakeAction.TEST,
                                    )
                                },
                                onExportHandshake = { accessPoint, device, record ->
                                    handshakeAction = MonitorHandshakeActionSelection(
                                        bssid = accessPoint.bssid,
                                        deviceMac = device.mac,
                                        handshakeId = record.id,
                                        action = MonitorHandshakeAction.EXPORT,
                                    )
                                },
                                onExportDisconnection = { record ->
                                    disconnectionExportConsent = false
                                    disconnectionExportTarget = record
                                },
                            )
                        }
                    }
                }
            }
        }
        if (pagerState.currentPage == 0) {
            FloatingActionButton(
                onClick = { showFilterSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = contentPadding.calculateEndPadding(layoutDirection) + 4.dp,
                        bottom = contentPadding.calculateBottomPadding() + 12.dp,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = "筛选接入点地图",
                    tint = colorScheme.onPrimary,
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
                handshakeTest = handshakeTest,
                onClearHandshakeTestResult = onClearHandshakeTestResult,
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
                onSaveHc22000 = onSaveHc22000,
            )
        }
    }

    handshakeAction?.let { selection ->
        val accessPoint = accessPoints.firstOrNull { it.bssid == selection.bssid }
        val device = accessPoint?.devices?.firstOrNull { it.mac == selection.deviceMac }
        if (accessPoint != null && device != null) {
            MonitorDeviceDetailSheet(
                accessPoint = accessPoint,
                device = device,
                savedNetworks = savedNetworks,
                handshakeTest = handshakeTest,
                onClearHandshakeTestResult = onClearHandshakeTestResult,
                onDismiss = { handshakeAction = null },
                onExport = { _ -> },
                onTestHandshake = { handshakeId, password ->
                    onTestHandshake(accessPoint.bssid, device.mac, handshakeId, password)
                },
                onExportHandshake = { handshakeId ->
                    onExportHandshake(accessPoint.bssid, device.mac, handshakeId)
                },
                onSaveHc22000 = onSaveHc22000,
                showDeviceDetails = false,
                initialHandshakeId = selection.handshakeId,
                initialHandshakeAction = selection.action,
                onInitialActionFinished = { handshakeAction = null },
            )
        }
    }

    MonitorMapFilterSheet(
        show = showFilterSheet,
        state = filterState,
        onStateChange = onFilterStateChange,
        onDismiss = { showFilterSheet = false },
    )

    MonitorDisconnectionExportDialog(
        record = disconnectionExportTarget,
        accessPoint = disconnectionExportTarget?.let { record ->
            accessPoints.firstOrNull { it.bssid == record.bssid }
        },
        consent = disconnectionExportConsent,
        onConsentChange = { disconnectionExportConsent = it },
        onDismiss = {
            disconnectionExportTarget = null
            disconnectionExportConsent = false
        },
        onExport = { record ->
            onExportDisconnection(record.bssid, record.deviceMac, record.id)
            disconnectionExportTarget = null
            disconnectionExportConsent = false
        },
    )
}

@Composable
private fun MonitorModeTabRow(
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
) {
    TabRow(
        tabs = listOf("接入点地图", "连接日志"),
        selectedTabIndex = selectedTab,
        onTabSelected = onSelectedTabChange,
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .padding(vertical = 6.dp),
        colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
    )
}

@Composable
private fun MonitorMapFilterSheet(
    show: Boolean,
    state: MonitorMapFilterState,
    onStateChange: (MonitorMapFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    OverlayBottomSheet(
        show = show,
        title = "筛选接入点地图",
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding + 12.dp),
        ) {
            MonitorMapFilterRow(
                text = "显示仅探测的设备",
                checked = state.showProbeOnlyDevices,
                onCheckedChange = {
                    onStateChange(state.copy(showProbeOnlyDevices = it))
                },
            )
            MonitorMapFilterRow(
                text = "显示未知名称的网络",
                checked = state.showUnknownNetworks,
                onCheckedChange = {
                    onStateChange(state.copy(showUnknownNetworks = it))
                },
            )
        }
    }
}

@Composable
private fun MonitorMapFilterRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange(!checked) },
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 12.dp),
            color = colorScheme.onSurface,
        )
    }
}

private sealed interface MonitorConnectionLogItem {
    val stableKey: String
    val timestampUnixMillis: Long

    data class Handshake(
        val accessPoint: MonitorAccessPoint,
        val device: MonitorDevice,
        val record: MonitorHandshakeRecord,
    ) : MonitorConnectionLogItem {
        override val stableKey = "handshake:${accessPoint.bssid}:${device.mac}:${record.id}"
        override val timestampUnixMillis = record.startUnixMillis
    }

    data class Disconnection(
        val accessPoint: MonitorAccessPoint?,
        val record: MonitorDisconnectionRecord,
    ) : MonitorConnectionLogItem {
        override val stableKey = "disconnection:${record.bssid}:${record.deviceMac}:${record.id}"
        override val timestampUnixMillis = record.timestampUnixMillis
    }
}

private fun buildMonitorConnectionLogItems(
    accessPoints: List<MonitorAccessPoint>,
    disconnections: List<MonitorDisconnectionRecord>,
): List<MonitorConnectionLogItem> = buildList {
    accessPoints.forEach { accessPoint ->
        accessPoint.devices.forEach { device ->
            device.handshakes.forEach { record ->
                add(MonitorConnectionLogItem.Handshake(accessPoint, device, record))
            }
        }
    }
    disconnections.forEach { record ->
        add(
            MonitorConnectionLogItem.Disconnection(
                accessPoint = accessPoints.firstOrNull { it.bssid == record.bssid },
                record = record,
            ),
        )
    }
}.sortedByDescending(MonitorConnectionLogItem::timestampUnixMillis)

@Composable
private fun MonitorConnectionLogCard(
    item: MonitorConnectionLogItem,
    onTestHandshake: (
        MonitorAccessPoint,
        MonitorDevice,
        MonitorHandshakeRecord,
    ) -> Unit,
    onExportHandshake: (
        MonitorAccessPoint,
        MonitorDevice,
        MonitorHandshakeRecord,
    ) -> Unit,
    onExportDisconnection: (MonitorDisconnectionRecord) -> Unit,
) {
    Card {
        when (item) {
            is MonitorConnectionLogItem.Handshake -> {
                val qualityText = if (item.record.status == MonitorHandshakeStatus.IN_PROGRESS) {
                    "捕获中"
                } else {
                    when (item.record.captureQuality) {
                        MonitorHandshakeCaptureQuality.COMPLETE -> "完整"
                        MonitorHandshakeCaptureQuality.DATA_INCOMPLETE -> "数据不完整"
                        MonitorHandshakeCaptureQuality.PARTIALLY_MISSING -> "部分缺失"
                    }
                }
                BasicComponent(
                    title = "设备连接 · ${item.accessPoint.ssid ?: monitorUnknownName(item.accessPoint)}",
                    summary = buildString {
                        append("设备：${item.device.mac}\n")
                        append("接入点：${item.accessPoint.bssid}\n")
                        append("开始时间：${formatHandshakeStartTime(item.record.startUnixMillis)}\n")
                        append("持续时间：${formatHandshakeDuration(item.record.durationMillis)} · ")
                        append(handshakeStatusText(item.record))
                        append("\n捕获情况：$qualityText")
                        append("\n捕获阶段：")
                        append(
                            item.record.capturedSteps
                                .joinToString("、", transform = ::handshakeStepText)
                                .ifEmpty { "暂无" },
                        )
                        item.record.failedAtStep?.let { failedAtStep ->
                            append("\n失败阶段：${handshakeStepText(failedAtStep)}")
                        }
                        append("\nM2 捕获次数：${item.record.m2AttemptCount}")
                        append(" · 导出包数量：${item.record.exportPacketCount}")
                    },
                    bottomAction = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (item.record.canValidate) {
                                TextButton(
                                    text = "校验",
                                    onClick = {
                                        onTestHandshake(
                                            item.accessPoint,
                                            item.device,
                                            item.record,
                                        )
                                    },
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                            }
                            TextButton(
                                text = "导出",
                                onClick = {
                                    onExportHandshake(
                                        item.accessPoint,
                                        item.device,
                                        item.record,
                                    )
                                },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    },
                )
            }
            is MonitorConnectionLogItem.Disconnection -> {
                val eventName = when (item.record.type) {
                    MonitorDisconnectionType.DISASSOCIATION -> "解除关联"
                    MonitorDisconnectionType.DEAUTHENTICATION -> "解除认证"
                }
                BasicComponent(
                    title = eventName,
                    summary = buildString {
                        append("时间：${formatHandshakeStartTime(item.record.timestampUnixMillis)}\n")
                        append("网络：")
                        append(item.accessPoint?.ssid ?: item.accessPoint?.let(::monitorUnknownName) ?: "<未知网络>")
                        append("\n接入点：${item.record.bssid}\n")
                        append("设备：${item.record.deviceMac}\n")
                        append("原因码：${item.record.reasonCode?.toString() ?: "未知"}")
                    },
                    bottomAction = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                        ) {
                            TextButton(
                                text = "导出",
                                onClick = { onExportDisconnection(item.record) },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MonitorDisconnectionExportDialog(
    record: MonitorDisconnectionRecord?,
    accessPoint: MonitorAccessPoint?,
    consent: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onExport: (MonitorDisconnectionRecord) -> Unit,
) {
    OverlayDialog(
        show = record != null,
        title = "确认导出断开事件",
        summary = "将导出这一次解除认证或解除关联事件的原始 802.11 数据包。",
        onDismissRequest = onDismiss,
        content = {
            if (record != null) {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    Card {
                        BasicComponent(
                            title = "网络名称",
                            summary = accessPoint?.ssid
                                ?: accessPoint?.let(::monitorUnknownName)
                                ?: "<未知网络>",
                        )
                        BasicComponent(title = "接入点 MAC", summary = record.bssid)
                        BasicComponent(title = "目标设备 MAC", summary = record.deviceMac)
                        BasicComponent(
                            title = "发生时间",
                            summary = formatHandshakeStartTime(record.timestampUnixMillis),
                        )
                        BasicComponent(
                            title = "事件类型",
                            summary = when (record.type) {
                                MonitorDisconnectionType.DISASSOCIATION -> "解除关联"
                                MonitorDisconnectionType.DEAUTHENTICATION -> "解除认证"
                            },
                        )
                        BasicComponent(
                            title = "原因码",
                            summary = record.reasonCode?.toString() ?: "未知",
                        )
                        BasicComponent(
                            title = "导出包数量",
                            summary = record.exportPacketCount.toString(),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConsentChange(!consent) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = if (consent) ToggleableState.On else ToggleableState.Off,
                            onClick = { onConsentChange(!consent) },
                        )
                        Text(
                            text = "我已拥有目标设备或目标接入点的所有权，并知晓握手包仅用于诊断连接情况使用",
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                            color = colorScheme.onSurface,
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "取消",
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "导出",
                            enabled = consent,
                            onClick = { onExport(record) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        },
    )
}

private data class MonitorHandshakeActionSelection(
    val bssid: String,
    val deviceMac: String,
    val handshakeId: String,
    val action: MonitorHandshakeAction,
)

private data class PendingHc22000Export(
    val content: String,
    val fileName: String,
)

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
        .filter { device ->
            device.handshakes.any { record ->
                record.status == MonitorHandshakeStatus.SUCCESS &&
                    record.captureQuality != MonitorHandshakeCaptureQuality.DATA_INCOMPLETE
            }
        }
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
                    text = accessPoint.ssid ?: monitorUnknownName(accessPoint),
                    fontSize = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                if (
                    accessPoint.ssidVisibility == MonitorSsidVisibility.HIDDEN &&
                    !accessPoint.ssid.isNullOrBlank()
                ) {
                    MonitorMapBadge(text = "隐藏网络")
                }
                if (handshakeDeviceCount > 0) {
                    MonitorMapBadge(text = "${handshakeDeviceCount}台成功握手")
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
                    val handshakeCount = device.handshakes.count { record ->
                        record.status == MonitorHandshakeStatus.SUCCESS &&
                            record.captureQuality !=
                            MonitorHandshakeCaptureQuality.DATA_INCOMPLETE
                    }
                    if (device.name == null) {
                        BasicComponent(
                            title = device.mac,
                            startAction = {
                                MonitorDeviceIcon()
                            },
                            endActions = if (handshakeCount > 0) {
                                {
                                    MonitorMapBadge(
                                        text = "${handshakeCount}次成功握手",
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
                                        text = "${handshakeCount}次成功握手",
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

private fun monitorUnknownName(accessPoint: MonitorAccessPoint): String =
    if (accessPoint.ssidVisibility == MonitorSsidVisibility.HIDDEN) {
        "<隐藏的网络>"
    } else {
        "<未知网络>"
    }
