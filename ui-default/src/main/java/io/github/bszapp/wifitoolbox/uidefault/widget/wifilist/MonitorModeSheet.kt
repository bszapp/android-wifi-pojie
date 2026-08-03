package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

internal data class MonitorModeSheetTarget(
    val channel: Int,
    val frequencyMhz: Int,
    val accessPoints: List<MonitorModeAccessPoint>,
)

internal data class MonitorModeAccessPoint(
    val name: String,
    val mac: String,
)

private enum class MonitorModeSheetStep {
    CONFIRMATION,
    COMMAND,
}

@Composable
internal fun MonitorModeSheet(
    target: MonitorModeSheetTarget,
    onDismiss: () -> Unit,
    onExecute: (
        command: String,
        channel: Int,
        frequencyMhz: Int,
    ) -> Unit,
    show: Boolean
) {
    var step by remember(target.channel, target.frequencyMhz) {
        mutableStateOf(MonitorModeSheetStep.CONFIRMATION)
    }

    var permissionConfirmed by remember(
        target.channel,
        target.frequencyMhz,
    ) {
        mutableStateOf(false)
    }

    var command by remember(
        target.channel,
        target.frequencyMhz,
    ) {
        mutableStateOf(DEFAULT_MONITOR_COMMAND)
    }

    val bottomPadding = WindowInsets.safeDrawing
        .asPaddingValues()
        .calculateBottomPadding()

    OverlayBottomSheet(
        show = show,
        title = "进入监听模式",
        allowDismiss = true,
        enableNestedScroll = true,
        renderInRootScaffold = true,
        onDismissRequest = onDismiss,
    ) {
        /*
         * 不再使用 fillMaxHeight。
         *
         * OverlayBottomSheet 0.9.3 自身使用 wrapContentHeight +
         * heightIn(max = 屏幕高度 - 顶部安全区域)，因此：
         *
         * 1. 内容较少时，Sheet 自动适应内容高度；
         * 2. 内容超出可用空间时，中间 LazyColumn 获得有限高度并滚动；
         * 3. LazyColumn 到达顶部后继续下拉，嵌套滚动会交给 Sheet，
         *    从而拖动并关闭 Sheet。
         */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /*
             * 信道信息位于 AnimatedContent 和 LazyColumn 之外，
             * 不参与页面左右切换，也不会随中间内容滚动。
             */
            Text(
                text = "信道 ${target.channel} · " +
                        "${frequencyBand(target.frequencyMhz)} · " +
                        "${target.frequencyMhz} MHz",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )

            /*
             * 只有中间页面参与：
             *
             * 1. 内容滚动；
             * 2. 左右页面切换；
             * 3. 两个页面之间的高度变化动画。
             *
             * weight(fill = false) 很关键：
             * 页面较小时按实际内容高度测量；
             * 页面过高时最多使用 Sheet 剩余可用高度。
             */
            AnimatedContent(
                targetState = step,
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
                            durationMillis = PAGE_TRANSITION_DURATION_MILLIS,
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
                            durationMillis = PAGE_TRANSITION_DURATION_MILLIS,
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
                label = "MonitorModeSheetStep",
            ) { currentStep ->
                when (currentStep) {
                    MonitorModeSheetStep.CONFIRMATION -> {
                        MonitorModeConfirmationContent(
                            target = target,
                            permissionConfirmed = permissionConfirmed,
                            onPermissionConfirmedChange = {
                                permissionConfirmed = it
                            },
                        )
                    }

                    MonitorModeSheetStep.COMMAND -> {
                        MonitorModeCommandContent(
                            command = command,
                            onCommandChange = {
                                command = it
                            },
                        )
                    }
                }
            }

            /*
             * 按钮位于 AnimatedContent 和 LazyColumn 之外，
             * 不参与页面移动，也不会随内容滚动。
             *
             * Sheet 从底部对齐，所以高度变化期间按钮仍停留在底部。
             */
            MonitorModeActions(
                step = step,
                permissionConfirmed = permissionConfirmed,
                onDismiss = onDismiss,
                onPrevious = {
                    step = MonitorModeSheetStep.CONFIRMATION
                },
                onNext = {
                    step = MonitorModeSheetStep.COMMAND
                },
                onExecute = {
                    onExecute(
                        command,
                        target.channel,
                        target.frequencyMhz,
                    )
                },
            )
        }
    }
}

@Composable
private fun MonitorModeConfirmationContent(
    target: MonitorModeSheetTarget,
    permissionConfirmed: Boolean,
    onPermissionConfirmedChange: (Boolean) -> Unit,
) {
    val confirmationInteractionSource = remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .scrollEndHaptic()
            .overScrollVertical(),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "进入监听模式可能会录制到以下接入点的信号：",
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.subtitle,
            )
        }

        item {
            Card {
                target.accessPoints.forEach { accessPoint ->
                    BasicComponent(
                        title = accessPoint.name,
                        summary = accessPoint.mac,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = confirmationInteractionSource,
                        indication = null,
                        onClick = {
                            onPermissionConfirmedChange(!permissionConfirmed)
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    state = if (permissionConfirmed) {
                        ToggleableState.On
                    } else {
                        ToggleableState.Off
                    },
                    onClick = {
                        onPermissionConfirmedChange(!permissionConfirmed)
                    },
                )

                Text(
                    text = "我已拥有以上网络的测试权限，且知晓监听模式只用来诊断信号使用",
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.headline2,
                )
            }
        }
    }
}

@Composable
private fun MonitorModeCommandContent(
    command: String,
    onCommandChange: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .scrollEndHaptic()
            .overScrollVertical(),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "请将信道数字留为【信道】。\n" +
                        "支持监听模式的网卡市面较少，" +
                        "且大多数需要刷入自定义内核，极可能操作失败。\n" +
                        "以下命令只使用 Redmi Note 12T Pro 测试通过，" +
                        "其他设备支持情况未知，可根据设备情况修改命令。",
                color =
                    MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }

        item {
            TextField(
                value = command,
                onValueChange = onCommandChange,
                modifier = Modifier.fillMaxWidth(),
                label = "进入命令",
                minLines = 11,
                maxLines = 16,
            )
        }
    }
}

@Composable
private fun MonitorModeActions(
    step: MonitorModeSheetStep,
    permissionConfirmed: Boolean,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExecute: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (step) {
            MonitorModeSheetStep.CONFIRMATION -> {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )

                Spacer(
                    modifier = Modifier.width(20.dp),
                )

                TextButton(
                    text = "下一步",
                    onClick = onNext,
                    enabled = permissionConfirmed,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            MonitorModeSheetStep.COMMAND -> {
                TextButton(
                    text = "上一步",
                    onClick = onPrevious,
                    modifier = Modifier.weight(1f),
                )

                Spacer(
                    modifier = Modifier.width(20.dp),
                )

                TextButton(
                    text = "执行",
                    onClick = onExecute,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private const val PAGE_TRANSITION_DURATION_MILLIS = 320

private const val DEFAULT_MONITOR_COMMAND = """svc wifi disable
setprop ctl.restart wificond
setprop ctl.restart vendor.wifi_hal_legacy
start wificond
start vendor.wifi_hal_legacy
pkill wpa_supplicant
ip link set wlan0 down
iw wlan0 set type monitor
ip link set wlan0 up
iw dev wlan0 set channel 【信道】 HT20"""