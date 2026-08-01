package io.github.bszapp.wifitoolbox.uidefault.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.container.ContainerState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun InstallContainerConfirmationSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    //TODO:增加本地选择功能
    OverlayBottomSheet(
        show = show,
        title = "安装容器系统",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "确认解压内置容器系统？",
                color = MiuixTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                ) { Text("确认") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
fun ContainerProgressSheet(state: ContainerState) {
    val progress = state.progress
    OverlayBottomSheet(
        show = state.isBusy,
        title = state.operation?.title ?: "容器系统",
        allowDismiss = false,
        onDismissRequest = {},
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = progress?.message.orEmpty(),
                color = MiuixTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = progress?.fraction,
            )
            Text(
                text = progress?.detail.orEmpty(),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
