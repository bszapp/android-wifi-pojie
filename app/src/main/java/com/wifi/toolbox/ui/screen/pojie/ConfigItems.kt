package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.animation.*
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import com.wifi.toolbox.R
import com.wifi.toolbox.structs.PojieConfig
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigItems(
    config: PojieConfig,
    onConfigChange: (PojieConfig) -> Unit
) {
    val retryCountLabels = listOf("不重试", "1", "2", "3", "4", "5", "无限")
    val failureOptions = listOf("密码错误", "握手超时", "握手超次")

    val belowAnchorPopupPositionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
                return IntOffset(
                    x.coerceIn(0, windowSize.width - popupContentSize.width),
                    anchorBounds.bottom
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),

        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "单次尝试",
            modifier = Modifier.padding(0.dp, 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("最大尝试时间")
        Text(
            text = "${config.maxTryTime} ms",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
    Slider(
        value = config.maxTryTime.toFloat(),
        onValueChange = { onConfigChange(config.copy(maxTryTime = it.toInt())) },
        valueRange = 1000f..10000f,
        steps = (10000 - 1000) / 500 - 1
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "失败标志",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        val scope = rememberCoroutineScope()
        val tooltipState = rememberTooltipState(isPersistent = true)
        TooltipBox(
            positionProvider = belowAnchorPopupPositionProvider,
            tooltip = {
                RichTooltip(
                    modifier = Modifier
                        .width(320.dp)
                        .padding(8.dp),
                    title = {
                        Text("失败标志")
                    },
                    action = {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch { tooltipState.dismiss() }
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("了解更多")
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(start = 2.dp)
                                )
                            }

                            TextButton(onClick = {
                                scope.launch { tooltipState.dismiss() }
                            }) {
                                Text("知道了")
                            }
                        }
                    }
                ) {
                    Text(
                        text = stringResource(id = R.string.failure_flag_help_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            state = tooltipState
        ) {
            IconButton(onClick = {
                scope.launch {
                    tooltipState.show()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "帮助")
            }
        }
    }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        failureOptions.forEachIndexed { index, label ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = failureOptions.size
                ),
                onClick = { onConfigChange(config.copy(failureFlag = index)) },
                selected = index == config.failureFlag
            ) {
                Text(label)
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    AnimatedContent(
        targetState = config.failureFlag,
        label = "failure_option_content"
    ) { failureState ->
        when (failureState) {
            1 -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("超时时间")
                        Text(
                            text = "${config.timeout} ms",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Slider(
                        value = config.timeout.toFloat(),
                        onValueChange = { onConfigChange(config.copy(timeout = it.toInt())) },
                        valueRange = 500f..5000f,
                        steps = (5000f - 500f).toInt() / 250 - 1
                    )
                }
            }

            2 -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("最大握手次数")
                        Text(
                            text = "${config.maxHandshakeCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Slider(
                        value = config.maxHandshakeCount.toFloat(),
                        onValueChange = { onConfigChange(config.copy(maxHandshakeCount = it.toInt())) },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "异常重试",
            modifier = Modifier.padding(0.dp, 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        val scope = rememberCoroutineScope()
        val tooltipState = rememberTooltipState(isPersistent = true)
        TooltipBox(
            positionProvider = belowAnchorPopupPositionProvider,
            tooltip = {
                RichTooltip(
                    modifier = Modifier
                        .width(320.dp)
                        .padding(8.dp),
                    title = {
                        Text("异常重试")
                    },
                    action = {
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch { tooltipState.dismiss() }
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("了解更多")
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(start = 2.dp)
                                )
                            }

                            TextButton(onClick = {
                                scope.launch { tooltipState.dismiss() }
                            }) {
                                Text("知道了")
                            }
                        }
                    }
                ) {
                    Text(
                        text = stringResource(id = R.string.error_algin_help_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            state = tooltipState
        ) {
            IconButton(onClick = {
                scope.launch {
                    tooltipState.show()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "帮助")
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("重试次数")
        Text(
            text = retryCountLabels[config.retryCountType],
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
    Slider(
        value = config.retryCountType.toFloat(),
        onValueChange = { onConfigChange(config.copy(retryCountType = it.toInt())) },
        valueRange = 0f..6f,
        steps = 7
    )
    Spacer(modifier = Modifier.height(8.dp))
    AnimatedVisibility(
        visible = config.retryCountType != 0,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("等待时间翻倍基数")
                Text(
                    text = "${config.doublingBase} ms",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            Slider(
                value = config.doublingBase.toFloat(),
                onValueChange = { onConfigChange(config.copy(doublingBase = it.toInt())) },
                valueRange = 0f..8000f,
                steps = 15
            )
        }
    }
}