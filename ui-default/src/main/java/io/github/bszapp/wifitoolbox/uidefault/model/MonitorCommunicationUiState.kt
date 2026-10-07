package io.github.bszapp.wifitoolbox.uidefault.model

import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCommunicationPage
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCommunicationDetailPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ViewModel 的当前设备读取会话：只缓存一页索引和一页详情，关闭即释放。 */
class MonitorCommunicationUiState(private val controller: IWifiListController, private val scope: CoroutineScope) {
    data class Display(
        val page: MonitorCommunicationPage? = null,
        val detail: MonitorCommunicationDetailPage? = null,
        val selectedRecordId: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )
    private data class Target(val session: Long, val bssid: String, val mac: String)
    private val mutable = MutableStateFlow(Display())
    val state = mutable.asStateFlow()
    private var target: Target? = null
    private var index = 0L
    private var detailCursor = 0L
    private var lastRevision = -1L
    private var requestVersion = 0L
    private var worker: Job? = null

    fun open(bssid: String, mac: String) {
        val session = controller.modeState.value?.monitorStatistics?.sessionGeneration ?: return
        val next = Target(session, bssid, mac)
        if (target == next) return
        close()
        target = next
        mutable.value = Display(loading = true)
        worker = scope.launch {
            while (target == next) {
                val statistics = controller.modeState.value?.monitorStatistics
                if (statistics == null) { close(); break }
                if (statistics.sessionGeneration != next.session) { open(bssid, mac); break }
                val device = statistics.accessPoints.firstOrNull { it.bssid == bssid }
                    ?.devices?.firstOrNull { it.mac == mac }
                val revision = device?.communicationRevision ?: 0L
                val version = requestVersion
                if (lastRevision != revision) {
                    try {
                        val page = controller.readMonitorCommunications(session, bssid, mac, index)
                        val selected = mutable.value.selectedRecordId
                        val detail = selected?.let {
                            controller.readMonitorCommunicationDetail(session, bssid, mac, it, detailCursor)
                        }
                        if (target == next && version == requestVersion) {
                            mutable.value = Display(page, detail, selected)
                            lastRevision = revision
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Throwable) {
                        if (target == next && version == requestVersion) {
                            mutable.value = mutable.value.copy(loading = false, error = error.message ?: "读取失败")
                            lastRevision = revision
                        }
                    }
                }
                delay(250)
            }
        }
    }

    fun close() {
        worker?.cancel(); worker = null; target = null
        index = 0; detailCursor = 0; lastRevision = -1; requestVersion++
        mutable.value = Display()
    }

    fun page(from: Long) {
        index = from.coerceAtLeast(0)
        detailCursor = 0; requestVersion++; lastRevision = -1
        mutable.value = mutable.value.copy(detail = null, selectedRecordId = null, loading = true, error = null)
    }

    fun toggleRecord(id: String) {
        detailCursor = 0; requestVersion++; lastRevision = -1
        val selected = id.takeUnless { it == mutable.value.selectedRecordId }
        mutable.value = mutable.value.copy(selectedRecordId = selected, detail = null, loading = selected != null, error = null)
    }

    fun detailPage(cursor: Long) {
        if (cursor < 0) return
        detailCursor = cursor; requestVersion++; lastRevision = -1
        mutable.value = mutable.value.copy(loading = true, error = null)
    }
}
