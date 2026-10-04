// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package io.github.bszapp.wifitoolbox.uidefault.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** 沿用 InstallerX 锁定安装器的窗口级加载布局；仅返回键允许请求强制中断。 */
@Composable
fun BlockingLoadingDialog(
    visible: Boolean,
    operationId: Long,
    text: String,
    interruptionWarning: String,
    onInterrupt: (Long) -> Unit,
    progress: @Composable () -> Unit = {},
) {
    var confirming by rememberSaveable(operationId) { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) confirming = false }
    WindowDialog(show = visible, onDismissRequest = {}) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = visible,
            onBackCompleted = { confirming = true },
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InfiniteProgressIndicator()
                Spacer(Modifier.width(16.dp))
                Text(text)
            }
            progress()
        }
    }
    WindowDialog(
        show = visible && confirming,
        title = "确认强制中断",
        summary = interruptionWarning,
        onDismissRequest = { confirming = false },
    ) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = visible,
            onBackCompleted = { confirming = false },
        )
        Row(Modifier.fillMaxWidth()) {
            TextButton("继续等待", onClick = { confirming = false }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(20.dp))
            TextButton("强制中断", onClick = {
                confirming = false
                onInterrupt(operationId)
            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}
