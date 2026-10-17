package io.github.bszapp.wifitoolbox.uidefault.securityaudit

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
    val auditTasks by model.auditTasks.collectAsStateWithLifecycle()
    val sharedSources = auditTasks.filter { it.snapshot.state == TaskExecutionState.RUNNING }
        .flatMap { it.auditNetworks }.filterIsInstance<AuditRecord.Dictionary>()
        .filter { it.active }.distinctBy { it.groupId ?: it.id }
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .8f
    SingleOverlayBottomSheet(
        show = form.show, title = when (form.page) {
            SecurityAuditSheetPage.CONFIGURATION -> "安全测试运行行为"
            SecurityAuditSheetPage.DICTIONARIES -> "选择字典"
            SecurityAuditSheetPage.DETAIL -> "安全测试详情"
            SecurityAuditSheetPage.SAVED -> "已保存的网络"
            SecurityAuditSheetPage.RECOVERY -> "未完成的安全测试"
            SecurityAuditSheetPage.MANUAL -> "手动输入网络名称"
        }, onDismissRequest = model::dismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                when (form.page) {
                    SecurityAuditSheetPage.CONFIGURATION -> item {
                        AuditConfiguration(
                            form.config,
                            model::changeConfig
                        )
                    }

                    SecurityAuditSheetPage.DICTIONARIES -> {
                        item {
                            Text(form.ssid, style = MiuixTheme.textStyles.subtitle)
                            AuditDisclosure("字典处理规则",
                                "可选择多个普通字典。按选择顺序合并，去除空白、重复和少于八个字符的密码。")
                        }
                        if (sharedSources.isNotEmpty()) {
                            item {
                                Text("使用已有测试的字典", style = MiuixTheme.textStyles.subtitle)
                            }
                            items(sharedSources, key = { "shared-${it.id}" }) { source ->
                                Card(Modifier.fillMaxWidth()) {
                                    BasicComponent(
                                        title = source.ssid,
                                        summary = "已尝试 ${source.poolCompleted ?: source.completed} 项，共 ${source.total} 项",
                                        onClick = { model.selectSharedSource(source.id) },
                                        endActions = {
                                            RadioButton(
                                                selected = form.sharedSourceRecordId == source.id,
                                                onClick = { model.selectSharedSource(source.id) })
                                        })
                                }
                            }
                            item {
                                Text(
                                    "或选择新的普通字典",
                                    style = MiuixTheme.textStyles.subtitle
                                )
                            }
                        }
                        if (form.busy) item { Text("加载中") }
                        else if (dictionaries.isEmpty()) item { Text("请先在资源管理中添加普通字典。") }
                        items(dictionaries.sortedBy { resource ->
                            form.selectedIds.indexOf(resource.id).takeIf { it >= 0 }
                                ?: Int.MAX_VALUE
                        }, key = { it.id }) { resource ->
                            Card(Modifier.fillMaxWidth()) {
                                AuditChoice(
                                    resource.name ?: resource.id,
                                    resource.id in form.selectedIds,
                                    { model.selectDictionary(resource.id) },
                                    resource.description
                                )
                            }
                        }
                        item {
                            Text(
                                "可尝试密码 ${form.candidateCount} 项",
                                style = MiuixTheme.textStyles.subtitle
                            )
                            AuditSlider("同时运行", form.taskCount.toLong(), 1, 8, 1, "项") {
                                model.changeTaskCount(it.toInt())
                            }
                            AuditDisclosure("多项测试", "各项测试独立排队，共用选中的密码字典。")
                            var expanded by rememberSaveable { mutableStateOf(false) }
                            TextButton(
                                if (expanded) "收起处理日志" else "展开处理日志",
                                onClick = { expanded = !expanded })
                            AnimatedVisibility(expanded) {
                                Column {
                                    form.preparationLog.forEach {
                                        Text(
                                            it,
                                            style = MiuixTheme.textStyles.footnote1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SecurityAuditSheetPage.MANUAL -> item {
                        TextField(
                            value = form.ssid,
                            onValueChange = model::changeSsid,
                            label = "SSID",
                            singleLine = true
                        )
                    }

                    SecurityAuditSheetPage.SAVED -> item {
                        Text("发现已保存密码", style = MiuixTheme.textStyles.subtitle)
                        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                            Text(form.ssid, style = MiuixTheme.textStyles.subtitle)
                            Text("密码", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(form.savedPassword ?: "未读取到密码", style = MiuixTheme.textStyles.body1)
                        }
                        TextButton(
                            "直接尝试",
                            model::useSavedPassword,
                            Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            "重新运行",
                            model::rerunAfterSaved,
                            Modifier.fillMaxWidth()
                        )
                    }

                    SecurityAuditSheetPage.RECOVERY -> item {
                        val previous = history.filterIsInstance<AuditHistoryRecord.Dictionary>()
                            .firstOrNull { it.id == form.recordId }
                        Text("发现未完成的历史", style = MiuixTheme.textStyles.subtitle)
                        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                            Text(form.ssid, style = MiuixTheme.textStyles.subtitle)
                            Text("已尝试 ${previous?.completed ?: 0} 项，共 ${previous?.total ?: 0} 项",
                                style = MiuixTheme.textStyles.body2)
                        }
                        TextButton(
                            "继续测试",
                            { previous?.let { model.resumeHistory(it.id) } },
                            Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            "重新选择字典",
                            model::chooseDictionaries,
                            Modifier.fillMaxWidth()
                        )
                    }

                    SecurityAuditSheetPage.DETAIL -> {
                        val record = history.filterIsInstance<AuditHistoryRecord.Dictionary>()
                            .firstOrNull { it.ssid == form.ssid }
                        if (record == null) item { Text("暂无记录") }
                        else {
                            item { Text(record.ssid, style = MiuixTheme.textStyles.title3) }
                            item { AuditRecordDetails(record) }
                            if (record.password == null && record.completed < record.total) item {
                                TextButton(
                                    "从此记录继续",
                                    { model.resumeHistory(record.id) },
                                    Modifier.fillMaxWidth()
                                )
                            }
                            record.password?.let { password ->
                                item {
                                    WeakPasswordResult(
                                        record.id,
                                        record.dictionaryPosition?.toLong(),
                                        password
                                    )
                                }
                            }
                        }
                    }

                }
                form.message?.let { message ->
                    item {
                        Text(
                            message,
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                }
                if (form.page == SecurityAuditSheetPage.DICTIONARIES) {
                    item {
                        val activeRecords = auditTasks.filter { it.snapshot.state == TaskExecutionState.RUNNING }
                            .flatMap { it.auditNetworks }.count { it is AuditRecord.Dictionary && it.active }
                        if (activeRecords > 0) Text("当前有 $activeRecords 项正在测试",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            // 配置页修改即时生效，不再提供关闭/应用按钮；拖拽或返回即可关闭。
            if (form.page != SecurityAuditSheetPage.CONFIGURATION) Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton("关闭", onClick = model::dismiss, modifier = Modifier.weight(1f))
                when (form.page) {
                    SecurityAuditSheetPage.DICTIONARIES -> TextButton(
                        if (form.busy) "准备中" else "运行",
                        onClick = model::requestRun,
                        enabled = !form.busy && form.candidateCount > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )

                    SecurityAuditSheetPage.DETAIL -> Unit
                    SecurityAuditSheetPage.SAVED, SecurityAuditSheetPage.RECOVERY -> Unit
                    SecurityAuditSheetPage.MANUAL -> TextButton(
                        "下一步", model::selectManualNetwork,
                        Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary()
                    )

                    SecurityAuditSheetPage.CONFIGURATION -> Unit
                }
            }
            Spacer(Modifier.height(safeBottom))
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(form.authorizationShow, form.authorizationReadyAt) {
        while (form.authorizationShow) {
            now = SystemClock.elapsedRealtime(); delay(100)
        }
    }
    val remaining = ((form.authorizationReadyAt - now + 999) / 1000).coerceAtLeast(0)
    OverlayDialog(
        show = form.authorizationShow, title = "确认授权安全测试",
        onDismissRequest = model::dismissAuthorization
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("目标网络：${form.ssid}")
            if (form.taskCount > 1) Text("本次将创建 ${form.taskCount} 个测试任务，归属同一个安全测试任务并共享候选池。")
            Text(
                "此功能仅用于已授权网络的安全评估。未经授权的在线密码尝试可能构成非法侵入、干扰他人网络，并承担法律责任。勾选声明不能替代实际授权。",
                style = MiuixTheme.textStyles.body2
            )
            if (form.saved && !form.useSavedPassword && form.config.connectionMethod == SecurityAuditConnectionMethod.SYSTEM) {
                Text(
                    "系统安全测试会更新此网络的保存配置。每轮结束保留配置；停止、字典耗尽或执行异常时删除，成功时保留。不恢复测试前的密码。",
                    style = MiuixTheme.textStyles.body2
                )
            }
            AuditChoice(
                "我已获得目标网络的测试授权或所有权",
                form.authorizationAgreed,
                { model.agree(!form.authorizationAgreed) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", model::dismissAuthorization, Modifier.weight(1f))
                TextButton(
                    if (remaining > 0) "继续（${remaining}秒）" else "继续",
                    model::confirmRun,
                    Modifier.weight(1f),
                    enabled = form.authorizationAgreed && remaining == 0L,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
internal fun AuditChoice(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    summary: String? = null
) {
    BasicComponent(title = title, summary = summary, onClick = onClick, endActions = {
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick = onClick)
    })
}

@Composable
internal fun AuditDisclosure(title: String, detail: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    TextButton(if (expanded) "收起$title" else "了解$title", onClick = { expanded = !expanded })
    AnimatedVisibility(expanded) {
        Text(detail, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun AuditConfiguration(
    config: SecurityAuditConfig,
    onChange: (SecurityAuditConfig) -> Unit
) {
    val connection = config.connection
    val flags = connection.failureFlags
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接方式", style = MiuixTheme.textStyles.subtitle)
        LegacyTabRow(
            tabs = listOf("测试连通性", "测试连通性（网卡）"),
            selectedTabIndex = config.connectionMethod.ordinal,
            onTabSelected = {
                val method = SecurityAuditConnectionMethod.entries[it]
                // 多线程只有网卡方式支持，切回系统方式时一并关掉，避免带着非法配置进服务侧校验。
                onChange(
                    config.copy(
                        connectionMethod = method, multithreaded = config.multithreaded &&
                                method == SecurityAuditConnectionMethod.NETWORK_CARD
                    )
                )
            })
        AnimatedVisibility(config.connectionMethod == SecurityAuditConnectionMethod.NETWORK_CARD) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MAC 策略", style = MiuixTheme.textStyles.subtitle)
                LegacyTabRow(
                    tabs = listOf("设备 MAC", "异常时更换", "每次更换"),
                    selectedTabIndex = config.macMode.ordinal,
                    onTabSelected = { onChange(config.copy(macMode = SecurityAuditMacMode.entries[it])) })
                AnimatedVisibility(config.macMode == SecurityAuditMacMode.ON_FAILURE) {
                    AuditSlider("连续重试次数", config.macChangeThreshold.toLong(), 1, 5, 1, "次") {
                        onChange(config.copy(macChangeThreshold = it.toInt()))
                    }
                }
                AuditChoice(
                    "开启多线程运行（实验性）", config.multithreaded,
                    onClick = { onChange(config.copy(multithreaded = !config.multithreaded)) })
                AuditDisclosure("多线程运行", "使用可用网卡并行测试；网卡不足时自动排队。")
            }
        }
        Text(
            "单次尝试",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.primary
        )
        AuditSlider("最大尝试时间", connection.timeoutMillis, 1000L, 10000L, 500L, "ms") {
            onChange(config.copy(connection = connection.copy(timeoutMillis = it)))
        }
        Text("失败标志", style = MiuixTheme.textStyles.subtitle)
        val failure = when {
            flags.handshakeAttemptsExceeded != null -> 2; flags.handshakeTimeout != null -> 1; else -> 0
        }
        LegacyTabRow(
            tabs = listOf("密码错误", "握手超时", "握手超次"),
            selectedTabIndex = failure,
            onTabSelected = {
                onChange(
                    config.copy(
                        connection = connection.copy(
                            failureFlags = when (it) {
                                0 -> ConnectWifiFailureFlags(true, null, null)
                                1 -> ConnectWifiFailureFlags(
                                    false,
                                    null,
                                    HandshakeTimeoutFlag(config.handshakeTimeoutMillis)
                                )

                                else -> ConnectWifiFailureFlags(
                                    false,
                                    HandshakeAttemptsExceededFlag(config.maxHandshakeAttempts),
                                    null
                                )
                            }
                        )
                    )
                )
            })
        AnimatedVisibility(failure == 1) {
            AuditSlider(
                "握手超时",
                flags.handshakeTimeout?.handshakeStepTimeoutMillis ?: 1500,
                500,
                5000,
                250,
                "ms"
            ) {
                onChange(
                    config.copy(
                        handshakeTimeoutMillis = it,
                        connection = connection.copy(
                            failureFlags = flags.copy(
                                handshakeTimeout = HandshakeTimeoutFlag(it)
                            )
                        )
                    )
                )
            }
        }
        AnimatedVisibility(failure == 2) {
            AuditSlider(
                "最大握手次数",
                (flags.handshakeAttemptsExceeded?.maxHandshakeAttempts ?: 1).toLong(),
                1,
                5,
                1,
                "次"
            ) {
                onChange(
                    config.copy(
                        maxHandshakeAttempts = it.toInt(),
                        connection = connection.copy(
                            failureFlags = flags.copy(
                                handshakeAttemptsExceeded = HandshakeAttemptsExceededFlag(it.toInt())
                            )
                        )
                    )
                )
            }
        }
        Text(
            "异常重试",
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.primary
        )
        AuditDisclosure("异常重试", "超时或拒绝接入时重试，达到上限后尝试下一个密码。冷却时间逐次翻倍。")
        AuditSlider(
            "重试次数",
            config.retryCount.toLong(),
            0,
            6,
            1,
            "次",
            valueText = if (config.retryCount == 6) "无限" else null
        ) {
            onChange(config.copy(retryCount = it.toInt()))
        }
        AnimatedVisibility(config.retryCount != 0) {
            AuditSlider(
                "冷却翻倍基数", config.cooldownBaseMillis, 0, 8000, 500, "ms",
                valueText = if (config.cooldownBaseMillis == 0L) "不冷却" else null
            ) { onChange(config.copy(cooldownBaseMillis = it)) }
        }
    }
}

@Composable
internal fun AuditSlider(
    title: String, value: Long, min: Long, max: Long, step: Long, unit: String,
    valueText: String? = null, onChange: (Long) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text(
                valueText ?: if (unit == "ms") "${value}毫秒" else "$value$unit",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange((it / step).roundToLong() * step) },
            valueRange = min.toFloat()..max.toFloat(), steps = ((max - min) / step - 1).toInt()
        )
    }
}

@Composable
fun AuditNetworkProgress(records: List<AuditRecord.Dictionary>, onStopRecord: (String) -> Unit,
    onInspectRecord: ((String) -> Unit)? = null) {
    var lastRecords by remember { mutableStateOf(records) }
    if (records.isNotEmpty()) lastRecords = records
    AnimatedVisibility(
        visible = records.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val members = lastRecords
        val newest = members.maxByOrNull { it.updatedAt } ?: return@AnimatedVisibility
        val statistics = members.maxBy { it.groupElapsedMillis }
        val completed = members.maxOf { it.poolCompleted ?: it.completed }
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (members.size == 1) newest.state.label(newest.kind)
                    else "${members.count { it.active }} 项测试中", style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onTertiaryContainer)
                if (members.size == 1 && newest.active) TextButton("停止", onClick = { onStopRecord(newest.id) })
            }
            LinearProgressIndicator(
                progress = if (newest.total > 0) completed.toFloat() / newest.total else null,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已试 $completed 项，共 ${newest.total} 项", style = MiuixTheme.textStyles.footnote1)
                statistics.groupAverageAttemptMillis?.takeIf { it > 0 }?.let { cost ->
                    Text("每分钟 ${60000 / cost} 次", style = MiuixTheme.textStyles.footnote1)
                }
            }
            statistics.groupAverageAttemptMillis?.takeIf { it > 0 }?.let { cost ->
                Text("预计还需 ${formatAuditDuration((newest.total - completed).coerceAtLeast(0) * cost)}",
                    style = MiuixTheme.textStyles.footnote1)
            }
            if (members.size == 1) {
                Text(newest.runStatus(), style = MiuixTheme.textStyles.body2,
                    modifier = if (onInspectRecord != null) Modifier.clickable { onInspectRecord(newest.id) } else Modifier)
            } else members.forEachIndexed { index, record ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${index + 1} 项：${record.runStatus()}",
                        modifier = Modifier
                            .weight(1f)
                            .then(if (onInspectRecord != null) Modifier.clickable {
                                onInspectRecord(record.id)
                            } else Modifier), style = MiuixTheme.textStyles.body2)
                    if (record.active) TextButton("停止", onClick = { onStopRecord(record.id) })
                }
            }
        }
    }
}

private fun AuditRecord.Dictionary.runStatus(): String = when (state) {
    SecurityAuditNetworkState.CONNECTING ->
        stage?.label() ?: "正在连接路由器"
    SecurityAuditNetworkState.QUEUED -> if (config.connectionMethod == SecurityAuditConnectionMethod.SYSTEM)
        "排队等待连接" else "排队等待网卡"
    SecurityAuditNetworkState.COOLDOWN -> "冷却中"
    else -> state.label()
}

@Composable
private fun AuditRecordDetails(record: AuditHistoryRecord.Dictionary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(record.historySummary(), style = MiuixTheme.textStyles.subtitle)
        Text("记录时间：${timestamp(record.updatedAt)}", style = MiuixTheme.textStyles.body2)
        Text("已试 ${record.completed} 项，共 ${record.total} 项", style = MiuixTheme.textStyles.body2)
        Text("总用时：${formatAuditDuration(record.groupElapsedMillis)}", style = MiuixTheme.textStyles.body2)
        record.groupAverageAttemptMillis?.let {
            if (it > 0) Text("平均每分钟 ${60000 / it} 次", style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
fun SecurityAuditTaskSheet(
    task: TrackedTaskState, onStop: () -> Unit, onStopRecord: (String) -> Unit,
    onStopNetwork: (String) -> Unit, onDismiss: () -> Unit,
    onInspect: (String) -> Unit,
) {
    val dictionaryNetworks = task.auditNetworks.filterIsInstance<AuditRecord.Dictionary>()
        .groupBy { it.ssid }
    var show by rememberSaveable(task.snapshot.taskId) { mutableStateOf(true) }
    var logsExpanded by rememberSaveable(task.snapshot.taskId) { mutableStateOf(false) }
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    SingleOverlayBottomSheet(
        show = show, title = "网络安全测试",
        onDismissRequest = { show = false }, onDismissFinished = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        if (task.snapshot.state == TaskExecutionState.RUNNING) "运行中" else "任务已结束",
                        style = MiuixTheme.textStyles.subtitle
                    )
                }
                items(dictionaryNetworks.toList(), key = { "network-${it.first}" }) { (ssid, records) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(ssid, style = MiuixTheme.textStyles.subtitle,
                                modifier = Modifier.weight(1f))
                            if (task.snapshot.state == TaskExecutionState.RUNNING && records.any { it.active }) {
                                TextButton("停止此网络", onClick = { onStopNetwork(ssid) })
                            }
                        }
                        records.groupBy { it.groupId ?: it.id }.values.forEach { members ->
                            AuditNetworkProgress(members, onStopRecord, onInspectRecord = { recordId ->
                                show = false
                                onInspect(recordId)
                            })
                        }
                    }
                }
                item {
                    TextButton(
                        if (logsExpanded) "收起运行日志" else "展开运行日志",
                        onClick = { logsExpanded = !logsExpanded })
                }
                if (logsExpanded) items(task.logs.entries, key = { it.taskLineId }) {
                    Text(
                        it.text,
                        style = MiuixTheme.textStyles.body2
                    )
                }
            }
            if (task.snapshot.state == TaskExecutionState.RUNNING) TextButton(
                "停止任务",
                onStop,
                Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            Spacer(Modifier.height(safeBottom))
        }
    }
}

@Composable
fun SecurityAuditHistoryScreen(viewModel: DefaultViewModel) {
    val navigator = LocalNavigator.current
    val history by viewModel.securityAudit.history.collectAsStateWithLifecycle()
    val sorted = remember(history) {
        history.sortedWith(compareByDescending<AuditHistoryRecord> { it.password != null }
            .thenByDescending { it.updatedAt })
    }
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = "安全测试历史",
                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                "返回"
                            )
                        }
                    },
                    scrollBehavior = scroll
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
    ) { padding ->
        Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scroll.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = WindowInsets.safeDrawing.asPaddingValues()
                        .calculateBottomPadding() + 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp), overscrollEffect = null
            ) {
                if (sorted.isEmpty()) item { Text("暂无安全测试历史") }
                items(
                    sorted,
                    key = { if (it is AuditHistoryRecord.Dictionary) "dictionary-${it.ssid}" else "wps-${it.id}" }) { record ->
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(
                            onClick = {
                                if (record is AuditHistoryRecord.Dictionary) viewModel.securityAudit.inspect(
                                    record.id
                                )
                                else viewModel.securityAudit.inspectWps(record.id)
                            }, endActions = {
                                IconButton(onClick = {
                                    viewModel.showConfirmationDialog(
                                        title = "删除历史记录",
                                        content = "删除 ${record.ssid} 的这条安全测试记录？",
                                        onConfirmed = {
                                            viewModel.securityAudit.deleteHistory(record.id)
                                        })
                                }) {
                                    Icon(Icons.Rounded.DeleteOutline, "删除历史")
                                }
                            }) {
                            Text(record.ssid.ifEmpty { "隐藏网络" }, style = MiuixTheme.textStyles.subtitle)
                            Text(if (record.kind == AuditRecordKind.WPS) "WPS 安全测试" else "密码安全测试",
                                style = MiuixTheme.textStyles.footnote1)
                            Text(record.historySummary(), style = MiuixTheme.textStyles.body2)
                            Text(timestamp(record.updatedAt), style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
    }
}

internal fun timestamp(value: Long) =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))

