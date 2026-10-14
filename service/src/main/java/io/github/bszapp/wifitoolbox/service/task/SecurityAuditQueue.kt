package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock

/** 从字典安全测试提取的排队与指数冷却规则，两类执行器共用。 */
internal interface SecurityAuditQueueEntry {
    val active: Boolean
    var lastAttemptAt: Long
    var readyAt: Long
}

internal object SecurityAuditQueue {
    fun <T : SecurityAuditQueueEntry> next(entries: List<T>): T? =
        entries.filter { it.active && it.readyAt <= SystemClock.elapsedRealtime() }.minByOrNull { it.lastAttemptAt }

    fun waitMillis(entries: List<SecurityAuditQueueEntry>): Long =
        (entries.filter { it.active }.minOf { it.readyAt } - SystemClock.elapsedRealtime()).coerceIn(1, 100)

    fun retryExhausted(limit: Int, count: Int) = limit < 6 && count > limit

    fun cooldownMillis(base: Long, retries: Int): Long {
        if (base == 0L || retries <= 0) return 0
        val multiplier = 1L shl (retries - 1).coerceAtMost(62)
        return if (multiplier > Long.MAX_VALUE / base) Long.MAX_VALUE else multiplier * base
    }

    fun readyAt(lastAttemptAt: Long, delay: Long) =
        if (delay > Long.MAX_VALUE - lastAttemptAt) Long.MAX_VALUE else lastAttemptAt + delay

    fun displayTime(readyAt: Long): Long {
        val now = System.currentTimeMillis()
        val remaining = (readyAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        return if (remaining > Long.MAX_VALUE - now) Long.MAX_VALUE else now + remaining
    }
}
