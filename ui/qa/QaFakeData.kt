// UI QA 假数据与桩动作：驱动 WifiListContent / ListPageContent 的全部真实 UI 逻辑。
package qa

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskConfig
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.AuditHistoryRecord
import io.github.bszapp.wifitoolbox.contract.task.AuditRecordKind
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatFinding
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelState
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelStep
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatNetwork
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskState
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditPhase
import io.github.bszapp.wifitoolbox.ui.dictionary.DictionaryResource
import io.github.bszapp.wifitoolbox.ui.hashcat.HashcatSheetPage
import io.github.bszapp.wifitoolbox.ui.hashcat.HashcatSheetState
import io.github.bszapp.wifitoolbox.contract.settings.AuditLogPanelConfig
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.task.TaskUpdateRequest
import io.github.bszapp.wifitoolbox.contract.task.WpsCapturedNetwork
import io.github.bszapp.wifitoolbox.contract.task.WpsPbcTaskInput
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChannel
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDecryptionStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDeviceProtocol
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDeviceRealtime
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeCaptureQuality
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSecurityProtocol
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSignalStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSsidVisibility
import io.github.bszapp.wifitoolbox.contract.wifilist.SystemScanData
import io.github.bszapp.wifitoolbox.contract.wifilist.SavedWifiList
import io.github.bszapp.wifitoolbox.contract.wifilist.UnderlyingScanData
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiListDataSource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode
import io.github.bszapp.wifitoolbox.ui.navigation.Route
import io.github.bszapp.wifitoolbox.ui.screen.ListPageActions
import io.github.bszapp.wifitoolbox.ui.screen.ListPageState
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiListPageActions
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiListPageState

// ── 框架类型假数据 ─────────────────────────────────────────────────────────────

fun qaScanResult(
    ssid: String?,
    bssid: String,
    caps: String,
    frequency: Int = 2412,
    level: Int = -60,
): ScanResult = ScanResult().apply {
    SSID = ssid
    BSSID = bssid
    capabilities = caps
    this.frequency = frequency
    this.level = level
    timestamp = 0L
    channelWidth = 1
}

fun qaScanResults(): List<ScanResult> = listOf(
    qaScanResult("ChinaNet-Home", "8c:de:f9:0b:1a:20", "[WPA2-PSK-CCMP][ESS][WPS]", 2412, -42),
    qaScanResult("ChinaNet-Home", "8c:de:f9:0b:1a:21", "[WPA2-PSK-CCMP][ESS][WPS]", 2437, -51),
    qaScanResult("ChinaNet-Home-5G", "8c:de:f9:0b:1a:30", "[WPA2-PSK-CCMP][ESS][WPS]", 5180, -57),
    qaScanResult("Xiaomi_Router", "50:64:2b:d1:ce:94", "[WPA2-PSK-CCMP][SAE][ESS]", 2462, -53),
    qaScanResult("TP-LINK_A302", "1c:7c:59:a3:02:11", "[WPA-PSK-CCMP][WPA2-PSK-CCMP][WPS][ESS]", 2437, -68),
    qaScanResult("CMCC-Office", "66:3b:97:af:9e:01", "[WPA2-PSK-CCMP][ESS]", 2462, -76),
    qaScanResult("OpenCoffee", "9a:4e:02:73:1d:19", "[ESS]", 2412, -71),
    qaScanResult(null, "3e:5d:70:22:ae:44", "[WPA2-PSK-CCMP][ESS]", 2412, -65),
)

fun qaScanResult_OtherChannels(): ScanResult = qaScanResult("TP-LINK_A302", "1c:7c:59:a3:02:11", "[WPA-PSK-CCMP][WPA2-PSK-CCMP][WPS][ESS]", 2462, -73)

private fun qaSavedConfiguration(
    ssid: String,
    networkId: Int,
    status: Int = WifiConfiguration.Status.ENABLED,
): WifiConfiguration = WifiConfiguration().apply {
    SSID = "\"$ssid\""
    this.networkId = networkId
    this.status = status
    preSharedKey = "\"local-password\""
}

/** 已保存网络：ChinaNet-Home（含当前连接）、OpenCoffee。 */
fun qaSavedNetworksNetList(): List<WifiConfiguration> = listOf(
    qaSavedConfiguration("ChinaNet-Home", 3, WifiConfiguration.Status.CURRENT),
    qaSavedConfiguration("OpenCoffee", 7),
)

fun qaSavedNetworkList(): SavedWifiList = SavedWifiList(qaSavedNetworksNetList())

