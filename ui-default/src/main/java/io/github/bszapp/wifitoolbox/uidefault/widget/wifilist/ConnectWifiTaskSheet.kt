package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiFailureFlags
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiStage
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskConfig
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.HandshakeAttemptsExceededFlag
import io.github.bszapp.wifitoolbox.contract.task.HandshakeTimeoutFlag
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskLogEntry
import io.github.bszapp.wifitoolbox.contract.task.TaskProgress
import io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal sealed interface ConnectWifiSheetContent {
    data class Configuration(
        val networkId: Int,
        val ssid: String,
    ) : ConnectWifiSheetContent

    data class Network(
        val ssid: String,
        val hasSavedConfiguration: Boolean,
        val allowNetworkCardTest: Boolean = false,
    ) : ConnectWifiSheetContent

    data object Task : ConnectWifiSheetContent
}

internal enum class ConnectWifiOperation {
    TEST_CONNECTIVITY,
    TEST_CONNECTIVITY_NETWORK_CARD,
    SAVE_ONLY,
    SAVE_AND_CONNECT,
}

internal sealed interface ConnectWifiSheetSubmission {
    data class UseSavedNetwork(
        val config: ConnectWifiTaskConfig,
    ) : ConnectWifiSheetSubmission

    data class TestConnectivity(
        val password: String,
        val config: ConnectWifiTaskConfig,
    ) : ConnectWifiSheetSubmission

    data class SaveOnly(
        val password: String,
    ) : ConnectWifiSheetSubmission

    data class NetworkCardTest(
        val passwords: List<String>,
        val mac: String?,
        val name: String?,
        val config: ConnectWifiTaskConfig,
    ) : ConnectWifiSheetSubmission

    data class SaveAndConnect(
        val password: String,
        val config: ConnectWifiTaskConfig,
    ) : ConnectWifiSheetSubmission
}

private enum class ConnectWifiSheetStep {
    CONFIGURATION,
    RUNNING,
    COMPLETED,
}

private enum class ConnectWifiAnimatedPage {
    CONFIGURATION,
    TASK,
}

