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
        var oldestAvailableId = announcedRange.oldestAvailableId
        var targetId = announcedRange.latestId
        applyVisibleRange(binding, state, oldestAvailableId, targetId)

        while (isCurrent(binding) && oldestAvailableId <= targetId) {
            var localEntries = state.mutableEntries.value
            val fromId = maxOf(
                oldestAvailableId,
                localEntries.lastOrNull()?.id?.plus(1L) ?: oldestAvailableId,
            )
            if (fromId > targetId) return

            val toId = min(fromId + FETCH_SIZE - 1L, targetId)
            val descriptor = when (source) {
                LogSource.Service -> binding.service.getServiceLogs(fromId, toId)
                LogSource.SystemWifi -> binding.service.getSystemWifiLogs(fromId, toId)
            }
            val batch = ServiceLogTransport.decode(descriptor)
            if (!isCurrent(binding)) return

            oldestAvailableId = maxOf(oldestAvailableId, batch.oldestAvailableId)
            targetId = maxOf(targetId, batch.latestId)
            applyVisibleRange(binding, state, oldestAvailableId, targetId)
            if (oldestAvailableId > targetId) return

            localEntries = state.mutableEntries.value
            val fetched = batch.entries.filter { it.id >= oldestAvailableId }
            if (fetched.isEmpty()) {
                if (fromId < oldestAvailableId) continue
                return
            }

            state.mutableEntries.value = if (
                localEntries.isEmpty() ||
                fetched.first().id == localEntries.last().id + 1L
            ) {
                localEntries + fetched
            } else {
                fetched
            }
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
        if (latestId > state.mutableLatestId.value) state.mutableLatestId.value = latestId
    }

    private fun clearLocalEntries() {
        sourceStates.values.forEach { state ->
            state.mutableEntries.value = emptyList()
            state.mutableLatestId.value = 0L
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
        val mutableLatestId = MutableStateFlow(0L)
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
