package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCaptureClearProgress
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCaptureClearStage
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 始终参与组合；退场期间使用服务保留的最后一次操作内容。 */
@Composable
internal fun MonitorCaptureClearSheet(progress: MonitorCaptureClearProgress?) {
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val message = when (progress?.stage) {
        MonitorCaptureClearStage.SWITCHING -> "正在切换清理后的数据"
        MonitorCaptureClearStage.FINISHED -> "清理完成"
        else -> "正在处理尾部增量"
    }
    SingleOverlayBottomSheet(
        show = progress?.isRunning == true,
        title = if (progress?.handshakesOnly == true) "清空非握手数据" else "清空抓取数据",
        allowDismiss = false,
        onDismissRequest = {},
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(message, style = MiuixTheme.textStyles.body2)
            }
            if (progress != null && progress.stage == MonitorCaptureClearStage.PREPARING && progress.totalBytes > 0L) {
                val processed = progress.processedBytes.coerceIn(0L, progress.totalBytes)
                Text(
                    "${formatMonitorByteCount(processed)} / ${formatMonitorByteCount(progress.totalBytes)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = (processed.toDouble() / progress.totalBytes).toFloat(),
                )
            }
        }
    }
}