fun qaConnectedInfo(): WifiInfo = WifiInfo().apply {
    ssid = "\"ChinaNet-Home\""
    bssid = "8c:de:f9:0b:1a:20"
    networkId = 3
    frequency = 2412
    rssi = -42
    linkSpeed = 433
}

fun qaWifiStateScan(): WifiState =
    WifiState.System(SystemScanData.Enabled(qaScanResults(), isScanning = false, connection = null))

fun qaWifiStateConnected(): WifiState =
    WifiState.System(SystemScanData.Enabled(qaScanResults(), isScanning = false, connection = qaConnectedInfo()))

fun qaWifiStateEmpty(): WifiState =
    WifiState.System(SystemScanData.Enabled(emptyList(), isScanning = false, connection = null))

fun qaWifiStateDisabled(): WifiState = WifiState.System(SystemScanData.Disabled)

fun qaWifiStateUnderlying(): WifiState =
    WifiState.Underlying(UnderlyingScanData(qaScanResults(), isScanning = false, connection = null))

fun qaWifiStateMonitor(capturing: Boolean = false): WifiState =
    WifiState.Monitor(qaScanResults(), isScanning = false)

// ── 监听模式假数据 ─────────────────────────────────────────────────────────────

private fun qaSignal(dbm: Int): MonitorSignalStatistics = MonitorSignalStatistics(
    latestDbm = dbm,
    averageDbm = dbm - 4f,
    minimumDbm = dbm - 12,
    maximumDbm = dbm,
    sampleCount = 28,
    lastSeenUnixMillis = 0L,
)

private fun qaHandshakeRecord(id: String, success: Boolean): MonitorHandshakeRecord = MonitorHandshakeRecord(
    id = id,
    startUnixMillis = 0L,
    durationMillis = if (success) 480L else null,
    status = if (success) MonitorHandshakeStatus.SUCCESS else MonitorHandshakeStatus.IN_PROGRESS,
    canValidate = success,
    captureQuality = MonitorHandshakeCaptureQuality.COMPLETE,
    capturedSteps = emptyList(),
    failedAtStep = null,
    failureReason = null,
    m2AttemptCount = 1,
    exportPacketCount = 0,
    hc22000 = null,
    capturedPacketTypes = emptyList(),
)

private fun qaMonitorDevice(
    mac: String,
    name: String? = null,
    probeOnly: Boolean = false,
    handshakes: List<MonitorHandshakeRecord> = emptyList(),
): MonitorDevice = MonitorDevice(
    mac = mac,
    name = name,
    frameGroups = emptyList(),
    handshakes = handshakes,
    realtime = MonitorDeviceRealtime(),
    probeOnly = probeOnly,
    protocol = MonitorDeviceProtocol.WPA2_PSK,
    decryptionStatus = MonitorDecryptionStatus.READY,
    communicationCount = 128L,
    communicationRevision = 2L,
    parsedCommunicationCount = 96L,
)

fun qaMonitorAccessPoints(): List<MonitorAccessPoint> = listOf(
    MonitorAccessPoint(
        bssid = "8c:de:f9:0b:1a:20",
        ssid = "ChinaNet-Home",
        ssidVisibility = MonitorSsidVisibility.VISIBLE,
        securityProtocols = listOf(MonitorSecurityProtocol.WPA2),
        devices = listOf(
            qaMonitorDevice("aa:bb:cc:01:02:03", name = "user-phone", handshakes = listOf(qaHandshakeRecord("hs-1", success = true))),
            qaMonitorDevice("aa:bb:cc:04:05:06", name = "TV", probeOnly = false),
        ),
    ),
    MonitorAccessPoint(
        bssid = "50:64:2b:d1:ce:94",
        ssid = "Xiaomi_Router",
        ssidVisibility = MonitorSsidVisibility.VISIBLE,
        securityProtocols = listOf(MonitorSecurityProtocol.WPA),
        devices = listOf(qaMonitorDevice("aa:bb:cc:09:0a:0b", name = null, probeOnly = true)),
    ),
)

private fun qaMonitorChannels(): List<MonitorChannel> = listOf(
    MonitorChannel(1, 2412),
    MonitorChannel(6, 2437),
    MonitorChannel(11, 2462),
    MonitorChannel(13, 2472),
)

fun qaNormalModeState(): WifiModeState = WifiModeState(mode = WifiMode.NORMAL, listDataSource = WifiListDataSource.SYSTEM)

