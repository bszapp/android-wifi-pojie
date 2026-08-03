@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import android.net.wifi.WifiConfiguration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorFrameGroupStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorFrameSubtypeStatistics
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeFailureReason
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStep
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestOutcome
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestResult
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSecurityProtocol
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSignalStatistics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private enum class DeviceSheetPage {
    DETAILS,
    EXPORT,
}

@Composable
fun MonitorDeviceDetailSheet(
    accessPoint: MonitorAccessPoint,
    device: MonitorDevice,
    savedNetworks: List<WifiConfiguration>,
    handshakeTestResults: SharedFlow<MonitorHandshakeTestResult>,
    onDismiss: () -> Unit,
    onExport: (Set<String>) -> Unit,
    onTestHandshake: (handshakeId: String, password: String) -> String,
    onExportHandshake: (handshakeId: String) -> String,
) {
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val expandedGroups = remember(accessPoint.bssid, device.mac) {
        mutableStateMapOf<String, Boolean>()
    }
    var page by remember(accessPoint.bssid, device.mac) { mutableStateOf(DeviceSheetPage.DETAILS) }
    var selectedSubtypeIds by remember(accessPoint.bssid, device.mac) {
        mutableStateOf(emptySet<String>())
    }
    val totalPacketCount = device.frameGroups.sumOf(MonitorFrameGroupStatistics::packetCount)
    val totalByteCount = device.frameGroups.sumOf(MonitorFrameGroupStatistics::byteCount)
    val savedConfiguration = remember(savedNetworks, accessPoint.ssid, accessPoint.securityProtocols) {
        findSavedWpaPskConfiguration(accessPoint, savedNetworks)
    }
    val canTestHandshake = remember(accessPoint.ssid, accessPoint.securityProtocols) {
        !accessPoint.ssid.isNullOrBlank() &&
            (MonitorSecurityProtocol.WPA in accessPoint.securityProtocols ||
                MonitorSecurityProtocol.WPA2 in accessPoint.securityProtocols)
    }
    var handshakeTestTarget by remember(accessPoint.bssid, device.mac) {
        mutableStateOf<MonitorHandshakeRecord?>(null)
    }
    var handshakeTestPassword by remember(accessPoint.bssid, device.mac) {
        mutableStateOf("")
    }
    var pendingHandshakeTestRequestId by remember(accessPoint.bssid, device.mac) {
        mutableStateOf<String?>(null)
    }
    var handshakeTestOutcome by remember(accessPoint.bssid, device.mac) {
        mutableStateOf<MonitorHandshakeTestOutcome?>(null)
    }
    var handshakeExportTarget by remember(accessPoint.bssid, device.mac) {
        mutableStateOf<MonitorHandshakeRecord?>(null)
    }
    var handshakeExportConsent by remember(accessPoint.bssid, device.mac) {
        mutableStateOf(false)
    }

    LaunchedEffect(handshakeTestResults) {
        handshakeTestResults.collect { result ->
            if (result.requestId == pendingHandshakeTestRequestId) {
                pendingHandshakeTestRequestId = null
                handshakeTestOutcome = result.outcome.takeUnless {
                    it == MonitorHandshakeTestOutcome.FAILED
                }
            }
        }
    }

    OverlayBottomSheet(
        show = true,
        title = if (page == DeviceSheetPage.DETAILS) "设备详情" else "导出 PCAP",
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(bottom = bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                DeviceSheetPage.DETAILS -> {
                    item {
                        SmallTitle(text = "设备")
                        Card {
                            device.name?.let { name ->
                                BasicComponent(title = "设备名称或型号", summary = name)
                            }
                            BasicComponent(title = "设备 MAC", summary = device.mac)
                        }
                    }
                    item {
                        SmallTitle(text = "接入点")
                        Card {
                            BasicComponent(
                                title = "网络名称",
                                summary = accessPoint.ssid ?: "<未知网络>",
                            )
                            BasicComponent(title = "接入点 MAC", summary = accessPoint.bssid)
                        }
                    }
                    item {
                        SmallTitle(text = "实时网速")
                        Card {
                            BasicComponent(
                                title = "上传",
                                summary = formatBytesPerSecond(
                                    device.realtime.uploadBytesPerSecond,
                                ),
                            )
                            BasicComponent(
                                title = "下载",
                                summary = formatBytesPerSecond(
                                    device.realtime.downloadBytesPerSecond,
                                ),
                            )
                        }
                    }
                    item {
                        SmallTitle(text = "信号分析")
                        Card {
                            SignalComponents("目标设备信号", device.realtime.signal)
                            SignalComponents("接入点信号", accessPoint.signal)
                        }
                    }
                    item {
                        SmallTitle(text = "802.11 帧统计")
                        Card {
                            BasicComponent(
                                title = "全部数据",
                                endActions = {
                                    FrameStatisticsValues(totalByteCount, totalPacketCount)
                                },
                            )
                        }
                    }
                    item {
                        FrameGroupList(
                            groups = device.frameGroups,
                            expandedGroups = expandedGroups,
                            selectedSubtypeIds = null,
                            onSelectedSubtypeIdsChange = {},
                        )
                    }
                    item {
                        SmallTitle(text = "握手包")
                        HandshakeRecordsCard(
                            records = device.handshakes,
                            canTestHandshake = canTestHandshake,
                            testing = pendingHandshakeTestRequestId != null,
                            onTest = { record ->
                                handshakeTestPassword = savedConfiguration
                                    ?.preSharedKey
                                    ?.removeSurrounding("\"")
                                    ?.takeUnless { it == "*" }
                                    .orEmpty()
                                handshakeTestTarget = record
                            },
                            onExport = { record ->
                                handshakeExportConsent = false
                                handshakeExportTarget = record
                            },
                        )
                    }
                    item {
                        Card {
                            BasicComponent(
                                title = "导出此设备 PCAP",
                                summary = "按 802.11 帧类型选择导出内容",
                                startAction = {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                        tint = MiuixTheme.colorScheme.onBackground,
                                    )
                                },
                                onClick = {
                                    selectedSubtypeIds = device.frameGroups
                                        .flatMap(MonitorFrameGroupStatistics::subtypes)
                                        .map(MonitorFrameSubtypeStatistics::id)
                                        .toSet()
                                    page = DeviceSheetPage.EXPORT
                                },
                            )
                        }
                    }
                }

                DeviceSheetPage.EXPORT -> {
                    item {
                        Text(
                            text = "选择需要写入导出文件的帧子类型。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    item {
                        Card {
                            BasicComponent(
                                title = "全部数据",
                                endActions = {
                                    FrameStatisticsValues(totalByteCount, totalPacketCount)
                                },
                            )
                        }
                    }
                    item {
                        FrameGroupList(
                            groups = device.frameGroups,
                            expandedGroups = expandedGroups,
                            selectedSubtypeIds = selectedSubtypeIds,
                            onSelectedSubtypeIdsChange = { selectedSubtypeIds = it },
                        )
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                text = "返回",
                                onClick = { page = DeviceSheetPage.DETAILS },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = "导出",
                                onClick = { onExport(selectedSubtypeIds) },
                                enabled = selectedSubtypeIds.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }
        }
    }

    OverlayDialog(
        show = handshakeTestTarget != null,
        title = "校验握手包",
        summary = "输入用于校验这次 WPA/WPA2 握手的密码。",
        onDismissRequest = {
            handshakeTestTarget = null
            handshakeTestPassword = ""
        },
        content = {
            val target = handshakeTestTarget
            if (target != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TextField(
                        value = handshakeTestPassword,
                        onValueChange = { handshakeTestPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "密码",
                        maxLines = 1,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "取消",
                            onClick = {
                                handshakeTestTarget = null
                                handshakeTestPassword = ""
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "执行校验",
                            onClick = {
                                pendingHandshakeTestRequestId = onTestHandshake(
                                    target.id,
                                    handshakeTestPassword,
                                )
                                handshakeTestTarget = null
                                handshakeTestPassword = ""
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        },
    )

    OverlayDialog(
        show = handshakeTestOutcome != null,
        title = when (handshakeTestOutcome) {
            MonitorHandshakeTestOutcome.MATCHED -> "校验通过"
            MonitorHandshakeTestOutcome.NOT_MATCHED -> "密码不匹配"
            else -> "握手包校验"
        },
        onDismissRequest = { handshakeTestOutcome = null },
        content = {
            TextButton(
                text = "确定",
                onClick = { handshakeTestOutcome = null },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        },
    )

    val exportTarget = handshakeExportTarget?.let { selected ->
        device.handshakes.firstOrNull { it.id == selected.id } ?: selected
    }
    val handshakeExportDetailsMaxHeight = minOf(
        360.dp,
        LocalWindowInfo.current.containerDpSize.height * 0.42f,
    )
    OverlayDialog(
        show = exportTarget != null,
        title = "确认导出握手包",
        summary = "将导出这一次连接过程中用于诊断和校验的必要 802.11 数据包。",
        onDismissRequest = {
            handshakeExportTarget = null
            handshakeExportConsent = false
        },
        content = {
            if (exportTarget != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = handshakeExportDetailsMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .scrollEndHaptic()
                            .overScrollVertical(),
                    ) {
                        Card {
                            BasicComponent(
                                title = "网络名称",
                                summary = accessPoint.ssid ?: "<未知网络>",
                            )
                            BasicComponent(title = "接入点 MAC", summary = accessPoint.bssid)
                            BasicComponent(title = "目标设备 MAC", summary = device.mac)
                            BasicComponent(
                                title = "开始时间",
                                summary = formatHandshakeStartTime(exportTarget.startUnixMillis),
                            )
                            BasicComponent(
                                title = "持续时间",
                                summary = formatHandshakeDuration(exportTarget.durationMillis),
                            )
                            BasicComponent(
                                title = "握手结果",
                                summary = handshakeStatusText(exportTarget),
                            )
                            BasicComponent(
                                title = "已捕获阶段",
                                summary = exportTarget.capturedSteps
                                    .joinToString("、", transform = ::handshakeStepText)
                                    .ifEmpty { "暂无" },
                            )
                            exportTarget.failedAtStep?.let { failedAtStep ->
                                BasicComponent(
                                    title = "失败阶段",
                                    summary = handshakeStepText(failedAtStep),
                                )
                            }
                            BasicComponent(
                                title = "M2 捕获次数",
                                summary = exportTarget.m2AttemptCount.toString(),
                            )
                            BasicComponent(
                                title = "导出包数量",
                                summary = exportTarget.exportPacketCount.toString(),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { handshakeExportConsent = !handshakeExportConsent }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = if (handshakeExportConsent) {
                                ToggleableState.On
                            } else {
                                ToggleableState.Off
                            },
                            onClick = {
                                handshakeExportConsent = !handshakeExportConsent
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "我已拥有目标设备或目标接入点的所有权，并知晓握手包仅用于诊断连接情况使用",
                            modifier = Modifier.weight(1f),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "取消",
                            onClick = {
                                handshakeExportTarget = null
                                handshakeExportConsent = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = "导出",
                            enabled = handshakeExportConsent,
                            onClick = {
                                onExportHandshake(exportTarget.id)
                                handshakeExportTarget = null
                                handshakeExportConsent = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun HandshakeRecordsCard(
    records: List<MonitorHandshakeRecord>,
    canTestHandshake: Boolean,
    testing: Boolean,
    onTest: (MonitorHandshakeRecord) -> Unit,
    onExport: (MonitorHandshakeRecord) -> Unit,
) {
    Card {
        if (records.isEmpty()) {
            BasicComponent(title = "暂无握手包")
        } else {
            records.forEachIndexed { index, record ->
                BasicComponent(
                    title = "握手包 ${index + 1}",
                    summary = buildString {
                        append("开始时间：")
                        append(formatHandshakeStartTime(record.startUnixMillis))
                        append("\n持续时间：")
                        append(formatHandshakeDuration(record.durationMillis))
                        append(" · ")
                        append(handshakeStatusText(record))
                        record.failedAtStep?.let { failedAtStep ->
                            append("\n失败阶段：")
                            append(handshakeStepText(failedAtStep))
                        }
                    },
                    endActions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (canTestHandshake && record.canValidate) {
                                TextButton(
                                    text = if (testing) "校验中" else "校验",
                                    enabled = !testing,
                                    onClick = { onTest(record) },
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                            }
                            TextButton(
                                text = "立即导出",
                                onClick = { onExport(record) },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    },
                    bottomAction = if (
                        record.status == MonitorHandshakeStatus.SUCCESS &&
                        !record.validationDataComplete
                    ) {
                        {
                            Text(
                                text = "缺失数据",
                                color = MaterialTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.body2,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun findSavedWpaPskConfiguration(
    accessPoint: MonitorAccessPoint,
    savedNetworks: List<WifiConfiguration>,
): WifiConfiguration? {
    val ssid = accessPoint.ssid?.takeIf(String::isNotBlank) ?: return null
    if (
        MonitorSecurityProtocol.WPA !in accessPoint.securityProtocols &&
        MonitorSecurityProtocol.WPA2 !in accessPoint.securityProtocols
    ) {
        return null
    }
    return savedNetworks.firstOrNull { configuration ->
        val savedSsid = configuration.SSID?.removeSurrounding("\"")
        val password = configuration.preSharedKey?.removeSurrounding("\"")
        val isWpaPsk =
            configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) ||
                configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        savedSsid == ssid && isWpaPsk && !password.isNullOrBlank() && password != "*"
    }
}

private fun formatHandshakeStartTime(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(value))

private fun formatHandshakeDuration(value: Long): String =
    if (value < 1000L) "$value ms" else "%.3f 秒".format(value / 1000.0)

private fun handshakeStatusText(record: MonitorHandshakeRecord): String = when (record.status) {
    MonitorHandshakeStatus.IN_PROGRESS -> "握手过程中"
    MonitorHandshakeStatus.SUCCESS -> "握手成功"
    MonitorHandshakeStatus.FAILED -> when (record.failureReason) {
        MonitorHandshakeFailureReason.ROUTER_REJECTED_CONNECTION ->
            "握手失败（路由器拒绝接入）"
        MonitorHandshakeFailureReason.M2_RETRY_LIMIT_EXCEEDED ->
            "握手失败（M2 重试次数过多，${record.m2AttemptCount} 次）"
        MonitorHandshakeFailureReason.DISCONNECTED_AFTER_M2 ->
            "握手失败（在 M2 后断开）"
        MonitorHandshakeFailureReason.DISCONNECTED_DURING_HANDSHAKE ->
            "握手失败（握手过程中断开）"
        MonitorHandshakeFailureReason.REPLACED_BY_NEW_ATTEMPT ->
            "握手失败（被新的连接尝试替代）"
        null -> "握手失败"
    }
}

private fun handshakeStepText(step: MonitorHandshakeStep): String = when (step) {
    MonitorHandshakeStep.AUTHENTICATION -> "Authentication"
    MonitorHandshakeStep.ASSOCIATION -> "Association/Reassociation"
    MonitorHandshakeStep.EAPOL_MESSAGE_1 -> "EAPOL M1"
    MonitorHandshakeStep.EAPOL_MESSAGE_2 -> "EAPOL M2"
    MonitorHandshakeStep.EAPOL_MESSAGE_3 -> "EAPOL M3"
    MonitorHandshakeStep.EAPOL_MESSAGE_4 -> "EAPOL M4"
    MonitorHandshakeStep.DISCONNECTION -> "Deauthentication/Disassociation"
}

@Composable
private fun FrameGroupList(
    groups: List<MonitorFrameGroupStatistics>,
    expandedGroups: MutableMap<String, Boolean>,
    selectedSubtypeIds: Set<String>?,
    onSelectedSubtypeIdsChange: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { group ->
            FrameGroupCard(
                group = group,
                expanded = expandedGroups[group.id] == true,
                onExpandedChange = { expandedGroups[group.id] = it },
                selectedSubtypeIds = selectedSubtypeIds,
                onSelectedSubtypeIdsChange = onSelectedSubtypeIdsChange,
            )
        }
    }
}

@Composable
private fun FrameGroupCard(
    group: MonitorFrameGroupStatistics,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedSubtypeIds: Set<String>?,
    onSelectedSubtypeIdsChange: (Set<String>) -> Unit,
) {
    val selectableIds = group.subtypes.map(MonitorFrameSubtypeStatistics::id).toSet()
    val groupToggleState = when {
        selectedSubtypeIds == null || selectableIds.isEmpty() -> ToggleableState.Off
        selectableIds.all(selectedSubtypeIds::contains) -> ToggleableState.On
        selectableIds.any(selectedSubtypeIds::contains) -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    val toggleGroup = {
        if (selectedSubtypeIds != null && selectableIds.isNotEmpty()) {
            onSelectedSubtypeIdsChange(
                if (groupToggleState == ToggleableState.On) {
                    selectedSubtypeIds - selectableIds
                } else {
                    selectedSubtypeIds + selectableIds
                },
            )
        }
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "MonitorFrameGroupArrow",
    )

    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (selectedSubtypeIds == null) {
                        onExpandedChange(!expanded)
                    } else {
                        toggleGroup()
                    }
                }
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedSubtypeIds != null) {
                Checkbox(
                    state = groupToggleState,
                    onClick = toggleGroup,
                    enabled = selectableIds.isNotEmpty(),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = group.displayName,
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                enabled = group.subtypes.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开",
                    modifier = Modifier.size(20.dp).rotate(arrowRotation),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            FrameStatisticsValues(group.byteCount, group.packetCount)
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                group.subtypes.forEach { subtype ->
                    FrameSubtypeRow(
                        subtype = subtype,
                        selected = selectedSubtypeIds?.contains(subtype.id),
                        onSelectedChange = { selected ->
                            if (selectedSubtypeIds != null) {
                                onSelectedSubtypeIdsChange(
                                    if (selected) {
                                        selectedSubtypeIds + subtype.id
                                    } else {
                                        selectedSubtypeIds - subtype.id
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FrameSubtypeRow(
    subtype: MonitorFrameSubtypeStatistics,
    selected: Boolean?,
    onSelectedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { modifier ->
                if (selected == null) modifier else modifier.clickable {
                    onSelectedChange(!selected)
                }
            }
            .padding(start = if (selected == null) 24.dp else 16.dp, end = 16.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = { onSelectedChange(!selected) },
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = subtype.displayName,
            modifier = Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
        )
        FrameStatisticsValues(subtype.byteCount, subtype.packetCount)
    }
}

@Composable
private fun FrameStatisticsValues(byteCount: Long, packetCount: Long) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = formatMonitorByteCount(byteCount),
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            style = MiuixTheme.textStyles.body2,
        )
        Text(
            text = "$packetCount 个包",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

@Composable
private fun SignalComponents(title: String, signal: MonitorSignalStatistics?) {
    if (signal == null) {
        BasicComponent(title = title, summary = "未知")
        return
    }
    BasicComponent(title = title, summary = "${signal.latestDbm} dBm")
    BasicComponent(
        title = "$title · 最近一秒",
        summary = if (signal.sampleCount > 0) {
            "平均 ${formatDbm(signal.averageDbm)} · " +
                "范围 ${signal.minimumDbm}～${signal.maximumDbm} dBm · " +
                "${signal.sampleCount} 个样本"
        } else {
            "暂无最近一秒样本"
        },
    )
}

private fun formatDbm(value: Float): String = "%.1f dBm".format(value)

private fun formatBytesPerSecond(value: Long): String = "${formatMonitorByteCount(value)}/s"
