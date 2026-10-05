@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import android.net.wifi.ScanResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChannel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class MonitorChartBand(val title: String, val frequencies: IntRange) {
    BAND_24("2.4 GHz", 2400..2500),
    BAND_5("5 GHz", 4900..5900),
}

/** 全部标准 20 MHz 主信道，不依赖本机是否支持或是否扫描到网络。 */
private fun standardChartFrequencies(band: MonitorChartBand): List<Int> = when (band) {
    MonitorChartBand.BAND_24 -> (1..13).map { 2407 + it * 5 } + 2484
    MonitorChartBand.BAND_5 -> ((36..64 step 4) + (100..144 step 4) + (149..177 step 4))
        .map { 5000 + it * 5 }
}

/** 图形、刻度与手势共用同一线性坐标轴，信道 14 等非等距编号按真实 MHz 定位。 */
private data class FrequencyAxis(val minimum: Float, val maximum: Float) {
    fun position(frequency: Float, left: Float, right: Float): Float =
        left + (frequency - minimum) / (maximum - minimum) * (right - left)

    fun frequency(position: Float, left: Float, right: Float): Float =
        minimum + ((position - left) / (right - left)).coerceIn(0f, 1f) * (maximum - minimum)
}

private data class ChannelTickLabel(
    val frequency: Int,
    val layout: TextLayoutResult,
    val x: Float,
    val row: Int,
)

/** 绘制和命中判断共用的标称工作频率范围；80+80 MHz 分成两个独立区间。 */
internal fun scanFrequencyWindows(network: ScanResult): List<ClosedFloatingPointRange<Float>> {
    val width = when (network.channelWidth) {
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40f
        ScanResult.CHANNEL_WIDTH_80MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80f
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160f
        ScanResult.CHANNEL_WIDTH_320MHZ -> 320f
        else -> 20f
    }
    val center = network.centerFreq0.takeIf { it > 0 } ?: network.frequency
    val halfWidth = if (network.centerFreq0 > 0) width / 2 else 10f
    val windows = mutableListOf((center - halfWidth)..(center + halfWidth))
    if (network.channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ && network.centerFreq1 > 0) {
        windows += (network.centerFreq1 - 40f)..(network.centerFreq1 + 40f)
    }
    return windows
}

internal fun chartHitNetworks(scanResults: List<ScanResult>, frequency: Int): List<ScanResult> =
    scanResults.filter { network ->
        network.level < 0 && scanFrequencyWindows(network).any { frequency.toFloat() in it }
    }.distinctBy { it.BSSID.lowercase() }.sortedByDescending { it.level }

