package io.github.bszapp.wifitoolbox.uidefault.model

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.wifilist.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** ViewModel 只记录筛选、可见窗口及导航准备；通信索引由 App 常驻同步器持有。 */
class CaptureViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    data class Filters(val accessPoint: String = "", val device: String = "", val keyword: String = "",
        val protocols: Set<String> = emptySet(), val contentKinds: Set<String> = emptySet(), val statusGroups: Set<Int> = emptySet())
    data class Display(val session: Long? = null, val count: Int = 0, val rows: Map<Int, MonitorCommunicationRecord> = emptyMap(),
        val error: String? = null, val viewed: Set<String> = emptySet(),
        val available: MonitorCommunicationAvailability = MonitorCommunicationAvailability())
    data class Opening(val operationId: Long, val session: Long, val recordId: String, val bytes: Long = 0,
        val total: Long = 0, val snapshotId: String? = null)

    private val controller = AppControllerProvider.get().wifiList
    val modeState = controller.modeState
    private val mutableFilters = MutableStateFlow(Filters(saved["ap"] ?: "", saved["device"] ?: "",
        saved["keyword"] ?: "", saved.get<ArrayList<String>>("protocols")?.toSet() ?: saved.get<String>("type")?.let(::setOf).orEmpty(),
        saved.get<ArrayList<String>>("contents")?.toSet().orEmpty(), saved.get<ArrayList<Int>>("statuses")?.toSet().orEmpty()))
    val filters = mutableFilters.asStateFlow()
    private val mutable = MutableStateFlow(Display())
    val state = mutable.asStateFlow()
    private val mutableOpening = MutableStateFlow<Opening?>(null)
    val opening = mutableOpening.asStateFlow()
    private var openingJob: Job? = null
    private val window = MutableStateFlow(0 to 32)

    init {
        viewModelScope.launch {
            combine(filters, window, controller.monitorCommunicationUpdates,
                modeState.map { it?.monitorStatistics?.sessionGeneration }.distinctUntilChanged()) { filter, visible, _, _ ->
                filter to visible
            }.collectLatest { (filter, visible) ->
                try { refreshWindow(filter, visible) }
                catch (error: CancellationException) { throw error }
                catch (error: Throwable) { mutable.update { it.copy(error = error.message ?: "读取失败") } }
            }
        }
    }

    private suspend fun refreshWindow(filter: Filters = filters.value, visible: Pair<Int, Int> = window.value) {
        val value = controller.monitorCommunicationWindow(MonitorCommunicationQuery(filter.accessPoint, filter.device,
            filter.keyword, filter.protocols, filter.contentKinds, filter.statusGroups), visible.first, visible.second)
        mutable.value = Display(value.session, value.count, value.rows, viewed = value.viewed, available = value.available)
    }

    fun open(bssid: String, deviceMac: String) {
        if (saved.get<Boolean>("initialized") == true) return
        saved["initialized"] = true
        updateFilters(filters.value.copy(accessPoint = bssid, device = deviceMac))
    }
    fun updateFilters(value: Filters) {
        mutableFilters.value = value
        saved["ap"] = value.accessPoint; saved["device"] = value.device; saved["keyword"] = value.keyword
        saved["protocols"] = ArrayList(value.protocols); saved["contents"] = ArrayList(value.contentKinds)
        saved["statuses"] = ArrayList(value.statusGroups)
    }
    fun visibleWindow(first: Int, last: Int) {
        window.value = first.coerceAtLeast(0) to last.coerceAtLeast(first.coerceAtLeast(0))
    }
    fun prepareDetail(record: MonitorCommunicationRecord) {
        val session = state.value.session ?: return
        cancelOpening()
        val operationId = SystemClock.elapsedRealtimeNanos()
        mutableOpening.value = Opening(operationId, session, record.id)
        openingJob = viewModelScope.launch {
            var token: String? = null
            try {
                token = controller.prepareMonitorCommunicationDetail(session, record.bssid, record.deviceMac, record.id) { bytes, total ->
                    mutableOpening.update { current ->
                        if (current?.operationId == operationId) current.copy(bytes = bytes, total = total) else current
                    }
                }
                ensureActive()
                mutableOpening.update { current ->
                    if (current?.operationId == operationId) current.copy(snapshotId = token) else current
                }
            } catch (error: CancellationException) {
                token?.let(controller::releasePreparedMonitorCommunication)
                throw error
            } catch (error: Throwable) {
                token?.let(controller::releasePreparedMonitorCommunication)
                ensureActive()
                if (mutableOpening.value?.operationId == operationId) {
                    mutableOpening.value = null
                    mutable.update { it.copy(error = error.message ?: "获取完整详情失败") }
                }
            }
        }
    }
    fun takePreparedDetail(): String? {
        val value = opening.value ?: return null
        val token = value.snapshotId ?: return null
        mutableOpening.value = null
        viewModelScope.launch {
            controller.markMonitorCommunicationViewed(value.session, value.recordId)
            refreshWindow()
        }
        return token
    }
    fun cancelOpening() {
        openingJob?.cancel(); openingJob = null
        mutableOpening.value?.snapshotId?.let(controller::releasePreparedMonitorCommunication)
        mutableOpening.value = null
    }
    fun clearNonHandshakeData() = controller.clearMonitorCapture(true)
    fun interruptClear(operationId: Long) = controller.interruptMonitorClear(operationId)
    override fun onCleared() { cancelOpening(); super.onCleared() }
}

