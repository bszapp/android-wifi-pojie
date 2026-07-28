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

    private val _entries = MutableStateFlow<List<ServiceLogEntry>>(emptyList())
    override val entries: StateFlow<List<ServiceLogEntry>> = _entries.asStateFlow()

    private val _latestId = MutableStateFlow(0L)
    override val latestId: StateFlow<Long> = _latestId.asStateFlow()

    private val _rawViewEnabled = MutableStateFlow(false)
    override val rawViewEnabled: StateFlow<Boolean> = _rawViewEnabled.asStateFlow()

    private var activeBinding: Binding? = null

    fun connect(service: IMainService) {
        val updates = Channel<ServiceLogRange>(Channel.CONFLATED)
        lateinit var binding: Binding
        val callback = object : IServiceLogCallback.Stub() {
            override fun onServiceLogRangeChanged(
                oldestAvailableId: Long,
                latestId: Long,
            ) {
                if (isCurrent(binding)) {
                    updates.trySend(ServiceLogRange(oldestAvailableId, latestId))
                }
            }
        }
        binding = Binding(
            service = service,
            callback = callback,
            updates = updates,
        )

        val previous = synchronized(lock) {
            activeBinding.also { activeBinding = binding }
        }
        release(previous)
        _entries.value = emptyList()
        _latestId.value = 0L

        binding.job = scope.launch(Dispatchers.IO) {
            try {
                synchronized(binding.registrationLock) {
                    if (!isCurrent(binding)) return@launch
                    service.registerServiceLogCallback(callback)
                    binding.registered = true
                }

                for (range in updates) {
                    if (!isCurrent(binding)) break
                    syncTo(binding, range)
                }
            } catch (error: Throwable) {
                if (isActive && isCurrent(binding)) {
                    Log.w(TAG, "同步 Service 日志失败：${error.message}", error)
                }
            }
        }
    }

    fun disconnect() {
        val previous = synchronized(lock) {
            activeBinding.also { activeBinding = null }
        }
        release(previous)
        _entries.value = emptyList()
        _latestId.value = 0L
    }

    override fun clear() {
        val binding = synchronized(lock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (isCurrent(binding) && binding.service.asBinder().isBinderAlive) {
                runCatching { binding.service.clearServiceLogs() }
                    .onFailure { error ->
                        if (isCurrent(binding)) {
                            Log.w(TAG, "请求清空 Service 日志失败：${error.message}", error)
                        }
                    }
            }
        }
    }

    override fun setRawViewEnabled(enabled: Boolean) {
        _rawViewEnabled.value = enabled
    }

    private suspend fun syncTo(binding: Binding, announcedRange: ServiceLogRange) {
        var oldestAvailableId = announcedRange.oldestAvailableId
        var targetId = announcedRange.latestId
        applyVisibleRange(binding, oldestAvailableId, targetId)

        while (isCurrent(binding) && oldestAvailableId <= targetId) {
            var localEntries = _entries.value
            val fromId = maxOf(
                oldestAvailableId,
                localEntries.lastOrNull()?.id?.plus(1L)
                    ?: maxOf(oldestAvailableId, targetId - MAX_APP_ENTRIES + 1L),
            )
            if (fromId > targetId) return

            val toId = min(fromId + FETCH_SIZE - 1L, targetId)
            val descriptor = binding.service.getServiceLogs(fromId, toId)
            val batch = ServiceLogTransport.decode(descriptor)
            if (!isCurrent(binding)) return

            oldestAvailableId = maxOf(oldestAvailableId, batch.oldestAvailableId)
            targetId = maxOf(targetId, batch.latestId)
            applyVisibleRange(binding, oldestAvailableId, targetId)
            if (oldestAvailableId > targetId) return

            localEntries = _entries.value

            val fetched = batch.entries.filter { it.id >= oldestAvailableId }
            if (fetched.isEmpty()) {
                if (fromId < oldestAvailableId) continue
                return
            }

            val merged = if (
                localEntries.isEmpty() ||
                fetched.first().id == localEntries.last().id + 1L
            ) {
                localEntries + fetched
            } else {
                fetched
            }
            _entries.value = merged.takeLast(MAX_APP_ENTRIES)
        }
    }

    private fun applyVisibleRange(
        binding: Binding,
        oldestAvailableId: Long,
        latestId: Long,
    ) {
        if (!isCurrent(binding)) return
        _entries.value = if (oldestAvailableId > latestId) {
            emptyList()
        } else {
            _entries.value.dropWhile { it.id < oldestAvailableId }
        }
        if (latestId > _latestId.value) _latestId.value = latestId
    }

    private fun isCurrent(binding: Binding): Boolean =
        synchronized(lock) { activeBinding === binding }

    private fun release(binding: Binding?) {
        if (binding == null) return
        binding.updates.close()
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

    private class Binding(
        val service: IMainService,
        val callback: IServiceLogCallback,
        val updates: Channel<ServiceLogRange>,
        val registrationLock: Any = Any(),
        @Volatile var registered: Boolean = false,
        @Volatile var job: Job? = null,
    )

    private data class ServiceLogRange(
        val oldestAvailableId: Long,
        val latestId: Long,
    )

    companion object {
        private const val TAG = "ServiceLogController"
        private const val FETCH_SIZE = 500L
        private const val MAX_APP_ENTRIES = 4_000
    }
}
