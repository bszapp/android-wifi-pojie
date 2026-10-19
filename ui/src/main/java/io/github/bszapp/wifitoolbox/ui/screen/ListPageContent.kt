// 列表页壳/内容拆分：本文件为"内容"侧（Scaffold + TopAppBar + 顶层 Sheet + 任务 Sheet 分发）。
// ListPageState 为状态快照，ListPageActions 为动作接口；ListScreen 只是 Android 侧映射
// （flow 收集 + ActivityResult 文件导出胶水），预览/无头渲染直接构造假数据。
package io.github.bszapp.wifitoolbox.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.task.AuditRecordKind
import io.github.bszapp.wifitoolbox.contract.settings.AuditLogPanelConfig
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditConfig
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditConfig
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdatePayload
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.task.WpsPbcTaskInput
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorPcapExportResult
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiListDataSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.contract.wifilist.isScanning
import io.github.bszapp.wifitoolbox.contract.wifilist.scanResults
import io.github.bszapp.wifitoolbox.ui.component.ListPopupDefaults
import io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode
import io.github.bszapp.wifitoolbox.ui.model.resolveWifiListDisplayMode
import io.github.bszapp.wifitoolbox.ui.model.WpsPinFormState
import io.github.bszapp.wifitoolbox.ui.navigation.Route
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.PskSecurityAuditTaskSheet
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.WpsSecurityAuditTaskSheet
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.ui.util.BlurredBar
import io.github.bszapp.wifitoolbox.ui.util.rememberBlurBackdrop
import io.github.bszapp.wifitoolbox.ui.widget.WifiList
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.ConnectWifiSheetContent
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.ConnectWifiTaskSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorCaptureClearSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorCaptureSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorModeSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiModeSwitchSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WpsPbcTaskSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WpsPbcInputSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WpsPinInputSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WpsPinTaskSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.formatMonitorByteCount
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 列表页一帧的状态快照。 */
data class ListPageState(
    val wifiState: WifiState? = null,
    val isSendingScanRequest: Boolean = false,
    val modeState: WifiModeState? = null,
    val currentTask: TaskSnapshot? = null,
    val displayedTask: TrackedTaskState? = null,
    val wpsPinForm: WpsPinFormState? = null,
    val submode: NormalWifiSubmode = NormalWifiSubmode.SCAN,
    val auditTasks: List<TrackedTaskState> = emptyList(),
    val auditConfig: PskSecurityAuditConfig = PskSecurityAuditConfig(),
    val wpsAuditConfig: WpsSecurityAuditConfig = WpsSecurityAuditConfig(),
    val logPanel: AuditLogPanelConfig = AuditLogPanelConfig(),
    val runningWpsPbcTask: TaskSnapshot? = null,
)

/** 列表页动作接口（壳负责映射到 ViewModel / 导航 / 文件保存流程）。 */
interface ListPageActions {
    fun pushRoute(route: Route)
    fun startScan()
    fun setMode(mode: WifiMode)
    fun setWifiListDataSource(source: WifiListDataSource)
    fun toggleSmartInterfaces()
    fun selectSubmode(submode: NormalWifiSubmode)
    fun configureAudit()
    fun displayTask(taskId: Long)
    suspend fun startWpsPbcTask(input: WpsPbcTaskInput): Long
    suspend fun startUsbMonitorTask(): Long
    fun stopUsbMonitorTask()
    fun enterMonitorMode(command: String)
    fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int = 0, hopping: Boolean = false)
    fun setMonitorFrequency(frequencyMhz: Int, hopping: Boolean)
    fun clearMonitorCapture(handshakesOnly: Boolean)
    fun interruptModeSwitch(operationId: Long)
    fun interruptMonitorClear(operationId: Long)
    fun exportAllMonitorPcap(): String
    fun startWpsPin(pin: String, localMac: String, interfaceName: String)
    fun dismissWpsPinForm()
    fun wpsPinFormDismissed()
    fun stopDisplayedTask()
    fun closeDisplayedTask()
    fun updateDisplayedTask(update: TaskUpdateRequest)
    fun connectCapturedWpsNetwork(network: io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetwork)
    fun auditStopRecord(recordId: String)
    fun auditStopDictionaryNetwork(ssid: String)
    fun auditSetDetailedLogging(kind: AuditRecordKind, enabled: Boolean)
    fun auditChangeLogPanel(config: AuditLogPanelConfig)
    fun auditClearLogPanel(taskId: Long)
    fun auditInspect(recordId: String)
    fun auditInspectWps(recordId: String)
}

