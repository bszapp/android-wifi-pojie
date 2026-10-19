package io.github.bszapp.wifitoolbox.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorPcapExportResult
import io.github.bszapp.wifitoolbox.ui.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.ui.screen.ListPageActions
import io.github.bszapp.wifitoolbox.ui.screen.ListPageContent
import io.github.bszapp.wifitoolbox.ui.screen.ListPageState
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.WpsPbcTaskInput
import io.github.bszapp.wifitoolbox.contract.task.AuditRecordKind
import io.github.bszapp.wifitoolbox.contract.settings.AuditLogPanelConfig
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiListDataSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode
import io.github.bszapp.wifitoolbox.ui.model.resolveWifiListDisplayMode
import io.github.bszapp.wifitoolbox.ui.navigation.Route
import io.github.bszapp.wifitoolbox.ui.widget.WifiList

/**
 * 列表页薄壳：Android 侧 ViewModel flow 收集 + ActivityResult 文件导出胶水。
 * 全部界面在 [ListPageContent]；预览/无头渲染可直接构造 state + fake actions。
 */
@Composable
fun ListScreen(
    viewModel: DefaultViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val navigator = io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator.current
    val wifiState by viewModel.wifiList.state.collectAsStateWithLifecycle()
    val isSendingScanRequest by
        viewModel.wifiList.isSendingScanRequest.collectAsStateWithLifecycle()
    val modeState by
        viewModel.wifiList.modeState.collectAsStateWithLifecycle()
    val currentTask by viewModel.currentTask.collectAsStateWithLifecycle()
    val displayedTask by viewModel.displayedTask.collectAsStateWithLifecycle()
    val taskState by viewModel.tasks.state.collectAsStateWithLifecycle()
    val wpsPinForm by viewModel.wpsPinForm.collectAsStateWithLifecycle()
    val submode by viewModel.pskSecurityAudit.submode.collectAsStateWithLifecycle()
    val auditTasks by viewModel.pskSecurityAudit.auditTasks.collectAsStateWithLifecycle()
    val auditConfig by viewModel.pskSecurityAudit.config.collectAsStateWithLifecycle()
    val wpsAuditConfig by viewModel.pskSecurityAudit.wpsConfig.collectAsStateWithLifecycle()
    val logPanel by viewModel.pskSecurityAudit.logPanelConfig.collectAsStateWithLifecycle()

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

    val state = ListPageState(
        wifiState = wifiState,
        isSendingScanRequest = isSendingScanRequest,
        modeState = modeState,
        currentTask = currentTask,
        displayedTask = displayedTask,
        wpsPinForm = wpsPinForm,
        submode = submode,
        auditTasks = auditTasks,
        auditConfig = auditConfig,
        wpsAuditConfig = wpsAuditConfig,
        logPanel = logPanel,
        runningWpsPbcTask = taskState.tasks.values.map { it.snapshot }.filter {
            it.state == TaskExecutionState.RUNNING && it.request.payload is TaskRequestPayload.WpsPbc
        }.maxByOrNull { it.taskId },
    )
    val actions = remember(viewModel) {
        object : ListPageActions {
            override fun pushRoute(route: Route) {
                navigator.push(route)
            }

            override fun startScan() = viewModel.wifiList.startScan()
            override fun displayTask(taskId: Long) = viewModel.displayTask(taskId)
            override fun setMode(mode: WifiMode) = viewModel.wifiList.setMode(mode)
            override fun setWifiListDataSource(source: WifiListDataSource) =
                viewModel.wifiList.setWifiListDataSource(source)

            override fun toggleSmartInterfaces() = viewModel.wifiList.toggleSmartInterfaces()
            override fun selectSubmode(submode: NormalWifiSubmode) =
                viewModel.pskSecurityAudit.selectSubmode(submode)

            override fun configureAudit() {
                val displayMode = resolveWifiListDisplayMode(
                    viewModel.wifiList.modeState.value?.mode,
                    viewModel.pskSecurityAudit.submode.value,
                    viewModel.currentTask.value,
                )
                viewModel.pskSecurityAudit.configure(displayMode.submode)
            }
            override suspend fun startWpsPbcTask(input: WpsPbcTaskInput): Long = viewModel.startWpsPbcTask(input)
            override suspend fun startUsbMonitorTask() = viewModel.startUsbMonitorTask()

            override fun stopUsbMonitorTask() = viewModel.stopUsbMonitorTask()
            override fun enterMonitorMode(command: String) =
                viewModel.wifiList.enterMonitorMode(command)

            override fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int, hopping: Boolean) =
                viewModel.wifiList.setMonitorCapture(enabled, frequencyMhz, hopping)

            override fun clearMonitorCapture(handshakesOnly: Boolean) =
                viewModel.wifiList.clearMonitorCapture(handshakesOnly)

            override fun interruptModeSwitch(operationId: Long) =
                viewModel.wifiList.interruptModeSwitch(operationId)

            override fun interruptMonitorClear(operationId: Long) =
                viewModel.wifiList.interruptMonitorClear(operationId)

            override fun exportAllMonitorPcap() = viewModel.wifiList.exportAllMonitorPcap()

            override fun startWpsPin(pin: String, localMac: String, interfaceName: String) =
                viewModel.startWpsPin(pin, localMac, interfaceName)

            override fun dismissWpsPinForm() = viewModel.dismissWpsPinForm()
            override fun wpsPinFormDismissed() = viewModel.wpsPinFormDismissed()
            override fun stopDisplayedTask() = viewModel.stopDisplayedTask()
            override fun closeDisplayedTask() = viewModel.closeDisplayedTask()
            override fun updateDisplayedTask(update: TaskUpdateRequest) =
                viewModel.updateDisplayedTask(update)

            override fun connectCapturedWpsNetwork(network: io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetwork) =
                viewModel.connectCapturedWpsNetwork(network)

            override fun auditStopRecord(recordId: String) = viewModel.pskSecurityAudit.stopRecord(recordId)
            override fun auditStopDictionaryNetwork(ssid: String) =
                viewModel.pskSecurityAudit.stopDictionaryNetwork(ssid)

            override fun auditSetDetailedLogging(kind: AuditRecordKind, enabled: Boolean) =
                viewModel.pskSecurityAudit.setDetailedLogging(kind, enabled)

            override fun auditChangeLogPanel(config: AuditLogPanelConfig) =
                viewModel.pskSecurityAudit.changeLogPanel(config)

            override fun auditClearLogPanel(taskId: Long) = viewModel.pskSecurityAudit.clearLogPanel(taskId)

            override fun auditInspect(recordId: String) = viewModel.pskSecurityAudit.inspect(recordId)
            override fun auditInspectWps(recordId: String) = viewModel.pskSecurityAudit.inspectWps(recordId)
        }
    }

    ListPageContent(
        state = state,
        actions = actions,
        bottomInnerPadding = bottomInnerPadding,
        // 显式传入 ViewModel 的列表插槽：NavDisplay 的条目级 ViewModelStore 会让
        // WifiList 内部默认的 viewModel() 解析出另一个实例（submode 等状态隔离，
        // 卡片因此显示连接/断开），必须复用 ListScreen 的同一实例。
        wifiListContent = { modifier, listState, contentPadding, onInlineConnectTaskChanged, onSave ->
            WifiList(
                modifier = modifier,
                vm = viewModel,
                listState = listState,
                contentPadding = contentPadding,
                onInlineConnectTaskChanged = onInlineConnectTaskChanged,
                onSaveHc22000 = onSave,
            )
        },
        onSaveHc22000 = { content, fileName ->
            pendingHc22000Export = PendingHc22000Export(content, fileName)
            hc22000SaveLauncher.launch(fileName)
        },
    )
}

private data class PendingHc22000Export(
    val content: String,
    val fileName: String,
)