/** 页面只读已完整预取的 App 磁盘快照；正文显示仍使用有界本地窗口。 */
class CaptureDetailViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    data class Display(val record: MonitorCommunicationRecord? = null, val totalBytes: Long = 0,
        val chunks: Map<Int, ByteArray> = emptyMap(), val error: String? = null, val exporting: Boolean = false,
        val lines: Map<Int, Long> = emptyMap())
    private val controller = AppControllerProvider.get().wifiList
    val savedWifiList = controller.savedWifiList
    val handshakeActions = MonitorHandshakeUiState(controller, viewModelScope)
    val pcapExports = controller.monitorPcapExports
    private val exportRequests = mutableSetOf<String>()
    fun exportHandshake(record: MonitorCommunicationRecord): String = controller.exportMonitorHandshakePcap(
        record.bssid, record.deviceMac, requireNotNull(record.handshake).id,
    ).also { exportRequests.add(it) }
    fun ownsExport(requestId: String) = exportRequests.remove(requestId)
    fun savePcap(path: String, uri: Uri?) {
        if (uri == null) controller.releaseMonitorPcapExport(path) else controller.saveMonitorPcapExport(path, uri)
    }
    fun saveHc22000(content: String, uri: Uri?) { if (uri != null) controller.saveMonitorHc22000(content, uri) }
    private val mutable = MutableStateFlow(Display())
    val state = mutable.asStateFlow()
    private val mutableChannel = MutableStateFlow(saved.get<String>("channel")?.let(MonitorCommunicationChannel::valueOf) ?: MonitorCommunicationChannel.DETAIL)
    val channel = mutableChannel.asStateFlow()
    private var target: String? = null
    private var worker: Job? = null
    private val window = MutableStateFlow(0 to 0)
    val pendingExport: MonitorCommunicationChannel? get() = saved.get<String>("export")?.let(MonitorCommunicationChannel::valueOf)

    fun open(value: String) {
        if (target == value) return
        worker?.cancel(); target = value
        worker = viewModelScope.launch {
            combine(channel, window, controller.monitorCommunicationUpdates) { selected, visible, _ -> selected to visible }
                .collectLatest { (currentChannel, visible) ->
                try {
                    val head = controller.readPreparedMonitorCommunicationDetail(value, 0, currentChannel)
                    val chunks = mutableMapOf(0 to head.bytes)
                    val lines = mutableMapOf(0 to head.startLine)
                    val firstPage = (visible.first / 16 - 1).coerceAtLeast(0)
                    val lastPage = (visible.second / 16 + 1).coerceAtMost(firstPage + 6)
                    for (page in firstPage..lastPage) {
                        val offset = page * 65536L
                        if (page > 0 && offset < head.totalBytes) {
                            val part = controller.readPreparedMonitorCommunicationDetail(value, offset, currentChannel)
                            chunks[page] = part.bytes; lines[page] = part.startLine
                        }
                    }
                    if (currentChannel == channel.value) mutable.update { it.copy(record = head.record, totalBytes = head.totalBytes, chunks = chunks, lines = lines, error = null) }
                } catch (error: CancellationException) { throw error }
                catch (error: Throwable) { mutable.update { it.copy(error = error.message ?: "读取失败") } }
            }
        }
    }

    fun selectChannel(value: MonitorCommunicationChannel) {
        if (value == channel.value) return
        saved["channel"] = value.name; mutableChannel.value = value
        mutable.update { it.copy(chunks = emptyMap(), lines = emptyMap(), totalBytes = 0) }
        window.value = 0 to 0
    }
    fun visibleWindow(first: Int, last: Int) { window.value = first.coerceAtLeast(0) to last.coerceAtLeast(first.coerceAtLeast(0)) }
    fun prepareExport(value: MonitorCommunicationChannel) { saved["export"] = value.name }
    fun export(uri: Uri?) {
        val value = pendingExport ?: return
        saved["export"] = null
        val current = target ?: return
        if (uri == null) return
        viewModelScope.launch {
            mutable.update { it.copy(exporting = true) }
            try { controller.savePreparedMonitorCommunication(current, value, uri) }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) { mutable.update { it.copy(error = error.message ?: "导出失败") } }
            finally { mutable.update { it.copy(exporting = false) } }
        }
    }

    override fun onCleared() {
        target?.let(controller::releasePreparedMonitorCommunication)
        super.onCleared()
    }
}
