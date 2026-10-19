package io.github.bszapp.wifitoolbox.service.task

import android.os.SystemClock

/** 从弱密码检测提取的排队与指数冷却规则，两类执行器共用。 */
internal interface PskSecurityAuditQueueEntry {
    val active: Boolean
    var lastAttemptAt: Long
    var readyAt: Long
}

internal object PskSecurityAuditQueue {
    fun <T : PskSecurityAuditQueueEntry> next(entries: List<T>): T? =
        entries.filter { it.active && it.readyAt <= SystemClock.elapsedRealtime() }.minByOrNull { it.lastAttemptAt }

    fun waitMillis(entries: List<PskSecurityAuditQueueEntry>): Long =
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

/** 全部活跃成员都在冷却时，记录到最早恢复时刻的等待；轮询期间不重复写入。 */
internal class AuditCooldownLog {
    private var loggedReadyAt: Long? = null

    fun update(entries: List<PskSecurityAuditQueueEntry>, allCooling: Boolean, write: (String) -> Unit) {
        val readyAt = if (allCooling && entries.isNotEmpty()) entries.minOf { it.readyAt } else null
        val now = SystemClock.elapsedRealtime()
        if (readyAt == null || readyAt <= now) {
            loggedReadyAt = null
            return
        }
        if (loggedReadyAt != readyAt) {
            write("冷却中(${readyAt - now}ms)")
            loggedReadyAt = readyAt
        }
    }
}

/** 两类检测共用的最近样本统计；瞬态耗时并入上一样本。 */
internal class AuditAttemptStats {
    var elapsedMillis = 0L
        private set
    private val recent = ArrayDeque<Long>()

    val averageAttemptMillis: Long?
        get() = if (recent.size < 5) null else recent.sum() / recent.size

    fun recordConclusive(duration: Long) {
        elapsedMillis += duration
        recent.addLast(duration)
        if (recent.size > 20) recent.removeFirst()
    }

    fun recordTransient(duration: Long) {
        elapsedMillis += duration
        if (recent.isEmpty()) return
        val last = recent.removeLast()
        recent.addLast(last + duration)
    }
}
