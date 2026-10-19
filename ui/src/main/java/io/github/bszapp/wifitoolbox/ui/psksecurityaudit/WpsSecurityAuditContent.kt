package io.github.bszapp.wifitoolbox.ui.psksecurityaudit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bszapp.wifitoolbox.contract.task.AuditRecord
import io.github.bszapp.wifitoolbox.contract.settings.AuditLogPanelConfig
import io.github.bszapp.wifitoolbox.contract.task.AuditHistoryRecord
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditMacMode
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditNetworkState
import io.github.bszapp.wifitoolbox.contract.task.WpsHandshakeStage
import io.github.bszapp.wifitoolbox.contract.task.WpsPacketDirection
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditConfig
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditPhase
import io.github.bszapp.wifitoolbox.ui.component.LegacyTabRow
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.ui.component.WeakPasswordResult
import io.github.bszapp.wifitoolbox.ui.component.WifiInterfaceSelector
import io.github.bszapp.wifitoolbox.ui.model.PskSecurityAuditUiState
import io.github.bszapp.wifitoolbox.ui.model.WpsAuditSheetPage
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** PIN漏洞检测的配置、目标与详情抽屉，与弱密码检测共用同一份历史数据源。 */
@Composable
fun WpsAuditSheets(model: PskSecurityAuditUiState) {
    val form by model.wpsForm.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val availableInterfaces by model.availableInterfaces.collectAsStateWithLifecycle()
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .8f
    SingleOverlayBottomSheet(show = form.show, title = when (form.page) {
        WpsAuditSheetPage.TARGET -> "PIN漏洞检测"
        WpsAuditSheetPage.ACCESS_POINTS -> "选择接入点"
        WpsAuditSheetPage.CONFIGURATION -> "PIN漏洞检测运行行为"
        WpsAuditSheetPage.DETAIL -> "PIN漏洞检测详情"
        WpsAuditSheetPage.RECOVERY -> "未完成的PIN漏洞检测"
    }, onDismissRequest = model::dismissWps) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                when (form.page) {
                    WpsAuditSheetPage.CONFIGURATION -> item { WpsAuditConfiguration(form.config, model::changeWpsConfig, availableInterfaces) }
                    WpsAuditSheetPage.ACCESS_POINTS -> {
                        item { Text("选择接入点", style = MiuixTheme.textStyles.subtitle) }
                        items(form.accessPoints, key = { it.BSSID }) { accessPoint ->
                            Card(Modifier.fillMaxWidth()) {
                                AuditChoice(accessPoint.BSSID, accessPoint.BSSID.equals(form.selectedMac, true),
                                    { model.selectWpsAccessPoint(accessPoint.BSSID) })
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("频率 ${accessPoint.frequency} 兆赫", style = MiuixTheme.textStyles.footnote1)
                                    Text(if (accessPoint.level == 0) "信号未知" else "信号 ${accessPoint.level} 分贝毫瓦",
                                        style = MiuixTheme.textStyles.footnote1)
                                }
                            }
                        }
                        if (form.accessPoints.isEmpty()) item { Text("当前没有可选择的 WPS 接入点，请重新扫描。") }
                    }
                    WpsAuditSheetPage.TARGET -> {
                        if (form.saved) item {
                            Text("此网络已有保存配置，测试成功后保存配置的密码可能随之变化。", style = MiuixTheme.textStyles.body2)
                        }
                        item { WifiInterfaceSelector(form.config.interfaceName, availableInterfaces,
                            { model.changeWpsConfig(form.config.copy(interfaceName = it)) }, enabled = !form.busy) }
                        if (!form.supportsWps) item { Text("此网络未广播 WPS 能力，无法进行PIN漏洞检测。", style = MiuixTheme.textStyles.body2) }
                        form.selectedMac?.let { mac -> item {
                            Card(Modifier.fillMaxWidth()) {
                                BasicComponent(title = form.ssid.ifEmpty { "隐藏网络" }, summary = "接入点 $mac")
                            }
                        } }
                        if (form.resumeRecordId != null) {
                            item {
                                val previous = history.filterIsInstance<AuditHistoryRecord.Wps>().firstOrNull { it.id == form.resumeRecordId }
                                Text("从上次继续", style = MiuixTheme.textStyles.subtitle)
                                if (previous != null) WpsResumeSummary(previous)
                                else Text("历史记录已不存在，请重新开始", style = MiuixTheme.textStyles.body2)
                                TextButton("重新开始", model::restartWps, Modifier.fillMaxWidth())
                            }
                        }
                        if (form.resumeRecordId == null) {
                            item {
                                Text("起始阶段", style = MiuixTheme.textStyles.subtitle)
                                LegacyTabRow(tabs = listOf("PixieDust", "前四位", "后三位"),
                                    selectedTabIndex = form.startPhase.ordinal,
                                    onTabSelected = { model.changeWpsStartPhase(WpsSecurityAuditPhase.entries[it]) })
                            }
                            when (form.startPhase) {
                                WpsSecurityAuditPhase.FIRST_HALF -> item {
                                    TextField(value = form.firstHalfStart, onValueChange = model::changeWpsFirstHalfStart,
                                        label = "前四位起始值（0至9999）", singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth())
                                }
                                WpsSecurityAuditPhase.SECOND_HALF -> item {
                                    TextField(value = form.secondHalfStart, onValueChange = model::changeWpsSecondHalfStart,
                                        label = "七位起始数字", singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth())
                                    Text("前四位固定，后三位为起始值，校验位自动计算。", style = MiuixTheme.textStyles.footnote1)
                                }
                                else -> Unit
                            }
                        }
                        item {
                            AuditDisclosure("测试内容", "依次检查默认 PIN、随机数缺陷和 PIN 分段尝试。详细过程保存在运行日志中。")
                        }
                        item { TextButton("调整运行行为", model::configureWps, Modifier.fillMaxWidth()) }
                    }
                    WpsAuditSheetPage.RECOVERY -> item {
                        val previous = history.filterIsInstance<AuditHistoryRecord.Wps>().firstOrNull { it.id == form.recordId }
                        Text("发现未完成的历史", style = MiuixTheme.textStyles.subtitle)
                        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                            Text(form.ssid.ifEmpty { "隐藏网络" }, style = MiuixTheme.textStyles.subtitle)
                            previous?.let { WpsResumeSummary(it) }
                        }
                        TextButton("从上次继续", model::resumeWpsHistory, Modifier.fillMaxWidth(),
                            enabled = previous != null, colors = ButtonDefaults.textButtonColorsPrimary())
                        TextButton("重新开始", model::restartWps, Modifier.fillMaxWidth())
                    }
                    WpsAuditSheetPage.DETAIL -> {
                        val record = history.filterIsInstance<AuditHistoryRecord.Wps>().firstOrNull { it.id == form.recordId }
                        if (record == null) item { Text("暂无记录") }
                        else {
                            item { Text(record.ssid.ifEmpty { "<隐藏网络>" }, style = MiuixTheme.textStyles.title3) }
                            item { WpsAuditRecordDetails(record) }
                            record.password?.let { password ->
                                item { WeakPasswordResult(record.id, null, password,
                                    resultMessage = wpsAssessmentSummary(record), pin = record.pin) }
                            }
                        }
                    }
                }
                form.message?.let { message -> item { Text(message, style = MiuixTheme.textStyles.body2) } }
            }
            if (form.page != WpsAuditSheetPage.CONFIGURATION) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("关闭", onClick = model::dismissWps, modifier = Modifier.weight(1f))
                when (form.page) {
                    WpsAuditSheetPage.ACCESS_POINTS -> TextButton("下一步", model::confirmWpsAccessPoint,
                        modifier = Modifier.weight(1f), enabled = form.selectedMac != null,
                        colors = ButtonDefaults.textButtonColorsPrimary())
                    WpsAuditSheetPage.TARGET -> TextButton("运行", onClick = model::requestWpsRun,
                        enabled = form.supportsWps && !form.busy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                    WpsAuditSheetPage.CONFIGURATION -> Unit
                    WpsAuditSheetPage.DETAIL -> Unit
                    WpsAuditSheetPage.RECOVERY -> Unit
                }
            }
            Spacer(Modifier.height(safeBottom))
        }
    }

}

