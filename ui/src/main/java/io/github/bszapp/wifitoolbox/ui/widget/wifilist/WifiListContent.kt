// 列表页内容：壳/内容拆分的"内容"侧。
// WifiListPageState 为状态快照（DefaultViewModel 各 flow 的当帧值），WifiListPageActions 为动作接口；
// WifiList（ui/widget/WifiList.kt）只是把 ViewModel flow/方法映射进来的薄壳。
// 本组合体不依赖 ViewModel，可在无头渲染与 Android Studio 预览中直接构造。
package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bszapp.wifitoolbox.contract.task.AuditHistoryRecord
import io.github.bszapp.wifitoolbox.contract.task.AuditRecord
import io.github.bszapp.wifitoolbox.contract.task.AuditRecordKind
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskConfig
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditNetworkState
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeCaptureQuality
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
import io.github.bszapp.wifitoolbox.contract.wifilist.SystemScanData
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.contract.wifilist.connection
import io.github.bszapp.wifitoolbox.contract.wifilist.hasData
import io.github.bszapp.wifitoolbox.contract.wifilist.scanResults
import io.github.bszapp.wifitoolbox.contract.wifilist.supportsWps
import io.github.bszapp.wifitoolbox.ui.component.TagItem
import io.github.bszapp.wifitoolbox.ui.component.TagStyle
import io.github.bszapp.wifitoolbox.ui.component.WifiIcon
import io.github.bszapp.wifitoolbox.ui.model.MergedWifiGroup
import io.github.bszapp.wifitoolbox.ui.model.MonitorHandshakeTestUiState
import io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode
import io.github.bszapp.wifitoolbox.ui.model.resolveWifiListDisplayMode
import io.github.bszapp.wifitoolbox.ui.model.WifiDeviceAssessment
import io.github.bszapp.wifitoolbox.ui.model.WifiQrNetwork
import io.github.bszapp.wifitoolbox.ui.model.ConnectWifiSheetCloseRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class WifiListPlaceholder { EMPTY }

/** 列表页一帧的完整状态快照（不再有渠道差异：预览/无头渲染直接构造，真实运行由薄壳映射）。 */
data class WifiListPageState(
    val wifiState: WifiState? = null,
    val deviceAssessments: Map<String, WifiDeviceAssessment> = emptyMap(),
    val savedWifiList: SavedWifiList? = null,
    val modeState: WifiModeState? = null,
    val handshakeTest: MonitorHandshakeTestUiState = MonitorHandshakeTestUiState(),
    val currentTask: TaskSnapshot? = null,
    val submode: NormalWifiSubmode = NormalWifiSubmode.SCAN,
    val auditBatch: TrackedTaskState? = null,
    val auditTasks: List<TrackedTaskState> = emptyList(),
    val auditHistory: List<AuditHistoryRecord> = emptyList(),
    val displayedTask: TrackedTaskState? = null,
    val connectWifiSheetCloseRequest: ConnectWifiSheetCloseRequest? = null,
    val scannedWifiNetwork: WifiQrNetwork? = null,
)

