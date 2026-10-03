package io.github.bszapp.wifitoolbox.service.hashcat

import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatMemorySnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskMemory
import java.io.File

/** 只读取内核统计；没有权限/字段缺失时保留错误，禁止以预算或零代替存活进程的 PSS。 */
internal object HashcatMemoryReader {
    data class Task(val id: String, val pid: Int?, val budgetMiB: Int?, val processRunning: Boolean)

    fun read(tasks: List<Task>): HashcatMemorySnapshot {
        val capturedAt = System.currentTimeMillis()
        return try {
            val values = File("/proc/meminfo").useLines { lines ->
                lines.filter { it.startsWith("MemTotal:") || it.startsWith("MemAvailable:") }
                    .associate { line -> line.substringBefore(':') to parseKiB(line) }
            }
            val total = requireNotNull(values["MemTotal"]) { "内核未提供 MemTotal" }
            val available = requireNotNull(values["MemAvailable"]) { "内核未提供 MemAvailable" }
            require(total > 0 && available in 0..total) { "系统内存采样无效" }
            HashcatMemorySnapshot(capturedAt, total, available, tasks.map { task ->
                if (!task.processRunning) HashcatTaskMemory(task.id, 0, task.budgetMiB)
                else try {
                    val pid = requireNotNull(task.pid) { "无法获取 Hashcat 进程 PID" }
                    HashcatTaskMemory(task.id, pss(pid), task.budgetMiB)
                } catch (error: Throwable) {
                    HashcatTaskMemory(task.id, null, task.budgetMiB, error.message ?: "无法读取进程内存")
                }
            })
        } catch (error: Throwable) {
            HashcatMemorySnapshot(capturedAt, 0, 0, emptyList(), error.message ?: "无法读取系统内存")
        }
    }

    private fun pss(pid: Int): Long {
        val rollup = File("/proc/$pid/smaps_rollup")
        val maps = if (rollup.exists()) rollup else File("/proc/$pid/smaps")
        var found = false
        val bytes = maps.useLines { lines -> lines.filter { it.startsWith("Pss:") }.sumOf { found = true; parseKiB(it) } }
        check(found) { "进程 PSS 不可读取" }
        return bytes
    }

    private fun parseKiB(line: String): Long {
        val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
        require(fields.size >= 2 && fields[1] == "kB") { "内核内存单位无效" }
        return Math.multiplyExact(fields[0].toLong(), 1024)
    }
}