@Composable
private fun WpsResumeSummary(record: AuditHistoryRecord.Wps) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("上次阶段：${wpsPhaseLabel(record.phase)}", style = MiuixTheme.textStyles.body2)
        when (record.phase) {
            WpsSecurityAuditPhase.FIRST_HALF -> Text("前四位已尝试${record.firstHalfIndex}项", style = MiuixTheme.textStyles.body2)
            WpsSecurityAuditPhase.SECOND_HALF -> {
                Text("已知前四位：${record.firstHalf}", style = MiuixTheme.textStyles.body2)
                Text("后三位已尝试${record.secondHalfIndex}项", style = MiuixTheme.textStyles.body2)
            }
            else -> Unit
        }
    }
}

@Composable
internal fun WpsAuditConfiguration(config: WpsSecurityAuditConfig, onChange: (WpsSecurityAuditConfig) -> Unit,
    availableInterfaces: List<String> = emptyList()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WifiInterfaceSelector(config.interfaceName, availableInterfaces, { onChange(config.copy(interfaceName = it)) })
        Text("MAC 策略", style = MiuixTheme.textStyles.subtitle)
        LegacyTabRow(tabs = listOf("设备 MAC", "异常时更换", "每次更换"), selectedTabIndex = config.macMode.ordinal,
            onTabSelected = { onChange(config.copy(macMode = PskSecurityAuditMacMode.entries[it])) })
        AnimatedVisibility(config.macMode == PskSecurityAuditMacMode.ON_FAILURE) {
            AuditSlider("连续重试次数", config.macChangeThreshold.toLong(), 1, 5, 1, "次") {
                onChange(config.copy(macChangeThreshold = it.toInt()))
            }
        }
        Text("异常重试", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary)
        AuditDisclosure("重试规则", "连接异常时重试当前 PIN，达到次数后继续测试。失败原因可在运行日志查看。")
        AuditSlider("重试次数", config.retryCount.toLong(), 0, 6, 1, "次",
            valueText = if (config.retryCount == 6) "无限" else null) { onChange(config.copy(retryCount = it.toInt())) }
        AnimatedVisibility(config.retryCount != 0) {
            AuditSlider("冷却翻倍基数", config.cooldownBaseMillis, 0, 8000, 500, "ms",
                valueText = if (config.cooldownBaseMillis == 0L) "不冷却" else null) { onChange(config.copy(cooldownBaseMillis = it)) }
        }
        Text("单次尝试", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary)
        AuditDisclosure("尝试时间", "单次尝试超时后，按异常重试设置处理。")
        AuditSlider("单次尝试最大用时", config.attemptTimeoutMillis, 1000L, 10000L, 500L, "ms") {
            onChange(config.copy(attemptTimeoutMillis = it))
        }
    }
}