@Composable
internal fun ConnectWifiTaskSheet(
    content: ConnectWifiSheetContent,
    trackedTask: TrackedTaskState?,
    isSubmitting: Boolean,
    dismissRequested: Boolean,
    onSubmit: (ConnectWifiSheetSubmission) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    /*
     * 内部保存显示状态。
     * 关闭时先让 OverlayBottomSheet 完成退场动画，
     * 然后再通过 onDismissFinished 通知外部移除内容。
     */
    var showSheet by remember {
        mutableStateOf(true)
    }

    LaunchedEffect(dismissRequested) {
        if (dismissRequested) showSheet = false
    }

    /*
     * 配置切换为任务时仍保留配置页及其输入状态，
     * 供 AnimatedContent 完成旧页面退场动画。
     */
    val initialInput = remember {
        content.takeUnless { it is ConnectWifiSheetContent.Task }
    }

    val inputContent = content.takeUnless { it is ConnectWifiSheetContent.Task }
        ?: initialInput

    val formState = remember(inputContent) {
        ConnectWifiConfigurationFormState()
    }

    val taskContent = content as? ConnectWifiSheetContent.Task
    val snapshot = trackedTask?.snapshot
    val progress = snapshot?.progress as? TaskProgress.ConnectWifi
    val taskType = (
        snapshot?.request?.payload as? io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload.ConnectWifi
        )?.request?.input?.type ?: when (inputContent) {
        is ConnectWifiSheetContent.Configuration -> ConnectWifiTaskType.USE_SAVED_NETWORK
        is ConnectWifiSheetContent.Network -> ConnectWifiTaskType.CONNECT_TO_NETWORK
        else -> null
    }

    /*
     * Task 页面刚打开但快照还未加载时，也视为运行中。
     */
    val running = taskContent != null && (
            snapshot == null ||
                    snapshot.state == TaskExecutionState.RUNNING
            )

    val step = when {
        content !is ConnectWifiSheetContent.Task -> {
            ConnectWifiSheetStep.CONFIGURATION
        }

        running -> {
            ConnectWifiSheetStep.RUNNING
        }

        else -> {
            ConnectWifiSheetStep.COMPLETED
        }
    }

    /*
     * 运行中和完成状态映射到同一个 TASK 页面。
     * 因此 RUNNING -> COMPLETED 只会普通重组，
     * 不会再次播放页面滑动或高度切换动画。
     */
    val animatedPage = when (step) {
        ConnectWifiSheetStep.CONFIGURATION -> {
            ConnectWifiAnimatedPage.CONFIGURATION
        }

        ConnectWifiSheetStep.RUNNING,
        ConnectWifiSheetStep.COMPLETED,
            -> {
            ConnectWifiAnimatedPage.TASK
        }
    }

    val bottomPadding = WindowInsets.safeDrawing
        .asPaddingValues()
        .calculateBottomPadding()

    val requestDismiss = {
        showSheet = false
    }

    SingleOverlayBottomSheet(
        show = showSheet,
        title = when {
            content is ConnectWifiSheetContent.Configuration ->
                ConnectWifiTaskType.USE_SAVED_NETWORK.displayName()
            content is ConnectWifiSheetContent.Network -> "连接网络"
            taskType != null -> taskType.displayName()
            else -> "连接网络"
        },
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = requestDismiss,
        onDismissFinished = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /*
             * fill = false：
             *
             * 内容较少时按实际高度显示；
             * 内容较多时最多占用 Sheet 剩余高度；
             * 底部按钮始终固定，不参与滚动。
             */
            AnimatedContent(
                targetState = animatedPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(
                        weight = 1f,
                        fill = false,
                    ),
                contentAlignment = Alignment.TopStart,
                transitionSpec = {
                    val movingForward =
                        targetState.ordinal > initialState.ordinal

                    val enterTransition = slideInHorizontally(
                        animationSpec = tween(
                            durationMillis =
                                PAGE_TRANSITION_DURATION_MILLIS,
                        ),
                    ) { fullWidth ->
                        if (movingForward) {
                            fullWidth
                        } else {
                            -fullWidth
                        }
                    }

                    val exitTransition = slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis =
                                PAGE_TRANSITION_DURATION_MILLIS,
                        ),
                    ) { fullWidth ->
                        if (movingForward) {
                            -fullWidth
                        } else {
                            fullWidth
                        }
                    }

                    (enterTransition togetherWith exitTransition).using(
                        SizeTransform(
                            clip = true,
                        ) { _, _ ->
                            tween(
                                durationMillis =
                                    PAGE_TRANSITION_DURATION_MILLIS,
                            )
                        },
                    )
                },
                label = "ConnectWifiSheetPage",
            ) { page ->
                when (page) {
                    ConnectWifiAnimatedPage.CONFIGURATION -> {
                        inputContent?.let {
                            ConnectWifiConfigurationContent(
                                content = it,
                                formState = formState,
                            )
                        }
                    }

                    ConnectWifiAnimatedPage.TASK -> {
                        ConnectWifiTaskLogContent(
                            taskId = trackedTask?.snapshot?.taskId,
                            taskType = taskType,
                            running = running,
                            entries = trackedTask
                                ?.logs
                                ?.entries
                                .orEmpty(),
                        )
                    }
                }
            }

            /*
             * 底部操作区在 AnimatedContent 和滚动容器之外。
             */
            ConnectWifiSheetActions(
                step = step,
                progress = progress,
                isSubmitting = isSubmitting,
                formState = formState,
                inputContent = inputContent,
                onSubmit = onSubmit,
                onStop = onStop,
                onDismiss = requestDismiss,
            )
        }
    }
}

@Stable
private class ConnectWifiConfigurationFormState {
    var password by mutableStateOf("")
    var networkCardPasswords by mutableStateOf("")
    var networkCardMac by mutableStateOf("")
    var networkCardName by mutableStateOf("")
    var advancedExpanded by mutableStateOf(false)

    var operation by mutableStateOf(ConnectWifiOperation.TEST_CONNECTIVITY)

    var timeout by mutableStateOf("8000")

