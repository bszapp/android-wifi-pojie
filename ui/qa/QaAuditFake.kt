// UI QA 假数据扩展：WifiDetailSheet 的分组、弱密码检测 / PIN漏洞检测任务快照。

package qa

import io.github.bszapp.wifitoolbox.contract.task.AuditRecord
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditConfig
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditNetworkInput
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditNetworkState
import io.github.bszapp.wifitoolbox.contract.task.PskSecurityAuditTaskInput
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskLogEntry
import io.github.bszapp.wifitoolbox.contract.task.TaskLogState
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.task.TaskStartRequest
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditConfig
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditPhase
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditTaskInput
import io.github.bszapp.wifitoolbox.contract.task.WpsSecurityAuditTarget
import io.github.bszapp.wifitoolbox.ui.model.MergedWifiGroup

/** 已连接 ChinaNet-Home 的分组（连接页 WifiDetailSheet 首图常用）。 */
fun qaConnectedGroup(): MergedWifiGroup =
    MergedWifiGroup.buildFrom(
        qaScanResults(),
        qaSavedNetworksNetList(),
        qaConnectedInfo(),
    ).first { it.isConnected }

private fun auditLog(scopeTaskId: Long, vararg lines: Pair<String, Long>): TaskLogState =
    TaskLogState(
        scopeTaskId = scopeTaskId,
        lineCount = lines.size,
        latestId = (lines.size - 1).toLong(),
        entries = lines.mapIndexed { i, (text, ts) ->
            TaskLogEntry(scopeTaskId, i.toLong(), i.toLong(), ts, text)
        },
    )

/** 弱密码检测：ChinaNet-Home 共享池组（2 成员）+ 独立池组；TP-LINK 冷却；CMCC 排队。 */
fun qaPskSecurityAuditTracked(): TrackedTaskState {
    val inputs = listOf(
        PskSecurityAuditNetworkInput(
            ssid = "ChinaNet-Home",
            dictionaryId = "common-wifi-1000",
            dictionaryNames = listOf("常用Wi-Fi密码1000"),
            resourceIds = listOf("common-wifi-1000"),
        ),
        PskSecurityAuditNetworkInput(
            ssid = "TP-LINK_A302",
            dictionaryId = "common-wifi-1000",
            dictionaryNames = listOf("常用Wi-Fi密码1000"),
            resourceIds = listOf("common-wifi-1000"),
        ),
        PskSecurityAuditNetworkInput(
            ssid = "CMCC-Office",
            dictionaryId = "common-wifi-1000",
            dictionaryNames = listOf("常用Wi-Fi密码1000"),
            resourceIds = listOf("common-wifi-1000"),
        ),
    )
    // 共享池组：同 SSID 两台记录共享候选池游标（poolCompleted 同步推进）。
    val sharedPoolGroup = listOf(
        AuditRecord.Dictionary(
            id = "audit-1",
            input = inputs[0],
            config = PskSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.CONNECTING,
            total = 1000,
            attempts = 183,
            groupAverageAttemptMillis = 4200,
            stage = ConnectWifiStage.WPA_HANDSHAKE_2_OF_4,
            groupId = "pool-1",
            poolCompleted = 183,
            window = "wlan0",
            message = "183/1000，正在尝试13800138183",
        ),
        AuditRecord.Dictionary(
            id = "audit-1b",
            input = inputs[0],
            config = PskSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.COOLDOWN,
            total = 1000,
            attempts = 90,
            retryCount = 1,
            nextAttemptAt = 1_800L,
            groupAverageAttemptMillis = 4200,
            groupId = "pool-1",
            poolCompleted = 183,
            window = "wlan1",
            message = "连接超时，冷却后重试",
        ),
    )
    // 独立池组：同 SSID 的独立任务，自备候选池游标。
    val independentGroup = listOf(
        AuditRecord.Dictionary(
            id = "audit-4",
            input = inputs[0],
            config = PskSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.CONNECTING,
            total = 1000,
            completed = 402,
            attempts = 402,
            groupAverageAttemptMillis = 3600,
            window = "wlan2",
            message = "402/1000，正在尝试778899402",
        ),
    )
    val requests = sharedPoolGroup + listOf(
        AuditRecord.Dictionary(
            id = "audit-2",
            input = inputs[1],
            config = PskSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.COOLDOWN,
            total = 1000,
            completed = 756,
            attempts = 756,
            retryCount = 2,
            nextAttemptAt = 3_600L,
            groupAverageAttemptMillis = 4200,
            message = "连接超时，冷却后重试",
        ),
        AuditRecord.Dictionary(
            id = "audit-3",
            input = inputs[2],
            config = PskSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.QUEUED,
            total = 1000,
            message = "排队中",
        ),
    ) + independentGroup
    return TrackedTaskState(
        snapshot = TaskSnapshot(
            taskId = 9L,
            request = TaskStartRequest(
                TaskRequestPayload.PskSecurityAudit(PskSecurityAuditTaskInput(PskSecurityAuditConfig(), inputs[0])),
            ),
            state = TaskExecutionState.RUNNING,
            progress = TaskProgress.PskSecurityAudit(
                revision = 183L,
                networkCount = 5,
                config = PskSecurityAuditConfig(),
            ),
        ),
        logs = auditLog(
            9L,
            "开始任务：弱密码检测，共5个网络" to 0L,
            "ChinaNet-Home：查入常用Wi-Fi密码1000（序号 183/1000）" to 1_800L,
            "TP-LINK_A302：连接超时，冷却1.0s后重试" to 2_400L,
            "WPA 握手完成，连接校验成功" to 3_100L,
        ),
        auditNetworks = requests,
    )
}

