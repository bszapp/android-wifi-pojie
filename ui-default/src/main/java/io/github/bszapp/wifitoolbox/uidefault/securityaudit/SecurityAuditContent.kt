package io.github.bszapp.wifitoolbox.uidefault.securityaudit

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bszapp.wifitoolbox.contract.task.*
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.uidefault.component.WeakPasswordResult
import io.github.bszapp.wifitoolbox.uidefault.component.LegacyTabRow
import io.github.bszapp.wifitoolbox.uidefault.model.*
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun SecurityAuditSheets(model: SecurityAuditUiState) {
    val form by model.sheet.collectAsStateWithLifecycle()
    val dictionaries by model.dictionaries.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .8f
    SingleOverlayBottomSheet(show = form.show, title = when (form.page) {
        SecurityAuditSheetPage.CONFIGURATION -> "安全测试运行行为"
        SecurityAuditSheetPage.DICTIONARIES -> "选择字典 · ${form.ssid}"
        SecurityAuditSheetPage.DETAIL -> "安全测试详情"
        SecurityAuditSheetPage.SAVED -> "已保存的网络 · ${form.ssid}"
        SecurityAuditSheetPage.RECOVERY -> "未完成的安全测试 · ${form.ssid}"
        SecurityAuditSheetPage.MANUAL -> "手动输入网络名称"
    }, onDismissRequest = model::dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                when (form.page) {
                    SecurityAuditSheetPage.CONFIGURATION -> item { AuditConfiguration(form.config, model::changeConfig) }
                    SecurityAuditSheetPage.DICTIONARIES -> {
                        item { Text("选择普通字典，可多选。按选择顺序交错合并、去空白、去重并过滤不足八个字符的项；脚本字典不可选。", style = MiuixTheme.textStyles.body2) }
                        if (form.busy) item { Text("加载中") }
                        else if (dictionaries.isEmpty()) item { Text("请先在资源管理中添加普通字典。") }
                        items(dictionaries.sortedBy { resource -> form.selectedIds.indexOf(resource.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }, key = { it.id }) { resource ->
                            Card(Modifier.fillMaxWidth()) {
                                AuditChoice(resource.name ?: resource.id, resource.id in form.selectedIds,
                                    { model.selectDictionary(resource.id) }, resource.description)
                            }
                        }
                        item {
                            Text("有效密码：${form.candidateCount} 项", style = MiuixTheme.textStyles.subtitle)
                            var expanded by rememberSaveable { mutableStateOf(false) }
                            TextButton(if (expanded) "收起处理日志" else "展开处理日志", onClick = { expanded = !expanded })
                            AnimatedVisibility(expanded) { Column { form.preparationLog.forEach { Text(it, style = MiuixTheme.textStyles.footnote1) } } }
                        }
                    }
                    SecurityAuditSheetPage.MANUAL -> item {
                        TextField(value = form.ssid, onValueChange = model::changeSsid, label = "SSID", singleLine = true)
                    }
                    SecurityAuditSheetPage.SAVED -> item {
                        Text("此网络已有保存配置，可直接测试已保存的密码，或重新选择字典。", style = MiuixTheme.textStyles.body2)
                        TextButton("测试已保存的密码", model::useSavedPassword, Modifier.fillMaxWidth())
                        TextButton("重新选择字典", model::chooseDictionaries, Modifier.fillMaxWidth())
                    }
                    SecurityAuditSheetPage.RECOVERY -> item {
                        val previous = history.filterIsInstance<AuditRecord.Dictionary>().firstOrNull { it.id == form.recordId }
                        Text("上次进度：${previous?.completed ?: 0}/${previous?.total ?: 0}", style = MiuixTheme.textStyles.body2)
                        TextButton("继续上次测试", { previous?.let { model.resumeHistory(it.id) } }, Modifier.fillMaxWidth())
                        TextButton("重新选择字典", model::chooseDictionaries, Modifier.fillMaxWidth())
                    }
                    SecurityAuditSheetPage.DETAIL -> {
                        val record = history.filterIsInstance<AuditRecord.Dictionary>().firstOrNull { it.id == form.recordId }
                        if (record == null) item { Text("暂无记录") }
                        else {
                            item { Text(record.ssid, style = MiuixTheme.textStyles.title3) }
                            item { AuditRecordDetails(record) }
                            if (record.password == null && record.completed < record.total) item {
                                TextButton("从此记录继续", { model.resumeHistory(record.id) }, Modifier.fillMaxWidth())
                            }
                            item { TextButton("重新选择字典", model::chooseDictionaries, Modifier.fillMaxWidth()) }
                            record.password?.let { password -> item { WeakPasswordResult(record.id, record.dictionaryPosition?.toLong(), password) } }
                        }
                    }
                }
                form.message?.let { message -> item { Text(message, style = MiuixTheme.textStyles.body2) } }
            }
            // 配置页修改即时生效，不再提供关闭/应用按钮；拖拽或返回即可关闭。
            if (form.page != SecurityAuditSheetPage.CONFIGURATION) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("关闭", onClick = model::dismiss, modifier = Modifier.weight(1f))
                when (form.page) {
                    SecurityAuditSheetPage.DICTIONARIES -> TextButton(if (form.busy) "准备中" else "运行", onClick = model::requestRun,
                        enabled = !form.busy && form.candidateCount > 0, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    SecurityAuditSheetPage.DETAIL -> Unit
                    SecurityAuditSheetPage.SAVED, SecurityAuditSheetPage.RECOVERY -> Unit
                    SecurityAuditSheetPage.MANUAL -> TextButton("下一步", model::selectManualNetwork,
                        Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    SecurityAuditSheetPage.CONFIGURATION -> Unit
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
    OverlayDialog(show = form.authorizationShow, title = "确认授权安全测试",
        onDismissRequest = model::dismissAuthorization) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("目标网络：${form.ssid}")
            Text("此功能仅用于已授权网络的安全评估。未经授权的在线密码尝试可能构成非法侵入、干扰他人网络，并承担法律责任。勾选声明不能替代实际授权。",
                style = MiuixTheme.textStyles.body2)
            if (form.saved && !form.useSavedPassword && form.config.connectionMethod == SecurityAuditConnectionMethod.SYSTEM) {
                Text("系统安全测试会更新此网络的保存配置。每轮结束保留配置；停止、字典耗尽或执行异常时删除，成功时保留。不恢复测试前的密码。", style = MiuixTheme.textStyles.body2)
            }
            AuditChoice("我已获得目标网络的测试授权或所有权", form.authorizationAgreed, { model.agree(!form.authorizationAgreed) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", model::dismissAuthorization, Modifier.weight(1f))
                TextButton(if (remaining > 0) "继续（${remaining}秒）" else "继续", model::confirmRun, Modifier.weight(1f),
                    enabled = form.authorizationAgreed && remaining == 0L, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

@Composable
internal fun AuditChoice(title: String, checked: Boolean, onClick: () -> Unit, summary: String? = null) {
    BasicComponent(title = title, summary = summary, onClick = onClick, endActions = {
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick = onClick)
    })
}

@Composable
private fun AuditConfiguration(config: SecurityAuditConfig, onChange: (SecurityAuditConfig) -> Unit) {
    val connection = config.connection
    val flags = connection.failureFlags
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接方式", style = MiuixTheme.textStyles.subtitle)
        LegacyTabRow(tabs = listOf("测试连通性", "测试连通性（网卡）"), selectedTabIndex = config.connectionMethod.ordinal,
            onTabSelected = { onChange(config.copy(connectionMethod = SecurityAuditConnectionMethod.entries[it])) })
        AnimatedVisibility(config.connectionMethod == SecurityAuditConnectionMethod.NETWORK_CARD) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MAC 策略", style = MiuixTheme.textStyles.subtitle)
                Text("评估路由器对异常环境的风控能力；仅网卡方式支持，系统方式始终使用设备 MAC。",
                    style = MiuixTheme.textStyles.footnote1)
                LegacyTabRow(tabs = listOf("设备 MAC", "异常时更换", "每次更换"),
                    selectedTabIndex = config.macMode.ordinal,
                    onTabSelected = { onChange(config.copy(macMode = SecurityAuditMacMode.entries[it])) })
                AnimatedVisibility(config.macMode == SecurityAuditMacMode.ON_FAILURE) {
                    AuditSlider("连续重试次数", config.macChangeThreshold.toLong(), 1, 5, 1, "次") {
                        onChange(config.copy(macChangeThreshold = it.toInt()))
                    }
                }
            }
        }
        Text("单次尝试", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary)
        AuditSlider("最大尝试时间", connection.timeoutMillis, 1000L, 10000L, 500L, "ms") {
            onChange(config.copy(connection = connection.copy(timeoutMillis = it)))
        }
        Text("失败标志", style = MiuixTheme.textStyles.subtitle)
        val failure = when { flags.handshakeAttemptsExceeded != null -> 2; flags.handshakeTimeout != null -> 1; else -> 0 }
        LegacyTabRow(tabs = listOf("密码错误", "握手超时", "握手超次"), selectedTabIndex = failure, onTabSelected = {
            onChange(config.copy(connection = connection.copy(failureFlags = when (it) {
                0 -> ConnectWifiFailureFlags(true, null, null)
                1 -> ConnectWifiFailureFlags(false, null, HandshakeTimeoutFlag(config.handshakeTimeoutMillis))
                else -> ConnectWifiFailureFlags(false, HandshakeAttemptsExceededFlag(config.maxHandshakeAttempts), null)
            })))
        })
        AnimatedVisibility(failure == 1) {
            AuditSlider("握手超时", flags.handshakeTimeout?.handshakeStepTimeoutMillis ?: 1500, 500, 5000, 250, "ms") {
                onChange(config.copy(handshakeTimeoutMillis = it, connection = connection.copy(failureFlags = flags.copy(handshakeTimeout = HandshakeTimeoutFlag(it)))))
            }
        }
        AnimatedVisibility(failure == 2) {
            AuditSlider("最大握手次数", (flags.handshakeAttemptsExceeded?.maxHandshakeAttempts ?: 1).toLong(), 1, 5, 1, "次") {
                onChange(config.copy(maxHandshakeAttempts = it.toInt(), connection = connection.copy(failureFlags = flags.copy(handshakeAttemptsExceeded = HandshakeAttemptsExceededFlag(it.toInt())))))
            }
        }
        Text("异常重试", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary)
        Text("超时或拒绝接入时重试；达到上限后尝试下一个字典项。冷却时间每次翻倍。", style = MiuixTheme.textStyles.footnote1)
        AuditSlider("重试次数", config.retryCount.toLong(), 0, 6, 1, if (config.retryCount == 6) "（无限）" else "次") {
            onChange(config.copy(retryCount = it.toInt()))
        }
        AnimatedVisibility(config.retryCount != 0) {
            AuditSlider("冷却翻倍基数", config.cooldownBaseMillis, 0, 8000, 500, "ms") { onChange(config.copy(cooldownBaseMillis = it)) }
        }
    }
}

@Composable
internal fun AuditSlider(title: String, value: Long, min: Long, max: Long, step: Long, unit: String, onChange: (Long) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text("$value $unit", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(value = value.toFloat(), onValueChange = { onChange((it / step).roundToLong() * step) },
            valueRange = min.toFloat()..max.toFloat(), steps = ((max - min) / step - 1).toInt())
    }
}

@Composable
fun AuditNetworkProgress(record: AuditRecord.Dictionary?, onEdit: () -> Unit) {
    var lastRecord by remember { mutableStateOf(record) }
    if (record != null) lastRecord = record
    AnimatedVisibility(visible = record != null, enter = expandVertically(), exit = shrinkVertically()) {
    val record = lastRecord ?: return@AnimatedVisibility
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(record.state.label(), style = MiuixTheme.textStyles.subtitle)
            Text("${record.completed}/${record.total}", style = MiuixTheme.textStyles.footnote1)
        }
        LinearProgressIndicator(progress = if (record.total > 0) record.completed.toFloat() / record.total else 0f, modifier = Modifier.fillMaxWidth())
        Text(record.message, style = MiuixTheme.textStyles.body2)
        record.stage?.let { Text(it.label(), style = MiuixTheme.textStyles.footnote1) }
        Text("尝试 ${record.attempts} 次 · ${record.elapsedMillis} ms · 当前重试 ${record.retryCount} 次", style = MiuixTheme.textStyles.footnote1)
        record.averageAttemptMillis?.takeIf { it > 0 }?.let { cost ->
            Text("平均 ${60000 / cost} 次/分 · 预计剩余 ${(record.total - record.completed) * cost / 1000} 秒", style = MiuixTheme.textStyles.footnote1)
        } ?: Text("速度计算中（至少需要5个样本）", style = MiuixTheme.textStyles.footnote1)
        if (record.active) TextButton("编辑运行字典", onClick = onEdit)
    }
    }
}

@Composable
private fun AuditRecordDetails(record: AuditRecord.Dictionary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(record.state.label(), style = MiuixTheme.textStyles.subtitle)
        Text("开始时间：${timestamp(record.startedAt)}\n记录时间：${timestamp(record.updatedAt)}", style = MiuixTheme.textStyles.body2)
        Text("字典：${record.input.dictionaryNames.joinToString("、")}\n字典进度：${record.completed}/${record.total}\n尝试次数：${record.attempts}\n计算耗时：${record.elapsedMillis} ms", style = MiuixTheme.textStyles.body2)
        record.averageAttemptMillis?.let { Text("平均尝试耗时：$it ms\n平均速度：${if (it > 0) 60000 / it else 0} 次/分", style = MiuixTheme.textStyles.body2) }
        val config = record.config
        Text("方式：${if (config.connectionMethod == SecurityAuditConnectionMethod.SYSTEM) "测试连通性" else "测试连通性（网卡）"}\n单次最大时间：${config.connection.timeoutMillis} ms\n重试上限：${if (config.retryCount == 6) "无限" else config.retryCount}\n冷却基数：${config.cooldownBaseMillis} ms", style = MiuixTheme.textStyles.body2)
        Text(record.message, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
fun SecurityAuditTaskSheet(task: TrackedTaskState, onStop: () -> Unit, onDismiss: () -> Unit,
    onInspect: (String) -> Unit, onInspectWps: (String) -> Unit = onInspect) {
    var show by rememberSaveable(task.snapshot.taskId) { mutableStateOf(true) }
    var logsExpanded by rememberSaveable(task.snapshot.taskId) { mutableStateOf(false) }
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    SingleOverlayBottomSheet(show = show, title = "网络安全测试 · #${task.snapshot.taskId}",
        onDismissRequest = { show = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(if (task.snapshot.state == TaskExecutionState.RUNNING) "运行中" else "本批任务已结束", style = MiuixTheme.textStyles.subtitle) }
                items(task.auditNetworks, key = { it.id }) { record ->
                    Card(Modifier.fillMaxWidth(), onClick = {
                        show = false
                        if (record is AuditRecord.Dictionary) onInspect(record.id) else onInspectWps(record.id)
                    }) {
                        BasicComponent(title = record.ssid,
                            summary = when (record) {
                                is AuditRecord.Dictionary -> "${record.state.label(record.kind)} · ${record.completed}/${record.total}\n${record.message}"
                                is AuditRecord.Wps -> "${record.state.label(record.kind)} · ${wpsPhaseLabel(record.phase)}\n${record.message}"
                            })
                    }
                }
                item { TextButton(if (logsExpanded) "收起运行日志" else "展开运行日志", onClick = { logsExpanded = !logsExpanded }) }
                if (logsExpanded) items(task.logs.entries, key = { it.taskLineId }) { Text(it.text, style = MiuixTheme.textStyles.body2) }
            }
            if (task.snapshot.state == TaskExecutionState.RUNNING) TextButton("停止全部", onStop, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            Spacer(Modifier.height(safeBottom))
        }
    }
}

@Composable
fun SecurityAuditHistoryScreen(viewModel: DefaultViewModel) {
    val navigator = LocalNavigator.current
    val history by viewModel.securityAudit.history.collectAsStateWithLifecycle()
    val sorted = remember(history) { history.sortedWith(compareByDescending<AuditRecord> { it.password != null }.thenByDescending { it.updatedAt }) }
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    Scaffold(topBar = { BlurredBar(backdrop) {
        TopAppBar(title = "安全测试历史", color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
            navigationIcon = { IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, scrollBehavior = scroll)
    } }, contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)) { padding ->
        Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical().nestedScroll(scroll.nestedScrollConnection).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 12.dp,
                    bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), overscrollEffect = null) {
                if (sorted.isEmpty()) item { Text("暂无安全测试历史") }
                items(sorted, key = { it.id }) { record ->
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(title = record.ssid, summary = buildString {
                            if (record.kind == AuditRecordKind.WPS) append("WPS 安全测试 · ")
                            append(timestamp(record.updatedAt))
                            append(" · ${record.state.label(record.kind)}")
                            if (record.password != null && record.kind == AuditRecordKind.DICTIONARY) append(" · 发现弱密码")
                        }, onClick = {
                            if (record is AuditRecord.Dictionary) viewModel.securityAudit.inspect(record.id)
                            else viewModel.securityAudit.inspectWps(record.id)
                        }, endActions = {
                                IconButton(onClick = { viewModel.showConfirmationDialog(title = "删除历史记录", content = "删除 ${record.ssid} 的这条安全测试记录？",
                                    onConfirmed = { viewModel.securityAudit.deleteHistory(record.id) }) }) { Icon(Icons.Rounded.DeleteOutline, "删除历史") }
                            })
                    }
                }
            }
        }
    }
}