@Composable
fun WpsAuditNetworkProgress(record: AuditRecord.Wps?, onEdit: () -> Unit) {
    var lastRecord by remember { mutableStateOf(record) }
    if (record != null) lastRecord = record
    AnimatedVisibility(visible = record != null, enter = expandVertically(), exit = shrinkVertically()) {
        val record = lastRecord ?: return@AnimatedVisibility
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(record.state.label(record.kind), style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onTertiaryContainer)
                if (record.active) TextButton("停止", onClick = onEdit)
            }
            if (record.state == PskSecurityAuditNetworkState.COOLDOWN) {
                Text(auditCooldownRemainingLabel(record.nextAttemptAt), style = MiuixTheme.textStyles.footnote1)
            }
            if (record.state == PskSecurityAuditNetworkState.CONNECTING) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(wpsPhaseLabel(record.phase), style = MiuixTheme.textStyles.footnote1)
                    record.attemptPin?.let { Text("PIN$it", style = MiuixTheme.textStyles.footnote1) }
                    Text(record.attemptStageLabel(), style = MiuixTheme.textStyles.footnote1)
                }
            }
            val progress = record.progress
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            val cursor = when (record.phase) {
                WpsSecurityAuditPhase.FIRST_HALF -> record.firstHalfIndex to 10000
                WpsSecurityAuditPhase.SECOND_HALF -> record.secondHalfIndex to 1000
                else -> null
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(cursor?.let { "已试 ${it.first} 项，共 ${it.second} 项" }
                    ?: "正在${wpsPhaseLabel(record.phase)}", style = MiuixTheme.textStyles.footnote1)
                record.averageAttemptMillis?.takeIf { it > 0 }?.let { cost ->
                    Text("${60000 / cost}/m", style = MiuixTheme.textStyles.footnote1)
                }
            }
            Text("耗时${formatAuditDuration(record.elapsedMillis)}",
                style = MiuixTheme.textStyles.footnote1)
        }
    }
}

@Composable
internal fun WpsAuditRecordDetails(record: AuditHistoryRecord.Wps) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(record.historySummary(), style = MiuixTheme.textStyles.subtitle)
        Text("记录时间：${timestamp(record.updatedAt)}", style = MiuixTheme.textStyles.body2)
        record.mac?.let { Text("接入点：$it", style = MiuixTheme.textStyles.body2) }
        Text("最后阶段：${wpsPhaseLabel(record.phase)}", style = MiuixTheme.textStyles.body2)
        Text("耗时${formatAuditDuration(record.elapsedMillis)}",
            style = MiuixTheme.textStyles.body2)
        AuditDisclosure("详细进度", "前四位已试 ${record.firstHalfIndex} 项，后三位已试 ${record.secondHalfIndex} 项。")
        record.averageAttemptMillis?.takeIf { it > 0 }?.let {
            Text("${60000 / it}/m", style = MiuixTheme.textStyles.body2)
        }
    }
}