/** 列表页动作接口：薄壳把它映射到 DefaultViewModel，Fake 数据侧可空实现。 */
interface WifiListPageActions {
    fun setWifiEnabled(enabled: Boolean)
    fun disconnectCurrentNetwork(networkId: Int)
    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch)
    suspend fun saveWifiNetwork(ssid: String, password: String): Int
    suspend fun startSavedNetworkConnectTask(
        networkId: Int,
        type: ConnectWifiTaskType,
        config: ConnectWifiTaskConfig,
    ): Long

    suspend fun startTemporaryNetworkConnectTask(
        ssid: String,
        password: String,
        config: ConnectWifiTaskConfig,
    ): Long

    suspend fun startNetworkCardConnectTask(
        ssid: String,
        passwords: List<String>,
        mac: String?,
        name: String?,
        config: ConnectWifiTaskConfig,
    ): Long

    fun confirmSavedConfigurationConnectivityTest(
        sheetInstanceId: String,
        ssid: String,
        password: String,
        config: ConnectWifiTaskConfig,
    )

    fun displayTask(taskId: Long)
    fun consumeWifiQrNetwork()
    fun consumeConnectWifiSheetCloseRequest(requestId: Long)
    fun auditOpenNetwork(ssid: String, saved: Boolean, append: Boolean = false)
    fun auditOpenWpsNetwork(ssid: String, saved: Boolean, accessPoints: List<ScanResult>)
    fun auditStopNetwork(ssid: String, mac: String?)
    fun auditStopDictionaryNetwork(ssid: String)
    fun auditStopGroup(ssid: String, groupId: String)
    fun auditStopRecord(recordId: String)
    fun auditAddMember(ssid: String, groupId: String)
    fun auditManualNetwork()
    fun openWpsPin(ssid: String)
    fun clearMonitorHandshakeTestResult()
    fun exportMonitorDevicePcap(bssid: String, deviceMac: String, subtypeIds: Set<String>): String
    fun testMonitorHandshake(bssid: String, deviceMac: String, handshakeId: String, password: String)
    fun exportMonitorHandshakePcap(bssid: String, deviceMac: String, handshakeId: String): String
    fun stopDisplayedTask()
    fun closeDisplayedTask()
}

private const val CONFIRMATION_DIALOG_TEST_DELAY_MILLIS = 1_000L

