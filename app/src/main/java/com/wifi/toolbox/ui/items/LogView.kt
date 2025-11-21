package com.wifi.toolbox.ui.items

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch


class LogState {
    val logs = androidx.compose.runtime.mutableStateListOf<String>()
    var wordWrap by mutableStateOf(false)
    var autoScroll by mutableStateOf(true)

    fun addLog(log: String) {
        logs.add(log)
    }

    fun clear() {
        logs.clear()
    }

    fun setLine(log: String) {
        if (logs.isNotEmpty()) {
            logs[logs.size - 1] = log
        } else {
            addLog(log)
        }
    }
}

@Composable
fun rememberLogState(): LogState = remember { LogState() }

@Composable
fun LogView(
    logState: LogState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(logState.logs.size, logState.autoScroll) {
        if (logState.autoScroll) {
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background)
    ) {
        SelectionContainer {
            val allLogs = logState.logs.joinToString("\n")
            Text(
                text = allLogs,
                fontFamily = FontFamily.Monospace,
                softWrap = logState.wordWrap,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .verticalScroll(verticalScrollState)
                    .then(if (!logState.wordWrap) Modifier.horizontalScroll(horizontalScrollState) else Modifier)
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogActionsSplitButton(
                logState = logState,
                context = context,
                verticalScrollState = verticalScrollState
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LogActionsSplitButton(logState: LogState, context: Context, verticalScrollState: ScrollState) {

    var showMoreActions by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Row(
        Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {

        val buttonColor = ToggleButtonDefaults.toggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            ToggleButton(
                checked = false,
                onCheckedChange = { logState.clear() },
                modifier = Modifier,
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                colors = buttonColor
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "清空日志",
                )
            }

            ToggleButton(
                checked = false,
                onCheckedChange = {
                    val clipboardManager =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("Log", logState.logs.joinToString("\n"))
                    clipboardManager.setPrimaryClip(clipData)
                },

                modifier = Modifier,
                shapes = ButtonGroupDefaults.connectedMiddleButtonShapes(),
                colors = buttonColor
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制日志",
                )
            }

            Box {
                ToggleButton(
                    checked = showMoreActions,
                    onCheckedChange = { showMoreActions = it },
                    modifier = Modifier,
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    colors = buttonColor
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                    )
                }

                DropdownMenu(
                    expanded = showMoreActions,
                    onDismissRequest = { showMoreActions = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("自动滚动") },
                        onClick = {
                            logState.autoScroll = !logState.autoScroll
                            showMoreActions = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.KeyboardDoubleArrowDown,
                                contentDescription = "自动滚动"
                            )
                        },
                        trailingIcon = {
                            if (logState.autoScroll) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("自动换行") },
                        onClick = {
                            logState.wordWrap = !logState.wordWrap
                            showMoreActions = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.WrapText,
                                contentDescription = "自动换行"
                            )
                        },
                        trailingIcon = {
                            if (logState.wordWrap) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("滚动到顶部") },
                        onClick = {
                            showMoreActions = false
                            coroutineScope.launch { verticalScrollState.scrollTo(0) }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.VerticalAlignTop,
                                contentDescription = "滚动到顶部"
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("滚动到底部") },
                        onClick = {
                            showMoreActions = false
                            coroutineScope.launch {
                                verticalScrollState.animateScrollTo(
                                    verticalScrollState.maxValue
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.VerticalAlignBottom,
                                contentDescription = "滚动到底部"
                            )
                        }
                    )
                }
            }
        }

    }
}


@Preview(showBackground = true)
@Composable
fun LogViewPreview() {
    val logState = rememberLogState()
    LaunchedEffect(Unit) {
        logState.addLog("Log message 1")
        logState.addLog("Log message 2")
        logState.addLog("Another log message")
        logState.setLine("This is the last line, modified.")
    }
    LogView(logState = logState)
}