@Composable
internal fun MonitorChannelChart(
    band: MonitorChartBand,
    channels: List<MonitorChannel>,
    scanResults: List<ScanResult>,
    selectedFrequency: Int?,
    onSelect: (Int) -> Unit,
) {
    val supported = remember(channels, band) {
        channels.filter { it.frequencyMhz in band.frequencies }.distinctBy { it.frequencyMhz }
            .sortedBy { it.frequencyMhz }
    }
    val networks = remember(scanResults, band) {
        scanResults.filter { it.frequency in band.frequencies }.distinctBy { it.BSSID.lowercase() }
    }
    val visible = remember(networks) { networks.filter { it.level < 0 }.sortedBy { it.level } }
    val windows = remember(visible) { visible.map { it to scanFrequencyWindows(it) } }
    val ticks = remember(band, supported, networks) {
        (standardChartFrequencies(band) + supported.map { it.frequencyMhz } + networks.map { it.frequency })
            .distinct().sorted()
    }
    val axis = remember(ticks) { FrequencyAxis(ticks.first() - 20f, ticks.last() + 20f) }
    val supportedFrequencies = remember(supported) { supported.map { it.frequencyMhz }.toSet() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val axisColor = colors.onSurfaceVariantSummary
    val gridColor = axisColor.copy(alpha = if (dark) 0.22f else 0.16f)
    val cursorColor = if (dark) Color(0xFF64B5F6) else Color(0xFF1976D2)
    val labelStyle = MiuixTheme.textStyles.footnote1
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val density = LocalDensity.current
    val minimumDbm = minOf(-100, (floor((visible.minOfOrNull { it.level } ?: -100) / 10.0) * 10).toInt())
    val maximumDbm = maxOf(-30, (ceil((visible.maxOfOrNull { it.level } ?: -30) / 10.0) * 10).toInt())

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(band.title, style = MiuixTheme.textStyles.body1, color = colors.onSurface)
            Text(
                if (supported.isEmpty()) "网卡不支持此频段" else "信号强度（dBm） · 点击或拖动选信道，淡色刻度不可选",
                style = labelStyle, color = axisColor,
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val widthPx = with(density) { maxWidth.toPx() }
                val measuredTicks = remember(ticks, labelStyle, measurer) {
                    ticks.map { frequency -> frequency to measurer.measure(
                        frequencyToChannel(frequency)?.toString() ?: "?", style = labelStyle, softWrap = false,
                    ) }
                }
                val dbmLabels = remember(minimumDbm, maximumDbm, labelStyle, measurer) {
                    (minimumDbm..maximumDbm step 10).map { it to measurer.measure(it.toString(), style = labelStyle) }
                }
                // 为坐标文字预留真实测量宽度，轴刻度不移动、不裁掉，也不按数量抽样。
                val halfTickWidth = measuredTicks.maxOf { it.second.size.width } / 2f
                val left = dbmLabels.maxOf { it.second.size.width } + with(density) { 10.dp.toPx() } + halfTickWidth
                val right = widthPx - halfTickWidth - with(density) { 8.dp.toPx() }
                val top = with(density) { 24.dp.toPx() }
                val bottom = top + with(density) { 172.dp.toPx() }
                val rowHeight = measuredTicks.maxOf { it.second.size.height }.toFloat() + with(density) { 4.dp.toPx() }
                val tickLabels = remember(measuredTicks, axis, left, right, density) {
                    val rowEnds = mutableListOf<Float>()
                    val gap = with(density) { 4.dp.toPx() }
                    measuredTicks.map { (frequency, layout) ->
                        val tickX = axis.position(frequency.toFloat(), left, right)
                        val labelStart = tickX - layout.size.width / 2f
                        var row = rowEnds.indexOfFirst { it + gap <= labelStart }
                        if (row < 0) { row = rowEnds.size; rowEnds += Float.NEGATIVE_INFINITY }
                        rowEnds[row] = tickX + layout.size.width / 2f
                        ChannelTickLabel(frequency, layout, tickX, row)
                    }
                }
                val labelTop = bottom + with(density) { 8.dp.toPx() }
                val axisTitle = measurer.measure("Wi-Fi 信道", style = labelStyle)
                val axisTitleTop = labelTop + (tickLabels.maxOf { it.row } + 1) * rowHeight
                val chartHeight = with(density) { (axisTitleTop + axisTitle.size.height + 6.dp.toPx()).toDp() }
                Canvas(
                    modifier = Modifier.fillMaxWidth().height(chartHeight)
                        .semantics { contentDescription = "${band.title} 信道与信号图，${visible.size} 个接入点" }
                        .pointerInput(supported, axis, left, right, bottom) {
                            fun selectAt(position: Float) {
                                if (right <= left) return
                                val frequency = axis.frequency(position, left, right)
                                supported.minWithOrNull(compareBy<MonitorChannel> { abs(it.frequencyMhz - frequency) }
                                    .thenBy { it.frequencyMhz })?.let { currentOnSelect(it.frequencyMhz) }
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (supported.isEmpty() || down.position.x !in left..right || down.position.y !in top..bottom) {
                                    return@awaitEachGesture
                                }
                                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                    change.consume()
                                    selectAt(change.position.x)
                                }
                                if (drag != null) {
                                    horizontalDrag(drag.id) { change ->
                                        change.consume()
                                        selectAt(change.position.x)
                                    }
                                } else {
                                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                    if (up != null && !up.isConsumed && up.changedToUpIgnoreConsumed()) {
                                        selectAt(up.position.x)
                                    }
                                }
                            }
                        },
                ) {
                    fun x(frequency: Float) = axis.position(frequency, left, right)
                    fun y(dbm: Int) = bottom - (dbm - minimumDbm).toFloat() /
                        (maximumDbm - minimumDbm) * (bottom - top)
                    fun label(text: String, color: Color, position: Offset, centered: Boolean = false) {
                        val measured = measurer.measure(text, style = labelStyle, softWrap = false)
                        val labelX = if (centered) position.x - measured.size.width / 2f else position.x
                        drawText(measured, color = color,
                            topLeft = Offset(labelX.coerceIn(0f, maxOf(0f, size.width - measured.size.width)), position.y))
                    }
                    dbmLabels.forEach { (dbm, text) ->
                        drawLine(gridColor, Offset(left, y(dbm)), Offset(right, y(dbm)), 1.dp.toPx())
                        drawText(text, color = axisColor,
                            topLeft = Offset(left - text.size.width - 5.dp.toPx(), y(dbm) - text.size.height / 2f))
                    }
                    tickLabels.forEach { tick ->
                        val supportedTick = tick.frequency in supportedFrequencies
                        val selectedTick = tick.frequency == selectedFrequency
                        val tickColor = when {
                            selectedTick -> cursorColor
                            supportedTick -> colors.onSurface
                            else -> axisColor.copy(alpha = 0.55f)
                        }
                        drawLine(gridColor, Offset(tick.x, top), Offset(tick.x, bottom), 1.dp.toPx())
                        drawLine(tickColor.copy(alpha = 0.55f), Offset(tick.x, bottom),
                            Offset(tick.x, bottom + 4.dp.toPx()), 1.dp.toPx())
                        drawText(tick.layout, color = tickColor,
                            topLeft = Offset(tick.x - tick.layout.size.width / 2f, labelTop + tick.row * rowHeight))
                    }
                    drawText(axisTitle, color = axisColor,
                        topLeft = Offset((left + right - axisTitle.size.width) / 2f, axisTitleTop))
                    drawLine(axisColor.copy(alpha = 0.5f), Offset(left, top), Offset(left, bottom), 1.dp.toPx())
                    drawLine(axisColor.copy(alpha = 0.5f), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
                    clipRect(left, 0f, right, bottom) {
                        windows.forEach { (network, ranges) ->
                            val hue = Math.floorMod(network.BSSID.lowercase().hashCode(), 360).toFloat()
                            val color = Color.hsv(hue, if (dark) 0.55f else 0.78f, if (dark) 0.95f else 0.68f)
                            val hit = selectedFrequency != null && ranges.any { selectedFrequency.toFloat() in it }
                            ranges.forEach { range ->
                                val start = x(range.start)
                                val end = x(range.endInclusive)
                                val shoulder = (end - start) * 0.08f
                                val peak = y(network.level)
                                val path = Path().apply {
                                    moveTo(start, bottom)
                                    lineTo(start + shoulder, peak)
                                    lineTo(end - shoulder, peak)
                                    lineTo(end, bottom)
                                    close()
                                }
                                drawPath(path, color.copy(alpha = if (hit) 0.24f else 0.12f))
                                drawPath(path, color.copy(alpha = if (hit) 1f else 0.8f),
                                    style = Stroke(if (hit) 2.dp.toPx() else 1.3.dp.toPx()))
                            }
                            val text = "${network.SSID?.takeIf { it.isNotBlank() } ?: "<隐藏的网络>"} ${frequencyToChannel(network.frequency) ?: "?"}"
                            val measured = measurer.measure(text, style = labelStyle, softWrap = false)
                            label(text, color, Offset(x(network.frequency.toFloat()), y(network.level) - measured.size.height - 2.dp.toPx()),
                                centered = true)
                        }
                    }
                    selectedFrequency?.takeIf { it in band.frequencies }?.let { frequency ->
                        val cursorX = x(frequency.toFloat())
                        drawLine(cursorColor, Offset(cursorX, top), Offset(cursorX, bottom), 2.dp.toPx())
                        drawCircle(cursorColor, radius = 3.dp.toPx(), center = Offset(cursorX, bottom))
                        label("${frequencyToChannel(frequency)}", cursorColor,
                            Offset(cursorX, 0f), centered = true)
                    }
                    if (visible.isEmpty()) {
                        label("暂无信号记录", axisColor, Offset((left + right) / 2, (top + bottom) / 2), centered = true)
                    }
                }
            }
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = networks.size > visible.size) {
                Text("${networks.size - visible.size} 个接入点信号未知，未绘制", style = labelStyle, color = axisColor)
            }
        }
    }
}
