package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
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
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun WpsPbcTaskSheet(
    trackedTask: TrackedTaskState?,
    isStarting: Boolean,
    onContinuousCaptureChange: (Boolean) -> Unit,
    onAutoSaveToDeviceChange: (Boolean) -> Unit,
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

    OverlayBottomSheet(
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
                    0 -> WpsCapturedNetworksPage(progress)
                    else -> WpsTaskLogsPage(trackedTask)
                }
            }
            if (running && progress != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    WpsOptionRow(
                        title = "持续捕获",
                        checked = progress.continuousCapture,
                        onCheckedChange = onContinuousCaptureChange,
                    )
                    WpsOptionRow(
                        title = "自动保存",
                        checked = progress.autoSaveToDevice,
                        onCheckedChange = onAutoSaveToDeviceChange,
                    )
                }
            }
            if (running) {
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
private fun WpsCapturedNetworksPage(progress: TaskProgress.WpsPbc?) {
    val networks = progress?.networks.orEmpty()
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
            items(networks, key = { "${it.mac}\u0000${it.ssid}" }) { network ->
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
private fun WpsTaskLogsPage(task: TrackedTaskState?) {
    val entries = task?.logs?.entries.orEmpty()
    val listState = rememberLazyListState()
    val latestId = entries.lastOrNull()?.taskLineId
    LaunchedEffect(latestId) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    if (entries.isEmpty()) {
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
                items(entries, key = { it.taskLineId }) { entry ->
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

@Composable
private fun WpsOptionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = title,
        role = Role.Checkbox,
        onClick = { onCheckedChange(!checked) },
        endActions = {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = { onCheckedChange(!checked) },
            )
        },
    )
}
