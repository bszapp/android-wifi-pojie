package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiFailureFlags
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskConfig
import io.github.bszapp.wifitoolbox.contract.task.HandshakeAttemptsExceededFlag
import io.github.bszapp.wifitoolbox.contract.task.HandshakeTimeoutFlag
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskLogEntry
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface ConnectWifiSheetContent {
    data class Configuration(
        val networkId: Int,
        val ssid: String,
    ) : ConnectWifiSheetContent

    data class Task(val taskId: Long) : ConnectWifiSheetContent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectWifiTaskSheet(
    content: ConnectWifiSheetContent,
    trackedTask: TrackedTaskState?,
    isSubmitting: Boolean,
    onStart: (ConnectWifiTaskConfig) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        when (content) {
            is ConnectWifiSheetContent.Configuration -> ConnectWifiConfigurationContent(
                content = content,
                isSubmitting = isSubmitting,
                onStart = onStart,
                onDismiss = onDismiss,
            )
            is ConnectWifiSheetContent.Task -> ConnectWifiTaskContent(
                taskId = content.taskId,
                trackedTask = trackedTask,
                onStop = onStop,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ConnectWifiConfigurationContent(
    content: ConnectWifiSheetContent.Configuration,
    isSubmitting: Boolean,
    onStart: (ConnectWifiTaskConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var timeout by remember(content.networkId) { mutableStateOf("8000") }
    var passwordError by remember(content.networkId) { mutableStateOf(false) }
    var handshakeAttemptsEnabled by remember(content.networkId) { mutableStateOf(false) }
    var maxHandshakeAttempts by remember(content.networkId) { mutableStateOf("1") }
    var handshakeTimeoutEnabled by remember(content.networkId) { mutableStateOf(false) }
    var handshakeTimeout by remember(content.networkId) { mutableStateOf("1000") }
    var validationMessage by remember(content.networkId) { mutableStateOf<String?>(null) }
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 4.dp,
            bottom = bottomPadding + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(text = "使用此配置连接", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "${content.ssid} · 配置 #${content.networkId}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            MillisecondField(
                value = timeout,
                onValueChange = { timeout = it },
                label = "任务总超时（毫秒）",
            )
        }
        item {
            SwitchRow(
                title = "密码错误时结束任务",
                checked = passwordError,
                onCheckedChange = { passwordError = it },
            )
        }
        item {
            SwitchRow(
                title = "握手次数超限时结束任务",
                checked = handshakeAttemptsEnabled,
                onCheckedChange = { handshakeAttemptsEnabled = it },
            )
        }
        if (handshakeAttemptsEnabled) {
            item {
                MillisecondField(
                    value = maxHandshakeAttempts,
                    onValueChange = { maxHandshakeAttempts = it },
                    label = "最大握手次数",
                )
            }
        }
        item {
            SwitchRow(
                title = "握手超时时结束任务",
                checked = handshakeTimeoutEnabled,
                onCheckedChange = { handshakeTimeoutEnabled = it },
            )
        }
        if (handshakeTimeoutEnabled) {
            item {
                MillisecondField(
                    value = handshakeTimeout,
                    onValueChange = { handshakeTimeout = it },
                    label = "握手超时（毫秒）",
                )
            }
        }
        validationMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        if (isSubmitting) return@Button
                        val totalMillis = timeout.toLongOrNull()
                        val maxAttempts = maxHandshakeAttempts.toIntOrNull()
                        val handshakeMillis = handshakeTimeout.toLongOrNull()
                        validationMessage = when {
                            totalMillis == null || totalMillis <= 0L -> "任务总超时必须大于 0"
                            handshakeAttemptsEnabled &&
                                (maxAttempts == null || maxAttempts <= 0) ->
                                "最大握手次数必须大于 0"
                            handshakeTimeoutEnabled &&
                                (handshakeMillis == null || handshakeMillis <= 0L) ->
                                "握手超时必须大于 0"
                            else -> null
                        }
                        if (validationMessage == null) {
                            onStart(
                                ConnectWifiTaskConfig(
                                    timeoutMillis = totalMillis!!,
                                    failureFlags = ConnectWifiFailureFlags(
                                        passwordError = passwordError,
                                        handshakeAttemptsExceeded = if (
                                            handshakeAttemptsEnabled
                                        ) {
                                            HandshakeAttemptsExceededFlag(
                                                maxHandshakeAttempts = maxAttempts!!,
                                            )
                                        } else {
                                            null
                                        },
                                        handshakeTimeout = if (handshakeTimeoutEnabled) {
                                            HandshakeTimeoutFlag(
                                                handshakeStepTimeoutMillis = handshakeMillis!!,
                                            )
                                        } else {
                                            null
                                        },
                                    ),
                                ),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isSubmitting) "正在提交" else "开始")
                }
            }
        }
    }
}

@Composable
private fun ConnectWifiTaskContent(
    taskId: Long,
    trackedTask: TrackedTaskState?,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val snapshot = trackedTask?.snapshot
    val progress = snapshot?.progress as? TaskProgress.ConnectWifi
    val running = snapshot?.state == TaskExecutionState.RUNNING
    LazyColumn(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 4.dp,
            bottom = bottomPadding + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = when {
                    snapshot == null -> "加载中"
                    running -> "任务运行中"
                    else -> "任务已结束"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "任务 #$taskId",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (snapshot != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = progress?.stage.displayName(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("关闭")
                }
                if (running) {
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("停止任务")
                    }
                }
            }
        }
        trackedTask?.logs?.entries.orEmpty().forEach { entry ->
            item(key = entry.taskLineId) {
                Text(
                    text = entry.displayText(),
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

@Composable
private fun MillisecondField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun ConnectWifiStage?.displayName(): String = when (this) {
    ConnectWifiStage.ROUTER_COMMUNICATION -> "与路由器建立通信"
    ConnectWifiStage.WPA_HANDSHAKE_1_OF_4 -> "WPA 握手 1/4"
    ConnectWifiStage.WPA_HANDSHAKE_2_OF_4 -> "WPA 握手 2/4"
    ConnectWifiStage.WPA_HANDSHAKE_3_OF_4 -> "WPA 握手 3/4"
    ConnectWifiStage.WPA_HANDSHAKE_4_OF_4 -> "WPA 握手 4/4"
    null -> "加载中"
}

private fun TaskLogEntry.displayText(): String {
    val timestamp = requireNotNull(TASK_TIME_FORMAT.get()).format(Date(timestampMillis))
    return "$timestamp  $text"
}

private val TASK_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
}
