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
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.displayStage
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
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
        WpsAuditSheetPage.TARGET -> "WPS 安全测试 · ${form.ssid}"
        WpsAuditSheetPage.CONFIGURATION -> "WPS 安全测试运行行为"
        WpsAuditSheetPage.DETAIL -> "WPS 安全测试详情"
    }, onDismissRequest = model::dismissWps) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                when (form.page) {
                    WpsAuditSheetPage.CONFIGURATION -> item { WpsAuditConfiguration(form.config, model::changeWpsConfig) }
                    WpsAuditSheetPage.TARGET -> {
                        item {
                            Text("依次执行三个阶段：先用 12345670 抓握手并离线计算随机数缺陷漏洞 PIN，再逐个尝试前四位 0000~9999，" +
                                "收到 M5 确认前四位后逐个尝试后三位。全程走同一条 FIFO 会话，进度自动保存，可断点续跑。",
                                style = MiuixTheme.textStyles.body2)
                        }
                        if (!form.supportsWps) item { Text("此网络未广播 WPS 能力，无法进行 WPS 安全测试。", style = MiuixTheme.textStyles.body2) }
                        history.filterIsInstance<AuditRecord.Wps>().firstOrNull { it.ssid == form.ssid }?.let { previous ->
                            item {
                                Text("上次进度：${wpsPhaseLabel(previous.phase)}" +
                                    (previous.firstHalf?.let {
                                        if (previous.password != null) " · 前四位已确定" else " · 前四位 $it"
                                    } ?: "") +
                                    (previous.vulnPin?.let { " · 已计算出漏洞 PIN" } ?: "") +
                                    (previous.password?.let { "\n${wpsAssessmentSummary(previous)}" } ?: ""),
                                    style = MiuixTheme.textStyles.body2)
                            }
                        }
                        item {
                            Text("评估内容", style = MiuixTheme.textStyles.subtitle)
                            Text("① 弱 PIN：是否能直接使用默认 PIN 取得网络密码。\n" +
                                "② 随机数缺陷（Pixie Dust）：能否用握手数据离线算出漏洞 PIN。\n" +
                                "③ 批量 PIN 尝试：记录本次实际能否取得网络密码，不推断设备的锁定机制。",
                                style = MiuixTheme.textStyles.footnote1)
                        }
                        item { TextButton("调整运行行为", model::configureWps, Modifier.fillMaxWidth()) }
                    }
                    WpsAuditSheetPage.DETAIL -> {
                        val record = history.filterIsInstance<AuditRecord.Wps>().firstOrNull { it.id == form.recordId }
                        if (record == null) item { Text("暂无记录") }
                        else {
                            item { Text(record.ssid, style = MiuixTheme.textStyles.title3) }
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
            Text("目标网络：${form.ssid}")
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
        Text("WPS 走网卡通道，任何策略都会实际改写本机 MAC；设备 MAC 表示沿用原地址。", style = MiuixTheme.textStyles.footnote1)
        LegacyTabRow(tabs = listOf("设备 MAC", "异常时更换", "每次更换"), selectedTabIndex = config.macMode.ordinal,
            onTabSelected = { onChange(config.copy(macMode = SecurityAuditMacMode.entries[it])) })
        AnimatedVisibility(config.macMode == SecurityAuditMacMode.ON_FAILURE) {
            AuditSlider("连续重试次数", config.macChangeThreshold.toLong(), 1, 5, 1, "次") {
                onChange(config.copy(macChangeThreshold = it.toInt()))
            }
        }
        Text("异常重试", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary)
        Text("PIN 被正常拒绝属正常结果，直接尝试下一个 PIN；只有超时和 WPS-FAIL 等异常才重试当前 PIN。冷却时间每次翻倍。",
            style = MiuixTheme.textStyles.footnote1)
        AuditSlider("重试次数", config.retryCount.toLong(), 0, 6, 1, "次") { onChange(config.copy(retryCount = it.toInt())) }
        AnimatedVisibility(config.retryCount != 0) {
            AuditSlider("冷却翻倍基数", config.cooldownBaseMillis, 0, 8000, 500, "ms") { onChange(config.copy(cooldownBaseMillis = it)) }
        }
        Text("任务上限", style = MiuixTheme.textStyles.subtitle)
        AuditSlider("单网络尝试上限", config.maxAttemptsPerNetwork.toLong(), 0, 20000, 1000,
            if (config.maxAttemptsPerNetwork == 0) "（不限）" else "次") {
            onChange(config.copy(maxAttemptsPerNetwork = it.toInt()))
        }
    }
}

@Composable
fun WpsAuditNetworkProgress(record: AuditRecord.Wps?, onEdit: () -> Unit) {
    var lastRecord by remember { mutableStateOf(record) }
    if (record != null) lastRecord = record
    AnimatedVisibility(visible = record != null, enter = expandVertically(), exit = shrinkVertically()) {
        val record = lastRecord ?: return@AnimatedVisibility
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(record.state.label(record.kind), style = MiuixTheme.textStyles.subtitle)
                Text(wpsPhaseLabel(record.phase), style = MiuixTheme.textStyles.footnote1)
            }
            val progress = record.progress
            if (progress != null) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                val cursor = when (record.phase) {
                    WpsSecurityAuditPhase.FIRST_HALF -> record.firstHalfIndex to 10000
                    WpsSecurityAuditPhase.SECOND_HALF -> record.secondHalfIndex to 1000
                    else -> 0 to 0
                }
                Text("${cursor.first} / ${cursor.second}", style = MiuixTheme.textStyles.footnote1)
            } else Text("仙尘阶段无进度可统计", style = MiuixTheme.textStyles.footnote1)
            Text(record.message, style = MiuixTheme.textStyles.body2)
            record.handshakeProgress?.let {
                Text("当前阶段：${it.displayStage()}", style = MiuixTheme.textStyles.body2)
            }
            Text("尝试 ${record.attempts} 次 · ${record.elapsedMillis} ms · 当前重试 ${record.retryCount} 次",
                style = MiuixTheme.textStyles.footnote1)
            record.vulnPin?.let { Text("已计算出随机数缺陷漏洞 PIN", style = MiuixTheme.textStyles.footnote1) }
            if (record.active) TextButton("停止此网络", onClick = onEdit)
        }
    }
}

