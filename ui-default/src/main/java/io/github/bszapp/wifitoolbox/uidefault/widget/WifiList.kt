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
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.contract.wifilist.SystemScanData
import io.github.bszapp.wifitoolbox.contract.wifilist.scanResults
import io.github.bszapp.wifitoolbox.contract.wifilist.connection
import io.github.bszapp.wifitoolbox.contract.wifilist.hasData
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeCaptureQuality
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorDeviceDetailSheet
import io.github.bszapp.wifitoolbox.uidefault.component.TagItem
import io.github.bszapp.wifitoolbox.uidefault.component.TagStyle
import io.github.bszapp.wifitoolbox.uidefault.component.WifiIcon
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.model.MergedWifiGroup
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiSheetContent
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiSheetSubmission
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.ConnectWifiTaskSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WifiDetailSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.WifiGroupCardActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WifiList(
    modifier: Modifier = Modifier,
    vm: DefaultViewModel = viewModel(),
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    onSaveHc22000: (content: String, fileName: String) -> Unit = { _, _ -> },
    onInlineConnectTaskChanged: (Long?) -> Unit = {},
) {
    val wifiState by vm.wifiList.state.collectAsStateWithLifecycle()
    val savedWifiList by vm.wifiList.savedWifiList.collectAsStateWithLifecycle()
    val modeState by vm.wifiList.modeState.collectAsStateWithLifecycle()
    val handshakeTest by vm.wifiList.monitorHandshakeTest.collectAsStateWithLifecycle()
    val capturedAccessPoints = modeState?.monitorStatistics?.accessPoints.orEmpty()
    val currentTask by vm.currentTask.collectAsStateWithLifecycle()
    val displayedTask by vm.displayedTask.collectAsStateWithLifecycle()
    val connectWifiSheetCloseRequest by
        vm.connectWifiSheetCloseRequest.collectAsStateWithLifecycle()
    var selectedSsid by rememberSaveable { mutableStateOf<String?>(null) }
    var showWifiDetailSheet by rememberSaveable { mutableStateOf(false) }
    var connectSheetContent by remember { mutableStateOf<ConnectWifiSheetContent?>(null) }
    var inlineConnectTaskId by remember { mutableStateOf<Long?>(null) }
    var dismissConnectSheet by remember { mutableStateOf(false) }
    var isSubmittingTask by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val connectSheetInstanceId = remember(connectSheetContent != null) {
        java.util.UUID.randomUUID().toString()
    }

    LaunchedEffect(connectWifiSheetCloseRequest?.id) {
        connectWifiSheetCloseRequest?.let { request ->
            if (request.sheetInstanceId == connectSheetInstanceId) {
                dismissConnectSheet = true
                isSubmittingTask = false
            }
            vm.consumeConnectWifiSheetCloseRequest(request.id)
        }
    }

    // WifiState 与 SavedWifiList 是两种同级数据：
    // 扫描结果只来自 Enabled，已保存配置只来自独立的 SavedWifiList。
    val scanResults: List<ScanResult> = wifiState.scanResults
    val savedNetworks: List<WifiConfiguration> =
        savedWifiList?.networks ?: emptyList()
    val connection = wifiState.connection

    // 设备计数和网速更新不改变列表分组，只以接入点元数据参与列表合并。
    val capturedMetadata = remember(capturedAccessPoints) {
        capturedAccessPoints.map { it.copy(devices = emptyList()) }
    }
    val groups = remember(scanResults, savedNetworks, connection, capturedMetadata, modeState?.mode) {
        MergedWifiGroup.buildFrom(
            results = scanResults,
            savedWifiList = savedNetworks,
            connection = connection,
            capturedAccessPoints = if (modeState?.mode == WifiMode.MONITOR) capturedMetadata else emptyList(),
        )
    }
    val successfulHandshakeAccessPointsBySsid = remember(capturedAccessPoints, modeState?.mode) {
        if (modeState?.mode != WifiMode.MONITOR) {
            emptyMap()
        } else {
            capturedAccessPoints.asSequence()
                .filter { accessPoint ->
                    !accessPoint.ssid.isNullOrBlank() &&
                        accessPoint.devices.any { it.hasCompleteSuccessfulHandshake() }
                }
                .groupBy { it.ssid!! }
                .mapValues { (_, accessPoints) ->
                    accessPoints.distinctBy { it.bssid.lowercase() }.size
                }
        }
    }
    val discoveredHiddenNameCount = remember(capturedAccessPoints, scanResults, modeState?.mode) {
        if (modeState?.mode != WifiMode.MONITOR) {
            0
        } else {
            val hiddenScanBssids = scanResults.asSequence()
                .filter { it.SSID.isNullOrEmpty() }
                .mapNotNull { it.BSSID?.lowercase() }
                .toSet()
            capturedAccessPoints.asSequence()
                .filter {
                    !it.ssid.isNullOrBlank() &&
                        (it.ssidVisibility == MonitorSsidVisibility.HIDDEN ||
                            (it.ssidVisibility != MonitorSsidVisibility.VISIBLE &&
                                it.bssid.lowercase() in hiddenScanBssids))
                }
                .distinctBy { it.bssid.lowercase() }
                .count()
        }
    }
    val selectedGroup = selectedSsid?.let { ssid ->
        groups.firstOrNull { it.ssid == ssid }
    }

    LaunchedEffect(selectedSsid, selectedGroup) {
        if (showWifiDetailSheet && selectedSsid != null && selectedGroup == null) {
            showWifiDetailSheet = false
            selectedSsid = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            (wifiState as? WifiState.System)?.data is SystemScanData.Disabled -> WifiDisabledContent(
                onEnableWifi = { vm.wifiList.setWifiEnabled(true) },
            )

            wifiState.hasData -> LazyColumn(
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
                                    "WPS-PBC · 已获取 ${progress.networkCount} 个网络"
                                null -> "加载中"
                            },
                            onClick = {
                                vm.displayTask(task.taskId)
                            },
                        )
                    }
                }
                item(key = "wifi-empty") {
                    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = groups.isEmpty()) {
                        // 空状态仍放在可滚动容器中，保证 PullToRefresh 能收到 nested scroll。
                        WifiEmptyContent(
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = group.ssid) {
                        WifiGroupCard(
                                vm = vm,
                                group = group,
                                successfulHandshakeAccessPointCount =
                                    successfulHandshakeAccessPointsBySsid[group.ssid] ?: 0,
                                discoveredHiddenNameCount =
                                    discoveredHiddenNameCount.takeIf { group.ssid.isBlank() } ?: 0,
                                modifier = Modifier.animateItem(),
                                onClick = {
                                    selectedSsid = group.ssid
                                    showWifiDetailSheet = true
                                },
                                onConnect = {
                                    dismissConnectSheet = false
                                    connectSheetContent = ConnectWifiSheetContent.Network(
                                        ssid = group.ssid,
                                        hasSavedConfiguration = group.savedWifiList.isNotEmpty(),
                                        allowNetworkCardTest = modeState?.mode == WifiMode.NORMAL,
                                    )
                                },
                                onConnectWithConfig = { config ->
                                    dismissConnectSheet = false
                                    connectSheetContent = ConnectWifiSheetContent.Configuration(
                                        networkId = config.networkId,
                                        ssid = group.displaySsid,
                                    )
                                },
                        )
                    }
                }
            }
            else -> Box(modifier = Modifier.fillMaxSize())
        }
    }

    selectedGroup?.takeIf { selectedSsid != null }?.let { group ->
        WifiDetailSheet(
            show = showWifiDetailSheet,
            group = group,
            onDismiss = { showWifiDetailSheet = false },
            onDismissFinished = {
                showWifiDetailSheet = false
                selectedSsid = null
            },
            capturedAccessPoints = capturedAccessPoints.filter { ap ->
                group.networks.any { it.BSSID.equals(ap.bssid, ignoreCase = true) } ||
                    group.virtualAccessPoint?.bssid.equals(ap.bssid, ignoreCase = true) ||
                    // 扫描列表里已消失、但抓包仍在记录的同名接入点，一并合并展示
                    (group.ssid.isNotBlank() && ap.ssid == group.ssid)
            },
            deviceDetailContent = { accessPoint, device ->
                val bssid = accessPoint.bssid
                val mac = device.mac
                MonitorDeviceDetailSheet(
                    accessPoint = accessPoint,
                    device = device,
                    savedNetworks = savedNetworks,
                    handshakeTest = handshakeTest,
                    onClearHandshakeTestResult = vm.wifiList::clearMonitorHandshakeTestResult,
                    onDismiss = { showWifiDetailSheet = false },
                    onExport = { vm.wifiList.exportMonitorDevicePcap(bssid, mac, it) },
                    onTestHandshake = { id, password -> vm.wifiList.testMonitorHandshake(bssid, mac, id, password) },
                    onExportHandshake = { id -> vm.wifiList.exportMonitorHandshakePcap(bssid, mac, id) },
                    onSaveHc22000 = onSaveHc22000,
                    communications = vm.wifiList.communications,
                    renderSheet = false,
                )
            },
        )
    }

    connectSheetContent?.let { content ->
        ConnectWifiTaskSheet(
            content = content,
            trackedTask = displayedTask?.takeIf { it.snapshot.taskId == inlineConnectTaskId },
            isSubmitting = isSubmittingTask,
            dismissRequested = dismissConnectSheet,
            onSubmit = { submission ->
                val savedConfigurationTest =
                    submission as? ConnectWifiSheetSubmission.TestConnectivity
                val network = content as? ConnectWifiSheetContent.Network
                if (
                    savedConfigurationTest != null &&
                    network?.hasSavedConfiguration == true
                ) {
                    scope.launch {
                        delay(CONFIRMATION_DIALOG_TEST_DELAY_MILLIS)
                        vm.confirmSavedConfigurationConnectivityTest(
                            sheetInstanceId = connectSheetInstanceId,
                            ssid = network.ssid,
                            password = savedConfigurationTest.password,
                            config = savedConfigurationTest.config,
                        )
                    }
                } else if (!isSubmittingTask) {
                    isSubmittingTask = true
                    scope.launch {
                        when (submission) {
                            is ConnectWifiSheetSubmission.UseSavedNetwork -> {
                                val configuration = content as? ConnectWifiSheetContent.Configuration
                                if (configuration != null) {
                                    runCatching {
                                        vm.startSavedNetworkConnectTask(
                                            networkId = configuration.networkId,
                                            type = ConnectWifiTaskType.USE_SAVED_NETWORK,
                                            config = submission.config,
                                        )
                                    }.onSuccess { taskId ->
                                        inlineConnectTaskId = taskId
                                        onInlineConnectTaskChanged(taskId)
                                        connectSheetContent = ConnectWifiSheetContent.Task
                                    }
                                }
                            }
                            is ConnectWifiSheetSubmission.TestConnectivity -> {
                                val network = content as? ConnectWifiSheetContent.Network
                                if (network != null) {
                                    runCatching {
                                        vm.startTemporaryNetworkConnectTask(
                                            ssid = network.ssid,
                                            password = submission.password,
                                            config = submission.config,
                                        )
                                    }.onSuccess { taskId ->
                                        inlineConnectTaskId = taskId
                                        onInlineConnectTaskChanged(taskId)
                                        connectSheetContent = ConnectWifiSheetContent.Task
                                    }
                                }
                            }
                            is ConnectWifiSheetSubmission.NetworkCardTest -> {
                                val network = content as? ConnectWifiSheetContent.Network
                                if (network != null) {
                                    runCatching {
                                        vm.startNetworkCardConnectTask(
                                            ssid = network.ssid,
                                            passwords = submission.passwords,
                                            mac = submission.mac,
                                            name = submission.name,
                                            config = submission.config,
                                        )
                                    }.onSuccess { taskId ->
                                        inlineConnectTaskId = taskId
                                        onInlineConnectTaskChanged(taskId)
                                        connectSheetContent = ConnectWifiSheetContent.Task
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
                                        vm.startSavedNetworkConnectTask(
                                            networkId = networkId,
                                            type = ConnectWifiTaskType.CONNECT_TO_NETWORK,
                                            config = submission.config,
                                        )
                                    }.onSuccess { taskId ->
                                        inlineConnectTaskId = taskId
                                        onInlineConnectTaskChanged(taskId)
                                        connectSheetContent = ConnectWifiSheetContent.Task
                                    }
                                }
                            }
                        }
                        isSubmittingTask = false
                    }
                }
            },
            onStop = vm::stopDisplayedTask,
            onDismiss = {
                if (inlineConnectTaskId != null) vm.closeDisplayedTask()
                inlineConnectTaskId = null
                onInlineConnectTaskChanged(null)
                connectSheetContent = null
                dismissConnectSheet = false
                isSubmittingTask = false
            },
        )
    }

}