fun qaMonitorModeState(capturing: Boolean): WifiModeState = WifiModeState(
    mode = WifiMode.MONITOR,
    listDataSource = WifiListDataSource.SYSTEM,
    capturing = capturing,
    hoppingCapture = false,
    availableChannels = qaMonitorChannels(),
    monitorStatistics = MonitorModeStatistics(
        sessionGeneration = 1L,
        revision = 8L,
        recordedBytes = 1_548_213L,
        channel = if (capturing) 6 else 0,
        frequencyMhz = if (capturing) 2437 else 0,
        accessPoints = if (capturing) qaMonitorAccessPoints() else qaMonitorAccessPoints(),
        disconnections = emptyList(),
        nonHandshakeBytes = 235_980L,
    ),
)

// ── 页面状态快照 ───────────────────────────────────────────────────────────────

fun qaListPageState(
    wifiState: WifiState,
    modeState: WifiModeState? = qaNormalModeState(),
    submode: NormalWifiSubmode = NormalWifiSubmode.SCAN,
    auditTasks: List<TrackedTaskState> = emptyList(),
): ListPageState = ListPageState(
    wifiState = wifiState,
    isSendingScanRequest = false,
    modeState = modeState,
    currentTask = null,
    displayedTask = null,
    wpsPinForm = null,
    submode = submode,
    auditTasks = auditTasks,
)

fun qaWifiListPageState(
    wifiState: WifiState,
    modeState: WifiModeState? = qaNormalModeState(),
    submode: NormalWifiSubmode = NormalWifiSubmode.SCAN,
    auditTasks: List<TrackedTaskState> = emptyList(),
    auditBatch: TrackedTaskState? = null,
    auditHistory: List<AuditHistoryRecord> = emptyList(),
    currentTask: TaskSnapshot? = null,
): WifiListPageState = WifiListPageState(
    wifiState = wifiState,
    savedWifiList = qaSavedNetworkList(),
    modeState = modeState,
    submode = submode,
    auditTasks = auditTasks,
    auditBatch = auditBatch,
    auditHistory = auditHistory,
    currentTask = currentTask,
)

// ── 桩动作 ─────────────────────────────────────────────────────────────────────

class QaListPageActions : ListPageActions {
    override fun pushRoute(route: Route) = Unit
    override fun startScan() = Unit
    override fun displayTask(taskId: Long) = Unit
    override fun setMode(mode: WifiMode) = Unit
    override fun setWifiListDataSource(source: WifiListDataSource) = Unit
    override fun toggleSmartInterfaces() = Unit
    override fun selectSubmode(submode: NormalWifiSubmode) = Unit
    override fun configureAudit() = Unit
    override suspend fun startWpsPbcTask(input: WpsPbcTaskInput): Long = 0L
    override suspend fun startUsbMonitorTask(): Long = 0L
    override fun stopUsbMonitorTask() = Unit
    override fun enterMonitorMode(command: String) = Unit
    override fun setMonitorCapture(enabled: Boolean, frequencyMhz: Int, hopping: Boolean) = Unit
    override fun setMonitorFrequency(frequencyMhz: Int, hopping: Boolean) = Unit
    override fun clearMonitorCapture(handshakesOnly: Boolean) = Unit
    override fun interruptModeSwitch(operationId: Long) = Unit
    override fun interruptMonitorClear(operationId: Long) = Unit
    override fun exportAllMonitorPcap(): String = "qa-none.pcap"
    override fun startWpsPin(pin: String, localMac: String, interfaceName: String) = Unit
    override fun dismissWpsPinForm() = Unit
    override fun wpsPinFormDismissed() = Unit
    override fun stopDisplayedTask() = Unit
    override fun closeDisplayedTask() = Unit
    override fun updateDisplayedTask(update: TaskUpdateRequest) = Unit
    override fun connectCapturedWpsNetwork(network: WpsCapturedNetwork) = Unit
    override fun auditStopRecord(recordId: String) = Unit
    override fun auditStopDictionaryNetwork(ssid: String) = Unit
    override fun auditSetDetailedLogging(kind: AuditRecordKind, enabled: Boolean) = Unit
    override fun auditChangeLogPanel(config: AuditLogPanelConfig) = Unit
    override fun auditClearLogPanel(taskId: Long) = Unit
    override fun auditInspect(recordId: String) = Unit
    override fun auditInspectWps(recordId: String) = Unit
}

// ── 弱密码检测历史假数据 ──────────────────────────────────────────────────────

