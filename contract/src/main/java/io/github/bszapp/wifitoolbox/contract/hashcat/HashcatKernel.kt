package io.github.bszapp.wifitoolbox.contract.hashcat

import org.json.JSONArray
import org.json.JSONObject

enum class HashcatKernelState { NOT_COMPILED, COMPILING, READY, FAILED }

data class HashcatKernelStep(
    val id: String,
    val label: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
) {
    fun toJson() = JSONObject().put("id", id).put("label", label)
        .put("startedAt", startedAt).put("finishedAt", finishedAt)

    companion object {
        fun fromJson(json: JSONObject) = HashcatKernelStep(
            json.getString("id"), json.getString("label"), json.getLong("startedAt"),
            if (json.isNull("finishedAt")) null else json.getLong("finishedAt"),
        )
    }
}

/** 服务负责设备内核缓存及完成记录；App 只镜像，不以用户同意状态代替编译结果。 */
data class HashcatKernelSnapshot(
    val state: HashcatKernelState = HashcatKernelState.NOT_COMPILED,
    val stage: String = "内核暂未编译",
    val steps: List<HashcatKernelStep> = emptyList(),
    val error: String? = null,
    val revision: Long = 0,
) {
    fun toJson() = JSONObject().put("state", state.name).put("stage", stage)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } }).put("error", error).put("revision", revision)

    companion object {
        fun fromJson(json: JSONObject) = HashcatKernelSnapshot(
            HashcatKernelState.valueOf(json.getString("state")), json.getString("stage"),
            json.getJSONArray("steps").let { array -> List(array.length()) { HashcatKernelStep.fromJson(array.getJSONObject(it)) } },
            if (json.isNull("error")) null else json.getString("error"),
            json.optLong("revision"),
        )
    }
}