internal fun formatAuditDuration(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1000
    if (seconds < 60) return "${seconds}秒"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}分${seconds % 60}秒"
    return "${minutes / 60}时${minutes % 60}分"
}

internal fun SecurityAuditNetworkState.label(kind: AuditRecordKind = AuditRecordKind.DICTIONARY) =
    when (this) {
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

internal fun AuditHistoryRecord.historySummary(): String = when (this) {
    is AuditHistoryRecord.Dictionary -> when {
        password != null -> "发现弱密码"
        completed >= total -> "字典已耗尽"
        else -> "已试 $completed 项，共 $total 项"
    }

    is AuditHistoryRecord.Wps -> if (password != null) "取得密码" else "${wpsPhaseLabel(phase)}，已记录进度"
}

private fun ConnectWifiStage.label() = when (this) {
    ConnectWifiStage.ROUTER_COMMUNICATION -> "与路由器建立通信"
    ConnectWifiStage.WPA_HANDSHAKE_1_OF_4 -> "握手第一步"
    ConnectWifiStage.WPA_HANDSHAKE_2_OF_4 -> "握手第二步"
    ConnectWifiStage.WPA_HANDSHAKE_3_OF_4 -> "握手第三步"
    ConnectWifiStage.WPA_HANDSHAKE_4_OF_4 -> "握手第四步"
    ConnectWifiStage.IP_NEGOTIATION -> "获取 IP 地址"
}