internal fun timestamp(value: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
internal fun SecurityAuditNetworkState.label(kind: AuditRecordKind = AuditRecordKind.DICTIONARY) = when (this) {
    SecurityAuditNetworkState.QUEUED -> "排队中"
    SecurityAuditNetworkState.CONNECTING -> if (kind == AuditRecordKind.WPS) "握手中" else "测试中"
    SecurityAuditNetworkState.COOLDOWN -> "冷却中"
    SecurityAuditNetworkState.SUCCEEDED -> if (kind == AuditRecordKind.WPS) "取得密码" else "发现弱密码"
    SecurityAuditNetworkState.EXHAUSTED -> if (kind == AuditRecordKind.WPS) "已结束" else "字典已耗尽"
    SecurityAuditNetworkState.STOPPED -> "已停止"
    SecurityAuditNetworkState.ERROR -> "执行异常"
}
internal fun wpsPhaseLabel(phase: WpsSecurityAuditPhase) = when (phase) {
    WpsSecurityAuditPhase.FAIRY_DUST -> "仙尘阶段"
    WpsSecurityAuditPhase.FIRST_HALF -> "前四位枚举"
    WpsSecurityAuditPhase.SECOND_HALF -> "后三位枚举"
    WpsSecurityAuditPhase.DONE -> "已结束"
}
internal fun wpsNDayLabel(value: Boolean?) = when (value) {
    true -> "存在随机数缺陷漏洞"
    false -> "未发现随机数缺陷漏洞"
    null -> "随机数缺陷未评估"
}
private fun ConnectWifiStage.label() = when (this) {
    ConnectWifiStage.ROUTER_COMMUNICATION -> "与路由器建立通信"
    ConnectWifiStage.WPA_HANDSHAKE_1_OF_4 -> "WPA 握手 1/4"
    ConnectWifiStage.WPA_HANDSHAKE_2_OF_4 -> "WPA 握手 2/4"
    ConnectWifiStage.WPA_HANDSHAKE_3_OF_4 -> "WPA 握手 3/4"
    ConnectWifiStage.WPA_HANDSHAKE_4_OF_4 -> "WPA 握手 4/4"
    ConnectWifiStage.IP_NEGOTIATION -> "获取 IP 地址"
}
