package io.github.bszapp.wifitoolbox.logs

import android.util.Log
import io.github.bszapp.wifitoolbox.contract.log.IServiceLogController
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogEntry
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogTransport
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.IServiceLogCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

class ServiceLogController(
    private val scope: CoroutineScope,
) : IServiceLogController {
    private val lock = Any()
    private val sourceStates = LogSource.entries.associateWith { LogSourceState() }

    override val entries: StateFlow<List<ServiceLogEntry>> =
        state(LogSource.Service).entries
    override val latestId: StateFlow<Long> =
        state(LogSource.Service).latestId
    override val rawViewEnabled: StateFlow<Boolean> =
        state(LogSource.Service).rawViewEnabled
    override val systemWifiEntries: StateFlow<List<ServiceLogEntry>> =
        state(LogSource.SystemWifi).entries
    override val systemWifiLatestId: StateFlow<Long> =
        state(LogSource.SystemWifi).latestId
    override val systemWifiRawViewEnabled: StateFlow<Boolean> =
        state(LogSource.SystemWifi).rawViewEnabled

    private var activeBinding: Binding? = null

    fun connect(service: IMainService) {
        lateinit var binding: Binding
        val callback = object : IServiceLogCallback.Stub() {
            override fun onServiceLogRangeChanged(
                oldestAvailableId: Long,
                latestId: Long,
            ) {
                submitRange(binding, LogSource.Service, oldestAvailableId, latestId)
            }

            override fun onSystemWifiLogRangeChanged(
                oldestAvailableId: Long,
                latestId: Long,
            ) {
                submitRange(binding, LogSource.SystemWifi, oldestAvailableId, latestId)
            }
        }
        binding = Binding(
            service = service,
            callback = callback,
        )

        val previous = synchronized(lock) {
            activeBinding.also { activeBinding = binding }
        }
        release(previous)
        clearLocalEntries()

        binding.job = scope.launch(Dispatchers.IO) {
            try {
                synchronized(binding.registrationLock) {
                    if (!isCurrent(binding)) return@launch
                    service.registerServiceLogCallback(callback)
                    binding.registered = true
                }

                coroutineScope {
                    LogSource.entries.forEach { source ->
                        launch {
                            val initial = when (source) {
                                LogSource.Service -> service.getServiceLogRange()
                                LogSource.SystemWifi -> service.getSystemWifiLogRange()
                            }
                            require(initial.size == 2)
                            syncTo(binding, source, LogRange(initial[0], initial[1]))
                            for (range in binding.updates.getValue(source)) {
                                if (!isCurrent(binding)) break
                                syncTo(binding, source, range)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                if (isActive && isCurrent(binding)) {
                    Log.w(TAG, "同步日志失败：${error.message}", error)
                }
            }
        }
    }

    fun disconnect() {
        val previous = synchronized(lock) {
            activeBinding.also { activeBinding = null }
        }
        release(previous)
        clearLocalEntries()
    }

    override fun clear() = clear(LogSource.Service)

    override fun clearSystemWifi() = clear(LogSource.SystemWifi)

    override fun setRawViewEnabled(enabled: Boolean) {
        state(LogSource.Service).mutableRawViewEnabled.value = enabled
    }

    override fun setSystemWifiRawViewEnabled(enabled: Boolean) {
        state(LogSource.SystemWifi).mutableRawViewEnabled.value = enabled
    }

    private fun clear(source: LogSource) {
        val binding = synchronized(lock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (isCurrent(binding) && binding.service.asBinder().isBinderAlive) {
                runCatching {
                    when (source) {
                        LogSource.Service -> binding.service.clearServiceLogs()
                        LogSource.SystemWifi -> binding.service.clearSystemWifiLogs()
                    }
                }.onFailure { error ->
                    if (isCurrent(binding)) {
                        Log.w(TAG, "请求清空${source.displayName}失败：${error.message}", error)
                    }
                }
            }
        }
    }

    private fun submitRange(
        binding: Binding,
        source: LogSource,
        oldestAvailableId: Long,
        latestId: Long,
    ) {
        if (isCurrent(binding)) {
            binding.updates.getValue(source).trySend(
                LogRange(oldestAvailableId, latestId),
            )
        }
    }

    private suspend fun syncTo(
        binding: Binding,
        source: LogSource,
        announcedRange: LogRange,
    ) {
        val state = state(source)
        var oldest = announcedRange.oldestAvailableId
        var latest = announcedRange.latestId
        val existing = state.mutableEntries.value
        val local = ArrayList(existing.filter { it.id in oldest..latest })
        if (local.isNotEmpty() && local.first().id != oldest) local.clear()
        var from = local.lastOrNull()?.id?.plus(1L) ?: oldest
        while (isCurrent(binding) && from <= latest) {
            val to = min(from + FETCH_SIZE - 1L, latest)
            val fd = when (source) {
                LogSource.Service -> binding.service.getServiceLogs(from, to)
                LogSource.SystemWifi -> binding.service.getSystemWifiLogs(from, to)
            }
            val batch = ServiceLogTransport.decode(fd)
            if (!isCurrent(binding)) return
            if (batch.oldestAvailableId > oldest) {
                oldest = batch.oldestAvailableId
                local.clear()
                from = oldest
                latest = batch.latestId
                continue
            }
            if (batch.entries.isEmpty()) break
            require(batch.entries.first().id == from) { "日志范围返回不连续" }
            local.addAll(batch.entries)
            from = batch.entries.last().id + 1L
        }
        synchronized(lock) {
            if (activeBinding !== binding) return
            state.mutableEntries.value = local.toList()
            state.mutableLatestId.value = latest
        }
    }

    private fun applyVisibleRange(
        binding: Binding,
        state: LogSourceState,
        oldestAvailableId: Long,
        latestId: Long,
    ) {
        if (!isCurrent(binding)) return
        state.mutableEntries.value = if (oldestAvailableId > latestId) {
            emptyList()
        } else {
            state.mutableEntries.value.dropWhile { it.id < oldestAvailableId }
        }
        state.mutableLatestId.value = latestId
    }

    private fun clearLocalEntries() {
        sourceStates.values.forEach { state ->
            state.mutableEntries.value = emptyList()
            state.mutableLatestId.value = -1L
            state.mutableRawViewEnabled.value = false
        }
    }

    private fun state(source: LogSource): LogSourceState = sourceStates.getValue(source)

    private fun isCurrent(binding: Binding): Boolean =
        synchronized(lock) { activeBinding === binding }

    private fun release(binding: Binding?) {
        if (binding == null) return
        binding.updates.values.forEach { it.close() }
        binding.job?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(binding.registrationLock) {
                if (binding.registered && binding.service.asBinder().isBinderAlive) {
                    runCatching {
                        binding.service.unregisterServiceLogCallback(binding.callback)
                    }
                }
                binding.registered = false
            }
        }
    }

    private class LogSourceState {
        val mutableEntries = MutableStateFlow<List<ServiceLogEntry>>(emptyList())
        val entries = mutableEntries.asStateFlow()
        val mutableLatestId = MutableStateFlow(-1L)
        val latestId = mutableLatestId.asStateFlow()
        val mutableRawViewEnabled = MutableStateFlow(false)
        val rawViewEnabled = mutableRawViewEnabled.asStateFlow()
    }

    private class Binding(
        val service: IMainService,
        val callback: IServiceLogCallback,
        val updates: Map<LogSource, Channel<LogRange>> =
            LogSource.entries.associateWith { Channel(Channel.CONFLATED) },
        val registrationLock: Any = Any(),
        @Volatile var registered: Boolean = false,
        @Volatile var job: Job? = null,
    )

    private data class LogRange(
        val oldestAvailableId: Long,
        val latestId: Long,
    )

    private enum class LogSource(val displayName: String) {
        Service("服务日志"),
        SystemWifi("系统wifi日志"),
    }

    companion object {
        private const val TAG = "ServiceLogController"
        private const val FETCH_SIZE = 500L
    }
}
