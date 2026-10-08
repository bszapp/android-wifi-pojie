package io.github.bszapp.wifitoolbox.wifilist

import io.github.bszapp.wifitoolbox.contract.PagedDataTransport
import io.github.bszapp.wifitoolbox.contract.wifilist.*
import io.github.bszapp.wifitoolbox.service.IMainService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** 仅 App 内部拥有缺失范围、待更新 ID 和重试进度；页面只读取本地数据。 */
internal class MonitorCommunicationSync(
    private val scope: CoroutineScope,
    private val index: MonitorCommunicationIndex,
    private val details: MonitorCommunicationDetailCache,
    private val onUpdated: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private class Binding(val generation: Long, val service: IMainService, val valid: () -> Boolean) {
        val records = Channel<Unit>(Channel.CONFLATED)
        val search = Channel<Unit>(Channel.CONFLATED)
        val details = Channel<Unit>(Channel.CONFLATED)
        val jobs = mutableListOf<Job>()
    }
    @Volatile private var binding: Binding? = null
    private fun current(value: Binding) = binding === value && value.valid()

    fun connect(generation: Long, service: IMainService, valid: () -> Boolean) {
        disconnect()
        val value = Binding(generation, service, valid)
        binding = value
        value.jobs += scope.launch(Dispatchers.IO) {
            index.reset(generation, -1L)
            if (!current(value)) return@launch
            try {
                val range = PagedDataTransport.decode(service.getMonitorCommunicationRange(), MonitorCommunicationRange::class.java)
                announce(generation, range, longArrayOf())
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { if (current(value)) onError(error) }
        }
        value.jobs += scope.launch(Dispatchers.IO) {
            for (signal in value.records) {
                try {
                    while (current(value) && isActive) {
                        val request = index.request()?.takeIf { it.generation == generation } ?: break
                        val page = PagedDataTransport.decode(service.getMonitorCommunicationRecords(request.session,
                            request.ranges(), request.keyword), MonitorCommunicationPage::class.java)
                        if (!current(value)) break
                        if (page.range.sessionGeneration != request.session) {
                            announce(generation, page.range, longArrayOf()); break
                        }
                        if (index.apply(request, page)) {
                            details.invalidate(page.records.mapTo(hashSetOf()) { it.id })
                            onUpdated()
                            value.details.trySend(Unit)
                        }
                        yield()
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    if (current(value)) { onError(error); delay(500); value.records.trySend(Unit) }
                }
            }
        }
        value.jobs += scope.launch(Dispatchers.IO) {
            for (signal in value.search) {
                try {
                    while (current(value) && isActive) {
                        val request = index.searchRequest()?.takeIf { it.generation == generation } ?: break
                        val page = PagedDataTransport.decode(service.searchMonitorCommunications(request.session,
                            request.keyword, request.after, request.through), MonitorCommunicationSearchPage::class.java)
                        if (!current(value)) break
                        if (page.sessionGeneration != request.session) break
                        if (index.applySearch(request, page)) onUpdated()
                        yield()
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    if (current(value)) { onError(error); delay(500); value.search.trySend(Unit) }
                }
            }
        }
        value.jobs += scope.launch(Dispatchers.IO) {
            for (signal in value.details) {
                try {
                    while (current(value) && isActive && details.hasPendingRefresh()) {
                        details.refresh(emptySet()) { bssid, mac, id, offset, channel ->
                            check(current(value)) { "服务连接已失效" }
                            val request = index.requestSession(generation) ?: error("抓包数据已重置")
                            PagedDataTransport.decode(service.getMonitorCommunicationDetail(request, bssid, mac, id,
                                offset, channel.name), MonitorCommunicationDetailPage::class.java)
                        }
                        if (current(value)) onUpdated()
                        yield()
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) {
                    if (current(value)) { onError(error); delay(500); value.details.trySend(Unit) }
                }
            }
        }
    }

    fun announce(generation: Long, range: MonitorCommunicationRange, updatedIds: LongArray) {
        val value = binding?.takeIf { it.generation == generation } ?: return
        scope.launch(Dispatchers.IO) {
            if (!current(value)) return@launch
            if (index.announce(generation, range, updatedIds)) onUpdated()
            value.records.trySend(Unit)
            value.search.trySend(Unit)
        }
    }

    fun search() { binding?.search?.trySend(Unit) }
    fun refreshDetails() { binding?.details?.trySend(Unit) }
    fun disconnect() {
        val previous = binding ?: return
        binding = null
        previous.jobs.forEach { it.cancel() }
        previous.records.close(); previous.search.close(); previous.details.close()
    }
}