/** 列表页 wifi 列表插槽：生产默认走 ViewModel 版 WifiList；渲染/预览可注入 WifiListContent 假数据版。 */
@Composable
private fun DefaultWifiListSlot(
    modifier: Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState,
    contentPadding: PaddingValues,
    onInlineConnectTaskChanged: (Long?) -> Unit,
    onSaveHc22000: (content: String, fileName: String) -> Unit,
) {
    WifiList(
        modifier = modifier,
        listState = listState,
        contentPadding = contentPadding,
        onInlineConnectTaskChanged = onInlineConnectTaskChanged,
        onSaveHc22000 = onSaveHc22000,
    )
}

@Composable
fun ListPageContent(
    state: ListPageState,
    actions: ListPageActions,
    bottomInnerPadding: Dp = 0.dp,
    onSaveHc22000: (content: String, fileName: String) -> Unit = { _, _ -> },
    wifiListContent: @Composable (
        modifier: Modifier,
        listState: androidx.compose.foundation.lazy.LazyListState,
        contentPadding: PaddingValues,
        onInlineConnectTaskChanged: (Long?) -> Unit,
        onSaveHc22000: (content: String, fileName: String) -> Unit,
    ) -> Unit = ::DefaultWifiListSlot,
) {
    val navigator = actions
    val wifiState = state.wifiState
    val modeState = state.modeState
    val currentTask = state.currentTask
    val usbMonitorTask = currentTask?.takeIf {
        it.state == TaskExecutionState.RUNNING && it.request.payload is TaskRequestPayload.UsbMonitor
    }
    val displayedTask = state.displayedTask
    val wpsPinForm = state.wpsPinForm
    val displayMode = resolveWifiListDisplayMode(modeState?.mode, state.submode, currentTask)
    val selectedSource = displayMode.mode
    val submode = displayMode.submode
    val pageSubmode = ListPageSubmode.current(selectedSource, submode)
    val auditMode = selectedSource == WifiMode.NORMAL && submode != NormalWifiSubmode.SCAN
    val wpsAuditMode = selectedSource == WifiMode.NORMAL && submode == NormalWifiSubmode.WPS_SECURITY_AUDIT
    // 任务 FAB 的目标：服务掌管的同类型（弱密码检测/PIN漏洞检测）最新一个任务，结束后仍可打开。
    val auditKindTarget = state.auditTasks
        .filter {
            when (it.snapshot.request.payload) {
                is TaskRequestPayload.PskSecurityAudit -> !wpsAuditMode
                is TaskRequestPayload.WpsSecurityAudit -> wpsAuditMode
                else -> false
            }
        }
        .maxByOrNull { it.snapshot.taskId }
    val isScanning = wifiState.isScanning || state.isSendingScanRequest
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
    var showAuditTaskSheet by rememberSaveable { mutableStateOf(false) }
    var showWpsPbcInput by rememberSaveable { mutableStateOf(false) }
    var startingWpsPbc by remember { mutableStateOf(false) }

    var expandedToolbarMenu by rememberSaveable { mutableStateOf<ListToolbarAction?>(null) }
    val sourceOptions = WifiListDataSource.entries.map { source ->
        ListPageMenuOption(
            id = source,
            text = when (source) {
                WifiListDataSource.SYSTEM -> "系统扫描"
                WifiListDataSource.UNDERLYING -> "底层扫描"
            },
            selected = modeState?.listDataSource == source,
            onSelect = { actions.setWifiListDataSource(source) },
        )
    }
    val smart = modeState?.smartInterfaces
    val smartOptions = listOf(ListPageMenuOption(
        id = "smart-interfaces",
        text = if (smart?.enabled == true) "智能增加网卡（已有${smart.interfaceCount}个）" else "智能增加网卡",
        selected = smart?.enabled == true,
        enabled = smart?.isRunning == false,
        summary = if (smart?.isRunning == true) "脚本执行中" else null,
        onSelect = actions::toggleSmartInterfaces,
    ))
    val moreGroups = when (pageSubmode) {
        ListPageSubmode.SCAN -> listOf(sourceOptions)
        ListPageSubmode.PSK_AUDIT, ListPageSubmode.PIN_AUDIT -> listOf(sourceOptions, smartOptions)
        ListPageSubmode.MONITOR -> emptyList()
    }
    val toolbarButtons = pageSubmode.toolbarActions.map { action ->
        val enabled = when (action) {
            ListToolbarAction.CAPTURE, ListToolbarAction.EXPORT -> usbMonitorTask == null
            ListToolbarAction.CLEAR, ListToolbarAction.REFRESH ->
                usbMonitorTask == null && !controlsBusy && modeState?.clearingCapture != true
            else -> true
        }
        val menuGroups = when (action) {
            ListToolbarAction.MORE -> moreGroups
            ListToolbarAction.CLEAR -> listOf(listOf(
                ListPageMenuOption(
                    id = "clear-all",
                    text = "清空抓取数据（${formatMonitorByteCount(modeState?.monitorStatistics?.recordedBytes ?: 0L)}）",
                    onSelect = { actions.clearMonitorCapture(false) },
                ),
                ListPageMenuOption(
                    id = "clear-non-handshakes",
                    text = "仅清空非握手数据（${formatMonitorByteCount(modeState?.monitorStatistics?.nonHandshakeBytes ?: 0L)}）",
                    onSelect = { actions.clearMonitorCapture(true) },
                ),
            ))
            else -> emptyList()
        }
        val onClick: () -> Unit = when (action) {
            ListToolbarAction.HISTORY -> ({ navigator.pushRoute(Route.PskSecurityAuditHistory) })
            ListToolbarAction.QR -> ({ navigator.pushRoute(Route.WifiQrScanner) })
            ListToolbarAction.PBC -> ({
                val running = state.runningWpsPbcTask
                if (running != null) actions.displayTask(running.taskId) else showWpsPbcInput = true
            })
            ListToolbarAction.CAPTURE -> ({ navigator.pushRoute(Route.Capture()) })
            ListToolbarAction.EXPORT -> ({ actions.exportAllMonitorPcap(); Unit })
            ListToolbarAction.REFRESH -> actions::startScan
            ListToolbarAction.MORE, ListToolbarAction.CLEAR -> ({ expandedToolbarMenu = action })
        }
        ListPageToolbarButton(action, enabled, menuGroups, onClick)
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = pageSubmode.title,
                    navigationIcon = {
                        Box {
                            val showTopPopup = remember { mutableStateOf(false) }
                            OverlayListPopup(
                                show = showTopPopup.value,
                                popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                alignment = PopupPositionProvider.Align.TopEnd,
                                onDismissRequest = { showTopPopup.value = false },
                                content = {
                                    val modes = WifiMode.entries.map { mode ->
                                        ListPageMenuOption(
                                            id = mode,
                                            text = mode.displayName,
                                            selected = selectedSource == mode,
                                            onSelect = {
                                                when (mode) {
                                                    WifiMode.NORMAL -> actions.setMode(mode)
                                                    WifiMode.MONITOR -> if (selectedSource != mode) showMonitorCommand = true
                                                }
                                            },
                                        )
                                    }
                                    val submodes = ListPageSubmode.available(selectedSource).map { entry ->
                                        ListPageMenuOption(
                                            id = entry,
                                            text = entry.title,
                                            selected = pageSubmode == entry,
                                            enabled = !displayMode.taskRunning,
                                            onSelect = { entry.normalSubmode?.let(actions::selectSubmode) },
                                        )
                                    }
                                    ListPageMenuContent(listOf(modes, submodes), onSelected = { showTopPopup.value = false })
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
                        toolbarButtons.forEach { button ->
                            key(button.action) {
                                Box {
                                    OverlayListPopup(
                                        show = expandedToolbarMenu == button.action,
                                        popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                        alignment = PopupPositionProvider.Align.TopEnd,
                                        onDismissRequest = { expandedToolbarMenu = null },
                                    ) {
                                        ListPageMenuContent(button.menuGroups, onSelected = { expandedToolbarMenu = null })
                                    }
                                    IconButton(onClick = button.onClick, enabled = button.enabled) {
                                        Icon(button.action.icon, button.action.description, tint = colorScheme.onSurface)
                                    }
                                }
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
        if (usbMonitorTask != null) {
            UsbMonitorTaskContent(
                progress = usbMonitorTask.progress as? TaskProgress.UsbMonitor,
                onStop = actions::stopUsbMonitorTask,
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
                actions.startScan()
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
                wifiListContent(
                    Modifier
                        .fillMaxHeight()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    listState,
                    PaddingValues(
                        top = innerPadding.calculateTopPadding() + 14.dp,
                        start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                        bottom = bottomInnerPadding + when {
                            auditMode -> 160.dp
                            selectedSource == WifiMode.MONITOR -> 160.dp
                            else -> 8.dp
                        },
                    ),
                    { inlineConnectTaskId = it },
                    onSaveHc22000,
                )

                pageSubmode.floatingActions.forEachIndexed { index, action ->
                    key(action) {
                        Box(Modifier.align(Alignment.BottomEnd).padding(
                            end = 16.dp, bottom = bottomInnerPadding + 16.dp + 76.dp * index,
                        )) {
                            when (action) {
                                ListFloatingAction.CAPTURE -> FloatingActionButton(
                                    onClick = {
                                        if (modeState?.capturing == true) actions.setMonitorCapture(false)
                                        else actions.setMonitorCapture(true)
                                    },
                                ) {
                                    Icon(
                                        if (modeState?.capturing == true) Icons.Outlined.StopCircle else Icons.Rounded.RocketLaunch,
                                        if (modeState?.capturing == true) "停止持续抓取" else "持续抓取",
                                        tint = colorScheme.onPrimary,
                                    )
                                }
                                ListFloatingAction.FREQUENCY -> FloatingActionButton(onClick = { showCaptureChannels = true }) {
                                    Icon(Icons.Rounded.Tune, "设置监听频率", tint = colorScheme.onPrimary)
                                }
                                ListFloatingAction.CONFIGURE -> FloatingActionButton(onClick = actions::configureAudit) {
                                    Icon(Icons.Rounded.Tune, "编辑检测运行行为", tint = colorScheme.onPrimary)
                                }
                                ListFloatingAction.TASK -> AuditTaskFab(
                                    running = auditKindTarget?.snapshot?.state == TaskExecutionState.RUNNING,
                                    onClick = {
                                        showAuditTaskSheet = true
                                        auditKindTarget?.let { actions.displayTask(it.snapshot.taskId) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    WifiModeSwitchSheet(progress = modeState?.modeSwitch, onInterrupt = actions::interruptModeSwitch)
    MonitorCaptureClearSheet(progress = modeState?.captureClearProgress, onInterrupt = actions::interruptMonitorClear)
    MonitorModeSheet(
        show = showMonitorCommand,
        onDismiss = { showMonitorCommand = false },
        onExecute = { command ->
            showMonitorCommand = false
            actions.enterMonitorMode(command)
        },
    )
    MonitorCaptureSheet(
        show = showCaptureChannels && selectedSource == WifiMode.MONITOR,
        channels = modeState?.availableChannels.orEmpty(),
        scanResults = wifiState.scanResults,
        frequencyMhz = modeState?.monitorStatistics?.frequencyMhz ?: 0,
        configuredFrequencyMhz = modeState?.monitorFrequencyMhz ?: 0,
        hopping = modeState?.autoHoppingEnabled == true,
        capturing = modeState?.capturing == true,
        enabled = usbMonitorTask == null && !controlsBusy && modeState?.clearingCapture != true,
        onDismiss = { showCaptureChannels = false },
        onChange = actions::setMonitorFrequency,
    )

    WpsPbcInputSheet(show = showWpsPbcInput, isStarting = startingWpsPbc,
        availableInterfaces = modeState?.smartInterfaces?.managedInterfaces.orEmpty(),
        onStart = { input ->
            if (!startingWpsPbc) {
                startingWpsPbc = true
                taskActionScope.launch {
                    try {
                        actions.startWpsPbcTask(input)
                        showWpsPbcInput = false
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 统一错误广播报告启动失败，保留配置面板。
                    } finally { startingWpsPbc = false }
                }
            }
        }, onDismiss = { if (!startingWpsPbc) showWpsPbcInput = false })
    wpsPinForm?.let { form ->
        WpsPinInputSheet(form,
            onStart = { pin, localMac, interfaceName -> actions.startWpsPin(pin, localMac, interfaceName) },
            onDismiss = actions::dismissWpsPinForm,
            onDismissFinished = actions::wpsPinFormDismissed,
            availableInterfaces = modeState?.smartInterfaces?.managedInterfaces.orEmpty())
    }
    displayedTask?.let { task ->
        when (task.snapshot.request.payload) {
            // 弱密码检测类任务不走自动弹出，详情 sheet 由下方 FAB 标记直接渲染。
            is TaskRequestPayload.PskSecurityAudit, is TaskRequestPayload.WpsSecurityAudit -> Unit
            else -> if (task.snapshot.taskId != inlineConnectTaskId) {
                DisplayedTaskSheetView(task = task, actions = actions)
            }
        }
    }
    // 任务详情：目标 = 服务掌管的同类型最新任务（结束后仍可打开；从未运行过则展示空日志）。
    if (showAuditTaskSheet && auditMode) {
        val target = auditKindTarget
        if (wpsAuditMode) {
            WpsSecurityAuditTaskSheet(
                task = target,
                logPanel = state.logPanel,
                onLogPanelChanged = actions::auditChangeLogPanel,
                detailedLogging = state.wpsAuditConfig.detailedLogging,
                onDetailedLoggingChanged = { actions.auditSetDetailedLogging(AuditRecordKind.WPS, it) },
                onStop = actions::stopDisplayedTask,
                onDismiss = { showAuditTaskSheet = false },
                onClearLogPanel = { target?.snapshot?.taskId?.let(actions::auditClearLogPanel) },
            )
        } else {
            PskSecurityAuditTaskSheet(
                task = target,
                config = state.auditConfig,
                logPanel = state.logPanel,
                onLogPanelChanged = actions::auditChangeLogPanel,
                onStop = actions::stopDisplayedTask,
                onDismiss = { showAuditTaskSheet = false },
                onDetailedLoggingChanged = { actions.auditSetDetailedLogging(AuditRecordKind.DICTIONARY, it) },
                onClearLogPanel = { target?.snapshot?.taskId?.let(actions::auditClearLogPanel) },
            )
        }
    }
}

@Composable
internal fun DisplayedTaskSheetView(
    task: TrackedTaskState,
    actions: ListPageActions,
) {
    when (task.snapshot.request.payload) {
        // 弱密码检测类详情直达渲染（见 ListPageContent 上方），不经由本入口。
        is TaskRequestPayload.PskSecurityAudit, is TaskRequestPayload.WpsSecurityAudit -> Unit
        is TaskRequestPayload.ConnectWifi -> ConnectWifiTaskSheet(
            content = ConnectWifiSheetContent.Task,
            trackedTask = task,
            isSubmitting = false,
            dismissRequested = false,
            onSubmit = {},
            onStop = actions::stopDisplayedTask,
            onDismiss = actions::closeDisplayedTask,
        )

        is TaskRequestPayload.WpsPbc -> WpsPbcTaskSheet(
            trackedTask = task,
            isStarting = false,
            onContinuousCaptureChange = { enabled ->
                actions.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcContinuousCapture(enabled)),
                )
            },
            onAutoSaveToDeviceChange = { enabled ->
                actions.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcAutoSaveToDevice(enabled)),
                )
            },
            onUseIncompleteProtocolChange = { enabled ->
                actions.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcUseIncompleteProtocol(enabled)),
                )
            },
            onIgnoreRepeatedDevicesChange = { enabled ->
                actions.updateDisplayedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcIgnoreRepeatedDevices(enabled)),
                )
            },
            onStop = actions::stopDisplayedTask,
            onDismiss = actions::closeDisplayedTask,
        )
        TaskRequestPayload.UsbMonitor -> Unit
        is TaskRequestPayload.WpsPin -> WpsPinTaskSheet(task, actions::stopDisplayedTask,
            actions::closeDisplayedTask, actions::connectCapturedWpsNetwork)
    }
}
