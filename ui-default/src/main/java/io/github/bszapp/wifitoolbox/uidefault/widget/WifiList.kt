@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.widget

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskInput
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTarget
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdatePayload
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.uidefault.component.TagItem
import io.github.bszapp.wifitoolbox.uidefault.component.TagStyle
import io.github.bszapp.wifitoolbox.uidefault.component.WifiIcon
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.model.MergedWifiGroup
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiSheetContent
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiSheetSubmission
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiTaskSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorModeAccessPoint
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorModeSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorModeSheetTarget
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WifiDetailSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WifiGroupCardActions
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WpsPbcTaskSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.frequencyToChannel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WifiList(
    modifier: Modifier = Modifier,
    vm: DefaultViewModel = viewModel(),
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    wpsPbcStartSignal: Int = 0,
) {
    val wifiState by vm.wifiList.state.collectAsStateWithLifecycle()
    val savedWifiList by vm.wifiList.savedWifiList.collectAsStateWithLifecycle()
    val currentTask by vm.taskTracker.currentTask.collectAsStateWithLifecycle()
    val trackedTask by vm.taskTracker.trackedTask.collectAsStateWithLifecycle()
    var selectedSsid by rememberSaveable { mutableStateOf<String?>(null) }
    var connectSheetContent by remember { mutableStateOf<ConnectWifiSheetContent?>(null) }
    var dismissConnectSheet by remember { mutableStateOf(false) }
    var monitorModeTarget by remember { mutableStateOf<MonitorModeSheetTarget?>(null) }
    var showMonitorModeSheet by remember { mutableStateOf(false) }
    var isSubmittingTask by remember { mutableStateOf(false) }
    var showWpsPbcSheet by remember { mutableStateOf(false) }
    var isStartingWpsPbc by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // WifiState 与 SavedWifiList 是两种同级数据：
    // 扫描结果只来自 Enabled，已保存配置只来自独立的 SavedWifiList。
    val scanResults: List<ScanResult> = when (val state = wifiState) {
        is WifiState.Data.Enabled -> state.scanResults
        else -> emptyList()
    }
    val savedNetworks: List<WifiConfiguration> =
        savedWifiList?.networks ?: emptyList()
    val connection = (wifiState as? WifiState.Data.Enabled)?.connection

    val groups = remember(scanResults, savedNetworks, connection) {
        MergedWifiGroup.buildFrom(
            results = scanResults,
            savedWifiList = savedNetworks,
            connection = connection,
        )
    }
    val selectedGroup = selectedSsid?.let { ssid ->
        groups.firstOrNull { it.ssid == ssid }
    }

    LaunchedEffect(selectedSsid, selectedGroup) {
        if (selectedSsid != null && selectedGroup == null) selectedSsid = null
    }

    LaunchedEffect(wpsPbcStartSignal) {
        if (wpsPbcStartSignal <= 0) return@LaunchedEffect
        showWpsPbcSheet = true
        isStartingWpsPbc = true
        runCatching {
            vm.taskTracker.startTask(TaskStartRequest.wpsPbc())
        }.onFailure {
            showWpsPbcSheet = false
        }
        isStartingWpsPbc = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        when (val state = wifiState) {
            null -> Box(modifier = Modifier.fillMaxSize())

            is WifiState.Data.Disabled -> WifiDisabledContent(
                onEnableWifi = { vm.wifiList.setWifiEnabled(true) },
            )

            is WifiState.Error -> WifiErrorContent(
                message = state.exception.message ?: "未知错误",
            )

            is WifiState.Data.Enabled -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                currentTask?.let { task ->
                    item(key = "running-task-${task.taskId}") {
                        RunningTaskCard(
                            taskId = task.taskId,
                            stage = when (val progress = task.progress) {
                                is TaskProgress.ConnectWifi -> progress.stage.name.toTaskStageText()
                                is TaskProgress.WpsPbc ->
                                    "WPS-PBC · 已获取 ${progress.networks.size} 个网络"
                                null -> "加载中"
                            },
                            onClick = {
                                vm.taskTracker.track(task.taskId)
                                when (task.request.payload) {
                                    is TaskRequestPayload.ConnectWifi -> {
                                        connectSheetContent = ConnectWifiSheetContent.Task(task.taskId)
                                    }
                                    is TaskRequestPayload.WpsPbc -> {
                                        showWpsPbcSheet = true
                                    }
                                }
                            },
                        )
                    }
                }
                if (groups.isEmpty()) {
                    item(key = "wifi-empty") {
                        // 空状态仍放在可滚动容器中，保证 PullToRefresh 能收到 nested scroll。
                        WifiEmptyContent(
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    groups.forEachIndexed { index, group ->
                        val startsUnknownSignalSection =
                            !group.isConnected && group.hasUnknownSignal &&
                                groups.take(index).none {
                                    !it.isConnected && it.hasUnknownSignal
                                }
                        if (startsUnknownSignalSection) {
                            item(key = "wifi-unknown-signal-section") {
                                Text(
                                    text = "未知信号",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = 12.dp,
                                        top = 12.dp,
                                        bottom = 4.dp,
                                    ),
                                )
                            }
                        }
                        item(key = group.ssid) {
                            WifiGroupCard(
                                vm = vm,
                                group = group,
                                modifier = Modifier.animateItem(),
                                onClick = { selectedSsid = group.ssid },
                                onConnect = {
                                    dismissConnectSheet = false
                                    connectSheetContent = ConnectWifiSheetContent.Network(
                                        ssid = group.ssid,
                                        hasSavedConfiguration = group.savedWifiList.isNotEmpty(),
                                    )
                                },
                                onConnectWithConfig = { config ->
                                    dismissConnectSheet = false
                                    connectSheetContent = ConnectWifiSheetContent.Configuration(
                                        networkId = config.networkId,
                                        ssid = group.displaySsid,
                                    )
                                },
                                onEnterMonitorMode = {
                                    val frequency = group.strongest?.frequency
                                        ?: group.virtualAccessPoint?.frequency
                                        ?: 0
                                    val channel = frequencyToChannel(frequency)
                                    if (channel != null) {
                                        val accessPoints = scanResults
                                            .asSequence()
                                            .filter {
                                                frequencyToChannel(it.frequency) == channel
                                            }
                                            .distinctBy { it.BSSID.lowercase() }
                                            .map {
                                                MonitorModeAccessPoint(
                                                    name = it.SSID
                                                        ?.takeIf(String::isNotEmpty)
                                                        ?: "<隐藏的网络>",
                                                    mac = it.BSSID,
                                                )
                                            }
                                            .toMutableList()
                                        group.virtualAccessPoint?.let { virtual ->
                                            val bssid = virtual.bssid
                                            if (
                                                frequencyToChannel(virtual.frequency) == channel &&
                                                !bssid.isNullOrBlank() &&
                                                accessPoints.none {
                                                    it.mac.equals(bssid, ignoreCase = true)
                                                }
                                            ) {
                                                accessPoints += MonitorModeAccessPoint(
                                                    name = virtual.ssid
                                                        ?.takeUnless {
                                                            it == WifiManager.UNKNOWN_SSID
                                                        }
                                                        ?.removeSurrounding("\"")
                                                        ?.takeIf(String::isNotEmpty)
                                                        ?: "<隐藏的网络>",
                                                    mac = bssid,
                                                )
                                            }
                                        }
                                        monitorModeTarget = MonitorModeSheetTarget(
                                            channel = channel,
                                            frequencyMhz = frequency,
                                            accessPoints = accessPoints,
                                        )
                                        showMonitorModeSheet = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    selectedGroup?.let { group ->
        WifiDetailSheet(group = group, onDismiss = { selectedSsid = null })
    }

    monitorModeTarget?.let { target ->
        MonitorModeSheet(
            target = target,
            show = showMonitorModeSheet,
            onDismiss = {
                showMonitorModeSheet = false
            },
            onExecute = { command, channel, frequencyMhz ->
                showMonitorModeSheet = false
                vm.wifiList.enterMonitorMode(command, channel, frequencyMhz)
            },
        )
    }

    connectSheetContent?.let { content ->
        ConnectWifiTaskSheet(
            content = content,
            trackedTask = trackedTask,
            isSubmitting = isSubmittingTask,
            dismissRequested = dismissConnectSheet,
            onSubmit = { submission ->
                if (!isSubmittingTask) {
                    isSubmittingTask = true
                    scope.launch {
                        when (submission) {
                            is ConnectWifiSheetSubmission.UseSavedNetwork -> {
                                val configuration = content as? ConnectWifiSheetContent.Configuration
                                if (configuration != null) {
                                    runCatching {
                                        vm.taskTracker.startTask(
                                            TaskStartRequest.connectWifi(
                                                input = ConnectWifiTaskInput(
                                                    type = ConnectWifiTaskType.USE_SAVED_NETWORK,
                                                    target = ConnectWifiTarget.SavedNetwork(
                                                        configuration.networkId,
                                                    ),
                                                ),
                                                config = submission.config,
                                            ),
                                        )
                                    }.onSuccess { taskId ->
                                        connectSheetContent = ConnectWifiSheetContent.Task(taskId)
                                    }
                                }
                            }
                            is ConnectWifiSheetSubmission.TestConnectivity -> {
                                val network = content as? ConnectWifiSheetContent.Network
                                if (network != null) {
                                    runCatching {
                                        vm.taskTracker.startTask(
                                            TaskStartRequest.connectWifi(
                                                input = ConnectWifiTaskInput(
                                                    type = ConnectWifiTaskType.CONNECT_TO_NETWORK,
                                                    target = ConnectWifiTarget.TemporaryNetwork(
                                                        ssid = network.ssid,
                                                        password = submission.password,
                                                    ),
                                                ),
                                                config = submission.config,
                                            ),
                                        )
                                    }.onSuccess { taskId ->
                                        connectSheetContent = ConnectWifiSheetContent.Task(taskId)
                                    }
                                }
                            }
                            is ConnectWifiSheetSubmission.SaveOnly -> {
                                val network = content as? ConnectWifiSheetContent.Network
                                if (network != null) {
                                    runCatching {
                                        vm.wifiList.saveWifiNetwork(network.ssid, submission.password)
                                    }.onSuccess {
                                        dismissConnectSheet = true
                                    }
                                }
                            }
                            is ConnectWifiSheetSubmission.SaveAndConnect -> {
                                val network = content as? ConnectWifiSheetContent.Network
                                if (network != null) {
                                    runCatching {
                                        val networkId = vm.wifiList.saveWifiNetwork(
                                            network.ssid,
                                            submission.password,
                                        )
                                        vm.taskTracker.startTask(
                                            TaskStartRequest.connectWifi(
                                                input = ConnectWifiTaskInput(
                                                    type = ConnectWifiTaskType.CONNECT_TO_NETWORK,
                                                    target = ConnectWifiTarget.SavedNetwork(networkId),
                                                ),
                                                config = submission.config,
                                            ),
                                        )
                                    }.onSuccess { taskId ->
                                        connectSheetContent = ConnectWifiSheetContent.Task(taskId)
                                    }
                                }
                            }
                        }
                        isSubmittingTask = false
                    }
                }
            },
            onStop = vm.taskTracker::stopTrackedTask,
            onDismiss = {
                if (content is ConnectWifiSheetContent.Task) {
                    vm.taskTracker.clearTracking()
                }
                connectSheetContent = null
                dismissConnectSheet = false
                isSubmittingTask = false
            },
        )
    }

    if (showWpsPbcSheet) {
        WpsPbcTaskSheet(
            trackedTask = trackedTask,
            isStarting = isStartingWpsPbc,
            onContinuousCaptureChange = { enabled ->
                vm.taskTracker.updateTrackedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcContinuousCapture(enabled)),
                )
            },
            onAutoSaveToDeviceChange = { enabled ->
                vm.taskTracker.updateTrackedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcAutoSaveToDevice(enabled)),
                )
            },
            onUseIncompleteProtocolChange = { enabled ->
                vm.taskTracker.updateTrackedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcUseIncompleteProtocol(enabled)),
                )
            },
            onIgnoreRepeatedDevicesChange = { enabled ->
                vm.taskTracker.updateTrackedTask(
                    TaskUpdateRequest(TaskUpdatePayload.WpsPbcIgnoreRepeatedDevices(enabled)),
                )
            },
            onStop = vm.taskTracker::stopTrackedTask,
            onDismiss = {
                vm.taskTracker.clearTracking()
                showWpsPbcSheet = false
                isStartingWpsPbc = false
            },
        )
    }
}

@Composable
private fun RunningTaskCard(
    taskId: Long,
    stage: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "任务运行中 · #$taskId",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
        }
    }
}

private fun String.toTaskStageText(): String = when (this) {
    "ROUTER_COMMUNICATION" -> "与路由器建立通信"
    "WPA_HANDSHAKE_1_OF_4" -> "WPA 握手 1/4"
    "WPA_HANDSHAKE_2_OF_4" -> "WPA 握手 2/4"
    "WPA_HANDSHAKE_3_OF_4" -> "WPA 握手 3/4"
    "WPA_HANDSHAKE_4_OF_4" -> "WPA 握手 4/4"
    else -> "加载中"
}

@Composable
private fun WifiEmptyContent(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Inbox,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "空空如也",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WifiDisabledContent(
    onEnableWifi: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "WiFi 未开启",
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.title1,
                textAlign = TextAlign.Center,
            )

            Spacer(
                modifier = Modifier.height(8.dp),
            )

            Text(
                text = "开启 WiFi 后即可扫描附近的无线网络",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                textAlign = TextAlign.Center,
            )

            Spacer(
                modifier = Modifier.height(24.dp),
            )

            TextButton(
                text = "开启 WiFi",
                onClick = onEnableWifi,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun WifiErrorContent(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

// ── 单个 Wi-Fi 卡片 ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WifiGroupCard(
    vm: DefaultViewModel,
    group: MergedWifiGroup,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onConnectWithConfig: (WifiConfiguration) -> Unit,
    onEnterMonitorMode: () -> Unit,
) {
    val isConnected = group.isConnected
    val levelIndex = group.signalDbm?.let {
        WifiManager.calculateSignalLevel(it, 5)
    } ?: 0
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "WifiCardBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isConnected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "WifiCardContent",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            WifiIcon(
                modifier = Modifier.size(28.dp),
                level = levelIndex,
                color = contentColor,
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = group.displaySsid,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                    )

                    if (isConnected) {
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = contentColor.copy(alpha = 0.1f),
                        ) {
                            Text(
                                text = "已连接",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = contentColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    val hasTags = group.accessPointCount > 1 || group.savedWifiList.isNotEmpty()
                    if (hasTags) {
                        Spacer(Modifier.width(4.dp))
                        if (group.accessPointCount > 1) {
                            TagItem(
                                text = group.accessPointCount.toString(),
                                icon = Icons.Default.Layers,
                                style = TagStyle.Tertiary
                            )
                        }
                        if (group.savedWifiList.isNotEmpty()) {
                            TagItem(
                                text = "已保存",
                                style = TagStyle.Primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = group.signalDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.width(12.dp))
            WifiGroupCardActions(
                group = group,
                isConnected = isConnected,
                buttonContainerColor = MaterialTheme.colorScheme.primary.takeIf { isConnected },
                buttonContentColor = MaterialTheme.colorScheme.onPrimary.takeIf { isConnected },
                onConnect = onConnect,
                onDisconnect = {
                    group.connection?.networkId?.let(vm.wifiList::disconnectCurrentNetwork)
                },
                onOpenDetail = { onClick() },
                onConnectWithConfig = onConnectWithConfig,
                onEnterMonitorMode = onEnterMonitorMode,
                onUpdateConfig = { networkId, patch ->
                    vm.wifiList.updateWifiConfig(networkId, patch)
                },
            )

        }
    }
}