/** 历史页：成功破解（字典）、未完成（字典）、未完成（第二段PIN）、隐藏网络记录。 */
fun qaAuditHistory(): List<AuditHistoryRecord> = listOf(
    AuditHistoryRecord.Dictionary(
        id = "history-1",
        ssid = "ChinaNet-Home",
        passwords = List(1000) { "candidate-$it" },
        completed = 384,
        password = null,
        updatedAt = 1_760_000_320_000,
        groupElapsedMillis = 26 * 60_000L,
        groupAverageAttemptMillis = 4200,
        dictionaryPosition = 384,
    ),
    AuditHistoryRecord.Dictionary(
        id = "history-2",
        ssid = "Xiaomi_Router",
        passwords = List(500) { "candidate-$it" },
        completed = 500,
        password = "778899123",
        updatedAt = 1_760_000_000_000,
        groupElapsedMillis = 41 * 60_000L,
        groupAverageAttemptMillis = 4900,
        dictionaryPosition = 193,
    ),
    AuditHistoryRecord.Wps(
        id = "history-3",
        ssid = "TP-LINK_A302",
        mac = "1c:7c:59:a3:02:11",
        updatedAt = 1_759_999_640_000,
        phase = WpsSecurityAuditPhase.SECOND_HALF,
        firstHalf = "994411222",
        firstHalfIndex = 10000,
        secondHalfIndex = 412,
        attempts = 10012,
        elapsedMillis = 95 * 60_000L,
        averageAttemptMillis = 560,
        pin = null,
        password = null,
        passwordFoundPhase = null,
    ),
    AuditHistoryRecord.Wps(
        id = "history-4",
        ssid = "",
        mac = "aa:bb:cc:dd:ee:88",
        updatedAt = 1_759_999_000_000,
        phase = WpsSecurityAuditPhase.DONE,
        firstHalf = "7788990011",
        firstHalfIndex = 10000,
        secondHalfIndex = 1000,
        attempts = 20112,
        elapsedMillis = 121 * 60_000L,
        averageAttemptMillis = 560,
        pin = "77889900",
        password = "hidden-wifi-password",
        passwordFoundPhase = WpsSecurityAuditPhase.SECOND_HALF,
    ),
)

// ── WPA Hashcat 假数据 ────────────────────────────────────────────────────────

private const val qaHashcatNetworkLine = "aa:bb:cc:dd:ee:ff"

/** 列表页：运行中（有进度与速度）、已成功（含密码）、已失败（含错误）三条。 */
fun qaHashcatTasks(): List<HashcatTaskSnapshot> = listOf(
    HashcatTaskSnapshot(
        id = "hashcat-1",
        createdAt = 1_760_000_320_000,
        state = HashcatTaskState.RUNNING,
        step = "正在恢复第2个候选批次",
        completed = 184_320,
        total = 1_000_000,
        progressUnit = "BYTES",
        stepCompleted = 640,
        stepTotal = 2048,
        stepUnit = "BYTES",
        remainingSeconds = 42,
        speed = 183_200,
        devices = "Intel Iris Xe (CPU), 8 GiB",
        candidates = "common-wifi-1000.txt [184320-184320] bytes",
        dictionaryNames = listOf("常用Wi-Fi密码1000"),
        computeDurationMillis = 0,
        averageSpeed = 0,
        networks = listOf(HashcatNetwork("ChinaNet-Home", qaHashcatNetworkLine)),
    ),
    HashcatTaskSnapshot(
        id = "hashcat-2",
        createdAt = 1_759_990_000_000,
        state = HashcatTaskState.SUCCEEDED,
        completed = 1_000_000,
        total = 1_000_000,
        progressUnit = "BYTES",
        devices = "Intel Iris Xe (CPU), 8 GiB",
        candidates = "rockyou-top1000.txt [0-1000] bytes",
        dictionaryNames = listOf("RockYou前1000"),
        findings = listOf(
            HashcatFinding("password123", 147),
            HashcatFinding("wifi2024", 388),
        ),
        computeDurationMillis = 123_456,
        averageSpeed = 152_000,
        networks = listOf(HashcatNetwork("Xiaomi_Router", "50:64:2b:d1:ce:94")),
    ),
    HashcatTaskSnapshot(
        id = "hashcat-3",
        createdAt = 1_759_980_000_000,
        state = HashcatTaskState.FAILED,
        step = "内核准备失败",
        completed = 0,
        total = 0,
        devices = "Intel Iris Xe (CPU), 8 GiB",
        dictionaryNames = listOf("常用Wi-Fi密码1000"),
        error = "设备内存不足，无法为本批候选分配缓冲区",
        networks = listOf(HashcatNetwork("CMCC-Office", "66:3b:97:af:9e:01")),
    ),
)