/** PIN漏洞检测：两网络跑 Fairy Dust / First Half 阶段。 */
fun qaWpsAuditTracked(): TrackedTaskState {
    val targets = listOf(
        WpsSecurityAuditTarget(ssid = "TP-LINK_A302", mac = "1c:7c:59:a3:02:11"),
        WpsSecurityAuditTarget(ssid = "ChinaNet-Home", mac = "8c:de:f9:0b:1a:20"),
    )
    val requests = listOf(
        AuditRecord.Wps(
            id = "wps-audit-1",
            ssid = "TP-LINK_A302",
            config = WpsSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.CONNECTING,
            phase = WpsSecurityAuditPhase.FIRST_HALF,
            firstHalf = "1122334455",
            firstHalfIndex = 3419,
            attempts = 3,
            averageAttemptMillis = 2600,
            nextAttemptAt = null,
            mac = "1c:7c:59:a3:02:11",
            message = "第一段PIN 3419/10000",
        ),
        AuditRecord.Wps(
            id = "wps-audit-1b",
            ssid = "TP-LINK_A302",
            config = WpsSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.CONNECTING,
            phase = WpsSecurityAuditPhase.FAIRY_DUST,
            firstHalf = null,
            firstHalfIndex = 1200,
            attempts = 7,
            mac = "1c:7c:59:a3:02:99",
            message = "正在仙尘阶段提取弱点",
        ),
        AuditRecord.Wps(
            id = "wps-audit-hidden",
            ssid = "",
            config = WpsSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.CONNECTING,
            phase = WpsSecurityAuditPhase.FIRST_HALF,
            firstHalf = "7788990011",
            firstHalfIndex = 6543,
            attempts = 12,
            averageAttemptMillis = 2600,
            nextAttemptAt = null,
            mac = "aa:bb:cc:dd:ee:99",
            message = "隐藏网络第一段PIN 6543/10000",
        ),
        AuditRecord.Wps(
            id = "wps-audit-2",
            ssid = "ChinaNet-Home",
            config = WpsSecurityAuditConfig(),
            startedAt = 0L,
            updatedAt = 0L,
            state = PskSecurityAuditNetworkState.QUEUED,
            mac = "8c:de:f9:0b:1a:20",
            message = "排队中",
        ),
    )
    return TrackedTaskState(
        snapshot = TaskSnapshot(
            taskId = 12L,
            request = TaskStartRequest(
                TaskRequestPayload.WpsSecurityAudit(WpsSecurityAuditTaskInput(WpsSecurityAuditConfig(), targets)),
            ),
            state = TaskExecutionState.RUNNING,
            progress = TaskProgress.WpsSecurityAudit(
                revision = 2L,
                networkCount = 2,
                config = WpsSecurityAuditConfig(),
            ),
        ),
        logs = auditLog(
            12L,
            "开始任务：PIN漏洞检测，共3个网络" to 0L,
            "TP-LINK_A302：PixieDust无弱点，转First Half" to 1_200L,
            "TP-LINK_A302：第一段PIN 3419/10000" to 2_100L,
        ),
        auditNetworks = requests,
    )
}