@Composable
fun WifiListContent(
    state: WifiListPageState,
    actions: WifiListPageActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    onSaveHc22000: (content: String, fileName: String) -> Unit = { _, _ -> },
    onInlineConnectTaskChanged: (Long?) -> Unit = {},
) {
    val wifiState = state.wifiState
    val deviceAssessments = state.deviceAssessments
    val savedWifiList = state.savedWifiList
    val modeState = state.modeState
    val handshakeTest = state.handshakeTest
    val currentTask = state.currentTask
    val displayMode = resolveWifiListDisplayMode(modeState?.mode, state.submode, currentTask)
    val submode = displayMode.submode
    val auditBatch = state.auditBatch
    val auditTasks = state.auditTasks
    val auditHistory = state.auditHistory
    val displayedTask = state.displayedTask
    val connectWifiSheetCloseRequest = state.connectWifiSheetCloseRequest
    val capturedAccessPoints = modeState?.monitorStatistics?.accessPoints.orEmpty()
    val selectedMode = displayMode.mode
    val wpsAuditMode = selectedMode == WifiMode.NORMAL &&
        submode == NormalWifiSubmode.WPS_SECURITY_AUDIT
    val auditMode = selectedMode == WifiMode.NORMAL &&
        submode != NormalWifiSubmode.SCAN
    val auditKind = if (wpsAuditMode) AuditRecordKind.WPS else AuditRecordKind.DICTIONARY
    val latestAuditTask = auditTasks.filter {
        if (wpsAuditMode) it.snapshot.request.payload is TaskRequestPayload.WpsSecurityAudit
        else it.snapshot.request.payload is TaskRequestPayload.PskSecurityAudit
    }.maxByOrNull { it.snapshot.taskId }
    val latestAuditResults = remember(latestAuditTask?.auditNetworks, auditKind) {
        val records = latestAuditTask?.auditNetworks.orEmpty().filter { it.kind == auditKind }
        val successfulPools = records.filterIsInstance<AuditRecord.Dictionary>()
            .filter { it.state == PskSecurityAuditNetworkState.SUCCEEDED }
            .mapTo(mutableSetOf()) { it.ssid to (it.groupId ?: it.id) }
        records.filter { record ->
            when (record.state) {
                PskSecurityAuditNetworkState.SUCCEEDED -> record.password != null
                PskSecurityAuditNetworkState.ERROR -> true
                PskSecurityAuditNetworkState.STOPPED -> record !is AuditRecord.Dictionary ||
                    (record.ssid to (record.groupId ?: record.id)) !in successfulPools
                else -> false
            }
        }.groupBy { it.ssid }.mapValues { (_, results) -> results.maxBy { it.updatedAt } }
    }
    val scannedWifiNetwork = state.scannedWifiNetwork
    var selectedSsid by rememberSaveable { mutableStateOf<String?>(null) }
    var showWifiDetailSheet by rememberSaveable { mutableStateOf(false) }
    var connectSheetContent by remember { mutableStateOf<ConnectWifiSheetContent?>(null) }
    var inlineConnectTaskId by remember { mutableStateOf<Long?>(null) }
    var dismissConnectSheet by remember { mutableStateOf(false) }
    var isSubmittingTask by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(scannedWifiNetwork) {
        scannedWifiNetwork?.let { network ->
            dismissConnectSheet = false
            connectSheetContent = ConnectWifiSheetContent.Network(
                ssid = network.ssid,
                hasSavedConfiguration = savedWifiList?.networks.orEmpty().any { it.SSID?.removeSurrounding("\"") == network.ssid },
                allowNetworkCardTest = selectedMode == WifiMode.NORMAL,
                initialPassword = network.password,
            )
            actions.consumeWifiQrNetwork()
        }
    }
    val connectSheetInstanceId = remember(connectSheetContent != null) {
        java.util.UUID.randomUUID().toString()
    }

    LaunchedEffect(connectWifiSheetCloseRequest?.id) {
        connectWifiSheetCloseRequest?.let { request ->
            if (request.sheetInstanceId == connectSheetInstanceId) {
                dismissConnectSheet = true
                isSubmittingTask = false
            }
            actions.consumeConnectWifiSheetCloseRequest(request.id)
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
    val dictionaryAuditNetworks = auditTasks.filter { it.snapshot.state == TaskExecutionState.RUNNING }
        .flatMap { it.auditNetworks }.filterIsInstance<AuditRecord.Dictionary>().filter { it.active }
    val runningAuditNetworks = if (wpsAuditMode) auditBatch?.auditNetworks.orEmpty()
        .filter { it.active && it.kind == auditKind } else dictionaryAuditNetworks
    val groups = remember(scanResults, savedNetworks, connection, capturedMetadata, selectedMode, auditMode, runningAuditNetworks) {
        val scanned = MergedWifiGroup.buildFrom(
            results = scanResults,
            savedWifiList = savedNetworks,
            connection = connection,
            capturedAccessPoints = if (selectedMode == WifiMode.MONITOR) capturedMetadata else emptyList(),
        )
        if (!auditMode) scanned else {
            val missing = runningAuditNetworks.filter { entry -> scanned.none { it.ssid == entry.ssid } }
                .distinctBy { it.ssid }.map { entry ->
                MergedWifiGroup(entry.ssid, emptyList(), savedNetworks.filter { it.SSID?.removeSurrounding("\"") == entry.ssid }, null, null)
            }
            val all = scanned + missing
            runningAuditNetworks.distinctBy { it.ssid }.mapNotNull { entry -> all.firstOrNull { it.ssid == entry.ssid } } +
                scanned.filter { group -> runningAuditNetworks.none { it.ssid == group.ssid } }
        }
    }.distinctBy { it.ssid }
    LaunchedEffect(auditMode, runningAuditNetworks.size) {
        if (auditMode && runningAuditNetworks.isNotEmpty()) listState.animateScrollToItem(0)
    }
    val handshakeDevicesBySsid = remember(capturedAccessPoints, selectedMode) {
        if (selectedMode != WifiMode.MONITOR) {
            emptyMap()
        } else {
            capturedAccessPoints.asSequence()
                .groupBy { it.ssid.orEmpty() }
                .mapValues { (_, accessPoints) ->
                    accessPoints.asSequence().flatMap { it.devices.asSequence() }
                        .filter { it.handshakes.isNotEmpty() }
                        .distinctBy { it.mac.lowercase() }.count()
                }
        }
    }
    val discoveredHiddenNameCount = remember(capturedAccessPoints, scanResults, selectedMode) {
        if (selectedMode != WifiMode.MONITOR) {
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

    val placeholders = buildList {
        if (groups.isEmpty()) add(WifiListPlaceholder.EMPTY)
    }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            !auditMode && (wifiState as? WifiState.System)?.data is SystemScanData.Disabled -> WifiDisabledContent(
                onEnableWifi = { actions.setWifiEnabled(true) },
            )

            wifiState.hasData || auditMode -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(placeholders, key = { "wifi-empty" }) { placeholder ->
                    when (placeholder) {
                        WifiListPlaceholder.EMPTY -> WifiEmptyContent(modifier = Modifier.fillParentMaxSize())
                    }
                }
                groups.forEach { group ->
                    item(key = group.ssid) {
                        val dictMemberGroups = dictionaryAuditNetworks.filter { it.ssid == group.ssid }
                            .groupBy { it.groupId ?: it.id }.values
                            .filter { members -> members.any { it.active } }
                        val wpsRecords = auditBatch?.auditNetworks
                            ?.filterIsInstance<AuditRecord.Wps>()
                            ?.filter { it.ssid == group.ssid && it.active }
                            .orEmpty()
                        val auditActive = auditMode &&
                            (if (wpsAuditMode) {
                                // 隐藏网络目标审计记为空 ssid，允许并入隐藏网络分组展示。
                                wpsRecords.isNotEmpty() && (!group.ssid.isEmpty() || wpsRecords.all { it.ssid.isEmpty() })
                            } else {
                                dictMemberGroups.isNotEmpty() && group.ssid.isNotEmpty()
                            })
                        val hasAuditHistory = auditMode && auditHistory.any { it.ssid == group.ssid && it.kind == auditKind }
                        Column(Modifier.animateItem()) {
                        WifiGroupCard(
                                group = group,
                                assessments = group.networks.mapNotNull { deviceAssessments[it.BSSID?.lowercase()] },
                                handshakeDeviceCount = handshakeDevicesBySsid[group.ssid] ?: 0,
                                discoveredHiddenNameCount =
                                    discoveredHiddenNameCount.takeIf { group.ssid.isBlank() } ?: 0,
                                auditMode = auditMode,
                                hasAuditHistory = hasAuditHistory,
                                auditRunning = auditActive,
                                auditResult = latestAuditResults[group.ssid].takeIf { auditMode },
                                onAppendAudit = if (!wpsAuditMode) ({
                                    actions.auditOpenNetwork(group.ssid, group.savedWifiList.isNotEmpty(), append = true)
                                }) else null,
                                auditStopAll = (if (wpsAuditMode) wpsRecords.size else dictMemberGroups.size) > 1,
                                allowWpsPin = selectedMode == WifiMode.NORMAL && submode == NormalWifiSubmode.SCAN,
                                monitorMode = selectedMode == WifiMode.MONITOR,
                                onClick = {
                                    selectedSsid = group.ssid
                                    showWifiDetailSheet = true
                                },
                                onConnect = {
                                    if (auditMode) {
                                        if (auditActive) {
                                            if (wpsAuditMode) wpsRecords.forEach {
                                                actions.auditStopNetwork(it.ssid, it.mac)
                                            }
                                            else actions.auditStopDictionaryNetwork(group.ssid)
                                        }
                                        else if (wpsAuditMode) actions.auditOpenWpsNetwork(
                                            group.ssid, group.savedWifiList.isNotEmpty(), group.networks)
                                        else actions.auditOpenNetwork(group.ssid, group.savedWifiList.isNotEmpty())
                                        return@WifiGroupCard
                                    }
                                    dismissConnectSheet = false
                                    connectSheetContent = ConnectWifiSheetContent.Network(
                                        ssid = group.ssid,
                                        hasSavedConfiguration = group.savedWifiList.isNotEmpty(),
                                        allowNetworkCardTest = selectedMode == WifiMode.NORMAL,
                                    )
                                },
                                onWpsPin = { actions.openWpsPin(group.ssid) },
                                onDisconnect = {
                                    group.connection?.networkId?.let(actions::disconnectCurrentNetwork)
                                },
                                onUpdateConfig = { networkId, patch ->
                                    actions.updateWifiConfig(networkId, patch)
                                },
                                footer = if (auditActive) {
                                    {
                                        if (wpsAuditMode) {
                                            WpsAuditInlines(records = wpsRecords) { record ->
                                                actions.auditStopNetwork(record.ssid, record.mac)
                                            }
                                        } else {
                                            DictionaryAuditInlineGroups(
                                                groups = dictMemberGroups,
                                                onStopGroup = { groupId -> actions.auditStopGroup(group.ssid, groupId) },
                                                onAddMember = { ssid, groupId ->
                                                    actions.auditAddMember(ssid, groupId)
                                                },
                                                onRemoveMember = { recordId -> actions.auditStopRecord(recordId) },
                                            )
                                        }
                                    }
                                } else null,
                        )
                        }
                    }
                }
                if (auditMode) item(key = "audit-manual-ssid") {
                    TextButton("手动输入网络名称", actions::auditManualNetwork, Modifier.fillMaxWidth())
                }
            }
            else -> Box(modifier = Modifier.fillMaxSize())
        }
    }

    selectedGroup?.takeIf { selectedSsid != null }?.let { group ->
        WifiDetailSheet(
            show = showWifiDetailSheet,
            group = group,
            deviceAssessments = deviceAssessments,
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
                    onClearHandshakeTestResult = actions::clearMonitorHandshakeTestResult,
                    onDismiss = { showWifiDetailSheet = false },
                    onExport = { actions.exportMonitorDevicePcap(bssid, mac, it) },
                    onTestHandshake = { id, password -> actions.testMonitorHandshake(bssid, mac, id, password) },
                    onExportHandshake = { id -> actions.exportMonitorHandshakePcap(bssid, mac, id) },
                    onSaveHc22000 = onSaveHc22000,
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
                        delay(
                            CONFIRMATION_DIALOG_TEST_DELAY_MILLIS,
                        )
                        actions.confirmSavedConfigurationConnectivityTest(
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
                                        actions.startSavedNetworkConnectTask(
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
                                        actions.startTemporaryNetworkConnectTask(
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
                                        actions.startNetworkCardConnectTask(
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
                                        actions.saveWifiNetwork(network.ssid, submission.password)
                                    }.onSuccess {
                                        dismissConnectSheet = true
                                    }
                                }
                            }
                            is ConnectWifiSheetSubmission.SaveAndConnect -> {
                                val network = content as? ConnectWifiSheetContent.Network
                                if (network != null) {
                                    runCatching {
                                        val networkId = actions.saveWifiNetwork(
                                            network.ssid,
                                            submission.password,
                                        )
                                        actions.startSavedNetworkConnectTask(
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
            onStop = actions::stopDisplayedTask,
            onDismiss = {
                if (inlineConnectTaskId != null) actions.closeDisplayedTask()
                inlineConnectTaskId = null
                onInlineConnectTaskChanged(null)
                connectSheetContent = null
                dismissConnectSheet = false
                isSubmittingTask = false
            },
        )
    }

}

@Composable
internal fun RunningTaskCard(
    taskId: Long,
    stage: String,
    onClick: () -> Unit,
    title: String = "任务运行中 · #$taskId",
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
                    text = title,
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
internal fun WifiDisabledContent(
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

private fun MonitorDevice.hasCompleteSuccessfulHandshake(): Boolean = handshakes.any { record ->
    record.status == MonitorHandshakeStatus.SUCCESS &&
        record.captureQuality == MonitorHandshakeCaptureQuality.COMPLETE
}

private enum class WifiGroupTagKind { HISTORY, CONNECTED, WPS, WPS_RISK, ACCESS_POINTS, SAVED, HANDSHAKES, HIDDEN_NAMES }

private data class WifiGroupTag(
    val kind: WifiGroupTagKind,
    val text: String,
    val style: TagStyle = TagStyle.Secondary,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
)

// ── 单个 Wi-Fi 卡片 ───────────────────────────────────────────────────────────

@Composable
internal fun WifiGroupCard(
    group: MergedWifiGroup,
    assessments: List<WifiDeviceAssessment>,
    handshakeDeviceCount: Int,
    discoveredHiddenNameCount: Int,
    auditMode: Boolean = false,
    hasAuditHistory: Boolean = false,
    auditRunning: Boolean = false,
    auditResult: AuditRecord? = null,
    onAppendAudit: (() -> Unit)? = null,
    auditStopAll: Boolean = false,
    allowWpsPin: Boolean = false,
    monitorMode: Boolean = false,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    onClick: () -> Unit,
    onConnect: () -> Unit,
    onWpsPin: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onUpdateConfig: (networkId: Int, patch: WifiConfigPatch) -> Unit = { _, _ -> },
) {
    val isConnected = group.isConnected
    val levelIndex = group.signalDbm?.let {
        android.net.wifi.WifiManager.calculateSignalLevel(it, 5)
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

    val tags = buildList {
        if (hasAuditHistory) add(WifiGroupTag(WifiGroupTagKind.HISTORY, "有历史"))
        if (isConnected) add(WifiGroupTag(WifiGroupTagKind.CONNECTED, "已连接"))
        if (group.networks.any { it.supportsWps() }) add(WifiGroupTag(WifiGroupTagKind.WPS, "WPS"))
        if (assessments.any { it.potentiallyVulnerable }) {
            add(WifiGroupTag(WifiGroupTagKind.WPS_RISK, "WPS潜在风险", TagStyle.Tertiary, Icons.Rounded.WarningAmber))
        }
        if (group.accessPointCount > 1) {
            add(WifiGroupTag(WifiGroupTagKind.ACCESS_POINTS, group.accessPointCount.toString(), TagStyle.Tertiary, Icons.Default.Layers))
        }
        if (group.savedWifiList.isNotEmpty()) add(WifiGroupTag(WifiGroupTagKind.SAVED, "已保存", TagStyle.Primary))
        if (handshakeDeviceCount > 0) {
            add(WifiGroupTag(WifiGroupTagKind.HANDSHAKES, "成功握手${handshakeDeviceCount}台", TagStyle.Primary))
        }
        if (discoveredHiddenNameCount > 0) {
            add(WifiGroupTag(WifiGroupTagKind.HIDDEN_NAMES, "探测有名称${discoveredHiddenNameCount}个", TagStyle.Primary))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column {
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
                    tags.forEach { tag ->
                        key(tag.kind) {
                            when (tag.kind) {
                                WifiGroupTagKind.CONNECTED -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(4.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = contentColor.copy(alpha = 0.1f),
                                    ) {
                                        Text(
                                            text = tag.text,
                                            style = MiuixTheme.textStyles.footnote2,
                                            fontWeight = FontWeight.Medium,
                                            color = contentColor,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                else -> TagItem(text = tag.text, style = tag.style, icon = tag.icon)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = group.signalDisplay,
                    style = MiuixTheme.textStyles.footnote1,
                    lineHeight = 16.sp,
                    color = contentColor.copy(alpha = 0.7f),
                )
                auditResult?.let { result ->
                    Text(
                        text = when (result.state) {
                            PskSecurityAuditNetworkState.SUCCEEDED -> "连接成功：${result.password}"
                            PskSecurityAuditNetworkState.STOPPED -> "执行中断(index=${result.lastAttemptIndex})"
                            else -> "执行异常，请查看输出"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        lineHeight = 16.sp,
                        color = if (result.state == PskSecurityAuditNetworkState.SUCCEEDED)
                            MiuixTheme.colorScheme.primary else Color.Black,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            WifiGroupCardActions(
                group = group,
                isConnected = isConnected,
                auditMode = auditMode,
                auditRunning = auditRunning,
                onAppendAudit = onAppendAudit,
                buttonContainerColor = MiuixTheme.colorScheme.primary.takeIf { isConnected },
                buttonContentColor = MiuixTheme.colorScheme.onPrimary.takeIf { isConnected },
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onOpenDetail = { onClick() },
                monitorMode = monitorMode,
                allowWpsPin = allowWpsPin,
                onWpsPin = onWpsPin,
                onUpdateConfig = onUpdateConfig,
            )
        }

        footer?.invoke(this)
        }
    }
}
