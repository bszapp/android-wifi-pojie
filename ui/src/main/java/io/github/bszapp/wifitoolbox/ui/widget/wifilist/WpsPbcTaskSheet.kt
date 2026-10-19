package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskLogEntry
import io.github.bszapp.wifitoolbox.contract.task.TaskLogState
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshakeProgress
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshakeStage
import io.github.bszapp.wifitoolbox.contract.task.WpsPacketDirection
import io.github.bszapp.wifitoolbox.contract.task.WpsPbcTaskInput
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import io.github.bszapp.wifitoolbox.ui.component.PreviewSheetHost
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.ui.component.RandomMacButton
import io.github.bszapp.wifitoolbox.ui.component.WifiInterfaceSelector
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun WpsPbcInputSheet(show: Boolean, isStarting: Boolean, availableInterfaces: List<String>,
    onStart: (WpsPbcTaskInput) -> Unit, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf(WpsPbcTaskInput()) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    SingleOverlayBottomSheet(show = show, title = "WPS-PBC启动配置", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 550.dp)
            .padding(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { WifiInterfaceSelector(input.interfaceName, availableInterfaces,
                    { input = input.copy(interfaceName = it) }, enabled = !isStarting) }
                item {
                    Text("运行行为", style = MiuixTheme.textStyles.subtitle)
                    Card(Modifier.fillMaxWidth()) {
                        WpsOptionRow("持续捕获", input.continuousCapture, { input = input.copy(continuousCapture = it) }, !isStarting)
                        WpsOptionRow("自动保存", input.autoSaveToDevice, { input = input.copy(autoSaveToDevice = it) }, !isStarting)
                        WpsOptionRow("不使用完整协议", input.useIncompleteProtocol, { input = input.copy(useIncompleteProtocol = it) }, !isStarting)
                        WpsOptionRow("忽略重复握手的设备", input.ignoreRepeatedDevices, { input = input.copy(ignoreRepeatedDevices = it) }, !isStarting)
                    }
                }
                item {
                    TextField(value = input.localMac.orEmpty(),
                        onValueChange = { input = input.copy(localMac = it); message = null },
                        label = "本机MAC（留空使用设备MAC）", singleLine = true, enabled = !isStarting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { RandomMacButton({ input = input.copy(localMac = it); message = null }, !isStarting) })
                }
                message?.let { text -> item { Text(text, style = MiuixTheme.textStyles.body2) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", onDismiss, Modifier.weight(1f), enabled = !isStarting)
                TextButton(if (isStarting) "正在提交" else "开始", {
                    val mac = input.localMac?.trim()?.takeIf { it.isNotEmpty() }
                    if (mac != null && (!Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}").matches(mac) ||
                            (mac.substringBefore(':').toInt(16) and 1) != 0 || mac == "00:00:00:00:00:00")) {
                        message = "本机MAC必须为有效单播地址"
                    } else onStart(input.copy(localMac = mac))
                }, Modifier.weight(1f), enabled = !isStarting, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Preview(name = "WPS 按键获取 · 进行中", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
internal fun WpsPbcTaskSheetPreview() {
    PreviewSheetHost {
        WpsPbcTaskSheet(
            trackedTask = TrackedTaskState(
                snapshot = TaskSnapshot(
                    taskId = 4,
                    request = TaskStartRequest.wpsPbc(),
                    state = TaskExecutionState.RUNNING,
                    progress = TaskProgress.WpsPbc(
                        continuousCapture = true,
                        autoSaveToDevice = true,
                        useIncompleteProtocol = true,
                        ignoreRepeatedDevices = true,
                        networkCount = 0,
                        handshake = WpsHandshakeProgress(
                            stage = WpsHandshakeStage.EAPOL_START,
                            packet = "M1",
                            direction = WpsPacketDirection.SENDING,
                        ),
                    ),
                ),
                logs = TaskLogState(
                    scopeTaskId = 4,
                    lineCount = 2,
                    latestId = 2L,
                    entries = listOf(
                        TaskLogEntry(4, 0, 0, 0L, "开始任务：WPS-PBC"),
                        TaskLogEntry(4, 1, 1, 1_800L, "等待附近设备发起 WPS 按键协商"),
                    ),
                ),
            ),
            isStarting = false,
            onContinuousCaptureChange = {},
            onAutoSaveToDeviceChange = {},
            onUseIncompleteProtocolChange = {},
            onIgnoreRepeatedDevicesChange = {},
            onStop = {},
            onDismiss = {},
        )
    }
}

@Composable
internal fun WpsPbcTaskSheet(
    trackedTask: TrackedTaskState?,
    isStarting: Boolean,
    onContinuousCaptureChange: (Boolean) -> Unit,
    onAutoSaveToDeviceChange: (Boolean) -> Unit,
    onUseIncompleteProtocolChange: (Boolean) -> Unit,
    onIgnoreRepeatedDevicesChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(true) }
    val snapshot = trackedTask?.snapshot
    val progress = snapshot?.progress as? TaskProgress.WpsPbc
    val running = isStarting || snapshot == null || snapshot.state == TaskExecutionState.RUNNING
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    SingleOverlayBottomSheet(
        show = showSheet,
        title = "WPS-PBC",
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = { showSheet = false },
        onDismissFinished = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            (snapshot?.request?.payload as? TaskRequestPayload.WpsPbc)?.input?.let {
                Text("使用网卡：${it.interfaceName}", style = MiuixTheme.textStyles.body2)
            }
            TabRow(
                tabs = listOf("获取到的网络", "运行日志"),
                selectedTabIndex = pagerState.currentPage,
                onTabSelected = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
                modifier = Modifier.fillMaxWidth(),
                colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(300.dp),
                verticalAlignment = Alignment.Top,
            ) { page ->
                when (page) {
                    0 -> WpsCapturedNetworksPage(trackedTask)
                    else -> WpsTaskLogsPage(trackedTask)
                }
            }
            io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
                targetState = progress?.takeIf { running },
                contentKey = { it == null },
                label = "wps-task-options",
            ) { visibleProgress ->
            if (visibleProgress != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    WpsOptionRow(
                        title = "持续捕获",
                        checked = visibleProgress.continuousCapture,
                        onCheckedChange = onContinuousCaptureChange,
                    )
                    WpsOptionRow(
                        title = "自动保存",
                        checked = visibleProgress.autoSaveToDevice,
                        onCheckedChange = onAutoSaveToDeviceChange,
                    )
                    WpsOptionRow(
                        title = "不使用完整协议",
                        checked = visibleProgress.useIncompleteProtocol,
                        onCheckedChange = onUseIncompleteProtocolChange,
                    )
                    WpsOptionRow(
                        title = "忽略重复握手的设备",
                        checked = visibleProgress.ignoreRepeatedDevices,
                        onCheckedChange = onIgnoreRepeatedDevicesChange,
                    )
                }
            }
            }
            io.github.bszapp.wifitoolbox.ui.component.ImmediateVisibility(visible = running) {
                TextButton(
                    text = "停止",
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun WpsCapturedNetworksPage(task: TrackedTaskState?) {
    val progress = task?.snapshot?.progress as? TaskProgress.WpsPbc
    val networks = task?.capturedNetworks.orEmpty()
    when {
        progress == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        networks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "尚未获取网络",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                items = networks,
                key = { index, _ -> index },
            ) { _, network ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = network.ssid,
                        summary = "${network.mac}\n${network.password}",
                    )
                }
            }
        }
    }
}

@Composable
internal fun WpsTaskLogsPage(task: TrackedTaskState?) {
    val entries = task?.logs?.entries.orEmpty()
    val listState = rememberLazyListState()
    val latestId = entries.lastOrNull()?.taskLineId
    LaunchedEffect(latestId) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
        targetState = entries,
        contentKey = { it.isEmpty() },
        label = "wps-task-log-content",
    ) { visibleEntries ->
    if (visibleEntries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "暂无日志",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
    } else {
        Card(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleEntries, key = { it.taskLineId }) { entry ->
                    Text(
                        text = entry.text,
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun WpsOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    BasicComponent(
        title = title,
        role = Role.Checkbox,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        endActions = {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
            )
        },
    )
}
