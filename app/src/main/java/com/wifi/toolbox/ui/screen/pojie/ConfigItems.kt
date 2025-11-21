package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.wifi.toolbox.R
import com.wifi.toolbox.structs.PojieConfig
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigItems(
    config: PojieConfig,
    onConfigChange: (PojieConfig) -> Unit,
    failureOptions: List<String>
) {
    val retryCountLabels = listOf("不重试", "1", "2", "3", "4", "5", "无限")

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
            text = "${config.maxTryTime.roundToInt()} ms",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
    Slider(
        value = config.maxTryTime,
        onValueChange = { onConfigChange(config.copy(maxTryTime = it)) },
        valueRange = 1000f..10000f,
        steps = (10000f - 1000f).toInt() / 500 - 1
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
                PlainTooltip(
                    modifier = Modifier
                        .width(320.dp)
                        .padding(8.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.failure_flag_help_text),
                        modifier = Modifier.padding(4.dp),
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
                            text = "${config.timeout.roundToInt()} ms",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Slider(
                        value = config.timeout,
                        onValueChange = { onConfigChange(config.copy(timeout = it)) },
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
                            text = "${config.maxHandshakeCount.roundToInt()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    Slider(
                        value = config.maxHandshakeCount,
                        onValueChange = { onConfigChange(config.copy(maxHandshakeCount = it)) },
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
                PlainTooltip(
                    modifier = Modifier
                        .width(320.dp)
                        .padding(8.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.error_algin_help_text),
                        modifier = Modifier.padding(4.dp),
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
            text = retryCountLabels[config.retryCount.roundToInt()],
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
    Slider(
        value = config.retryCount,
        onValueChange = { onConfigChange(config.copy(retryCount = it)) },
        valueRange = 0f..6f,
        steps = 5
    )
    Spacer(modifier = Modifier.height(8.dp))
    AnimatedVisibility(visible = config.retryCount.roundToInt() != 0) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("等待时间翻倍基数")
                Text(
                    text = if (config.doublingBase.roundToInt() == 0) "不翻倍" else "${config.doublingBase.roundToInt() * 1000} ms",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            Slider(
                value = config.doublingBase,
                onValueChange = { onConfigChange(config.copy(doublingBase = it)) },
                valueRange = 0f..8f,
                steps = 7
            )
        }
    }
}