@Composable
internal fun WpsAuditRecordDetails(record: AuditRecord.Wps) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(record.state.label(record.kind), style = MiuixTheme.textStyles.subtitle)
        Text("开始时间：${timestamp(record.startedAt)}\n记录时间：${timestamp(record.updatedAt)}", style = MiuixTheme.textStyles.body2)
        Text("目标 MAC：${record.mac ?: "待解析"}\n阶段：${wpsPhaseLabel(record.phase)}\n前四位：${
            if (record.password != null && record.firstHalf != null) "已确定" else record.firstHalf ?: "未确定"}" +
            "\n随机数缺陷漏洞 PIN：${if (record.vulnPin != null) "已计算出" else "未解出"}\n尝试次数：${record.attempts}\n累计耗时：${record.elapsedMillis} ms",
            style = MiuixTheme.textStyles.body2)
        record.handshakeProgress?.let { Text("当前阶段：${it.displayStage()}", style = MiuixTheme.textStyles.body2) }
        Text(wpsNDayLabel(record.nDayVerdict), style = MiuixTheme.textStyles.body2)
        val config = record.config
        Text("MAC 策略：${when (config.macMode) {
            SecurityAuditMacMode.DEVICE -> "设备 MAC"
            SecurityAuditMacMode.ON_FAILURE -> "异常时更换（${config.macChangeThreshold} 次）"
            SecurityAuditMacMode.EVERY_ATTEMPT -> "每次更换"
        }}\n重试上限：${config.retryCount}\n冷却基数：${config.cooldownBaseMillis} ms\n单网络尝试上限：${
            if (config.maxAttemptsPerNetwork == 0) "不限" else config.maxAttemptsPerNetwork}", style = MiuixTheme.textStyles.body2)
        record.averageAttemptMillis?.takeIf { it > 0 }?.let {
            Text("平均尝试耗时：$it ms\n平均速度：${60000 / it} 次/分", style = MiuixTheme.textStyles.body2)
        }
        record.handshake?.let { handshake ->
            Text("WPS 握手数据", style = MiuixTheme.textStyles.subtitle)
            Text("PKE：${handshake.pke}\nPKR：${handshake.pkr}\nE-Nonce：${handshake.eNonce}\nAuthKey：${handshake.authKey}" +
                "\nE-Hash1：${handshake.eHash1}\nE-Hash2：${handshake.eHash2}", style = MiuixTheme.textStyles.footnote1)
        }
        Text(record.message, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
fun WpsSecurityAuditTaskSheet(task: TrackedTaskState, onStop: () -> Unit, onDismiss: () -> Unit, onInspect: (String) -> Unit) {
    var show by rememberSaveable(task.snapshot.taskId) { mutableStateOf(true) }
    var logsExpanded by rememberSaveable(task.snapshot.taskId) { mutableStateOf(false) }
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    SingleOverlayBottomSheet(show = show, title = "WPS 安全测试 · #${task.snapshot.taskId}",
        onDismissRequest = { show = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(if (task.snapshot.state == TaskExecutionState.RUNNING) "运行中" else "本批任务已结束",
                    style = MiuixTheme.textStyles.subtitle) }
                items(task.auditNetworks.filterIsInstance<AuditRecord.Wps>(), key = { it.id }) { record ->
                    Card(Modifier.fillMaxWidth(), onClick = { show = false; onInspect(record.id) }) {
                        BasicComponent(title = record.ssid, summary = when {
                            record.password != null -> wpsAssessmentSummary(record)
                            else -> "${record.state.label(record.kind)} · ${wpsPhaseLabel(record.phase)}\n${record.message}"
                        })
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
private fun wpsAssessmentSummary(record: AuditRecord.Wps): String = buildList {
    if (record.pin == "12345670") add("此设备使用弱 PIN，已取得网络密码。")
    if (record.nDayVerdict == true) add("此设备存在 Pixie Dust 随机数缺陷漏洞。")
    if (record.passwordFoundPhase == WpsSecurityAuditPhase.FIRST_HALF ||
        record.passwordFoundPhase == WpsSecurityAuditPhase.SECOND_HALF) {
        add("已通过批量 PIN 尝试取得网络密码，此设备未有效阻止本次批量尝试。")
    }
    if (isEmpty()) add("已通过 WPS PIN 取得网络密码，具体发现方式未记录。")
    add("建议立即更换 PIN 并关闭 WPS PIN 功能。")
}.joinToString("\n")
