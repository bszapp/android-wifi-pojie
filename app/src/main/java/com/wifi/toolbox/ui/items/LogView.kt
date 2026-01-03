package com.wifi.toolbox.ui.items

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifi.toolbox.R
import com.wifi.toolbox.utils.LogState
import kotlinx.coroutines.launch

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .then(if (!logState.wordWrap) Modifier.horizontalScroll(horizontalScrollState) else Modifier)
        ) {
            SelectionContainer {
                val allLogs = logState.logs.joinToString("\n")
                Text(
                    text = allLogs,
                    fontFamily = FontFamily(
                        Font(resId = R.font.mono, weight = FontWeight.Normal)
                    ),
                    fontSize = 14.sp,
                    softWrap = logState.wordWrap,
                    style = TextStyle(
                        lineHeight = 19.sp,
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        ),
                        lineBreak = LineBreak(
                            strategy = LineBreak.Strategy.Simple,
                            strictness = LineBreak.Strictness.Loose,
                            wordBreak = LineBreak.WordBreak.Default
                        )
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            }
        }

        LogActionsFab(
            logState = logState,
            context = context,
            verticalScrollState = verticalScrollState,
            fabVisible = true,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.LogActionsFab(
    logState: LogState,
    context: Context,
    verticalScrollState: ScrollState,
    fabVisible: Boolean,
    modifier: Modifier = Modifier
) {
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val menuItems = listOf(
        FabMenuItem(
            label = "自动滚动",
            icon = Icons.Default.KeyboardDoubleArrowDown,
            isSelected = logState.autoScroll,
            onClick = { logState.autoScroll = !logState.autoScroll }
        ),
        FabMenuItem(
            label = "自动换行",
            icon = Icons.AutoMirrored.Filled.WrapText,
            isSelected = logState.wordWrap,
            onClick = { logState.wordWrap = !logState.wordWrap }
        ),
        FabMenuItem(
            label = "清空日志",
            icon = Icons.Filled.DeleteSweep,
            onClick = { logState.clear() }
        ),
        FabMenuItem(
            label = "复制日志",
            icon = Icons.Filled.ContentCopy,
            onClick = {
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText("Log", logState.logs.joinToString("\n"))
                clipboardManager.setPrimaryClip(clipData)
            }
        ),
        FabMenuItem(
            label = "滚动到顶部",
            icon = Icons.Default.VerticalAlignTop,
            onClick = { coroutineScope.launch { verticalScrollState.animateScrollTo(0) } }
        ),
        FabMenuItem(
            label = "滚动到底部",
            icon = Icons.Default.VerticalAlignBottom,
            onClick = {
                coroutineScope.launch {
                    verticalScrollState.animateScrollTo(
                        verticalScrollState.maxValue
                    )
                }
            }
        )
    )

    CustomFabMenu(
        expanded = fabMenuExpanded,
        onCheckedChange = { fabMenuExpanded = it },
        items = menuItems,
        visible = fabVisible,
        modifier = modifier
    )
}

@Preview(showBackground = true, device = "spec:width=1080px,height=1080px,dpi=480")
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