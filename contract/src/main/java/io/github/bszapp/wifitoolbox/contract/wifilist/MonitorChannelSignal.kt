package io.github.bszapp.wifitoolbox.contract.wifilist

import android.net.wifi.ScanResult
import kotlin.math.exp
import kotlin.math.roundToLong

/** 标称覆盖范围；主频段顶点为主信道，80+80 的辅助段顶点为该段中心。 */
data class MonitorSignalWindow(val startMhz: Float, val peakMhz: Float, val endMhz: Float) {
    fun contributionAt(frequencyMhz: Float): Double = when {
        frequencyMhz < startMhz || frequencyMhz > endMhz -> 0.0
        frequencyMhz == peakMhz -> 1.0
        frequencyMhz < peakMhz -> ((frequencyMhz - startMhz) / (peakMhz - startMhz)).toDouble()
        else -> ((endMhz - frequencyMhz) / (endMhz - peakMhz)).toDouble()
    }
}

fun monitorSignalWindows(network: ScanResult): List<MonitorSignalWindow> {
    val width = when (network.channelWidth) {
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40f
        ScanResult.CHANNEL_WIDTH_80MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80f
        ScanResult.CHANNEL_WIDTH_160MHZ -> 160f
        ScanResult.CHANNEL_WIDTH_320MHZ -> 320f
        else -> 20f
    }
    val center = network.centerFreq0.takeIf { it > 0 } ?: network.frequency
    val half = if (network.centerFreq0 > 0) width / 2f else 10f
    fun window(centerMhz: Int, halfWidth: Float): MonitorSignalWindow {
        val start = centerMhz - halfWidth
        val end = centerMhz + halfWidth
        val peak = network.frequency.toFloat().takeIf { it in start..end } ?: centerMhz.toFloat()
        return MonitorSignalWindow(start, peak, end)
    }
    return buildList {
        add(window(center, half))
        if (network.channelWidth == ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ && network.centerFreq1 > 0) {
            add(window(network.centerFreq1, 40f))
        }
    }
}

/**
 * 图表与服务跳频共用的预测指数。RSSI 转为 max(1, RSSI + 100)，
 * 乘以各频率的三角形覆盖系数后平方求和，再使用 ±4 MHz 高斯核平滑。
 * 仅使用扫描快照，未知 RSSI 不参与；该指数不表示实测流量或功率谱。
 */
fun monitorSignalIndices(scanResults: List<ScanResult>, frequenciesMhz: List<Float>): List<Double> {
    val profiles = scanResults.asSequence()
        .filter { it.level < 0 && it.frequency > 0 }
        .distinctBy { it.BSSID.lowercase() }
        .map { it.level.plus(100).coerceIn(1, 100).toDouble() to monitorSignalWindows(it) }
        .toList()
    val kernel = (-4..4).map { it to exp(-it * it / 8.0) }
    val kernelSum = kernel.sumOf { it.second }
    // 图表相邻采样点共用原始指数，避免为每个平滑窗口重复遍历全部接入点。
    val rawIndices = HashMap<Float, Double>()
    fun rawIndexAt(frequency: Float): Double = rawIndices.getOrPut(frequency) {
        profiles.sumOf { (strength, windows) ->
            val contribution = strength * windows.maxOf { it.contributionAt(frequency) }
            contribution * contribution
        }
    }
    return frequenciesMhz.map { frequency ->
        kernel.sumOf { (offset, weight) ->
            weight * rawIndexAt(frequency + offset)
        } / kernelSum
    }
}

/** 每信道至少 100ms，其余周期时间按指数分配；无信号时等时轮询。 */
fun monitorHoppingDwellMillis(scanResults: List<ScanResult>, channels: List<MonitorChannel>): List<Long> {
    if (channels.isEmpty()) return emptyList()
    val indices = monitorSignalIndices(scanResults, channels.map { it.frequencyMhz.toFloat() })
    val total = indices.sum()
    val cycleMillis = maxOf(3_000L, channels.size * 120L)
    val weightedMillis = cycleMillis - channels.size * 100L
    return indices.map { index ->
        val proportion = if (total > 0.0) index / total else 1.0 / channels.size
        100L + (weightedMillis * proportion).roundToLong()
    }
}
