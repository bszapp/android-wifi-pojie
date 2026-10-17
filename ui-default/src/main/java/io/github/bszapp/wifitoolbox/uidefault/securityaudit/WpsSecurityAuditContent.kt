package io.github.bszapp.wifitoolbox.uidefault.securityaudit

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bszapp.wifitoolbox.contract.task.AuditRecord
import io.github.bszapp.wifitoolbox.contract.task.AuditHistoryRecord
import io.github.bszapp.wifitoolbox.contract.task.SecurityAuditMacMode
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditConfig
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditPhase
import io.github.bszapp.wifitoolbox.uidefault.component.LegacyTabRow
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.uidefault.component.WeakPasswordResult
import io.github.bszapp.wifitoolbox.uidefault.model.SecurityAuditUiState
import io.github.bszapp.wifitoolbox.uidefault.model.WpsAuditSheetPage
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import kotlin.math.roundToLong

/** WPS 安全测试的配置、目标、授权与详情抽屉，与安全测试共用同一份历史数据源。 */
@Composable
fun WpsAuditSheets(model: SecurityAuditUiState) {
    val form by model.wpsForm.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .8f
    SingleOverlayBottomSheet(show = form.show, title = when (form.page) {
        WpsAuditSheetPage.TARGET -> "WPS 安全测试"
        WpsAuditSheetPage.ACCESS_POINTS -> "选择已授权的接入点"
        WpsAuditSheetPage.CONFIGURATION -> "WPS 安全测试运行行为"
        WpsAuditSheetPage.DETAIL -> "WPS 安全测试详情"
    }, onDismissRequest = model::dismissWps) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                when (form.page) {
                    WpsAuditSheetPage.CONFIGURATION -> item { WpsAuditConfiguration(form.config, model::changeWpsConfig) }
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
                        if (!form.supportsWps) item { Text("此网络未广播 WPS 能力，无法进行 WPS 安全测试。", style = MiuixTheme.textStyles.body2) }
                        form.selectedMac?.let { mac -> item {
                            Card(Modifier.fillMaxWidth()) {
                                BasicComponent(title = form.ssid.ifEmpty { "隐藏网络" }, summary = "接入点 $mac")
                            }
                        } }
                        history.filterIsInstance<AuditHistoryRecord.Wps>().firstOrNull { it.id == form.recordId }?.let { previous ->
                            item {
                                Text("上次停在${wpsPhaseLabel(previous.phase)}", style = MiuixTheme.textStyles.body2)
                            }
                        }
                        item {
                            AuditDisclosure("测试内容", "依次检查默认 PIN、随机数缺陷和 PIN 分段尝试。详细过程保存在运行日志中。")
                        }
                        item { TextButton("调整运行行为", model::configureWps, Modifier.fillMaxWidth()) }
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
                }
            }
            Spacer(Modifier.height(safeBottom))
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(form.authorizationShow, form.authorizationReadyAt) {
        while (form.authorizationShow) { now = SystemClock.elapsedRealtime(); delay(100) }
    }
    val remaining = ((form.authorizationReadyAt - now + 999) / 1000).coerceAtLeast(0)
    OverlayDialog(show = form.authorizationShow, title = "确认授权 WPS 安全测试",
        onDismissRequest = model::dismissWpsAuthorization) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("目标网络：${form.ssid.ifEmpty { "<隐藏网络>" }}")
            form.selectedMac?.let { Text("目标接入点：$it") }
            Text("此功能仅用于已授权网络的安全评估。批量 PIN 尝试会长时间触发路由器的 WPS 错误计数，" +
                "未经授权的在线尝试可能构成非法侵入、干扰他人网络，并承担法律责任。勾选声明不能替代实际授权。",
                style = MiuixTheme.textStyles.body2)
            if (form.saved) Text("此网络已有保存配置，测试成功后保存配置的密码可能随之变化。", style = MiuixTheme.textStyles.body2)
            AuditChoice("我已获得目标网络的测试授权或所有权", form.authorizationAgreed, { model.agreeWps(!form.authorizationAgreed) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", model::dismissWpsAuthorization, Modifier.weight(1f))
                TextButton(if (remaining > 0) "继续（${remaining}秒）" else "继续", model::confirmWpsRun, Modifier.weight(1f),
                    enabled = form.authorizationAgreed && remaining == 0L, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
internal fun WpsAuditConfiguration(config: WpsSecurityAuditConfig, onChange: (WpsSecurityAuditConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MAC 策略", style = MiuixTheme.textStyles.subtitle)
        LegacyTabRow(tabs = listOf("设备 MAC", "异常时更换", "每次更换"), selectedTabIndex = config.macMode.ordinal,
            onTabSelected = { onChange(config.copy(macMode = SecurityAuditMacMode.entries[it])) })
        AnimatedVisibility(config.macMode == SecurityAuditMacMode.ON_FAILURE) {
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
                    Text("每分钟 ${60000 / cost} 次", style = MiuixTheme.textStyles.footnote1)
                }
            }
            Text("已尝试 ${record.attempts} 次，耗时 ${formatAuditDuration(record.elapsedMillis)}",
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
        Text("已尝试 ${record.attempts} 次，耗时 ${formatAuditDuration(record.elapsedMillis)}",
            style = MiuixTheme.textStyles.body2)
        AuditDisclosure("详细进度", "前四位已试 ${record.firstHalfIndex} 项，后三位已试 ${record.secondHalfIndex} 项。")
        record.averageAttemptMillis?.takeIf { it > 0 }?.let {
            Text("平均每分钟 ${60000 / it} 次", style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
fun WpsSecurityAuditTaskSheet(task: TrackedTaskState, onStop: () -> Unit, onDismiss: () -> Unit, onInspect: (String) -> Unit) {
    var show by rememberSaveable(task.snapshot.taskId) { mutableStateOf(true) }
    var logsExpanded by rememberSaveable(task.snapshot.taskId) { mutableStateOf(false) }
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    SingleOverlayBottomSheet(show = show, title = "WPS 安全测试",
        onDismissRequest = { show = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(if (task.snapshot.state == TaskExecutionState.RUNNING) "运行中" else "本批任务已结束",
                    style = MiuixTheme.textStyles.subtitle) }
                items(task.auditNetworks.filterIsInstance<AuditRecord.Wps>(), key = { it.id }) { record ->
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp),
                        onClick = { show = false; onInspect(record.id) }) {
                        Text(record.ssid.ifEmpty { "隐藏网络" }, style = MiuixTheme.textStyles.subtitle)
                        Text(if (record.password != null) "已取得密码，点击查看结果"
                            else record.state.label(record.kind), style = MiuixTheme.textStyles.body2)
                        if (record.password == null) Text(wpsPhaseLabel(record.phase),
                            style = MiuixTheme.textStyles.footnote1)
                        LinearProgressIndicator(progress = record.progress,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                item { TextButton(if (logsExpanded) "收起运行日志" else "展开运行日志", onClick = { logsExpanded = !logsExpanded }) }
                if (logsExpanded) items(task.logs.entries, key = { it.taskLineId }) { Text(it.text, style = MiuixTheme.textStyles.body2) }
            }
            if (task.snapshot.state == TaskExecutionState.RUNNING) TextButton("停止全部", onStop, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary())
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
