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

/** availableBytes 来自内核 MemAvailable（包含可回收内存），最大内存不等于实际分配。 */
data class HashcatMemorySnapshot(
    val capturedAt: Long,
    val totalBytes: Long,
    val availableBytes: Long,
    val tasks: List<HashcatTaskMemory>,
    val error: String? = null,
) {
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
