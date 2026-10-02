package io.github.bszapp.wifitoolbox.logs

import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.log.ILogController
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogEntry
import io.github.bszapp.wifitoolbox.service.LogcatRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/** App 进程拥有的日志来源；采集和解析复用服务的记录器，不绑定服务连接。 */
class AppLogController(private val scope: CoroutineScope) : ILogController {
    private val lock = Any()
    private val recorder = LogcatRecorder(
        recorderName = "AppLogRecorder",
        threadName = "toolbox-app-logcat",
        startMessage = "开始采集应用日志，pid=${Process.myPid()}",
        boundaryTag = "AppLogBoundary",
        processId = Process.myPid(),
        redirectStandardStreams = true,
    )
    private val updates = Channel<LogRange>(Channel.CONFLATED)
    private var syncJob: Job? = null
    private val mutableEntries = MutableStateFlow<List<ServiceLogEntry>>(emptyList())
    override val entries: StateFlow<List<ServiceLogEntry>> = mutableEntries.asStateFlow()
    private val mutableLatestId = MutableStateFlow(-1L)
    override val latestId: StateFlow<Long> = mutableLatestId.asStateFlow()
    private val mutableRawViewEnabled = MutableStateFlow(false)
    override val rawViewEnabled: StateFlow<Boolean> = mutableRawViewEnabled.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (syncJob != null) return
            syncJob = scope.launch(Dispatchers.IO) {
                for (range in updates) {
                    try {
                        syncTo(range)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        Log.w(TAG, "同步应用日志失败：${error.message}", error)
                    }
                }
            }
            recorder.setOnVisibleRangeChanged { oldestAvailableId, latestId ->
                updates.trySend(LogRange(oldestAvailableId, latestId))
            }
            recorder.start()
        }
    }

    override fun clear() {
        synchronized(lock) {
            recorder.clear()
            mutableEntries.value = emptyList()
            mutableLatestId.value = recorder.latestId()
        }
    }

    override fun setRawViewEnabled(enabled: Boolean) {
        mutableRawViewEnabled.value = enabled
    }

    private suspend fun syncTo(announcedRange: LogRange) {
        var oldest = announcedRange.oldestAvailableId
        var latest = announcedRange.latestId
        val existing = mutableEntries.value
        val local = ArrayList(existing.filter { it.id in oldest..latest })
        if (local.isNotEmpty() && local.first().id != oldest) local.clear()
        var from = local.lastOrNull()?.id?.plus(1L) ?: oldest
        while (currentCoroutineContext().isActive && from <= latest) {
            val to = min(from + FETCH_SIZE - 1L, latest)
            val batch = recorder.getRange(from, to)
            if (batch.oldestAvailableId > oldest) {
                oldest = batch.oldestAvailableId
                latest = batch.latestId
                local.clear()
                from = oldest
                continue
            }
            if (batch.entries.isEmpty()) break
            require(batch.entries.first().id == from) { "应用日志范围返回不连续" }
            local.addAll(batch.entries)
            from = batch.entries.last().id + 1L
        }
        synchronized(lock) {
            // 清空可能发生在分页读取期间；按记录器当前范围舍弃已清空的数据。
            val currentRange = recorder.visibleRange()
            mutableEntries.value = local.filter { it.id in currentRange.first..currentRange.second }
            mutableLatestId.value = currentRange.second
        }
    }

    fun close() {
        recorder.stop()
        updates.close()
        syncJob?.cancel()
    }

    private data class LogRange(val oldestAvailableId: Long, val latestId: Long)

    private companion object {
        const val TAG = "AppLogController"
        const val FETCH_SIZE = 500L
    }
}
