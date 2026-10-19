package io.github.bszapp.wifitoolbox.ui.psksecurityaudit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import io.github.bszapp.wifitoolbox.contract.settings.AuditLogPanelConfig
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.ui.component.RawLogView
import io.github.bszapp.wifitoolbox.ui.component.WeakPasswordResult
import io.github.bszapp.wifitoolbox.ui.component.LegacyTabRow
import io.github.bszapp.wifitoolbox.ui.model.*
import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.ui.util.BlurredBar
import io.github.bszapp.wifitoolbox.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun PskSecurityAuditSheets(model: PskSecurityAuditUiState) {
    val form by model.sheet.collectAsStateWithLifecycle()
    val dictionaries by model.dictionaries.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val auditTasks by model.auditTasks.collectAsStateWithLifecycle()
    val availableInterfaces by model.availableInterfaces.collectAsStateWithLifecycle()
    val sharedSources = auditTasks.filter { it.snapshot.state == TaskExecutionState.RUNNING }
        .flatMap { it.auditNetworks }.filterIsInstance<AuditRecord.Dictionary>()
        .filter { it.active }.distinctBy { it.groupId ?: it.id }
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .8f
    SingleOverlayBottomSheet(
        show = form.show, title = when (form.page) {
            PskSecurityAuditSheetPage.CONFIGURATION -> "弱密码检测运行行为"
            PskSecurityAuditSheetPage.DICTIONARIES -> "选择字典"
            PskSecurityAuditSheetPage.DETAIL -> "弱密码检测详情"
            PskSecurityAuditSheetPage.SAVED -> "已保存的网络"
            PskSecurityAuditSheetPage.RECOVERY -> "未完成的弱密码检测"
            PskSecurityAuditSheetPage.MANUAL -> "手动输入网络名称"
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
                    PskSecurityAuditSheetPage.CONFIGURATION -> item {
                        AuditConfiguration(
                            form.config,
                            model::changeConfig,
                            availableInterfaces,
                        )
                    }

                    PskSecurityAuditSheetPage.DICTIONARIES -> {
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

                    PskSecurityAuditSheetPage.MANUAL -> item {
                        TextField(
                            value = form.ssid,
                            onValueChange = model::changeSsid,
                            label = "SSID",
                            singleLine = true
                        )
                    }

                    PskSecurityAuditSheetPage.SAVED -> item {
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

                    PskSecurityAuditSheetPage.RECOVERY -> item {
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

                    PskSecurityAuditSheetPage.DETAIL -> {
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
                if (form.page == PskSecurityAuditSheetPage.DICTIONARIES) {
                    if (form.saved && !form.useSavedPassword && form.config.connectionMethod == PskSecurityAuditConnectionMethod.SYSTEM) item {
                        Text(
                            "系统弱密码检测会更新此网络的保存配置。每轮结束保留配置；停止、字典耗尽或执行异常时删除，成功时保留。不恢复测试前的密码。",
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
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
            if (form.page != PskSecurityAuditSheetPage.CONFIGURATION) Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton("关闭", onClick = model::dismiss, modifier = Modifier.weight(1f))
                when (form.page) {
                    PskSecurityAuditSheetPage.DICTIONARIES -> TextButton(
                        if (form.busy) "准备中" else "运行",
                        onClick = model::requestRun,
                        enabled = !form.busy && form.candidateCount > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )

                    PskSecurityAuditSheetPage.DETAIL -> Unit
                    PskSecurityAuditSheetPage.SAVED, PskSecurityAuditSheetPage.RECOVERY -> Unit
                    PskSecurityAuditSheetPage.MANUAL -> TextButton(
                        "下一步", model::selectManualNetwork,
                        Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary()
                    )

                    PskSecurityAuditSheetPage.CONFIGURATION -> Unit
                }
            }
            Spacer(Modifier.height(safeBottom))
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
    config: PskSecurityAuditConfig,
    onChange: (PskSecurityAuditConfig) -> Unit,
    availableInterfaces: List<String>,
) {
    val connection = config.connection
    val flags = connection.failureFlags
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接方式", style = MiuixTheme.textStyles.subtitle)
        LegacyTabRow(
            tabs = listOf("测试连通性", "测试连通性（网卡）"),
            selectedTabIndex = config.connectionMethod.ordinal,
            onTabSelected = {
                val method = PskSecurityAuditConnectionMethod.entries[it]
                onChange(config.copy(connectionMethod = method))
            })
        AnimatedVisibility(config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MAC 策略", style = MiuixTheme.textStyles.subtitle)
                LegacyTabRow(
                    tabs = listOf("设备 MAC", "异常时更换", "每次更换"),
                    selectedTabIndex = config.macMode.ordinal,
                    onTabSelected = { onChange(config.copy(macMode = PskSecurityAuditMacMode.entries[it])) })
                AnimatedVisibility(config.macMode == PskSecurityAuditMacMode.ON_FAILURE) {
                    AuditSlider("连续重试次数", config.macChangeThreshold.toLong(), 1, 5, 1, "次") {
                        onChange(config.copy(macChangeThreshold = it.toInt()))
                    }
                }
                Text("使用网卡（实验性）", style = MiuixTheme.textStyles.subtitle)
                (listOf("wlan0") + availableInterfaces + config.selectedInterfaces).distinct().forEach { name ->
                    AuditChoice(
                        if (name in availableInterfaces) name else "$name（未发现）",
                        name in config.selectedInterfaces,
                        onClick = {
                            val selected = if (name in config.selectedInterfaces) config.selectedInterfaces - name
                                else config.selectedInterfaces + name
                            onChange(config.copy(selectedInterfaces = selected))
                        },
                    )
                }
                AuditDisclosure("网卡多选（实验性）", "选中的可用网卡各运行一项尝试；全部取消时保持排队。未选wlan0时保持系统Wi-Fi连接。")
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
        val sharedPool = newest.groupId != null
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (members.size == 1) newest.state.label(newest.kind)
                        else "${members.count { it.active }}项测试中",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (members.size > 1) {
                        Text(
                            if (sharedPool) "共享池" else "独立池",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 6.dp),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    statistics.groupAverageAttemptMillis?.takeIf { it > 0 }?.let { cost ->
                        Text(
                            "${60000 / cost}/m",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 6.dp),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (members.size == 1 && newest.active) {
                    Text(
                        "停止",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .clickable { onStopRecord(newest.id) },
                    )
                }
            }
            if (newest.state == PskSecurityAuditNetworkState.QUEUED || newest.total <= 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.22f)),
                )
            } else {
                LinearProgressIndicator(
                    progress = completed.toFloat() / newest.total,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val status = newest.runStatus()
                Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已试$completed/${newest.total}", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (status.isNotEmpty()) {
                        Text(status, style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                val cost = statistics.groupAverageAttemptMillis?.takeIf { it > 0 }
                val remainingMillis = cost?.times((newest.total - completed).coerceAtLeast(0))
                Text(
                    if (remainingMillis != null) {
                        "预计剩余${formatAuditDuration(remainingMillis)}"
                    } else "计算剩余时间",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    softWrap = false)
            }
            if (members.size > 1) {
                members.forEachIndexed { index, record ->
                    val status = record.runStatus()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("第${index + 1}项", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = if (onInspectRecord != null) Modifier.clickable {
                                    onInspectRecord(record.id)
                                } else Modifier)
                            if (status.isNotEmpty()) {
                                Text(status, style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (onInspectRecord != null) Modifier.clickable {
                                        onInspectRecord(record.id)
                                    } else Modifier)
                            }
                        }
                        if (record.active) {
                            Text(
                                "停止",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .clickable { onStopRecord(record.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRecord.Dictionary.runStatus(): String = when (state) {
    PskSecurityAuditNetworkState.CONNECTING ->
        stage?.label() ?: "正在连接路由器"
    PskSecurityAuditNetworkState.QUEUED -> if (config.connectionMethod == PskSecurityAuditConnectionMethod.SYSTEM)
        "排队等待连接" else "排队等待网卡"
    PskSecurityAuditNetworkState.COOLDOWN -> "冷却中，${auditCooldownRemainingLabel(nextAttemptAt)}"
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
            if (it > 0) Text("${60000 / it}/m", style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
fun PskSecurityAuditTaskSheet(
    task: TrackedTaskState?, onStop: () -> Unit, onDismiss: () -> Unit,
    config: PskSecurityAuditConfig = PskSecurityAuditConfig(),
    logPanel: AuditLogPanelConfig = AuditLogPanelConfig(),
    onDetailedLoggingChanged: (Boolean) -> Unit = {},
    onLogPanelChanged: (AuditLogPanelConfig) -> Unit = {},
    onClearLogPanel: () -> Unit = {},
) {
    var show by rememberSaveable(task?.snapshot?.taskId) { mutableStateOf(true) }
    val running = task?.snapshot?.state == TaskExecutionState.RUNNING
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    SingleOverlayBottomSheet(
        show = show, title = "弱密码检测",
        onDismissRequest = { show = false }, onDismissFinished = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .8f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when {
                    task == null -> "空闲"
                    running -> "运行中"
                    else -> "已结束"
                },
                style = MiuixTheme.textStyles.subtitle,
            )
            AuditTaskLogView(
                task = task,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                logPanel = logPanel,
                onLogPanelChanged = onLogPanelChanged,
                detailedLogging = config.detailedLogging,
                onDetailedLoggingChanged = onDetailedLoggingChanged,
                onClearLogPanel = onClearLogPanel,
            )
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("停止任务")
                }
            }
            Spacer(Modifier.height(safeBottom))
        }
    }
}

@Composable
internal fun AuditTaskLogView(
    task: TrackedTaskState?,
    modifier: Modifier = Modifier,
    logPanel: AuditLogPanelConfig = AuditLogPanelConfig(),
    onLogPanelChanged: (AuditLogPanelConfig) -> Unit = {},
    detailedLogging: Boolean? = null,
    onDetailedLoggingChanged: (Boolean) -> Unit = {},
    onClearLogPanel: () -> Unit = {},
) {
    val softWrap = logPanel.softWrap
    val autoScroll = logPanel.autoScroll
    val listState = rememberLazyListState()
    val allEntries = task?.logs?.entries.orEmpty()
    val minIndex = task?.logPanelMinIndex ?: 0L
    val entries = remember(allEntries, minIndex) {
        val match = allEntries.binarySearchBy(minIndex) { it.taskLineId }
        val start = if (match >= 0) match else -match - 1
        allEntries.subList(start, allEntries.size)
    }
    LaunchedEffect(autoScroll, task?.snapshot?.taskId, task?.logs?.generation, minIndex, softWrap, logPanel.fontSizeSp) {
        if (autoScroll && entries.isNotEmpty()) listState.animateScrollToItem(entries.size)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("自动换行", style = MiuixTheme.textStyles.footnote1)
                Switch(checked = softWrap, onCheckedChange = { onLogPanelChanged(logPanel.copy(softWrap = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("自动滚动", style = MiuixTheme.textStyles.footnote1)
                Switch(checked = autoScroll, onCheckedChange = { onLogPanelChanged(logPanel.copy(autoScroll = it)) })
            }
            if (detailedLogging != null) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("详细日志", style = MiuixTheme.textStyles.footnote1)
                    Switch(
                        checked = detailedLogging,
                        onCheckedChange = onDetailedLoggingChanged,
                    )
                }
            }
            TextButton(text = "清空", onClick = onClearLogPanel, enabled = entries.isNotEmpty())
        }
        key(task?.snapshot?.taskId) {
            RawLogView(
                entries = entries,
                text = TaskLogEntry::text,
                listState = listState,
                softWrap = softWrap,
                fontSizeSp = logPanel.fontSizeSp,
                onFontSizeChanged = { onLogPanelChanged(logPanel.copy(fontSizeSp = it)) },
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

@Composable
fun PskSecurityAuditHistoryScreen(viewModel: DefaultViewModel) {
    val history by viewModel.pskSecurityAudit.history.collectAsStateWithLifecycle()
    PskSecurityAuditHistoryContent(
        history = history,
        onInspect = { record ->
            if (record is AuditHistoryRecord.Dictionary) viewModel.pskSecurityAudit.inspect(record.id)
            else viewModel.pskSecurityAudit.inspectWps(record.id)
        },
        onDelete = { record ->
            viewModel.showConfirmationDialog(
                title = "删除历史记录",
                content = "删除 ${record.ssid} 的这条弱密码检测记录？",
                onConfirmed = {
                    viewModel.pskSecurityAudit.deleteHistory(record.id)
                })
        },
    )
}

@Composable
fun PskSecurityAuditHistoryContent(
    history: List<AuditHistoryRecord>,
    onInspect: (AuditHistoryRecord) -> Unit,
    onDelete: (AuditHistoryRecord) -> Unit,
) {
    val navigator = LocalNavigator.current
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
                    title = "弱密码检测历史",
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
                if (sorted.isEmpty()) item { Text("暂无弱密码检测历史") }
                items(
                    sorted,
                    key = { if (it is AuditHistoryRecord.Dictionary) "dictionary-${it.ssid}" else "wps-${it.id}" }) { record ->
                    Card(Modifier.fillMaxWidth()) {
                        BasicComponent(
                            onClick = { onInspect(record) }, endActions = {
                                IconButton(onClick = { onDelete(record) }) {
                                    Icon(Icons.Rounded.DeleteOutline, "删除历史")
                                }
                            }) {
                            Text(record.ssid.ifEmpty { "隐藏网络" }, style = MiuixTheme.textStyles.subtitle)
                            Text(if (record.kind == AuditRecordKind.WPS) "PIN漏洞检测" else "密码弱密码检测",
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
    // 旧版时间格式：不足1分钟为「<1m」，其余按「Xd Xh Xm」拼接。
    val totalMinutes = millis.coerceAtLeast(0) / 60000
    if (totalMinutes < 1) return "<1m"
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    val parts = mutableListOf<String>()
    if (days > 0) parts.add("${days}d")
    if (hours > 0 || days > 0) parts.add("${hours}h")
    parts.add("${minutes}m")
    return parts.joinToString(" ")
}

/** 倒计时只在界面刷新，剩余不足一秒时向上取整。 */
@Composable
internal fun auditCooldownRemainingLabel(nextAttemptAt: Long?): String {
    val deadline = nextAttemptAt ?: return "等待重试"
    var now by remember(deadline) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (now < deadline) {
            delay((deadline - now).coerceIn(1L, 1000L))
            now = System.currentTimeMillis()
        }
    }
    val remaining = (deadline - now).coerceAtLeast(0)
    val seconds = remaining / 1000 + if (remaining % 1000 > 0) 1 else 0
    return "剩余${seconds}秒"
}

internal fun PskSecurityAuditNetworkState.label(kind: AuditRecordKind = AuditRecordKind.DICTIONARY) =
    when (this) {
        PskSecurityAuditNetworkState.QUEUED -> "排队中"
        PskSecurityAuditNetworkState.CONNECTING -> if (kind == AuditRecordKind.WPS) "握手中" else "测试中"
        PskSecurityAuditNetworkState.COOLDOWN -> "冷却中"
        PskSecurityAuditNetworkState.SUCCEEDED -> if (kind == AuditRecordKind.WPS) "取得密码" else "发现弱密码"
        PskSecurityAuditNetworkState.EXHAUSTED -> if (kind == AuditRecordKind.WPS) "已结束" else "字典已耗尽"
        PskSecurityAuditNetworkState.STOPPED -> "已停止"
        PskSecurityAuditNetworkState.ERROR -> "执行异常"
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