    /*
     * 默认勾选密码错误。
     */
    var passwordError by mutableStateOf(true)

    var handshakeAttemptsEnabled by mutableStateOf(false)
    var maxHandshakeAttempts by mutableStateOf("1")

    var handshakeTimeoutEnabled by mutableStateOf(false)
    var handshakeTimeout by mutableStateOf("1000")

    var validationMessage by mutableStateOf<String?>(null)

    fun createSubmission(
        content: ConnectWifiSheetContent,
    ): ConnectWifiSheetSubmission? = when (content) {
        is ConnectWifiSheetContent.Configuration -> createConfig()?.let {
            ConnectWifiSheetSubmission.UseSavedNetwork(it)
        }
        is ConnectWifiSheetContent.Network -> when (operation) {
            ConnectWifiOperation.TEST_CONNECTIVITY -> createConfig()?.let {
                ConnectWifiSheetSubmission.TestConnectivity(password, it)
            }
            ConnectWifiOperation.TEST_CONNECTIVITY_NETWORK_CARD -> createConfig()?.let { config ->
                val passwords = networkCardPasswords.replace("\r\n", "\n").split('\n')
                val mac = networkCardMac.trim().takeIf(String::isNotEmpty)
                validationMessage = when {
                    !content.allowNetworkCardTest -> "网卡测试仅用于普通模式"
                    passwords.any { it.toByteArray(Charsets.UTF_8).size !in 8..63 } ->
                        "每行密码需为 8～63 字节，空行也会被视为一项密码"
                    mac != null && !Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$").matches(mac) ->
                        "MAC 格式应为 00:11:45:14:19:1a"
                    mac != null && ((mac.substringBefore(':').toInt(16) and 1) != 0 ||
                        mac.equals("00:00:00:00:00:00", ignoreCase = true)) -> "MAC 必须为有效单播地址"
                    else -> null
                }
                if (validationMessage != null) null else ConnectWifiSheetSubmission.NetworkCardTest(
                    passwords = passwords,
                    mac = mac,
                    name = networkCardName.takeIf(String::isNotBlank),
                    config = config,
                )
            }
            ConnectWifiOperation.SAVE_ONLY -> {
                validationMessage = null
                ConnectWifiSheetSubmission.SaveOnly(password)
            }
            ConnectWifiOperation.SAVE_AND_CONNECT -> createConfig()?.let {
                ConnectWifiSheetSubmission.SaveAndConnect(password, it)
            }
        }
        is ConnectWifiSheetContent.Task -> null
    }

    fun createConfig(): ConnectWifiTaskConfig? {
        val totalMillis = timeout.toLongOrNull()
        val maxAttempts = maxHandshakeAttempts.toIntOrNull()
        val handshakeMillis = handshakeTimeout.toLongOrNull()

        validationMessage = when {
            totalMillis == null || totalMillis <= 0L -> {
                "总超时需大于 0"
            }

            handshakeAttemptsEnabled &&
                    (maxAttempts == null || maxAttempts <= 0) -> {
                "最大次数需大于 0"
            }

            handshakeTimeoutEnabled &&
                    (handshakeMillis == null ||
                            handshakeMillis <= 0L) -> {
                "握手超时需大于 0"
            }

            else -> null
        }

        if (validationMessage != null) {
            return null
        }

        return ConnectWifiTaskConfig(
            timeoutMillis = requireNotNull(totalMillis),
            failureFlags = ConnectWifiFailureFlags(
                passwordError = passwordError,
                handshakeAttemptsExceeded = if (
                    handshakeAttemptsEnabled
                ) {
                    HandshakeAttemptsExceededFlag(
                        maxHandshakeAttempts =
                            requireNotNull(maxAttempts),
                    )
                } else {
                    null
                },
                handshakeTimeout = if (
                    handshakeTimeoutEnabled
                ) {
                    HandshakeTimeoutFlag(
                        handshakeStepTimeoutMillis =
                            requireNotNull(handshakeMillis),
                    )
                } else {
                    null
                },
            ),
        )
    }
}

