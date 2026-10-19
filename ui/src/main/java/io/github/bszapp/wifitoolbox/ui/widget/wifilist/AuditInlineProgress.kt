package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.task.AuditRecord
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditConnectionMethod
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditNetworkState
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditPhase
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.formatAuditDuration
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.label
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.wpsPhaseLabel
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.attemptStageLabel
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.auditCooldownRemainingLabel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.size

/**
 * 审计整页的行内进度展开区：贴在网络组卡下方，无独立容器色，
 * 字典池展示公共进度和统计，每条测试成员独立展示状态与占用网卡，停止操作由主行按钮承担。
 * 文案一律用compose组合拆分，不使用分隔点或非键盘字符。
 */

/** 进度条与平面轨道的统一高度，与miuix线性指示器视觉一致。 */
private val AuditProgressHeight = 4.dp

@Composable
internal fun DictionaryAuditInlineGroups(
    modifier: Modifier = Modifier,
    groups: List<List<AuditRecord.Dictionary>> = emptyList(),
    onStopGroup: (String) -> Unit = {},
    onAddMember: (String, String) -> Unit = { _, _ -> },
    onRemoveMember: (String) -> Unit = {},
) {
    if (groups.isEmpty()) return
    // 按成员ID分配编号，状态刷新、移除成员与新增候选池都不重排已有编号。
    val taskNumbers = remember { mutableMapOf<String, Int>() }
    groups.flatten().forEach { record ->
        taskNumbers.getOrPut(record.id) { taskNumbers.size + 1 }
    }
    if (groups.size == 1) {
        DictionaryAuditInline(
            records = groups.first(),
            taskNumbers = taskNumbers,
            onAddMember = onAddMember,
            onRemoveMember = onRemoveMember,
            modifier = modifier,
        )
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { members ->
                DictionaryAuditInline(
                    records = members,
                    taskNumbers = taskNumbers,
                    showStop = true,
                    onStopGroup = onStopGroup,
                    onAddMember = onAddMember,
                    onRemoveMember = onRemoveMember,
                )
            }
        }
    }
}

@Composable
internal fun WpsAuditInlines(
    modifier: Modifier = Modifier,
    records: List<AuditRecord.Wps> = emptyList(),
    onStopRecord: (AuditRecord.Wps) -> Unit = {},
) {
    if (records.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        records.forEach { record ->
            WpsAuditInline(
                record = record,
                showStop = records.size > 1,
                onStopRecord = onStopRecord,
            )
        }
    }
}

/** 同 ssid 同池成员的增删控件：加号追加一条成员，减号移除一条成员，中间数字是池内成员数。 */
@Composable
private fun PoolMemberAdjuster(
    memberCount: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            Icons.Rounded.Add,
            contentDescription = "追加测试",
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onAdd)
                .padding(2.dp),
        )
        Text(
            "$memberCount",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            softWrap = false,
        )
        Icon(
            Icons.Rounded.Remove,
            contentDescription = "移除测试",
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onRemove)
                .padding(2.dp),
        )
    }
}