fun qaHashcatDictionaries(): List<DictionaryResource> = listOf(
    DictionaryResource(
        id = "common-wifi-1000",
        name = "常用Wi-Fi密码1000",
        description = "内置普通字典，共1000个候选",
        type = 0,
        content = "candidate",
        author = null,
        version = null,
    ),
    DictionaryResource(
        id = "phone-numbers",
        name = "手机号字典",
        description = "以1开头的11位常见手机号组合",
        type = 0,
        content = "candidate",
        author = "bsz",
        version = "1.2",
    ),
    DictionaryResource(
        id = "city-pinyin",
        name = "地名拼音组合",
        description = "中国主要城市拼音加年份组合",
        type = 0,
        content = "candidate",
        author = null,
        version = null,
    ),
)

fun qaHashcatKernelsCompiling(): HashcatKernelSnapshot = HashcatKernelSnapshot(
    state = HashcatKernelState.COMPILING,
    stage = "正在编译PBKDF2内核（第2步, 共4步）",
    steps = listOf(
        HashcatKernelStep("step-1", "解压内核源码", 1_000L, 6_000L),
        HashcatKernelStep("step-2", "编译PBKDF2内核", 6_000L, null),
    ),
    revision = 1L,
)

fun qaHashcatKernelsFailed(): HashcatKernelSnapshot = HashcatKernelSnapshot(
    state = HashcatKernelState.FAILED,
    stage = "内核编译失败",
    error = "编译器输出超过64KiB限制，请更换工具链或重试",
    revision = 1L,
)

fun qaHashcatSheet(
    page: HashcatSheetPage,
    input: String = "",
    selectedDictionaryIds: Set<String> = emptySet(),
    taskId: String? = null,
    busy: Boolean = false,
): HashcatSheetState = HashcatSheetState(
    show = true,
    page = page,
    input = input,
    selectedDictionaryIds = selectedDictionaryIds,
    taskId = taskId,
    busy = busy,
)

class QaWifiListPageActions : WifiListPageActions {
    override fun setWifiEnabled(enabled: Boolean) = Unit
    override fun disconnectCurrentNetwork(networkId: Int) = Unit
    override fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) = Unit
    override suspend fun saveWifiNetwork(ssid: String, password: String): Int = 0
    override suspend fun startSavedNetworkConnectTask(networkId: Int, type: ConnectWifiTaskType, config: ConnectWifiTaskConfig): Long = 0L
    override suspend fun startTemporaryNetworkConnectTask(ssid: String, password: String, config: ConnectWifiTaskConfig): Long = 0L
    override suspend fun startNetworkCardConnectTask(ssid: String, passwords: List<String>, mac: String?, name: String?, config: ConnectWifiTaskConfig): Long = 0L
    override fun confirmSavedConfigurationConnectivityTest(sheetInstanceId: String, ssid: String, password: String, config: ConnectWifiTaskConfig) = Unit
    override fun displayTask(taskId: Long) = Unit
    override fun consumeWifiQrNetwork() = Unit
    override fun consumeConnectWifiSheetCloseRequest(requestId: Long) = Unit
    override fun auditOpenNetwork(ssid: String, saved: Boolean, append: Boolean) = Unit
    override fun auditOpenWpsNetwork(ssid: String, saved: Boolean, accessPoints: List<ScanResult>) = Unit
    override fun auditStopNetwork(ssid: String, mac: String?) = Unit
    override fun auditStopDictionaryNetwork(ssid: String) = Unit
    override fun auditStopGroup(ssid: String, groupId: String) = Unit
    override fun auditAddMember(ssid: String, groupId: String) = Unit
    override fun auditStopRecord(recordId: String) = Unit
    override fun auditManualNetwork() = Unit
    override fun openWpsPin(ssid: String) = Unit
    override fun clearMonitorHandshakeTestResult() = Unit
    override fun exportMonitorDevicePcap(bssid: String, deviceMac: String, subtypeIds: Set<String>): String = "qa-none.pcap"
    override fun testMonitorHandshake(bssid: String, deviceMac: String, handshakeId: String, password: String) = Unit
    override fun exportMonitorHandshakePcap(bssid: String, deviceMac: String, handshakeId: String): String = "qa-none.pcap"
    override fun stopDisplayedTask() = Unit
    override fun closeDisplayedTask() = Unit
}