@Composable
private fun ConnectWifiConfigurationContent(
    content: ConnectWifiSheetContent,
    formState: ConnectWifiConfigurationFormState,
) {
    /*
     * 配置页只有中间表单参与滚动。
     * LazyColumn 内容较少时会按内容高度显示，
     * 超出 Sheet 可用高度后才开始滚动。
     */
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .scrollEndHaptic()
            .overScrollVertical(),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(
            key = "configuration-summary",
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = when (content) {
                        is ConnectWifiSheetContent.Configuration -> "使用已保存的网络连接"
                        is ConnectWifiSheetContent.Network -> "连接到网络"
                        is ConnectWifiSheetContent.Task -> "连接到网络"
                    },
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title3,
                )

                Text(
                    text = when (content) {
                        is ConnectWifiSheetContent.Configuration ->
                            "${content.ssid} · 配置 #${content.networkId}"
                        is ConnectWifiSheetContent.Network -> content.ssid
                        is ConnectWifiSheetContent.Task -> "正在载入任务"
                    },
                    color =
                        MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }

        if (content is ConnectWifiSheetContent.Network) {
            item(key = "network-password") {
                val cardTest = formState.operation == ConnectWifiOperation.TEST_CONNECTIVITY_NETWORK_CARD
                TextField(
                    value = if (cardTest) formState.networkCardPasswords else formState.password,
                    onValueChange = {
                        if (cardTest) formState.networkCardPasswords = it else formState.password = it
                        formState.validationMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = if (cardTest) "M2 密码（每行一个，按 M1 到达顺序使用）" else "密码",
                    singleLine = !cardTest,
                )
            }

            item(key = "operation-type-title") {
                Text(
                    text = "操作类型",
                    modifier = Modifier.padding(start = 4.dp),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.subtitle,
                )
            }

            item(key = "operation-types") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    OperationTypeRow(
                        title = "测试连通性",
                        summary = "临时连接并在确认 WPA 握手成功后立即断开",
                        selected = formState.operation == ConnectWifiOperation.TEST_CONNECTIVITY,
                        onClick = {
                            formState.operation = ConnectWifiOperation.TEST_CONNECTIVITY
                            formState.validationMessage = null
                        },
                    )
                    if (content.allowNetworkCardTest) {
                        OperationTypeRow(
                            title = "测试连通性（网卡）",
                            summary = "直接执行 WPA2-PSK/CCMP 握手与 DHCP，不修改保存配置；已有连接会断开",
                            selected = formState.operation == ConnectWifiOperation.TEST_CONNECTIVITY_NETWORK_CARD,
                            onClick = {
                                formState.operation = ConnectWifiOperation.TEST_CONNECTIVITY_NETWORK_CARD
                                formState.validationMessage = null
                            },
                        )
                    }
                    OperationTypeRow(
                        title = "仅保存",
                        summary = "保存网络配置并关闭自动加入",
                        selected = formState.operation == ConnectWifiOperation.SAVE_ONLY,
                        onClick = {
                            formState.operation = ConnectWifiOperation.SAVE_ONLY
                            formState.validationMessage = null
                        },
                    )
                    OperationTypeRow(
                        title = "保存并连接",
                        summary = "保存网络配置后运行连接任务",
                        selected = formState.operation == ConnectWifiOperation.SAVE_AND_CONNECT,
                        onClick = {
                            formState.operation = ConnectWifiOperation.SAVE_AND_CONNECT
                            formState.validationMessage = null
                        },
                    )
                }
            }
        }

        if (content is ConnectWifiSheetContent.Network &&
            formState.operation == ConnectWifiOperation.TEST_CONNECTIVITY_NETWORK_CARD
        ) {
            item(key = "network-card-instructions") {
                Text(
                    text = "每次有效 M1 使用下一行密码发送 M2；密码行耗尽时中断。取得 IP 后立即断开，IP 与协议详情记录在任务日志中。需要 Root 和最新容器系统。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            item(key = "network-card-advanced") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "高级选项",
                        summary = if (formState.advancedExpanded) "收起 MAC 和名称设置" else "自定义 MAC 和设备名称",
                        onClick = { formState.advancedExpanded = !formState.advancedExpanded },
                    )
                    AnimatedVisibility(
                        visible = formState.advancedExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextField(
                                value = formState.networkCardMac,
                                onValueChange = { formState.networkCardMac = it; formState.validationMessage = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = "MAC（留空使用设备 MAC）",
                                singleLine = true,
                            )
                            TextField(
                                value = formState.networkCardName,
                                onValueChange = { formState.networkCardName = it; formState.validationMessage = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = "名称（留空使用设备名称）",
                                singleLine = true,
                            )
                        }
                    }
                }
            }
        }

        if (
            content !is ConnectWifiSheetContent.Network ||
            formState.operation != ConnectWifiOperation.SAVE_ONLY
        ) {
            item(
                key = "total-timeout",
            ) {
                NumberField(
                    value = formState.timeout,
                    onValueChange = {
                        formState.timeout = it
                        formState.validationMessage = null
                    },
                    label = "总超时（毫秒）",
                )
            }

            item(
                key = "failure-flags-title",
            ) {
                Text(
                    text = "失败标志",
                    modifier = Modifier.padding(start = 4.dp),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.subtitle,
                )
            }

            item(
                key = "failure-flags",
            ) {
            /*
             * 三个失败选项合并为同一个 Miuix Card。
             */
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                FailureFlagRow(
                    title = "密码错误",
                    checked = formState.passwordError,
                    onCheckedChange = {
                        formState.passwordError = it
                    },
                )

                FailureFlagRow(
                    title = "握手超次",
                    checked = formState.handshakeAttemptsEnabled,
                    onCheckedChange = {
                        formState.handshakeAttemptsEnabled = it
                        formState.validationMessage = null
                    },
                )

                AnimatedVisibility(
                    visible =
                        formState.handshakeAttemptsEnabled,
                    enter = expandVertically(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                    ),
                    exit = shrinkVertically(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                    ),
                ) {
                    NumberField(
                        value =
                            formState.maxHandshakeAttempts,
                        onValueChange = {
                            formState.maxHandshakeAttempts = it
                            formState.validationMessage = null
                        },
                        label = "最大次数",
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 12.dp,
                        ),
                    )
                }

                FailureFlagRow(
                    title = "握手超时",
                    checked = formState.handshakeTimeoutEnabled,
                    onCheckedChange = {
                        formState.handshakeTimeoutEnabled = it
                        formState.validationMessage = null
                    },
                )

                AnimatedVisibility(
                    visible =
                        formState.handshakeTimeoutEnabled,
                    enter = expandVertically(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                        expandFrom = Alignment.Top,
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                    ),
                    exit = shrinkVertically(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                        shrinkTowards = Alignment.Top,
                    ) + fadeOut(
                        animationSpec = tween(
                            durationMillis =
                                EXPAND_DURATION_MILLIS,
                        ),
                    ),
                ) {
                    NumberField(
                        value = formState.handshakeTimeout,
                        onValueChange = {
                            formState.handshakeTimeout = it
                            formState.validationMessage = null
                        },
                        label = "单步超时（毫秒）",
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 12.dp,
                        ),
                    )
                }
                }
            }
        }

        formState.validationMessage?.let { message ->
            item(
                key = "validation-message",
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(
                        horizontal = 4.dp,
                    ),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

@Composable
private fun ConnectWifiTaskLogContent(
    taskId: Long?,
    taskType: ConnectWifiTaskType?,
    running: Boolean,
    entries: List<TaskLogEntry>,
) {
    val listState = rememberLazyListState()
    val lastLogId = entries.lastOrNull()?.taskLineId

    /*
     * 新日志到达后直接定位到最后一项。
     * 使用 scrollToItem，不播放滚动动画。
     */
    LaunchedEffect(lastLogId) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = taskType?.displayName() ?: "连接到网络",
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.title3,
        )

        Text(
            text = taskId?.let {
                if (running) "任务运行中 · #$it" else "任务已结束 · #$it"
            } ?: "正在载入任务",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
        )

        /*
         * 运行和完成状态共用同一个固定高度日志区域。
         */
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(TASK_LOG_HEIGHT),
        ) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无日志",
                        color = MiuixTheme
                            .colorScheme
                            .onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = entries,
                        key = {
                            it.taskLineId
                        },
                    ) { entry ->
                        Text(
                            text = entry.displayText(),
                            color =
                                MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body2,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectWifiSheetActions(
    step: ConnectWifiSheetStep,
    progress: TaskProgress.ConnectWifi?,
    isSubmitting: Boolean,
    formState: ConnectWifiConfigurationFormState,
    inputContent: ConnectWifiSheetContent?,
    onSubmit: (ConnectWifiSheetSubmission) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (step) {
        ConnectWifiSheetStep.CONFIGURATION -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )

                Spacer(
                    modifier = Modifier.width(20.dp),
                )

                TextButton(
                    text = if (isSubmitting) {
                        "提交中"
                    } else if (
                        inputContent is ConnectWifiSheetContent.Network &&
                        formState.operation == ConnectWifiOperation.SAVE_ONLY
                    ) {
                        "保存"
                    } else {
                        "开始"
                    },
                    onClick = {
                        if (isSubmitting) {
                            return@TextButton
                        }

                        inputContent
                            ?.let(formState::createSubmission)
                            ?.let(onSubmit)
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        ConnectWifiSheetStep.RUNNING -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp),
            ) {
                /*
                 * 当前步骤只在运行中显示，
                 * 并固定在停止按钮上方。
                 */
                Text(
                    text = "当前步骤 · ${progress?.stage.displayName()}",
                    modifier = Modifier.padding(
                        horizontal = 4.dp,
                    ),
                    color = MiuixTheme
                        .colorScheme
                        .onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )

                /*
                 * 运行状态只有停止按钮。
                 */
                TextButton(
                    text = "停止",
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        ConnectWifiSheetStep.COMPLETED -> {
            /*
             * 完成状态不再显示当前步骤。
             */
            TextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun OperationTypeRow(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        role = Role.RadioButton,
        onClick = onClick,
        endActions = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
    )
}

@Composable
private fun FailureFlagRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = title,
        role = Role.Switch,
        onClick = {
            onCheckedChange(!checked)
        },
        endActions = {
            /*
             * 点击行为由整个 BasicComponent 处理，
             * 避免 Switch 与整行重复触发。
             */
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = {
            onValueChange(
                it.filter(Char::isDigit),
            )
        },
        modifier = modifier.fillMaxWidth(),
        label = label,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        singleLine = true,
    )
}

private fun ConnectWifiStage?.displayName(): String = when (this) {
    ConnectWifiStage.ROUTER_COMMUNICATION -> {
        "与路由器建立通信"
    }

    ConnectWifiStage.WPA_HANDSHAKE_1_OF_4 -> {
        "WPA 握手 1/4"
    }

    ConnectWifiStage.WPA_HANDSHAKE_2_OF_4 -> {
        "WPA 握手 2/4"
    }

    ConnectWifiStage.WPA_HANDSHAKE_3_OF_4 -> {
        "WPA 握手 3/4"
    }

    ConnectWifiStage.WPA_HANDSHAKE_4_OF_4 -> {
        "WPA 握手 4/4"
    }

    ConnectWifiStage.IP_NEGOTIATION -> "获取 IP 地址"

    null -> {
        "加载中"
    }
}

private fun ConnectWifiTaskType.displayName(): String = when (this) {
    ConnectWifiTaskType.USE_SAVED_NETWORK -> "使用已保存的网络连接"
    ConnectWifiTaskType.CONNECT_TO_NETWORK -> "连接到网络"
}

private fun TaskLogEntry.displayText(): String {
    val timestamp = requireNotNull(
        TASK_TIME_FORMAT.get(),
    ).format(
        Date(timestampMillis),
    )

    return "$timestamp  $text"
}

private const val PAGE_TRANSITION_DURATION_MILLIS = 320
private const val EXPAND_DURATION_MILLIS = 240

private val TASK_LOG_HEIGHT = 240.dp

private val TASK_TIME_FORMAT =
    object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(
            "MM-dd HH:mm:ss.SSS",
            Locale.getDefault(),
        )
    }
