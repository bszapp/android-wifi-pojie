package io.github.bszapp.wifitoolbox.uidefault.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogEntry
import io.github.bszapp.wifitoolbox.contract.log.ParsedServiceLogLine
import io.github.bszapp.wifitoolbox.contract.task.TaskLogEntry
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogEntry
import io.github.bszapp.wifitoolbox.uidefault.component.ListPopupDefaults
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun LogScreen(
    viewModel: DefaultViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    //TODO:加个终端关闭、主动新建终端、清空终端、直接发送快捷键或命令？
    val context = LocalContext.current
    val entries by viewModel.serviceLogs.entries.collectAsStateWithLifecycle()
    val rawViewEnabled by viewModel.serviceLogs.rawViewEnabled.collectAsStateWithLifecycle()
    val systemWifiEntries by viewModel.serviceLogs.systemWifiEntries.collectAsStateWithLifecycle()
    val systemWifiRawViewEnabled by
        viewModel.serviceLogs.systemWifiRawViewEnabled.collectAsStateWithLifecycle()
    val terminalManagerState by viewModel.terminals.state.collectAsStateWithLifecycle()
    val appTerminalManagerState by viewModel.terminals.appState.collectAsStateWithLifecycle()
    val taskManagerState by viewModel.tasks.state.collectAsStateWithLifecycle()
    val taskLogEntries = taskManagerState.globalLogs.entries
    val servicePid by viewModel.startup.pid.collectAsStateWithLifecycle()
    val serviceTerminalIds = terminalManagerState.aliveTerminalIds
    val appTerminalIds = appTerminalManagerState.aliveTerminalIds
    val pages = remember(serviceTerminalIds, appTerminalIds) {
        listOf<LogPage>(LogPage.Service, LogPage.SystemWifi, LogPage.Task) +
            serviceTerminalIds.map(LogPage::ServiceTerminal) +
            appTerminalIds.map(LogPage::AppTerminal)
    }
    val tabs = remember(pages) {
        pages.map { page ->
            when (page) {
                LogPage.Service -> "日志"
                LogPage.SystemWifi -> "系统wifi日志"
                LogPage.Task -> "任务日志"
                is LogPage.ServiceTerminal -> "终端 ${page.id}"
                is LogPage.AppTerminal -> "App终端 ${page.id}"
            }
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val selectedTabIndex = pagerState.currentPage.coerceIn(0, tabs.lastIndex)
    val selectedPage = pages.getOrNull(selectedTabIndex) ?: LogPage.Service
    val selectedTerminal = when (selectedPage) {
        LogPage.Service, LogPage.SystemWifi, LogPage.Task -> null
        is LogPage.ServiceTerminal -> terminalManagerState.terminals[selectedPage.id]
        is LogPage.AppTerminal -> appTerminalManagerState.terminals[selectedPage.id]
    }
    val selectedRawViewEnabled = when (selectedPage) {
        LogPage.Service -> rawViewEnabled
        LogPage.SystemWifi -> systemWifiRawViewEnabled
        LogPage.Task -> false
        is LogPage.ServiceTerminal, is LogPage.AppTerminal -> false
    }
    val selectedPageIsTerminal =
        selectedPage is LogPage.ServiceTerminal || selectedPage is LogPage.AppTerminal
    val selectedPageSupportsParsedView =
        selectedPage == LogPage.Service || selectedPage == LogPage.SystemWifi
    val currentServiceEntriesForSave by rememberUpdatedState(entries)
    val currentSystemWifiEntriesForSave by rememberUpdatedState(systemWifiEntries)
    val currentTaskEntriesForSave by rememberUpdatedState(taskLogEntries)
    val currentServiceTerminalsForSave by rememberUpdatedState(
        serviceTerminalIds.map { terminalId ->
            terminalId to terminalManagerState.terminals[terminalId]?.entries.orEmpty()
        },
    )
    val listState = rememberLazyListState()
    val rawListState = rememberLazyListState()
    val systemWifiListState = rememberLazyListState()
    val systemWifiRawListState = rememberLazyListState()
    val taskLogListState = rememberLazyListState()
    val terminalListStates = remember { mutableMapOf<LogPage, LazyListState>() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    var showMenu by remember { mutableStateOf(false) }
    var showCreateTerminalMenu by remember { mutableStateOf(false) }
    var rawSoftWrapEnabled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pages) {
        terminalListStates.keys.retainAll(pages.toSet())
        if (pagerState.currentPage > tabs.lastIndex) pagerState.scrollToPage(tabs.lastIndex)
    }

    LaunchedEffect(pagerState.currentPage) {
        showMenu = false
        showCreateTerminalMenu = false
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            val serviceEntries = currentServiceEntriesForSave
            val systemWifiLogEntries = currentSystemWifiEntriesForSave
            val taskEntries = currentTaskEntriesForSave
            val serviceTerminals = currentServiceTerminalsForSave
            scope.launch(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: return@launch
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    zip.writeLogEntry("日志.log") {
                        serviceEntries.forEach { entry -> emit(entry.rawLine) }
                    }
                    zip.writeLogEntry("系统wifi日志.log") {
                        systemWifiLogEntries.forEach { entry -> emit(entry.rawLine) }
                    }
                    zip.writeLogEntry("任务日志.log") {
                        taskEntries.forEach { entry -> emit(entry.displayLogLine()) }
                    }
                    serviceTerminals.forEach { (terminalId, terminalEntries) ->
                        zip.writeLogEntry("终端${terminalId}.log") {
                            terminalEntries.forEach { entry -> emit(entry.text) }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                Column {
                    LogTopBar(
                        color = barColor,
                        scrollBehavior = scrollBehavior,
                        showMenu = showMenu,
                        showCreateTerminalMenu = showCreateTerminalMenu,
                        rawViewEnabled = selectedRawViewEnabled,
                        showRawViewOption = selectedPageSupportsParsedView,
                        rawSoftWrapEnabled = rawSoftWrapEnabled,
                        showRawSoftWrapOption =
                            selectedPageIsTerminal || selectedPage == LogPage.Task ||
                                selectedRawViewEnabled,
                        showCloseTerminalOption = selectedPageIsTerminal,
                        onShowMenuChange = {
                            showMenu = it
                            if (it) showCreateTerminalMenu = false
                        },
                        onShowCreateTerminalMenuChange = {
                            showCreateTerminalMenu = it
                            if (it) showMenu = false
                        },
                        onSave = {
                            val filename = SimpleDateFormat(
                                "'log_'yyyyMMdd_HHmmss'.zip'",
                                Locale.getDefault(),
                            ).format(Date())
                            saveLauncher.launch(filename)
                        },
                        onToggleRawView = {
                            when (selectedPage) {
                                LogPage.Service ->
                                    viewModel.serviceLogs.setRawViewEnabled(!rawViewEnabled)
                                LogPage.SystemWifi ->
                                    viewModel.serviceLogs.setSystemWifiRawViewEnabled(
                                        !systemWifiRawViewEnabled,
                                    )
                                LogPage.Task -> Unit
                                is LogPage.ServiceTerminal, is LogPage.AppTerminal -> Unit
                            }
                            showMenu = false
                        },
                        onToggleRawSoftWrap = {
                            rawSoftWrapEnabled = !rawSoftWrapEnabled
                            showMenu = false
                        },
                        onScrollToTop = {
                            showMenu = false
                            scope.launch {
                                when (selectedPage) {
                                    is LogPage.ServiceTerminal, is LogPage.AppTerminal ->
                                        terminalListStates[selectedPage]?.animateScrollToItem(0)
                                    LogPage.Service -> if (rawViewEnabled) {
                                        rawListState.animateScrollToItem(0)
                                    } else if (entries.isNotEmpty()) {
                                        listState.animateScrollToItem(0)
                                    }
                                    LogPage.SystemWifi -> if (systemWifiRawViewEnabled) {
                                        systemWifiRawListState.animateScrollToItem(0)
                                    } else if (systemWifiEntries.isNotEmpty()) {
                                        systemWifiListState.animateScrollToItem(0)
                                    }
                                    LogPage.Task -> if (taskLogEntries.isNotEmpty()) {
                                        taskLogListState.animateScrollToItem(0)
                                    }
                                }
                            }
                        },
                        onScrollToBottom = {
                            showMenu = false
                            scope.launch {
                                when (selectedPage) {
                                    is LogPage.ServiceTerminal, is LogPage.AppTerminal ->
                                        if (selectedTerminal?.entries?.isNotEmpty() == true) {
                                            terminalListStates[selectedPage]
                                                ?.animateScrollToItem(
                                                    selectedTerminal.entries.lastIndex,
                                                )
                                        }
                                    LogPage.Service -> if (rawViewEnabled && entries.isNotEmpty()) {
                                        rawListState.animateScrollToItem(entries.lastIndex)
                                    } else if (entries.isNotEmpty()) {
                                        listState.animateScrollToItem(entries.lastIndex)
                                    }
                                    LogPage.SystemWifi -> if (
                                        systemWifiRawViewEnabled && systemWifiEntries.isNotEmpty()
                                    ) {
                                        systemWifiRawListState.animateScrollToItem(
                                            systemWifiEntries.lastIndex,
                                        )
                                    } else if (systemWifiEntries.isNotEmpty()) {
                                        systemWifiListState.animateScrollToItem(
                                            systemWifiEntries.lastIndex,
                                        )
                                    }
                                    LogPage.Task -> if (taskLogEntries.isNotEmpty()) {
                                        taskLogListState.animateScrollToItem(
                                            taskLogEntries.lastIndex,
                                        )
                                    }
                                }
                            }
                        },
                        onClear = {
                            showMenu = false
                            when (selectedPage) {
                                LogPage.Service -> viewModel.serviceLogs.clear()
                                LogPage.SystemWifi -> viewModel.serviceLogs.clearSystemWifi()
                                LogPage.Task -> viewModel.tasks.clearLogs()
                                is LogPage.ServiceTerminal -> viewModel.terminals.clearLogs(selectedPage.id)
                                is LogPage.AppTerminal -> viewModel.terminals.clearAppLogs(selectedPage.id)
                            }
                        },
                        onCloseTerminal = {
                            showMenu = false
                            when (selectedPage) {
                                LogPage.Service, LogPage.SystemWifi, LogPage.Task -> Unit
                                is LogPage.ServiceTerminal -> viewModel.terminals.closeTerminal(selectedPage.id)
                                is LogPage.AppTerminal -> viewModel.terminals.closeAppTerminal(selectedPage.id)
                            }
                        },
                        onCreateServiceTerminal = {
                            showCreateTerminalMenu = false
                            showMenu = false
                            viewModel.terminals.createServiceTerminal()
                        },
                        onCreateAppTerminal = {
                            showCreateTerminalMenu = false
                            showMenu = false
                            viewModel.terminals.createAppTerminal()
                        },
                    )
                    TabRow(
                        tabs = tabs,
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = { index ->
                            showMenu = false
                            showCreateTerminalMenu = false
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        colors = TabRowDefaults.tabRowColors(
                            backgroundColor = Color.Transparent,
                        ),
                    )
                }
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        HorizontalPager(
            state = pagerState,
            modifier = (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .fillMaxSize(),
        ) { page ->
            //TODO:我不希望看见任何暂无日志的提示！
            when (val logPage = pages.getOrNull(page)) {
                null -> Unit
                LogPage.Service -> {
                    if (rawViewEnabled) {
                        RawLogView(
                            entries = entries,
                            text = ServiceLogEntry::rawLine,
                            listState = rawListState,
                            softWrap = rawSoftWrapEnabled,
                            scrollBehavior = scrollBehavior,
                            topPadding = innerPadding.calculateTopPadding(),
                            startPadding = innerPadding.calculateStartPadding(layoutDirection),
                            endPadding = innerPadding.calculateEndPadding(layoutDirection),
                            bottomPadding = bottomInnerPadding,
                        )
                    } else {
                        LogCardList(
                            entries = entries,
                            listState = listState,
                            scrollBehavior = scrollBehavior,
                            fallbackPid = servicePid,
                            topPadding = innerPadding.calculateTopPadding(),
                            startPadding = innerPadding.calculateStartPadding(layoutDirection),
                            endPadding = innerPadding.calculateEndPadding(layoutDirection),
                            bottomPadding = bottomInnerPadding,
                        )
                    }
                }
                LogPage.SystemWifi -> {
                    if (systemWifiRawViewEnabled) {
                        RawLogView(
                            entries = systemWifiEntries,
                            text = ServiceLogEntry::rawLine,
                            listState = systemWifiRawListState,
                            softWrap = rawSoftWrapEnabled,
                            scrollBehavior = scrollBehavior,
                            topPadding = innerPadding.calculateTopPadding(),
                            startPadding = innerPadding.calculateStartPadding(layoutDirection),
                            endPadding = innerPadding.calculateEndPadding(layoutDirection),
                            bottomPadding = bottomInnerPadding,
                        )
                    } else {
                        LogCardList(
                            entries = systemWifiEntries,
                            listState = systemWifiListState,
                            scrollBehavior = scrollBehavior,
                            fallbackPid = null,
                            topPadding = innerPadding.calculateTopPadding(),
                            startPadding = innerPadding.calculateStartPadding(layoutDirection),
                            endPadding = innerPadding.calculateEndPadding(layoutDirection),
                            bottomPadding = bottomInnerPadding,
                        )
                    }
                }
                LogPage.Task -> {
                    RawLogView(
                        entries = taskLogEntries,
                        text = TaskLogEntry::displayLogLine,
                        listState = taskLogListState,
                        softWrap = rawSoftWrapEnabled,
                        scrollBehavior = scrollBehavior,
                        topPadding = innerPadding.calculateTopPadding(),
                        startPadding = innerPadding.calculateStartPadding(layoutDirection),
                        endPadding = innerPadding.calculateEndPadding(layoutDirection),
                        bottomPadding = bottomInnerPadding,
                    )
                }
                is LogPage.ServiceTerminal -> {
                    val terminalId = logPage.id
                    val terminal = terminalManagerState.terminals[terminalId]
                    val terminalListState = rememberLazyListState()
                    SideEffect { terminalListStates[logPage] = terminalListState }
                    TerminalLogPage(
                        terminalId = terminalId,
                        entries = terminal?.entries.orEmpty(),
                        inputPrompt = terminal?.inputPrompt,
                        listState = terminalListState,
                        softWrap = rawSoftWrapEnabled,
                        scrollBehavior = scrollBehavior,
                        topPadding = innerPadding.calculateTopPadding(),
                        startPadding = innerPadding.calculateStartPadding(layoutDirection),
                        endPadding = innerPadding.calculateEndPadding(layoutDirection),
                        bottomPadding = bottomInnerPadding,
                        onSendInput = { text ->
                            viewModel.terminals.sendInput(terminalId, text)
                        },
                    )
                }
                is LogPage.AppTerminal -> {
                    val terminalId = logPage.id
                    val terminal = appTerminalManagerState.terminals[terminalId]
                    val terminalListState = rememberLazyListState()
                    SideEffect { terminalListStates[logPage] = terminalListState }
                    TerminalLogPage(
                        terminalId = terminalId,
                        entries = terminal?.entries.orEmpty(),
                        inputPrompt = terminal?.inputPrompt,
                        listState = terminalListState,
                        softWrap = rawSoftWrapEnabled,
                        scrollBehavior = scrollBehavior,
                        topPadding = innerPadding.calculateTopPadding(),
                        startPadding = innerPadding.calculateStartPadding(layoutDirection),
                        endPadding = innerPadding.calculateEndPadding(layoutDirection),
                        bottomPadding = bottomInnerPadding,
                        onSendInput = { text ->
                            viewModel.terminals.sendAppInput(terminalId, text)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalLogPage(
    terminalId: Long,
    entries: List<TerminalLogEntry>,
    inputPrompt: String?,
    listState: LazyListState,
    softWrap: Boolean,
    scrollBehavior: ScrollBehavior,
    topPadding: Dp,
    startPadding: Dp,
    endPadding: Dp,
    bottomPadding: Dp,
    onSendInput: (String) -> Unit,
) {
    var showSendDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        RawLogView(
            entries = entries,
            text = TerminalLogEntry::text,
            listState = listState,
            softWrap = softWrap,
            scrollBehavior = scrollBehavior,
            topPadding = topPadding,
            startPadding = startPadding,
            endPadding = endPadding,
            bottomPadding = bottomPadding + TERMINAL_FAB_CONTENT_PADDING,
        )
        FloatingActionButton(
            onClick = { showSendDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = endPadding + TERMINAL_FAB_EDGE_PADDING,
                    bottom = bottomPadding + TERMINAL_FAB_EDGE_PADDING,
                ),
        ) {
            Icon(
                imageVector = MiuixIcons.Send,
                contentDescription = "发送终端命令",
                tint = colorScheme.onPrimary,
            )
        }
        TerminalSendDialog(
            show = showSendDialog,
            terminalId = terminalId,
            inputPrompt = inputPrompt,
            onDismiss = { showSendDialog = false },
            onSend = onSendInput,
        )
    }
}

@Composable
private fun TerminalSendDialog(
    show: Boolean,
    terminalId: Long,
    inputPrompt: String?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var selectedTypeIndex by remember(show) { mutableIntStateOf(0) }
    var inputText by remember(show) { mutableStateOf("") }
    val selectedType = TerminalInputType.entries[selectedTypeIndex]
    val typeItems = remember {
        TerminalInputType.entries.map { type -> DropdownItem(text = type.label) }
    }

    OverlayDialog(
        show = show,
        title = "发送到终端 $terminalId",
        summary = inputPrompt,
        onDismissRequest = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OverlaySpinnerPreference(
                    items = typeItems,
                    selectedIndex = selectedTypeIndex,
                    title = "类型",
                    modifier = Modifier.fillMaxWidth(),
                    onSelectedIndexChange = { selectedTypeIndex = it },
                )
                AnimatedVisibility(visible = selectedType == TerminalInputType.Text) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 16.dp),
                        label = "命令",
                        useLabelAsPlaceholder = true,
                        maxLines = 4,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(width = 20.dp, height = 0.dp))
                    TextButton(
                        text = "发送",
                        onClick = {
                            onSend(selectedType.sequence ?: inputText)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}

@Composable
private fun LogTopBar(
    color: Color,
    scrollBehavior: ScrollBehavior,
    showMenu: Boolean,
    showCreateTerminalMenu: Boolean,
    rawViewEnabled: Boolean,
    showRawViewOption: Boolean,
    rawSoftWrapEnabled: Boolean,
    showRawSoftWrapOption: Boolean,
    showCloseTerminalOption: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onShowCreateTerminalMenuChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onToggleRawView: () -> Unit,
    onToggleRawSoftWrap: () -> Unit,
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
    onClear: () -> Unit,
    onCloseTerminal: () -> Unit,
    onCreateServiceTerminal: () -> Unit,
    onCreateAppTerminal: () -> Unit,
) {
    val menuEntries = listOfNotNull(
        listOfNotNull(
            if (showRawViewOption) {
                DropdownItem(
                    text = "原始视图",
                    selected = rawViewEnabled,
                    onClick = onToggleRawView,
                )
            } else {
                null
            },
            if (showRawSoftWrapOption) {
                DropdownItem(
                    text = "自动换行",
                    selected = rawSoftWrapEnabled,
                    onClick = onToggleRawSoftWrap,
                )
            } else {
                null
            },
        ).takeIf { it.isNotEmpty() }?.let(::DropdownEntry),
        DropdownEntry(
            items = listOf(
                DropdownItem(text = "滚动到顶部", onClick = onScrollToTop),
                DropdownItem(text = "滚动到底部", onClick = onScrollToBottom),
            ),
        ),
        DropdownEntry(
            items = buildList {
                add(DropdownItem(text = "立即清空日志", onClick = onClear))
                if (showCloseTerminalOption) {
                    add(DropdownItem(text = "关闭终端", onClick = onCloseTerminal))
                }
            },
        ),
    )
    val createTerminalEntries = listOf(
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = "新建服务chroot终端",
                    onClick = onCreateServiceTerminal,
                ),
                DropdownItem(
                    text = "新建App proot终端",
                    onClick = onCreateAppTerminal,
                ),
            ),
        ),
    )
    TopAppBar(
        color = color,
        title = "日志",
        actions = {
            IconButton(onClick = onSave) {
                Icon(
                    imageVector = MiuixIcons.Download,
                    tint = colorScheme.onSurface,
                    contentDescription = "保存全部日志",
                )
            }
            Box {
                OverlayCascadingListPopup(
                    show = showCreateTerminalMenu,
                    entries = createTerminalEntries,
                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { onShowCreateTerminalMenuChange(false) },
                )
                IconButton(
                    onClick = { onShowCreateTerminalMenuChange(true) },
                    holdDownState = showCreateTerminalMenu,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Add,
                        tint = colorScheme.onSurface,
                        contentDescription = "新建终端",
                    )
                }
            }
            Box {
                OverlayCascadingListPopup(
                    show = showMenu,
                    entries = menuEntries,
                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { onShowMenuChange(false) },
                )
                IconButton(
                    onClick = { onShowMenuChange(true) },
                    holdDownState = showMenu,
                ) {
                    Icon(
                        imageVector = MiuixIcons.MoreCircle,
                        tint = colorScheme.onSurface,
                        contentDescription = "更多",
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun LogCardList(
    entries: List<ServiceLogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollBehavior: ScrollBehavior,
    fallbackPid: Int?,
    topPadding: Dp,
    startPadding: Dp,
    endPadding: Dp,
    bottomPadding: Dp,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        state = listState,
        contentPadding = PaddingValues(
            top = topPadding + 12.dp,
            start = startPadding + 12.dp,
            end = endPadding + 12.dp,
            bottom = bottomPadding + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        overscrollEffect = null,
    ) {
        items(entries, key = ServiceLogEntry::id) { entry ->
            if (entry.tag == "Unknown") {
                UnknownLogLine(entry)
            } else {
                LogEntryCard(
                    entry = entry,
                    fallbackPid = fallbackPid,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun <T> RawLogView(
    entries: List<T>,
    text: (T) -> String,
    listState: LazyListState,
    softWrap: Boolean,
    scrollBehavior: ScrollBehavior,
    topPadding: Dp,
    startPadding: Dp,
    endPadding: Dp,
    bottomPadding: Dp,
) {
    var fontSize by remember { mutableFloatStateOf(DEFAULT_RAW_LOG_FONT_SIZE) }
    var multiplePointersPressed by remember { mutableStateOf(false) }
    val horizontalScrollState = rememberScrollState()
    val transformableState = rememberTransformableState { _, zoomChange, _, _ ->
        fontSize = (fontSize * zoomChange).coerceIn(
            MIN_RAW_LOG_FONT_SIZE,
            MAX_RAW_LOG_FONT_SIZE,
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            multiplePointersPressed = event.changes.count { it.pressed } > 1
                        }
                    }
                } finally {
                    multiplePointersPressed = false
                }
            }
            .padding(horizontal = 12.dp)
            .transformable(transformableState)
            .overScrollVertical()
            .scrollEndHaptic()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .then(
                if (softWrap) {
                    Modifier
                } else {
                    Modifier.horizontalScroll(horizontalScrollState)
                },
            ),
        state = listState,
        contentPadding = PaddingValues(
            top = topPadding,
            start = startPadding,
            end = endPadding,
            bottom = bottomPadding,
        ),
        userScrollEnabled = !multiplePointersPressed,
        overscrollEffect = null,
    ) {
        items(count = entries.size) { index ->
            Text(
                text = text(entries[index]),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                softWrap = softWrap,
                maxLines = if (softWrap) Int.MAX_VALUE else 1,
                color = colorScheme.onSurface,
                style = MiuixTheme.textStyles.body2,
            )
        }
        item {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun UnknownLogLine(entry: ServiceLogEntry) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Text(
        text = entry.rawLine.replace('\n', ' '),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(entry.id) {
                detectTapGestures(
                    onLongPress = {
                        clipboardManager.setText(AnnotatedString(entry.rawLine))
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                )
            },
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = colorScheme.onSurface,
    )
}

@Composable
private fun LogEntryCard(
    entry: ServiceLogEntry,
    fallbackPid: Int?,
) {
    val parsed = remember(entry.rawLine) { ParsedServiceLogLine.parse(entry.rawLine) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val priority = parsed?.priority ?: "I"
    val pid = parsed?.pid?.toString() ?: fallbackPid?.toString() ?: "?"
    val tid = parsed?.tid?.toString() ?: "?"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        showIndication = true,
        onClick = { expanded = !expanded },
        onLongPress = {
            clipboardManager.setText(AnnotatedString(entry.rawLine))
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card(
                    modifier = Modifier.size(17.dp),
                    cornerRadius = 5.dp,
                    insideMargin = PaddingValues(0.dp),
                    colors = CardDefaults.defaultColors(
                        color = priorityColor(priority),
                        contentColor = Color.White,
                    ),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = priority,
                            style = MiuixTheme.textStyles.footnote2,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.size(3.dp))
                Text(
                    text = "${entry.tag} ($pid:$tid)",
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colorScheme.onSurface,
                )
                if (parsed != null) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = parsed.timestamp,
                        style = MiuixTheme.textStyles.footnote2,
                        maxLines = 1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Text(
                text = parsed?.message ?: entry.rawLine,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LOG_LINES,
                overflow = TextOverflow.Ellipsis,
                color = colorScheme.onSurface,
            )
        }
    }
}

private fun priorityColor(priority: String): Color = when (priority) {
    "V", "D" -> Color(0xFF63C174)
    "I" -> Color(0xFF61B9E9)
    "W" -> Color(0xFFFFA726)
    "E", "F", "A" -> Color(0xFFFF5964)
    else -> Color(0xFF8A8A8A)
}

private const val COLLAPSED_LOG_LINES = 3
private const val DEFAULT_RAW_LOG_FONT_SIZE = 13f
private const val MIN_RAW_LOG_FONT_SIZE = 6f
private const val MAX_RAW_LOG_FONT_SIZE = 32f
private val TERMINAL_FAB_EDGE_PADDING = 16.dp
private val TERMINAL_FAB_CONTENT_PADDING = 88.dp

private sealed interface LogPage {
    data object Service : LogPage

    data object SystemWifi : LogPage

    data object Task : LogPage

    data class ServiceTerminal(val id: Long) : LogPage

    data class AppTerminal(val id: Long) : LogPage
}

private fun TaskLogEntry.displayLogLine(): String {
    val timestamp = TASK_LOG_TIME_FORMAT.get().format(Date(timestampMillis))
    return "$timestamp [任务 #$taskId] $text"
}

private val TASK_LOG_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
}

private enum class TerminalInputType(
    val label: String,
    val sequence: String?,
) {
    Text("文本", null),
    CtrlC("Ctrl+C", "\u0003"),
    Tab("Tab", "\t"),
    Enter("Enter", ""),
    Escape("Esc", "\u001B"),
    CtrlD("Ctrl+D", "\u0004"),
    CtrlZ("Ctrl+Z", "\u001A"),
    Backspace("Backspace", "\u007F"),
}

private inline fun ZipOutputStream.writeLogEntry(
    name: String,
    content: ZipLogEntryWriter.() -> Unit,
) {
    putNextEntry(ZipEntry(name))
    try {
        ZipLogEntryWriter(this).content()
    } finally {
        closeEntry()
    }
}

private class ZipLogEntryWriter(
    private val output: ZipOutputStream,
) {
    fun emit(line: String) {
        output.write(line.toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
    }
}