/** 当前握手事件直接来自服务快照；发送后显示等待响应，不根据候选下标推测PIN。 */
internal fun AuditRecord.Wps.attemptStageLabel(): String {
    val progress = handshakeProgress ?: return if (attemptPin == null) message else "等待响应"
    return when (progress.stage) {
        WpsHandshakeStage.PREPARING -> "准备会话"
        WpsHandshakeStage.SCANNING -> "寻找接入点"
        WpsHandshakeStage.AUTHENTICATING -> "进行无线认证"
        WpsHandshakeStage.ASSOCIATING -> "关联接入点"
        WpsHandshakeStage.ASSOCIATED -> "已关联，等待响应"
        WpsHandshakeStage.EAPOL_START -> "发送EAPOL Start，等待响应"
        WpsHandshakeStage.IDENTITY_REQUEST -> "收到身份请求"
        WpsHandshakeStage.IDENTITY_RESPONSE -> "发送身份响应，等待响应"
        WpsHandshakeStage.HANDSHAKE -> when (progress.direction) {
            WpsPacketDirection.SENDING -> "发送${progress.packet ?: "WPS消息"}，等待响应"
            WpsPacketDirection.RECEIVED -> "收到${progress.packet ?: "WPS消息"}"
            null -> "等待握手响应"
        }
        WpsHandshakeStage.PROTOCOL_COMPLETED -> "协议交换完成"
        WpsHandshakeStage.SAVING_NETWORK -> "保存网络配置"
        WpsHandshakeStage.RESTORING_SYSTEM -> "恢复系统控制"
    }
}

@Composable
fun WpsSecurityAuditTaskSheet(task: TrackedTaskState?, onStop: () -> Unit, onDismiss: () -> Unit,
    logPanel: AuditLogPanelConfig = AuditLogPanelConfig(),
    onLogPanelChanged: (AuditLogPanelConfig) -> Unit = {},
    detailedLogging: Boolean = false,
    onDetailedLoggingChanged: (Boolean) -> Unit = {},
    onClearLogPanel: () -> Unit = {}) {
    var show by rememberSaveable(task?.snapshot?.taskId) { mutableStateOf(true) }
    val running = task?.snapshot?.state == TaskExecutionState.RUNNING
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    SingleOverlayBottomSheet(show = show, title = "PIN漏洞检测",
        onDismissRequest = { show = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    task == null -> "空闲"
                    running -> "运行中"
                    else -> "已结束"
                },
                style = MiuixTheme.textStyles.subtitle,
            )
            AuditTaskLogView(task = task, modifier = Modifier.weight(1f).fillMaxWidth(),
                logPanel = logPanel, onLogPanelChanged = onLogPanelChanged,
                detailedLogging = detailedLogging, onDetailedLoggingChanged = onDetailedLoggingChanged,
                onClearLogPanel = onClearLogPanel)
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("停止全部")
                }
            }
            Spacer(Modifier.height(safeBottom))
        }
    }
}

/** 只用实际记录的发现来源作结论，未知来源不推断为弱 PIN 或批量枚举成功。 */
private fun wpsAssessmentSummary(record: AuditRecord.Wps) = wpsAssessmentSummary(record.pin, record.passwordFoundPhase)
private fun wpsAssessmentSummary(record: AuditHistoryRecord.Wps) = wpsAssessmentSummary(record.pin, record.passwordFoundPhase)

private fun wpsAssessmentSummary(pin: String?, phase: WpsSecurityAuditPhase?): String = buildList {
    if (pin == "12345670") add("此设备使用弱 PIN，已取得网络密码。")
    if (phase == WpsSecurityAuditPhase.FAIRY_DUST && pin != "12345670") add("已在 Pixie Dust 测试阶段取得网络密码。")
    if (phase == WpsSecurityAuditPhase.FIRST_HALF || phase == WpsSecurityAuditPhase.SECOND_HALF) {
        add("已通过批量 PIN 尝试取得网络密码，此设备未有效阻止本次批量尝试。")
    }
    if (isEmpty()) add("已通过 WPS PIN 取得网络密码，具体发现方式未记录。")
    add("建议立即更换 PIN 并关闭 WPS PIN 功能。")
}.joinToString("\n")
