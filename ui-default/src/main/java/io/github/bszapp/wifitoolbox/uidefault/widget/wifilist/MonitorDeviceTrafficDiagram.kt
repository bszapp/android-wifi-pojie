package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorAccessPoint
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDevice
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorSignalStatistics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 图中的信号和速率沿用服务数据，仅距捕获时间由 UI 时钟渲染。 */
@Composable
internal fun MonitorDeviceTrafficDiagram(accessPoint: MonitorAccessPoint, device: MonitorDevice) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                nowMillis = System.currentTimeMillis()
                delay(1_000L - nowMillis % 1_000L)
            }
        }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DiagramNode(
                title = "本设备", icon = Icons.Rounded.Smartphone,
                modifier = Modifier.fillMaxWidth(0.46f),
            )
            SignalLinks(device.realtime.signal, accessPoint.signal, nowMillis)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                DiagramNode(
                    title = "目标设备", icon = Icons.Rounded.Smartphone,
                    mac = device.mac, name = device.name?.takeIf(String::isNotBlank),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Spacer(Modifier.width(16.dp))
                DiagramNode(
                    title = "目标路由器", icon = Icons.Rounded.Router,
                    mac = accessPoint.bssid,
                    name = accessPoint.ssid?.takeIf(String::isNotBlank) ?: "<未知网络>",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            DirectionalTrafficLinks(
                uploadBytesPerSecond = device.realtime.uploadBytesPerSecond,
                downloadBytesPerSecond = device.realtime.downloadBytesPerSecond,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "信号由本设备接收测量，时间为距最近捕获。\n速率为最近五秒捕获的定向传输平均值。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DiagramNode(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    mac: String? = null,
    name: String? = null,
) {
    Column(
        modifier = modifier
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
            .border(1.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(42.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
        }
        Text(title, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        name?.let {
            Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center)
        }
        mac?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SignalLinks(device: MonitorSignalStatistics?, router: MonitorSignalStatistics?, nowMillis: Long) {
    val lineColor = MiuixTheme.colorScheme.primary
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Canvas(Modifier) {
                val start = Offset(size.width * 0.5f, 0f)
                val left = (size.width - 16.dp.toPx()) / 4f
                arrow(start, Offset(left, size.height), lineColor.copy(alpha = 0.5f), dashed = true)
                arrow(start, Offset(size.width - left, size.height), lineColor.copy(alpha = 0.5f), dashed = true)
                drawCircle(lineColor, radius = 2.5.dp.toPx(), center = start)
            }
            SignalReading(device, nowMillis)
            SignalReading(router, nowMillis)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val labels = measurables.drop(1).map { it.measure(Constraints(maxWidth = (width * 0.32f).toInt())) }
        val height = maxOf(132.dp.roundToPx(), labels.maxOf { it.height } + 64.dp.roundToPx())
        val canvas = measurables.first().measure(Constraints.fixed(width, height))
        layout(width, height) {
            canvas.place(0, 0)
            labels.forEachIndexed { index, label ->
                val left = (width - 16.dp.toPx()) / 4f
                val target = if (index == 0) left else width - left
                val x = width * 0.5f + (target - width * 0.5f) * 0.65f
                label.place((x - label.width / 2f).toInt(), (height * 0.65f - label.height / 2f).toInt())
            }
        }
    }
}

@Composable
private fun SignalReading(signal: MonitorSignalStatistics?, nowMillis: Long) {
    Column(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(signal?.let { "${it.latestDbm} dBm" } ?: "未知",
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        Text(
            text = signal?.lastSeenUnixMillis?.takeIf { it > 0L }
                ?.let { formatCaptureAge(nowMillis, it) } ?: "暂无捕获",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
        )
    }
}

private fun formatCaptureAge(nowMillis: Long, capturedMillis: Long): String {
    val seconds = (nowMillis - capturedMillis).coerceAtLeast(0L) / 1_000L
    return when {
        seconds < 60L -> "${seconds}秒"
        seconds < 3_600L -> "${seconds / 60L}分"
        seconds < 86_400L -> "${seconds / 3_600L}时"
        else -> "${seconds / 86_400L}天"
    }
}

@Composable
private fun DirectionalTrafficLinks(uploadBytesPerSecond: Long, downloadBytesPerSecond: Long) {
    val lineColor = MiuixTheme.colorScheme.primary
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Canvas(Modifier) {
                val left = (size.width - 16.dp.toPx()) / 4f
                val right = size.width - left
                val uploadY = size.height / 3f
                val downloadY = size.height * 2f / 3f
                drawLine(lineColor.copy(alpha = 0.3f), Offset(left, 0f), Offset(left, downloadY), 1.5.dp.toPx())
                drawLine(lineColor.copy(alpha = 0.3f), Offset(right, 0f), Offset(right, downloadY), 1.5.dp.toPx())
                arrow(Offset(left, uploadY), Offset(right, uploadY), lineColor.copy(alpha = 0.7f))
                arrow(Offset(right, downloadY), Offset(left, downloadY), lineColor.copy(alpha = 0.7f))
                drawCircle(lineColor.copy(alpha = 0.7f), 2.dp.toPx(), Offset(left, uploadY))
                drawCircle(lineColor.copy(alpha = 0.7f), 2.dp.toPx(), Offset(right, downloadY))
            }
            RateReading("上传", uploadBytesPerSecond)
            RateReading("下载", downloadBytesPerSecond)
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val labels = measurables.drop(1).map { it.measure(Constraints(maxWidth = (width * 0.4f).toInt())) }
        val height = maxOf(120.dp.roundToPx(), labels.maxOf { it.height } * 3 + 12.dp.roundToPx())
        val canvas = measurables.first().measure(Constraints.fixed(width, height))
        layout(width, height) {
            canvas.place(0, 0)
            labels.forEachIndexed { index, label ->
                val y = height * (index + 1) / 3f
                label.place((width - label.width) / 2, (y - label.height / 2f).toInt())
            }
        }
    }
}

@Composable
private fun RateReading(title: String, bytesPerSecond: Long) {
    Column(
        modifier = Modifier
            .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text("${formatMonitorByteCount(bytesPerSecond)}/s", style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
    }
}

private fun DrawScope.arrow(start: Offset, end: Offset, color: Color, dashed: Boolean = false) {
    val vector = end - start
    val length = vector.getDistance()
    if (length <= 0f) return
    val direction = vector / length
    val normal = Offset(-direction.y, direction.x)
    val base = end - direction * 8.dp.toPx()
    val stroke = 1.5.dp.toPx()
    drawLine(color, start, end, stroke, cap = StrokeCap.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())) else null)
    drawLine(color, end, base + normal * 4.dp.toPx(), stroke, cap = StrokeCap.Round)
    drawLine(color, end, base - normal * 4.dp.toPx(), stroke, cap = StrokeCap.Round)
}