private const val CONFIRMATION_DIALOG_TEST_DELAY_MILLIS = 1_000L

@Composable
private fun RunningTaskCard(
    taskId: Long,
    stage: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.primaryContainer,
            contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
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
                    style = MiuixTheme.textStyles.headline2,
                )
                Text(
                    text = stage,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
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
    "IP_NEGOTIATION" -> "获取 IP 地址"
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
                tint = MiuixTheme.colorScheme.dividerLine,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "空空如也",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

// ── 单个 Wi-Fi 卡片 ───────────────────────────────────────────────────────────

@Composable
private fun WifiGroupCard(
    vm: DefaultViewModel,
    group: MergedWifiGroup,
    successfulHandshakeAccessPointCount: Int,
    discoveredHiddenNameCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onConnectWithConfig: (WifiConfiguration) -> Unit,
) {
    val isConnected = group.isConnected
    val levelIndex = group.signalDbm?.let {
        WifiManager.calculateSignalLevel(it, 5)
    } ?: 0
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) {
            MiuixTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "WifiCardBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isConnected) {
            MiuixTheme.colorScheme.onPrimaryContainer
        } else {
            MiuixTheme.colorScheme.onSurface
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
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                    )

                    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = isConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = contentColor.copy(alpha = 0.1f),
                            ) {
                                Text(
                                    text = "已连接",
                                    style = MiuixTheme.textStyles.footnote2,
                                    fontWeight = FontWeight.Medium,
                                    color = contentColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    val tags = buildList {
                        if (group.accessPointCount > 1) {
                            add(Triple(group.accessPointCount.toString(), TagStyle.Tertiary, Icons.Default.Layers))
                        }
                        if (group.savedWifiList.isNotEmpty()) {
                            add(Triple("已保存", TagStyle.Primary, null))
                        }
                        if (successfulHandshakeAccessPointCount > 0) {
                            add(
                                Triple(
                                    "成功握手${successfulHandshakeAccessPointCount}个",
                                    TagStyle.Primary,
                                    null,
                                ),
                            )
                        }
                        if (discoveredHiddenNameCount > 0) {
                            add(
                                Triple(
                                    "探测有名称${discoveredHiddenNameCount}个",
                                    TagStyle.Primary,
                                    null,
                                ),
                            )
                        }
                    }
                    tags.forEach { (text, style, icon) ->
                        TagItem(text = text, style = style, icon = icon)
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = group.signalDisplay,
                    style = MiuixTheme.textStyles.footnote1,
                    lineHeight = 16.sp,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.width(12.dp))
            WifiGroupCardActions(
                group = group,
                isConnected = isConnected,
                buttonContainerColor = MiuixTheme.colorScheme.primary.takeIf { isConnected },
                buttonContentColor = MiuixTheme.colorScheme.onPrimary.takeIf { isConnected },
                onConnect = onConnect,
                onDisconnect = {
                    group.connection?.networkId?.let(vm.wifiList::disconnectCurrentNetwork)
                },
                onOpenDetail = { onClick() },
                onConnectWithConfig = onConnectWithConfig,
                onUpdateConfig = { networkId, patch ->
                    vm.wifiList.updateWifiConfig(networkId, patch)
                },
            )

        }
    }
}

private fun MonitorDevice.hasCompleteSuccessfulHandshake(): Boolean = handshakes.any { record ->
    record.status == MonitorHandshakeStatus.SUCCESS &&
        record.captureQuality == MonitorHandshakeCaptureQuality.COMPLETE
}
