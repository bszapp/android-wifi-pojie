package io.github.bszapp.wifitoolbox.terminal

import android.content.Context
import android.os.DeadObjectException
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.terminal.ITerminalController
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogBatch
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogState
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogTransport
import io.github.bszapp.wifitoolbox.contract.terminal.TerminalManagerState
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.ITerminalManagerCallback
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

class TerminalController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val reportError: (
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String?,
    ) -> Unit,
) : ITerminalController {
    private val connectionLock = Any()
    private val _state = MutableStateFlow(TerminalManagerState())
    override val state: StateFlow<TerminalManagerState> = _state.asStateFlow()
    private val appTerminalManager = AppTerminalManager(context, scope, reportError)
    override val appState: StateFlow<TerminalManagerState> = appTerminalManager.state
    private var activeBinding: Binding? = null

    fun connect(service: IMainService) {
        lateinit var binding: Binding
        val callback = object : ITerminalManagerCallback.Stub() {
            override fun onAliveTerminalIdsChanged(generation: Long, terminalIds: LongArray) {
                if (!isCurrent(binding)) return
                synchronized(binding.pendingLock) {
                    val currentGeneration = maxOf(
                        binding.processedAliveGeneration,
                        binding.pendingAlive?.generation ?: 0L,
                    )
                    if (generation >= currentGeneration) {
                        binding.pendingAlive = AliveUpdate(generation, terminalIds.sorted())
                    }
                }
                binding.signal.trySend(Unit)
            }

            override fun onTerminalLogRangeChanged(
                terminalId: Long,
                generation: Long,
                oldestAvailableId: Long,
                latestId: Long,
                lineCount: Int,
            ) {
                if (!isCurrent(binding)) return
                val update = RangeUpdate(
                    terminalId = terminalId,
                    generation = generation,
                    oldestAvailableId = oldestAvailableId,
                    latestId = latestId,
                    lineCount = lineCount,
                )
                synchronized(binding.pendingLock) {
                    val processed = binding.processedRangeGenerations[terminalId] ?: -1L
                    val pending = binding.pendingRanges[terminalId]?.generation ?: -1L
                    if (generation >= maxOf(processed, pending)) {
                        binding.pendingRanges[terminalId] = update
                    }
                }
                binding.signal.trySend(Unit)
            }
        }
        binding = Binding(service, callback)

        val previous = synchronized(connectionLock) {
            activeBinding.also { activeBinding = binding }
        }
        release(previous)
        _state.value = TerminalManagerState()

        binding.job = scope.launch(Dispatchers.IO) {
            try {
                synchronized(binding.registrationLock) {
                    if (!isCurrent(binding)) return@launch
                    service.registerTerminalManagerCallback(callback)
                    binding.registered = true
                }
                reconcile(binding)

                while (isActive && isCurrent(binding)) {
                    val signaled = withTimeoutOrNull(RECONCILE_INTERVAL_MILLIS) {
                        binding.signal.receive()
                        true
                    } ?: false
                    if (signaled) {
                        delay(EVENT_COALESCE_MILLIS)
                        drainPending(binding)
                    } else {
                        reconcile(binding)
                    }
                }
            } catch (error: Throwable) {
                if (isActive && isCurrent(binding)) {
                    Log.w(TAG, "同步终端状态失败：${error.message}", error)
                }
            }
        }
    }

    fun disconnect() {
        val previous = synchronized(connectionLock) {
            activeBinding.also { activeBinding = null }
        }
        release(previous)
        _state.value = TerminalManagerState()
    }

    override fun createServiceTerminal() {
        val operation = "新建服务 chroot 终端"
        val binding = synchronized(connectionLock) { activeBinding }
        if (binding == null) {
            reportError(
                "App.TerminalController",
                operation,
                IllegalStateException("service 未连接"),
                null,
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            if (!isCurrent(binding) || !binding.service.asBinder().isBinderAlive) return@launch
            val rootfs = File(requireNotNull(context.filesDir.parentFile), "rootfs")
            val runtime = File(context.noBackupFilesDir, "rftool-runtime")
            val terminal = File(context.applicationInfo.nativeLibraryDir, "libterminal.so")
            runCatching {
                binding.service.createServiceTerminal(
                    rootfs.absolutePath, runtime.absolutePath, terminal.absolutePath,
                )
            }.onFailure { error ->
                if (isCurrent(binding)) {
                    reportError(
                        "App.TerminalController",
                        operation,
                        error,
                        null,
                    )
                }
            }
        }
    }

    override fun createAppTerminal() {
        appTerminalManager.createTerminal()
    }

    override fun clearLogs(terminalId: Long) {
        val binding = synchronized(connectionLock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (!isCurrent(binding) || !binding.service.asBinder().isBinderAlive) return@launch
            runCatching { binding.service.clearTerminalLogs(terminalId) }
                .onFailure { error ->
                    if (isCurrent(binding)) {
                        Log.w(TAG, "清空终端 $terminalId 日志失败：${error.message}", error)
                    }
                }
        }
    }

    override fun clearAppLogs(terminalId: Long) {
        appTerminalManager.clearLogs(terminalId)
    }

    override fun sendInput(terminalId: Long, text: String) {
        val binding = synchronized(connectionLock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (!isCurrent(binding) || !binding.service.asBinder().isBinderAlive) return@launch
            runCatching {
                binding.service.sendTerminalInput(terminalId, text)
            }.onFailure { error ->
                if (isCurrent(binding)) {
                    Log.w(TAG, "发送终端 $terminalId 输入失败：${error.message}", error)
                }
            }
        }
    }

    override fun sendAppInput(terminalId: Long, text: String) {
        appTerminalManager.writeInput(terminalId, text)
    }

    override fun closeTerminal(terminalId: Long) {
        val binding = synchronized(connectionLock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            if (!isCurrent(binding) || !binding.service.asBinder().isBinderAlive) return@launch
            runCatching { binding.service.stopTerminal(terminalId) }
                .onFailure { error ->
                    if (isCurrent(binding)) {
                        Log.w(TAG, "关闭终端 $terminalId 失败：${error.message}", error)
                    }
                }
        }
    }

    override fun closeAppTerminal(terminalId: Long) {
        appTerminalManager.stopTerminal(terminalId)
    }

    fun close() {
        appTerminalManager.close()
    }

    private suspend fun drainPending(binding: Binding) {
        val pending = synchronized(binding.pendingLock) {
            PendingUpdates(
                alive = binding.pendingAlive.also { binding.pendingAlive = null },
                ranges = binding.pendingRanges.values.toList().also {
                    binding.pendingRanges.clear()
                },
            )
        }

        pending.alive?.let { alive ->
            if (alive.generation >= binding.processedAliveGeneration) {
                applyAliveIds(binding, alive.terminalIds, alive.generation)
                binding.processedAliveGeneration = alive.generation
            }
        }
        pending.ranges.sortedBy { it.terminalId }.forEach { range ->
            if (!isCurrent(binding)) return
            val processed = binding.processedRangeGenerations[range.terminalId] ?: -1L
            if (range.generation < processed) return@forEach
            syncTerminalSafely(binding, range)
            binding.processedRangeGenerations[range.terminalId] = range.generation
        }
    }

    private fun reconcile(binding: Binding) {
        if (!isCurrent(binding)) return
        val alive = readStableAliveSnapshot(binding.service)
        applyAliveIds(binding, alive.terminalIds, alive.generation)
        binding.processedAliveGeneration = maxOf(
            binding.processedAliveGeneration,
            alive.generation,
        )
        alive.terminalIds.forEach { terminalId ->
            if (!isCurrent(binding)) return
            runCatching {
                val range = readRange(binding.service, terminalId)
                val stableRange = range
                syncTerminal(binding, stableRange)
                binding.processedRangeGenerations[terminalId] = maxOf(
                    binding.processedRangeGenerations[terminalId] ?: -1L,
                    stableRange.generation,
                )
            }.onFailure { error ->
                if (error !is DeadObjectException && isCurrent(binding)) {
                    Log.d(TAG, "对账时终端 $terminalId 已变化：${error.message}")
                }
            }
        }
    }

    private fun applyAliveIds(
        binding: Binding,
        ids: List<Long>,
        generation: Long?,
    ) {
        if (!isCurrent(binding)) return
        val idSet = ids.toSet()
        updateState(binding) { current ->
            val retained = current.terminals
                .filterKeys(idSet::contains)
                .toMutableMap()
            ids.forEach { terminalId ->
                retained.putIfAbsent(terminalId, TerminalLogState(terminalId))
            }
            current.copy(
                aliveGeneration = generation ?: current.aliveGeneration,
                aliveTerminalIds = ids,
                terminals = retained,
            )
        }
        binding.processedRangeGenerations.keys.retainAll(idSet)
    }

    private fun syncTerminalSafely(binding: Binding, range: RangeUpdate) {
        if (!isCurrent(binding)) return
        runCatching { syncTerminal(binding, range) }
            .onFailure { error ->
                if (error !is DeadObjectException && isCurrent(binding)) {
                    Log.w(TAG, "同步终端 ${range.terminalId} 日志失败：${error.message}", error)
                }
            }
    }

    private fun syncTerminal(binding: Binding, announced: RangeUpdate) {
        if (!isCurrent(binding)) return
        if (announced.terminalId !in _state.value.aliveTerminalIds) return

        if (announced.lineCount == 0 || announced.oldestAvailableId > announced.latestId) {
            updateTerminal(binding, announced, emptyList())
            return
        }

        var target = announced
        var local = ArrayList(_state.value.terminals[announced.terminalId]?.entries.orEmpty()
            .dropWhile { it.id < announced.oldestAvailableId }
            .takeWhile { it.id <= announced.latestId })

        val locallyContinuous = local.zipWithNext().all { (first, second) ->
            second.id == first.id + 1L
        }
        if (!locallyContinuous || (local.isNotEmpty() && local.first().id != announced.oldestAvailableId)) {
            local.clear()
        }

        var fromId = local.lastOrNull()?.id?.plus(1L) ?: target.oldestAvailableId
        var emptyFetches = 0
        while (isCurrent(binding) && fromId <= target.latestId) {
            val toId = min(fromId + FETCH_SIZE - 1L, target.latestId)
            val batch = TerminalLogTransport.decode(
                binding.service.getTerminalLogs(target.terminalId, fromId, toId),
            )
            if (!isCurrent(binding)) return
            target = batch.toRangeUpdate()
            if (local.firstOrNull()?.id?.let { it < target.oldestAvailableId } == true) {
                val dropCount = local.indexOfFirst { it.id >= target.oldestAvailableId }
                if (dropCount < 0) local.clear() else local.subList(0, dropCount).clear()
            }
            while (local.lastOrNull()?.id?.let { it > target.latestId } == true) local.removeAt(local.lastIndex)

            val fetched = batch.entries.filter { it.id >= target.oldestAvailableId }
            if (fetched.isEmpty()) {
                emptyFetches++
                if (emptyFetches >= MAX_EMPTY_FETCH_RETRIES) break
                fromId = maxOf(fromId, target.oldestAvailableId)
                continue
            }
            emptyFetches = 0

            val expectedId = local.lastOrNull()?.id?.plus(1L) ?: target.oldestAvailableId
            if (fetched.first().id != expectedId) {
                local.clear()
                fromId = target.oldestAvailableId
                continue
            }
            local.addAll(fetched)
            fromId = fetched.last().id + 1L
        }

        val complete = local.size == target.lineCount &&
            local.firstOrNull()?.id == target.oldestAvailableId &&
            local.lastOrNull()?.id == target.latestId
        if (!complete) {
            val loaded = reloadAvailableRange(binding, target)
            target = loaded.range
            local = ArrayList(loaded.entries)
        }
        updateTerminal(binding, target, local)
    }

    private fun reloadAvailableRange(
        binding: Binding,
        initialRange: RangeUpdate,
    ): LoadedTerminal {
        var target = initialRange
        val entries = ArrayList<io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogEntry>()
        var fromId = target.oldestAvailableId
        while (
            isCurrent(binding) &&
            fromId <= target.latestId
        ) {
            val toId = min(fromId + FETCH_SIZE - 1L, target.latestId)
            val batch = TerminalLogTransport.decode(
                binding.service.getTerminalLogs(target.terminalId, fromId, toId),
            )
            target = batch.toRangeUpdate()
            if (entries.firstOrNull()?.id?.let { it < target.oldestAvailableId } == true) {
                val dropCount = entries.indexOfFirst { it.id >= target.oldestAvailableId }
                if (dropCount < 0) entries.clear() else entries.subList(0, dropCount).clear()
            }
            val fetched = batch.entries.filter { it.id >= target.oldestAvailableId }
            if (fetched.isEmpty()) break
            val expected = entries.lastOrNull()?.id?.plus(1L) ?: target.oldestAvailableId
            if (fetched.first().id != expected) {
                entries.clear()
                fromId = target.oldestAvailableId
                continue
            }
            entries.addAll(fetched)
            fromId = fetched.last().id + 1L
        }
        return LoadedTerminal(target, entries)
    }

    private fun updateTerminal(
        binding: Binding,
        range: RangeUpdate,
        entries: List<io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogEntry>,
    ) {
        if (!isCurrent(binding)) return
        val inputPrompt = binding.service.getTerminalInputPrompt(range.terminalId)
        if (!isCurrent(binding)) return
        updateState(binding) { current ->
            if (range.terminalId !in current.aliveTerminalIds) return@updateState current
            current.copy(
                terminals = current.terminals + (
                    range.terminalId to TerminalLogState(
                        terminalId = range.terminalId,
                        generation = range.generation,
                        oldestAvailableId = range.oldestAvailableId,
                        latestId = range.latestId,
                        lineCount = range.lineCount,
                        entries = entries,
                        inputPrompt = inputPrompt,
                    )
                ),
            )
        }
    }

    private fun readRange(service: IMainService, terminalId: Long): RangeUpdate {
        val values = service.getTerminalLogRange(terminalId)
        require(values.size == RANGE_VALUE_COUNT) {
            "终端 $terminalId 日志范围字段数非法：${values.size}"
        }
        return RangeUpdate(
            terminalId = terminalId,
            generation = values[0],
            oldestAvailableId = values[1],
            latestId = values[2],
            lineCount = values[3].also { count ->
                require(count in 0L..Int.MAX_VALUE.toLong()) {
                    "终端 $terminalId 日志行数非法：$count"
                }
            }.toInt(),
        )
    }

    private fun readStableAliveSnapshot(service: IMainService): AliveUpdate {
        repeat(MAX_ALIVE_SNAPSHOT_RETRIES) {
            val before = service.getAliveTerminalGeneration()
            val terminalIds = service.getAliveTerminalIds().sorted()
            val after = service.getAliveTerminalGeneration()
            if (before == after) return AliveUpdate(after, terminalIds)
        }
        val generation = service.getAliveTerminalGeneration()
        return AliveUpdate(generation, service.getAliveTerminalIds().sorted())
    }

    private fun TerminalLogBatch.toRangeUpdate() = RangeUpdate(
        terminalId = terminalId,
        generation = generation,
        oldestAvailableId = oldestAvailableId,
        latestId = latestId,
        lineCount = lineCount,
    )

    private inline fun updateState(binding: Binding, transform: (TerminalManagerState) -> TerminalManagerState) {
        synchronized(connectionLock) {
            if (activeBinding !== binding) return
            _state.update(transform)
        }
    }

    private fun isCurrent(binding: Binding): Boolean = synchronized(connectionLock) {
        activeBinding === binding && binding.service.asBinder().isBinderAlive
    }

    private fun release(binding: Binding?) {
        if (binding == null) return
        binding.signal.close()
        binding.job?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(binding.registrationLock) {
                if (binding.registered && binding.service.asBinder().isBinderAlive) {
                    runCatching {
                        binding.service.unregisterTerminalManagerCallback(binding.callback)
                    }
                }
                binding.registered = false
            }
        }
    }

    private class Binding(
        val service: IMainService,
        val callback: ITerminalManagerCallback,
        val signal: Channel<Unit> = Channel(Channel.CONFLATED),
        val pendingLock: Any = Any(),
        val registrationLock: Any = Any(),
        val pendingRanges: MutableMap<Long, RangeUpdate> = mutableMapOf(),
        val processedRangeGenerations: MutableMap<Long, Long> = mutableMapOf(),
        var pendingAlive: AliveUpdate? = null,
        var processedAliveGeneration: Long = -1L,
        @Volatile var registered: Boolean = false,
        @Volatile var job: Job? = null,
    )

    private data class AliveUpdate(
        val generation: Long,
        val terminalIds: List<Long>,
    )

    private data class RangeUpdate(
        val terminalId: Long,
        val generation: Long,
        val oldestAvailableId: Long,
        val latestId: Long,
        val lineCount: Int,
    )

    private data class PendingUpdates(
        val alive: AliveUpdate?,
        val ranges: List<RangeUpdate>,
    )

    private data class LoadedTerminal(
        val range: RangeUpdate,
        val entries: List<io.github.bszapp.wifitoolbox.contract.terminal.TerminalLogEntry>,
    )

    companion object {
        private const val TAG = "TerminalController"
        private const val FETCH_SIZE = 500L
        private const val MAX_EMPTY_FETCH_RETRIES = 2
        private const val MAX_ALIVE_SNAPSHOT_RETRIES = 3
        private const val RANGE_VALUE_COUNT = 4
        private const val EVENT_COALESCE_MILLIS = 50L
        private const val RECONCILE_INTERVAL_MILLIS = 5_000L
    }
}
