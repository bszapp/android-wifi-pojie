package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCaptureClearProgress
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCaptureClearStage
import io.github.bszapp.wifitoolbox.uidefault.component.BlockingLoadingDialog
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 始终参与组合；退场期间使用服务保留的最后一次操作内容。 */
@Composable
internal fun MonitorCaptureClearSheet(progress: MonitorCaptureClearProgress?, onInterrupt: (Long) -> Unit) {
    val message = when (progress?.stage) {
        MonitorCaptureClearStage.SWITCHING -> "正在切换清理后的数据"
        MonitorCaptureClearStage.FINISHED -> "清理完成"
        else -> "正在处理尾部增量"
    }
    BlockingLoadingDialog(
        visible = progress?.isRunning == true,
        operationId = progress?.operationId ?: 0L,
        text = "${if (progress?.handshakesOnly == true) "清空非握手数据" else "清空抓取数据"}：$message",
        interruptionWarning = "强制停止本次清理及录制进程，保留当前已有的抓包文件。已完成的清理不会自动回滚。",
        onInterrupt = onInterrupt,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
                targetState = progress?.takeIf {
                    it.stage == MonitorCaptureClearStage.PREPARING && it.totalBytes > 0L
                },
                contentKey = { it == null },
                label = "monitor-clear-progress",
            ) { visibleProgress ->
            if (visibleProgress != null) {
                val processed = visibleProgress.processedBytes.coerceIn(0L, visibleProgress.totalBytes)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${formatMonitorByteCount(processed)} / ${formatMonitorByteCount(visibleProgress.totalBytes)}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        progress = (processed.toDouble() / visibleProgress.totalBytes).toFloat(),
                    )
                }
            }
            }
        }
    }
}
