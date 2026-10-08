package io.github.bszapp.wifitoolbox.uidefault.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.task.*
import io.github.bszapp.wifitoolbox.contract.wifilist.*
import io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility
import io.github.bszapp.wifitoolbox.uidefault.component.ListPopupDefaults
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import io.github.bszapp.wifitoolbox.uidefault.widget.WifiList
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
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
    val navigator = LocalNavigator.current
    val wifiState by viewModel.wifiList.state.collectAsStateWithLifecycle()
    val isSendingScanRequest by
        viewModel.wifiList.isSendingScanRequest.collectAsStateWithLifecycle()
    val modeState by
        viewModel.wifiList.modeState.collectAsStateWithLifecycle()
    val currentTask by viewModel.currentTask.collectAsStateWithLifecycle()
    val usbMonitorTask = currentTask?.takeIf {
        it.state == TaskExecutionState.RUNNING && it.request.payload is TaskRequestPayload.UsbMonitor
    }
    val displayedTask by viewModel.displayedTask.collectAsStateWithLifecycle()
    val selectedSource = modeState?.mode ?: WifiMode.NORMAL
    val isScanning = wifiState.isScanning || isSendingScanRequest
    val controlsBusy = isScanning || modeState?.modeSwitch?.isRunning == true
    val taskActionScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    var inlineConnectTaskId by remember { mutableStateOf<Long?>(null) }
    var showMonitorCommand by rememberSaveable { mutableStateOf(false) }
    var showCaptureChannels by rememberSaveable { mutableStateOf(false) }
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
                                        DropdownImpl(
                                            text = "普通模式",
                                            isSelected = selectedSource == WifiMode.NORMAL,
                                            optionSize = 2,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                viewModel.wifiList.setMode(
                                                    WifiMode.NORMAL,
                                                )
                                            },
                                            index = 0,
                                        )
                                        DropdownImpl(
                                            text = "监听模式",
                                            isSelected = selectedSource == WifiMode.MONITOR,
                                            optionSize = 2,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                if (selectedSource != WifiMode.MONITOR) showMonitorCommand = true
                                            },
                                            index = 1,
                                        )
                                    }
                                },
                            )
                            IconButton(
                                onClick = { showTopPopup.value = true },
                                holdDownState = showTopPopup.value,
                                enabled = usbMonitorTask == null,
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
                        ImmediateVisibility(
                            visible = selectedSource == WifiMode.NORMAL,
                        ) {
                            IconButton(onClick = { taskActionScope.launch { runCatching { viewModel.startWpsPbcTask() } } }) {
                                Icon(Icons.Rounded.WifiProtectedSetup, "启动 WPS-PBC", tint = colorScheme.onSurface)
                            }
                        }
                        ImmediateVisibility(visible = selectedSource == WifiMode.MONITOR) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { navigator.push(Route.Capture()) }, enabled = usbMonitorTask == null) {
                                    Icon(Icons.Rounded.ManageSearch, "抓包解析", tint = colorScheme.onSurface)
                                }
                                IconButton(onClick = { viewModel.wifiList.exportAllMonitorPcap() }, enabled = usbMonitorTask == null) {
                                    Icon(Icons.Rounded.Download, "导出全部 PCAP", tint = colorScheme.onSurface)
                                }
                                Box {
                                    val showClear = remember { mutableStateOf(false) }
                                    OverlayListPopup(
                                        show = showClear.value,
                                        popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                        alignment = PopupPositionProvider.Align.TopEnd,
                                        onDismissRequest = { showClear.value = false },
                                    ) {
                                        ListPopupColumn {
                                            // 在 Popup 内容组合中读取实时状态，展开后也持续刷新。
                                            val statistics = modeState?.monitorStatistics
                                            listOf(
                                                "清空抓取数据（${formatMonitorByteCount(statistics?.recordedBytes ?: 0L)}）",
                                                "仅清空非握手数据（${formatMonitorByteCount(statistics?.nonHandshakeBytes ?: 0L)}）",
                                            ).forEachIndexed { index, title ->
                                                DropdownImpl(text = title, isSelected = false, optionSize = 2, index = index,
                                                    onSelectedIndexChange = {
                                                        showClear.value = false
                                                        viewModel.wifiList.clearMonitorCapture(index == 1)
                                                    })
                                            }
                                        }
                                    }
                                    IconButton(onClick = { showClear.value = true }, enabled = usbMonitorTask == null && !controlsBusy && modeState?.clearingCapture != true) {
                                        Icon(Icons.Rounded.DeleteSweep, "清理抓取数据", tint = colorScheme.onSurface)
                                    }
                                }
                                // 电脑控制入口暂时隐藏；保留现有任务实现。
                                ImmediateVisibility(visible = false) {
                                    var showComputerControl by remember { mutableStateOf(false) }
                                    OverlayListPopup(
                                        show = showComputerControl,
                                        popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                        alignment = PopupPositionProvider.Align.TopEnd,
                                        onDismissRequest = { showComputerControl = false },
                                    ) {
                                        ListPopupColumn {
                                            DropdownImpl(
                                                text = "电脑控制", isSelected = false, optionSize = 1, index = 0,
                                                onSelectedIndexChange = {
                                                    showComputerControl = false
                                                    taskActionScope.launch { runCatching { viewModel.startUsbMonitorTask() } }
                                                },
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showComputerControl = true }, enabled = usbMonitorTask == null) {
                                        Icon(Icons.Rounded.MoreVert, "更多", tint = colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                        ImmediateVisibility(visible = selectedSource == WifiMode.NORMAL) {
                            Box {
                                val showMore = remember { mutableStateOf(false) }
                                OverlayListPopup(
                                    show = showMore.value,
                                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showMore.value = false },
                                ) {
                                    ListPopupColumn {
                                        DropdownImpl(text = "系统扫描", isSelected = modeState?.listDataSource == WifiListDataSource.SYSTEM,
                                            optionSize = 2, index = 0, onSelectedIndexChange = {
                                                viewModel.wifiList.setWifiListDataSource(WifiListDataSource.SYSTEM)
                                                showMore.value = false
                                            })
                                        DropdownImpl(text = "底层扫描", isSelected = modeState?.listDataSource == WifiListDataSource.UNDERLYING,
                                            optionSize = 2, index = 1, onSelectedIndexChange = {
                                                viewModel.wifiList.setWifiListDataSource(WifiListDataSource.UNDERLYING)
                                                showMore.value = false
                                            })
                                    }
                                }
                                IconButton(onClick = { showMore.value = true }) {
                                    Icon(Icons.Rounded.Route, "Wi-Fi 列表数据源", tint = colorScheme.onSurface)
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.wifiList.startScan() },
                            enabled = usbMonitorTask == null && !controlsBusy && modeState?.clearingCapture != true) {
                            Icon(MiuixIcons.Refresh, "刷新", tint = colorScheme.onSurface)
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
        if (usbMonitorTask != null) {
            UsbMonitorTaskContent(
                progress = usbMonitorTask.progress as? TaskProgress.UsbMonitor,
                onStop = viewModel::stopUsbMonitorTask,
                modifier = Modifier.fillMaxSize().padding(
                    top = innerPadding.calculateTopPadding() + 14.dp,
                    start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                    bottom = bottomInnerPadding + 12.dp,
                ),
            )
            return@Scaffold
        }
        val refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")
        PullToRefresh(
            isRefreshing = isScanning,
            pullToRefreshState = pullState,
            onRefresh = {
                viewModel.wifiList.startScan()
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
                WifiList(
                    modifier = Modifier
                        .fillMaxHeight()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    vm = viewModel,
                    listState = listState,
                    onInlineConnectTaskChanged = { inlineConnectTaskId = it },
                    onSaveHc22000 = { content, fileName ->
                        pendingHc22000Export = PendingHc22000Export(content, fileName)
                        hc22000SaveLauncher.launch(fileName)
                    },
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 14.dp,
                        start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                        bottom = bottomInnerPadding + if (selectedSource == WifiMode.MONITOR) 84.dp else 8.dp,
                    ),
                )
                ImmediateVisibility(
                    visible = selectedSource == WifiMode.MONITOR,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomInnerPadding + 16.dp),
                ) {
                    FloatingActionButton(
                        onClick = {
                            if (modeState?.capturing == true) viewModel.wifiList.setMonitorCapture(false)
                            else showCaptureChannels = true
                        },
                    ) {
                        Icon(
                            if (modeState?.capturing == true) Icons.Outlined.StopCircle else Icons.Rounded.RocketLaunch,
                            if (modeState?.capturing == true) "停止持续抓取" else "持续抓取",
                            tint = colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }

    WifiModeSwitchSheet(progress = modeState?.modeSwitch, onInterrupt = viewModel.wifiList::interruptModeSwitch)
    MonitorCaptureClearSheet(progress = modeState?.captureClearProgress, onInterrupt = viewModel.wifiList::interruptMonitorClear)
    MonitorModeSheet(
        show = showMonitorCommand,
        onDismiss = { showMonitorCommand = false },
        onExecute = { command ->
            showMonitorCommand = false
            viewModel.wifiList.enterMonitorMode(command)
        },
    )
    MonitorCaptureSheet(
        show = showCaptureChannels && selectedSource == WifiMode.MONITOR,
        channels = modeState?.availableChannels.orEmpty(),
        scanResults = wifiState.scanResults,
        onDismiss = { showCaptureChannels = false },
        onStart = { frequency, hopping ->
            showCaptureChannels = false
            viewModel.wifiList.setMonitorCapture(true, frequency, hopping)
        },
    )

    displayedTask?.let { task ->
        if (task.snapshot.taskId != inlineConnectTaskId) {
            DisplayedTaskSheet(viewModel = viewModel, task = task)
        }
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
        TaskRequestPayload.UsbMonitor -> Unit
    }
}

private data class PendingHc22000Export(
    val content: String,
    val fileName: String,
)
