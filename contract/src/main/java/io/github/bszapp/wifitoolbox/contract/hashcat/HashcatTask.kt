package io.github.bszapp.wifitoolbox.contract.hashcat

import java.io.File
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * WPA 字典安全评估独立于单任务的 Wi-Fi 控制任务管理器，可以并发运行。
 * 服务是运行状态的来源，App 保管输入及暂停后的恢复数据和长期历史。
 * PAUSED 表示进程已到恢复点退出且备份已由 App 确认落盘；不保存 GPU 内存状态。
 * App 退出不停止服务任务。正常退出服务前必须完成所有任务的暂停和备份。
 */
enum class HashcatTaskState { RUNNING, PAUSING, PAUSED, SUCCEEDED, EXHAUSTED, FAILED }

data class HashcatFinding(val password: String, val dictionaryPosition: Long)

/** 启动元数据尺寸固定；字典、握手及恢复文件均由 FD 提供。 */
@Parcelize
data class HashcatLaunch(val id: String, val createdAt: Long, val revision: Long, val memoryLimitMiB: Int? = null) : Parcelable

data class HashcatTaskSnapshot(
    val id: String,
    val createdAt: Long,
    val revision: Long = 0,
    val state: HashcatTaskState = HashcatTaskState.RUNNING,
    val step: String = "准备运行",
    val completed: Long = 0,
    val total: Long = 0,
    val progressUnit: String = "",
    val stepCompleted: Long = 0,
    val stepTotal: Long = 0,
    val stepUnit: String = "",
    val remainingSeconds: Long? = null,
    val speed: Long = 0,
    val devices: String = "",
    val candidates: String = "",
    val dictionaryNames: List<String> = emptyList(),
    val findings: List<HashcatFinding> = emptyList(),
    val error: String? = null,
    /** 每个计算设备的预算上限，非进程实际占用；null 保留上游自动调优。 */
    val memoryLimitMiB: Int? = null,
    val computeDurationMillis: Long = 0,
    val averageSpeed: Long = 0,
) {
    val active get() = state == HashcatTaskState.RUNNING || state == HashcatTaskState.PAUSING

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("createdAt", createdAt); put("revision", revision)
        put("state", state.name); put("step", step); put("completed", completed); put("total", total)
        put("progressUnit", progressUnit); put("remainingSeconds", remainingSeconds)
        put("stepCompleted", stepCompleted); put("stepTotal", stepTotal); put("stepUnit", stepUnit)
        put("speed", speed); put("devices", devices); put("candidates", candidates)
        put("dictionaryNames", JSONArray(dictionaryNames))
        put("findings", JSONArray().apply {
            findings.forEach { put(JSONObject().put("password", it.password).put("position", it.dictionaryPosition)) }
        })
        put("error", error)
        put("memoryLimitMiB", memoryLimitMiB)
        put("computeDurationMillis", computeDurationMillis); put("averageSpeed", averageSpeed)
    }

    companion object {
        fun fromJson(json: JSONObject) = HashcatTaskSnapshot(
            id = json.getString("id"), createdAt = json.getLong("createdAt"),
            revision = json.getLong("revision"), state = HashcatTaskState.valueOf(json.getString("state")),
            step = json.getString("step"), completed = json.getLong("completed"), total = json.getLong("total"),
            progressUnit = json.optString("progressUnit"),
            stepCompleted = json.optLong("stepCompleted"), stepTotal = json.optLong("stepTotal"), stepUnit = json.optString("stepUnit"),
            remainingSeconds = if (json.isNull("remainingSeconds")) null else json.getLong("remainingSeconds"),
            speed = json.optLong("speed"), devices = json.optString("devices"), candidates = json.optString("candidates"),
            dictionaryNames = json.getJSONArray("dictionaryNames").let { array -> List(array.length()) { array.getString(it) } },
            findings = json.getJSONArray("findings").let { array -> List(array.length()) {
                array.getJSONObject(it).let { item -> HashcatFinding(item.getString("password"), item.getLong("position")) }
            } },
            error = if (json.isNull("error")) null else json.getString("error"),
            memoryLimitMiB = if (json.isNull("memoryLimitMiB")) null else json.getInt("memoryLimitMiB"),
            computeDurationMillis = json.optLong("computeDurationMillis"), averageSpeed = json.optLong("averageSpeed"),
        )
    }
}

interface IHashcatController {
    val history: StateFlow<List<HashcatTaskSnapshot>>
    val connected: StateFlow<Boolean>
    val memory: StateFlow<HashcatMemorySnapshot?>
    val kernels: StateFlow<HashcatKernelSnapshot?>
    suspend fun refreshKernels()
    suspend fun compileKernels()
    suspend fun start(handshake: String, dictionary: File, dictionaryNames: List<String>, memoryLimitMiB: Int): String
    suspend fun pause(taskId: String)
    suspend fun resume(taskId: String)
    suspend fun setMemoryLimit(taskId: String, memoryLimitMiB: Int)
}
