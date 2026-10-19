package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.task.*
import io.github.bszapp.wifitoolbox.ui.component.PreviewSheetHost
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.ui.component.RandomMacButton
import io.github.bszapp.wifitoolbox.ui.component.WifiInterfaceSelector
import io.github.bszapp.wifitoolbox.ui.model.WpsPinFormState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Preview(name = "WPS PIN 输入", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
internal fun WpsPinInputSheetPreview() {
    PreviewSheetHost {
        WpsPinInputSheet(
            form = WpsPinFormState(ssid = "ChinaNet-Home-2.4G"),
            onStart = { _, _, _ -> },
            onDismiss = {},
            onDismissFinished = {},
        )
    }
}

@Preview(name = "WPS PIN 任务 · 进行中", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
internal fun WpsPinTaskSheetPreview() {
    PreviewSheetHost {
        WpsPinTaskSheet(
            task = TrackedTaskState(
                snapshot = TaskSnapshot(
                    taskId = 6,
                    request = TaskStartRequest.wpsPin(
                        ssid = "ChinaNet-Home-2.4G",
                        pin = "12345670",
                    ),
                    state = TaskExecutionState.RUNNING,
                    progress = TaskProgress.WpsPin(
                        targetMac = "AA:BB:CC:11:22:01",
                        networkCount = 1,
                    ),
                ),
                logs = TaskLogState(
                    scopeTaskId = 6,
                    lineCount = 2,
                    latestId = 2L,
                    entries = listOf(
                        TaskLogEntry(6, 0, 0, 0L, "开始任务：WPS PIN"),
                        TaskLogEntry(6, 1, 1, 1_500L, "已发送 PIN，等待路由器应答"),
                    ),
                ),
            ),
            onStop = {},
            onDismiss = {},
            onConnect = {},
        )
    }
}

@Composable
internal fun WpsPinInputSheet(form: WpsPinFormState, onStart: (pin: String, localMac: String, interfaceName: String) -> Unit,
    onDismiss: () -> Unit, onDismissFinished: () -> Unit, availableInterfaces: List<String> = emptyList()) {
    var pin by rememberSaveable(form.ssid) { mutableStateOf("") }
    var localMac by rememberSaveable(form.ssid) { mutableStateOf("") }
    var interfaceName by rememberSaveable(form.ssid) { mutableStateOf("wlan0") }
    SingleOverlayBottomSheet(show = form.visible, title = "使用PIN获取密码",
        onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 550.dp),
            contentPadding = PaddingValues(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(form.ssid, style = MiuixTheme.textStyles.headline2)
                    Text("将选择此名称下信号最强且支持PIN的接入点。获取后自动保存到本机，连接需另行确认。",
                        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item { WifiInterfaceSelector(interfaceName, availableInterfaces, { interfaceName = it }, enabled = !form.submitting) }
            item {
                TextField(value = pin, onValueChange = { pin = it }, label = "路由器的WPS PIN", singleLine = true,
                    enabled = !form.submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            item {
                TextField(value = localMac, onValueChange = { localMac = it },
                    label = "本机MAC（留空使用设备MAC）", singleLine = true, enabled = !form.submitting,
                    trailingIcon = { RandomMacButton({ localMac = it }, enabled = !form.submitting) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", onDismiss, Modifier.weight(1f))
                    TextButton(if (form.submitting) "正在提交" else "开始", { onStart(pin, localMac, interfaceName) }, Modifier.weight(1f),
                        enabled = !form.submitting && Regex("(?:[0-9]{4}|[0-9]{8})").matches(pin),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable
internal fun WpsPinTaskSheet(task: TrackedTaskState, onStop: () -> Unit, onDismiss: () -> Unit,
    onConnect: (WpsCapturedNetwork) -> Unit) {
    var show by rememberSaveable(task.snapshot.taskId) { mutableStateOf(true) }
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
    val running = task.snapshot.state == TaskExecutionState.RUNNING
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    SingleOverlayBottomSheet(show = show, title = "WPS PIN", onDismissRequest = { show = false },
        onDismissFinished = { onDismiss(); afterDismiss?.invoke() }) {
        Column(Modifier.fillMaxWidth().padding(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TabRow(tabs = listOf("网络信息", "运行日志"), selectedTabIndex = pager.currentPage,
                onTabSelected = { scope.launch { pager.animateScrollToPage(it) } },
                colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent))
            HorizontalPager(pager, Modifier.fillMaxWidth().height(300.dp), verticalAlignment = Alignment.Top) { page ->
                if (page == 1) WpsTaskLogsPage(task)
                else WpsPinNetworkPage(task) { network ->
                    afterDismiss = { onConnect(network) }
                    show = false
                }
            }
            if (running) TextButton("停止", onStop, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun WpsPinNetworkPage(task: TrackedTaskState, onConnect: (WpsCapturedNetwork) -> Unit) {
    val running = task.snapshot.state == TaskExecutionState.RUNNING
    val input = (task.snapshot.request.payload as TaskRequestPayload.WpsPin).input
    val progress = task.snapshot.progress as? TaskProgress.WpsPin
    val network = task.capturedNetworks.firstOrNull()
    val loading = !running && network == null && (progress?.networkCount ?: 0) > 0
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("使用网卡：${input.interfaceName}", style = MiuixTheme.textStyles.body2) }
        item {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = network?.ssid ?: input.ssid,
                    summary = network?.mac ?: progress?.targetMac,
                    startAction = {
                        Icon(Icons.Outlined.Wifi, contentDescription = null,
                            modifier = Modifier.padding(end = 16.dp).size(32.dp), tint = MiuixTheme.colorScheme.primary)
                    },
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (running || loading) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator(size = 28.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("加载中", style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text(if (loading) "加载网络信息" else progress?.handshake?.displayStage() ?: "准备 WPS 会话",
                                    style = MiuixTheme.textStyles.headline2, color = MiuixTheme.colorScheme.primary)
                            }
                        }
                    } else if (network != null) {
                        Text("网络密码", style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(network.password, style = MiuixTheme.textStyles.headline2)
                        TextButton("连接此网络", { onConnect(network) }, Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary())
                    } else {
                        Text("未获取到网络密码", style = MiuixTheme.textStyles.headline2)
                        Text("本次任务已结束，详细过程请查看运行日志。", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}

internal fun WpsHandshakeProgress.displayStage(): String = when (stage) {
    WpsHandshakeStage.PREPARING -> "准备 WPS 会话"
    WpsHandshakeStage.SCANNING -> "寻找目标接入点"
    WpsHandshakeStage.AUTHENTICATING -> "进行无线认证"
    WpsHandshakeStage.ASSOCIATING -> "关联目标接入点"
    WpsHandshakeStage.ASSOCIATED -> "已关联接入点"
    WpsHandshakeStage.EAPOL_START -> "发送 EAPOL Start"
    WpsHandshakeStage.IDENTITY_REQUEST -> "收到身份请求"
    WpsHandshakeStage.IDENTITY_RESPONSE -> "发送身份响应"
    WpsHandshakeStage.HANDSHAKE -> when (direction) {
        WpsPacketDirection.SENDING -> "发送 ${packet ?: "WPS 消息"}"
        WpsPacketDirection.RECEIVED -> "收到 ${packet ?: "WPS 消息"}"
        null -> "进行 WPS 握手"
    }
    WpsHandshakeStage.PROTOCOL_COMPLETED -> "WPS 协议交换完成"
    WpsHandshakeStage.SAVING_NETWORK -> "保存网络配置"
    WpsHandshakeStage.RESTORING_SYSTEM -> "恢复系统 Wi-Fi 控制"
}
