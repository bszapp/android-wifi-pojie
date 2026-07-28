package io.github.bszapp.wifitoolbox.uidefault.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.bszapp.wifitoolbox.uidefault.component.ListPopupDefaults
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun LogScreen(
    viewModel: DefaultViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val entries by viewModel.serviceLogs.entries.collectAsStateWithLifecycle()
    val rawViewEnabled by viewModel.serviceLogs.rawViewEnabled.collectAsStateWithLifecycle()
    val serviceUid by viewModel.startup.uid.collectAsStateWithLifecycle()
    val servicePid by viewModel.startup.pid.collectAsStateWithLifecycle()
    val currentEntries by rememberUpdatedState(entries)
    val rawText = remember(entries) {
        entries.joinToString(separator = "\n", transform = ServiceLogEntry::rawLine)
    }
    val listState = rememberLazyListState()
    val rawVerticalScrollState = rememberScrollState()
    val rawHorizontalScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    var showMenu by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            val logsToSave = currentEntries
            scope.launch(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: return@launch
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    logsToSave.forEach { entry ->
                        writer.write(entry.rawLine)
                        writer.newLine()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                LogTopBar(
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    showMenu = showMenu,
                    rawViewEnabled = rawViewEnabled,
                    onShowMenuChange = { showMenu = it },
                    onSave = {
                        val filename = SimpleDateFormat(
                            "yyyyMMdd_HHmmss'.log'",
                            Locale.getDefault(),
                        ).format(Date())
                        saveLauncher.launch(filename)
                    },
                    onToggleRawView = {
                        viewModel.serviceLogs.setRawViewEnabled(!rawViewEnabled)
                        showMenu = false
                    },
                    onScrollToTop = {
                        showMenu = false
                        scope.launch {
                            if (rawViewEnabled) {
                                rawVerticalScrollState.animateScrollTo(0)
                            } else if (entries.isNotEmpty()) {
                                listState.animateScrollToItem(0)
                            }
                        }
                    },
                    onScrollToBottom = {
                        showMenu = false
                        scope.launch {
                            if (rawViewEnabled) {
                                rawVerticalScrollState.animateScrollTo(rawVerticalScrollState.maxValue)
                            } else if (entries.isNotEmpty()) {
                                listState.animateScrollToItem(entries.lastIndex)
                            }
                        }
                    },
                    onClear = {
                        showMenu = false
                        viewModel.serviceLogs.clear()
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(
            modifier = (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .fillMaxSize(),
        ) {
            if (rawViewEnabled) {
                RawLogView(
                    text = rawText,
                    verticalScrollState = rawVerticalScrollState,
                    horizontalScrollState = rawHorizontalScrollState,
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
                    packageName = context.packageName,
                    serviceUid = serviceUid,
                    servicePid = servicePid,
                    topPadding = innerPadding.calculateTopPadding(),
                    startPadding = innerPadding.calculateStartPadding(layoutDirection),
                    endPadding = innerPadding.calculateEndPadding(layoutDirection),
                    bottomPadding = bottomInnerPadding,
                )
            }
        }
    }
}

@Composable
private fun LogTopBar(
    color: Color,
    scrollBehavior: ScrollBehavior,
    showMenu: Boolean,
    rawViewEnabled: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onToggleRawView: () -> Unit,
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
    onClear: () -> Unit,
) {
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
                OverlayListPopup(
                    show = showMenu,
                    popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                    alignment = PopupPositionProvider.Align.TopEnd,
                    onDismissRequest = { onShowMenuChange(false) },
                    content = {
                        ListPopupColumn {
                            DropdownImpl(
                                text = "原始视图",
                                isSelected = rawViewEnabled,
                                optionSize = 4,
                                index = 0,
                                onSelectedIndexChange = { onToggleRawView() },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            DropdownImpl(
                                text = "滚动到顶部",
                                isSelected = false,
                                optionSize = 4,
                                index = 1,
                                onSelectedIndexChange = { onScrollToTop() },
                            )
                            DropdownImpl(
                                text = "滚动到底部",
                                isSelected = false,
                                optionSize = 4,
                                index = 2,
                                onSelectedIndexChange = { onScrollToBottom() },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            DropdownImpl(
                                text = "立即清空日志",
                                isSelected = false,
                                optionSize = 4,
                                index = 3,
                                onSelectedIndexChange = { onClear() },
                            )
                        }
                    },
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
    packageName: String,
    serviceUid: Int?,
    servicePid: Int?,
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
                    packageName = packageName,
                    serviceUid = serviceUid,
                    servicePid = servicePid,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun RawLogView(
    text: String,
    verticalScrollState: androidx.compose.foundation.ScrollState,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    scrollBehavior: ScrollBehavior,
    topPadding: Dp,
    startPadding: Dp,
    endPadding: Dp,
    bottomPadding: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState),
    ) {
        SelectionContainer {
            Text(
                text = text.ifEmpty { "暂无服务日志" },
                modifier = Modifier.padding(
                    top = topPadding + 12.dp,
                    start = startPadding + 12.dp,
                    end = endPadding + 12.dp,
                    bottom = bottomPadding + 16.dp,
                ),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                softWrap = false,
                color = if (text.isEmpty()) {
                    colorScheme.onSurfaceVariantSummary
                } else {
                    colorScheme.onSurface
                },
            )
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
    packageName: String,
    serviceUid: Int?,
    servicePid: Int?,
) {
    val parsed = remember(entry.rawLine) { ParsedLogLine.parse(entry.rawLine) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val priority = parsed?.priority ?: "I"
    val pid = parsed?.pid ?: servicePid?.toString() ?: "?"
    val tid = parsed?.tid ?: "?"

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
                text = "$packageName  →  ${permissionName(serviceUid)}",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
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

private data class ParsedLogLine(
    val timestamp: String,
    val pid: String,
    val tid: String,
    val priority: String,
    val message: String,
) {
    companion object {
        fun parse(line: String): ParsedLogLine? {
            val lines = line.lineSequence().toList()
            val values = THREADTIME_PATTERN.matchEntire(lines.firstOrNull().orEmpty())
                ?.groupValues
                ?: return null
            val message = buildString {
                append(values[7])
                lines.drop(1).forEach { continuationLine ->
                    append('\n')
                    val continuationValues = THREADTIME_PATTERN
                        .matchEntire(continuationLine)
                        ?.groupValues
                    append(continuationValues?.get(7) ?: continuationLine)
                }
            }
            return ParsedLogLine(
                timestamp = "${values[1]} ${values[2].substringBeforeLast('.')}",
                pid = values[3],
                tid = values[4],
                priority = values[5],
                message = message,
            )
        }

        private val THREADTIME_PATTERN = Regex(
            """^\s*(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+(.+?)\s*:\s?(.*)$""",
        )
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
