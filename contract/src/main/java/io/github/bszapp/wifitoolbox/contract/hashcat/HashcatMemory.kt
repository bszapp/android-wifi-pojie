package io.github.bszapp.wifitoolbox.contract.hashcat

import org.json.JSONArray
import org.json.JSONObject

const val HASHCAT_MIB: Long = 1024L * 1024

/** PSS 为内核实测的进程分摊驻留内存，不包含驱动未映射到进程的独立 GPU 分配。 */
data class HashcatTaskMemory(
    val taskId: String,
    val pssBytes: Long?,
    val budgetMiB: Int?,
    val error: String? = null,
)

/** availableBytes 来自内核 MemAvailable（包含可回收内存），预算不等于实际分配。 */
data class HashcatMemorySnapshot(
    val capturedAt: Long,
    val totalBytes: Long,
    val availableBytes: Long,
    val tasks: List<HashcatTaskMemory>,
    val error: String? = null,
) {
    /** 其他任务已承诺但尚未体现在 PSS 中的预算仍需预留；自动预算的旧任务没有固定承诺值。 */
    fun assignableBytes(taskId: String? = null): Long {
        if (error != null || tasks.any { it.pssBytes == null }) return 0
        val current = tasks.firstOrNull { it.taskId == taskId }?.pssBytes ?: 0
        val reserved = tasks.filter { it.taskId != taskId }.sumOf {
            ((it.budgetMiB?.toLong() ?: 0) * HASHCAT_MIB - (it.pssBytes ?: 0)).coerceAtLeast(0)
        }
        return (availableBytes + current - reserved).coerceIn(0, totalBytes)
    }

    fun toJson() = JSONObject().apply {
        put("capturedAt", capturedAt); put("totalBytes", totalBytes); put("availableBytes", availableBytes)
        put("error", error)
        put("tasks", JSONArray().apply { tasks.forEach {
            put(JSONObject().put("taskId", it.taskId).put("pssBytes", it.pssBytes)
                .put("budgetMiB", it.budgetMiB).put("error", it.error))
        } })
    }

    companion object {
        fun fromJson(json: JSONObject) = HashcatMemorySnapshot(
            json.getLong("capturedAt"), json.getLong("totalBytes"), json.getLong("availableBytes"),
            json.getJSONArray("tasks").let { array -> List(array.length()) { index ->
                array.getJSONObject(index).let {
                    HashcatTaskMemory(it.getString("taskId"), if (it.isNull("pssBytes")) null else it.getLong("pssBytes"),
                        if (it.isNull("budgetMiB")) null else it.getInt("budgetMiB"),
                        if (it.isNull("error")) null else it.getString("error"))
                }
            } }, if (json.isNull("error")) null else json.getString("error"),
        )
    }
}
