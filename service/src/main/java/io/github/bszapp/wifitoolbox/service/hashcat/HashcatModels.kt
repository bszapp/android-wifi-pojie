package io.github.bszapp.wifitoolbox.service.hashcat

import java.io.File

/** 服务调用方提供文件，控制器直接使用同一 libhashcat.so 的独立执行入口。 */
data class HashcatRequest(
    val executable: File,
    val handshakeFile: File,
    val dictionaryFile: File,
    /** 单位 MiB，限制上游设备内存预算；null 不指定预算，始终保留自动调优。 */
    val deviceMemoryLimitMiB: Int? = null,
    /** null 使用原生默认查找，可显式指定设备已经提供的 OpenCL 驱动。 */
    val openClLibrary: File? = null,
    /** 持久任务使用固定 UUID 运行目录；null 保持诊断调用的临时会话行为。 */
    val runtimeDirectory: File? = null,
    val restore: Boolean = false,
    val precompile: Boolean = false,
)

enum class HashcatPhase {
    LOADING_BACKEND, LOADING_DEVICES, LOADING_BRIDGES, READING_HANDSHAKES,
    BUILDING_KERNELS, BUILDING_DICTIONARY_INDEX, DICTIONARY_INDEX_READY,
    SELF_TEST, AUTOTUNE, RUNNING,
}

enum class HashcatStep {
    COPYING_PROGRAM, COPYING_HANDSHAKE, COPYING_DICTIONARY,
    CLEANING_DICTIONARY, CLEANING_PROGRAM, CLEANING_RUNTIME,
}

enum class HashcatProgressUnit { BYTES, FILES }

/** 数值直接对应当前固定上游提交的 status_rc_t，未知数值保留在快照中。 */
enum class HashcatStatus(val code: Int) {
    INITIALIZING(0), AUTOTUNE(1), SELF_TEST(2), RUNNING(3), PAUSED(4),
    EXHAUSTED(5), CRACKED(6), ABORTED(7), QUIT(8), BYPASS(9),
    ABORTED_CHECKPOINT(10), ABORTED_RUNTIME(11), ERROR(13), ABORTED_FINISH(14),
    AUTODETECT(16);

    companion object {
        fun fromCode(code: Int): HashcatStatus? = entries.firstOrNull { it.code == code }
    }
}

data class HashcatDeviceStatus(
    val id: Int,
    val name: String,
    val type: String,
    val hashesPerSecond: Long,
    /** 上游不支持传感器时输出 -1，这里转为 null。 */
    val temperatureCelsius: Int?,
    val utilizationPercent: Int?,
    /** 上游当前批次的首尾候选文本，GPU 同时尝试该批次中的多个密码。 */
    val candidateRange: String? = null,
    /** 已完成的当前批次 PBKDF2 迭代，和完整校验过的候选数分别统计。 */
    val pbkdf2Completed: Long = 0,
    val pbkdf2Total: Long = 0,
)

data class HashcatStatusSnapshot(
    val session: String,
    val statusCode: Int,
    val progressCompleted: Long,
    val progressTotal: Long,
    val recoveredHashes: Int,
    val totalHashes: Int,
    val rejectedCandidates: Long,
    val restorePoint: Long,
    val startedAtEpochSeconds: Long?,
    val estimatedStopEpochSeconds: Long?,
    val remainingSeconds: Long?,
    val dictionary: String?,
    val dictionaryPercent: Double?,
    val devices: List<HashcatDeviceStatus>,
    val runningMillis: Long = 0,
) {
    val status: HashcatStatus? get() = HashcatStatus.fromCode(statusCode)
    val progressPercent: Double? get() = progressTotal.takeIf { it > 0 }
        ?.let { progressCompleted.toDouble() * 100.0 / it }
}

sealed interface HashcatEvent {
    data class KernelStep(val device: Int, val kind: String, val name: String, val finished: Boolean) : HashcatEvent
    data object KernelReady : HashcatEvent
    data class StepProgress(
        val step: HashcatStep,
        val completed: Long,
        val total: Long,
        val unit: HashcatProgressUnit,
        val finished: Boolean,
        val path: String,
    ) : HashcatEvent
    /** 原始 UTF-8 输出块，不在这里存储日志，日志的生命周期由调用者管理。 */
    data class Output(val text: String) : HashcatEvent
    data class Phase(
        val phase: HashcatPhase,
        val detail: String,
        val percent: Double? = null,
    ) : HashcatEvent
    data class Status(val snapshot: HashcatStatusSnapshot) : HashcatEvent
    data class ParseError(val text: String, val message: String) : HashcatEvent
}

data class HashcatResult(
    /** Hashcat 原始退出码：0 找到，1 字典耗尽，其他值由调用者处理。 */
    val exitCode: Int,
    val passwords: List<String>,
    val lastStatus: HashcatStatusSnapshot?,
    val stopRequested: Boolean,
    val dictionaryPositions: Map<String, Long> = emptyMap(),
)