@Composable
private fun DictionaryAuditInline(
    records: List<AuditRecord.Dictionary>,
    taskNumbers: Map<String, Int>,
    showStop: Boolean = false,
    onStopGroup: (String) -> Unit = {},
    onAddMember: (String, String) -> Unit = { _, _ -> },
    onRemoveMember: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val representative = records.firstOrNull() ?: return
    val statistics = records.maxBy { it.groupElapsedMillis }
    val completed = records.maxOf { it.poolCompleted ?: it.completed }
    val fraction = if (representative.total > 0) completed.toFloat() / representative.total else 0f
    val activeMembers = records.filter { it.active }
    val memberCount = activeMembers.size
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${memberCount}项测试",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showStop) {
                    Text(
                        if (representative.groupId != null) "共享池" else "独立池",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 6.dp),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                PoolMemberAdjuster(
                    memberCount = memberCount,
                    onAdd = { onAddMember(representative.input.ssid, representative.groupId ?: representative.id) },
                    onRemove = { activeMembers.firstOrNull()?.let { member -> onRemoveMember(member.id) } },
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            if (showStop) {
                Text(
                    "停止",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onStopGroup(representative.groupId ?: representative.id) },
                )
            } else {
                val cost = statistics.groupAverageAttemptMillis?.takeIf { it > 0 }
                Text(
                    if (cost != null) "${60000 / cost}/m" else "-/m",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        AuditInlineBar(
            progress = fraction,
            indeterminate = false,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已试$completed/${representative.total}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val cost = statistics.groupAverageAttemptMillis?.takeIf { it > 0 }
            val remainingMillis = cost?.times((representative.total - completed).coerceAtLeast(0))
            Text(
                if (remainingMillis != null) {
                    "预计剩余${formatAuditDuration(remainingMillis)}"
                } else "计算剩余时间",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
        activeMembers.sortedBy { taskNumbers.getValue(it.id) }.forEach { member ->
            key(member.id) {
                DictionaryAuditMemberLine(member, taskNumbers.getValue(member.id))
            }
        }
    }
}

/** 每条成员独立成行，所有字段均来自该成员的实时快照。 */
@Composable
private fun DictionaryAuditMemberLine(record: AuditRecord.Dictionary, number: Int) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "任务$number",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            record.stateLabel(),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
        )
        if (record.config.connectionMethod == PskSecurityAuditConnectionMethod.NETWORK_CARD) {
            val window = record.window
            if (window != null) {
                Row {
                    Text("网卡", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(window, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            } else {
                Text("未占用网卡", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else {
            Text("系统连接", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        val action = when (record.state) {
            PskSecurityAuditNetworkState.CONNECTING -> record.stage?.label() ?: "正在连接路由器"
            PskSecurityAuditNetworkState.QUEUED -> "等待调度"
            PskSecurityAuditNetworkState.COOLDOWN -> "等待第${record.retryCount}次重试"
            else -> record.message
        }
        Text(action, style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (record.state == PskSecurityAuditNetworkState.COOLDOWN) {
            Text(auditCooldownRemainingLabel(record.nextAttemptAt), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (record.state == PskSecurityAuditNetworkState.CONNECTING) {
            record.attemptPassword?.let { password ->
                Row {
                    Text("尝试", style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(password, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun WpsAuditInline(
    record: AuditRecord.Wps,
    showStop: Boolean = false,
    onStopRecord: (AuditRecord.Wps) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val fraction = record.progress
    val phaseText = wpsPhaseLabel(record.phase)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mac = record.mac
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    record.stateLabel(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showStop && mac != null) {
                    Text(
                        mac.takeLast(5),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (record.state == PskSecurityAuditNetworkState.CONNECTING) {
                    Text(
                        phaseText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false,
                    )
                    record.attemptPin?.let { pin ->
                        Text("PIN$pin", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, softWrap = false)
                    }
                    Text(record.attemptStageLabel(), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                } else if (record.state == PskSecurityAuditNetworkState.COOLDOWN) {
                    Text(auditCooldownRemainingLabel(record.nextAttemptAt), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (showStop) {
                Text(
                    "停止",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onStopRecord(record) },
                )
            }
        }
        AuditInlineBar(
            progress = fraction.takeIf { record.state != PskSecurityAuditNetworkState.QUEUED },
            indeterminate = record.state == PskSecurityAuditNetworkState.CONNECTING && fraction == null,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    record.state == PskSecurityAuditNetworkState.QUEUED -> Text(
                        "等待连接",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                    record.phase == WpsSecurityAuditPhase.FIRST_HALF -> {
                        Text(
                            "已试${record.firstHalfIndex}/10000",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    record.phase == WpsSecurityAuditPhase.SECOND_HALF -> {
                        Text(
                            "已试${record.secondHalfIndex}/1000",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    else -> Unit
                }
            }
            val avg = record.averageAttemptMillis
            Text(
                if (avg != null && avg > 0) "${60000 / avg}/m" else "-/m",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun AuditInlineBar(
    progress: Float?,
    indeterminate: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        indeterminate -> LinearProgressIndicator(progress = null, modifier = modifier.fillMaxWidth())
        progress == null || progress <= 0f -> Box(
            modifier = modifier
                .fillMaxWidth()
                .height(AuditProgressHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.22f)),
        )
        else -> LinearProgressIndicator(progress = progress, modifier = modifier.fillMaxWidth())
    }
}

private fun AuditRecord.stateLabel(): String = state.label(kind)

private fun ConnectWifiStage.label(): String = when (this) {
    ConnectWifiStage.ROUTER_COMMUNICATION -> "与路由器建立通信"
    ConnectWifiStage.WPA_HANDSHAKE_1_OF_4 -> "握手第一步"
    ConnectWifiStage.WPA_HANDSHAKE_2_OF_4 -> "握手第二步"
    ConnectWifiStage.WPA_HANDSHAKE_3_OF_4 -> "握手第三步"
    ConnectWifiStage.WPA_HANDSHAKE_4_OF_4 -> "握手第四步"
    ConnectWifiStage.IP_NEGOTIATION -> "获取IP地址"
}
