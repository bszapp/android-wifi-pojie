package io.github.bszapp.wifitoolbox.uidefault.model

import android.app.Application
import android.os.SystemClock
import android.net.wifi.ScanResult
import io.github.bszapp.wifitoolbox.contract.wifilist.supportsWps
import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.task.*
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class NormalWifiSubmode { SCAN, SECURITY_AUDIT, WPS_SECURITY_AUDIT }
enum class SecurityAuditSheetPage { CONFIGURATION, DICTIONARIES, DETAIL, SAVED, RECOVERY, MANUAL }
enum class WpsAuditSheetPage { TARGET, ACCESS_POINTS, CONFIGURATION, DETAIL }

data class WpsAuditSheetState(
    val show: Boolean = false,
    val page: WpsAuditSheetPage = WpsAuditSheetPage.TARGET,
    val config: WpsSecurityAuditConfig = WpsSecurityAuditConfig(),
    val ssid: String = "",
    val saved: Boolean = false,
    val supportsWps: Boolean = true,
    val recordId: String? = null,
    val selectedMac: String? = null,
    val accessPoints: List<ScanResult> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val authorizationShow: Boolean = false,
    val authorizationAgreed: Boolean = false,
    val authorizationReadyAt: Long = 0,
)
data class SecurityAuditSheetState(
    val show: Boolean = false,
    val page: SecurityAuditSheetPage = SecurityAuditSheetPage.CONFIGURATION,
    val config: SecurityAuditConfig = SecurityAuditConfig(),
    val ssid: String = "",
    val saved: Boolean = false,
    val savedPassword: String? = null,
    val selectedIds: List<String> = emptyList(),
    val taskCount: Int = 1,
    val candidateCount: Int = 0,
    val preparationLog: List<String> = emptyList(),
    val resumeRecordId: String? = null,
    val useSavedPassword: Boolean = false,
    val recordId: String? = null,
    val sharedSourceRecordId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val authorizationShow: Boolean = false,
    val authorizationAgreed: Boolean = false,
    val authorizationReadyAt: Long = 0,
)

/** 表单由 ViewModel 拥有；顶层任务 ID 与内部测试记录均从 App 的服务镜像读取。 */
class SecurityAuditUiState(
    private val app: Application,
    private val controller: IAppController,
    private val scope: CoroutineScope,
) {
    private val _submode = MutableStateFlow(NormalWifiSubmode.SCAN)
    val submode = _submode.asStateFlow()
    private val _sheet = MutableStateFlow(SecurityAuditSheetState())
    val sheet = _sheet.asStateFlow()
    private val _wpsForm = MutableStateFlow(WpsAuditSheetState())
    private val _dictionaries = MutableStateFlow<List<DictionaryResource>>(emptyList())
    val dictionaries = _dictionaries.asStateFlow()
    val history = controller.tasks.auditHistory
    private val submission = Mutex()
    private var preparation: Job? = null
    private var preparedPasswords: List<String> = emptyList()

    val auditTasks = controller.tasks.state.map { state ->
        state.tasks.values.filter { it.snapshot.request.payload is TaskRequestPayload.SecurityAudit }
            .sortedBy { it.snapshot.taskId }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val batch = controller.tasks.state.map { state ->
        state.currentTaskId?.let(state.tasks::get)?.takeIf {
            it.snapshot.request.payload is TaskRequestPayload.WpsSecurityAudit
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val wpsBatch = batch.map { running ->
        running?.takeIf { it.snapshot.request.payload is TaskRequestPayload.WpsSecurityAudit }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val wpsForm = _wpsForm.asStateFlow()

    fun selectSubmode(value: NormalWifiSubmode) { _submode.value = value }
    private fun currentConfig() = auditTasks.value.asReversed().firstNotNullOfOrNull {
        if (it.snapshot.state == TaskExecutionState.RUNNING)
            (it.snapshot.progress as? TaskProgress.SecurityAudit)?.config else null
    } ?: controller.tasks.auditConfig.value
    private fun currentWpsConfig() =
        (runningWpsBatch()?.snapshot?.progress as? TaskProgress.WpsSecurityAudit)?.config ?: controller.tasks.wpsAuditConfig.value
    fun configure() {
        if (submode.value == NormalWifiSubmode.WPS_SECURITY_AUDIT) configureWps() else _sheet.value =
            SecurityAuditSheetState(show = true, config = currentConfig())
    }
    fun changeConfig(config: SecurityAuditConfig) {
        controller.tasks.configureSecurityAudit(config)
        _sheet.update { it.copy(config = config) }
    }

    fun manualNetwork() { _sheet.value = SecurityAuditSheetState(show = true,
        page = SecurityAuditSheetPage.MANUAL, config = currentConfig()) }
    fun changeSsid(value: String) { _sheet.update { it.copy(ssid = value, message = null) } }
    fun selectManualNetwork() {
        val ssid = _sheet.value.ssid
        if (ssid.toByteArray(Charsets.UTF_8).size !in 1..32) {
            _sheet.update { it.copy(message = "SSID 长度应为 1～32 字节") }; return
        }
        openNetwork(ssid, controller.wifiList.savedWifiList.value?.networks.orEmpty().any { it.SSID?.removeSurrounding("\"") == ssid })
    }

    fun openNetwork(ssid: String, saved: Boolean) {
        preparation?.cancel()
        preparedPasswords = emptyList()
        val savedPassword = controller.wifiList.savedWifiList.value?.networks.orEmpty()
            .firstOrNull { it.SSID?.removeSurrounding("\"") == ssid }
            ?.preSharedKey?.removeSurrounding("\"")
        val running = hasRunningDictionaryTest(ssid)
        val previous = history.value.filterIsInstance<AuditHistoryRecord.Dictionary>()
            .filter { !running && it.ssid == ssid && it.password == null && it.completed < it.total }
            .maxByOrNull { it.updatedAt }
        val page = when { running -> SecurityAuditSheetPage.DICTIONARIES
            saved -> SecurityAuditSheetPage.SAVED
            previous != null -> SecurityAuditSheetPage.RECOVERY
            else -> SecurityAuditSheetPage.DICTIONARIES }
        val form = SecurityAuditSheetState(show = true, page = SecurityAuditSheetPage.DICTIONARIES,
            config = currentConfig(), ssid = ssid, saved = saved, savedPassword = savedPassword,
            recordId = previous?.id)
            .copy(page = page)
        _sheet.value = form
        if (page == SecurityAuditSheetPage.DICTIONARIES) chooseDictionaries()
    }

    fun chooseDictionaries() {
        preparation?.cancel()
        val form = _sheet.value.copy(page = SecurityAuditSheetPage.DICTIONARIES,
            resumeRecordId = null, sharedSourceRecordId = null, useSavedPassword = false, busy = true, message = null)
        _sheet.value = form
        scope.launch(Dispatchers.IO) {
            runCatching { DictionaryStore.getAll(app).filter { it.type == 0 } }
                .onSuccess {
                    _dictionaries.value = it
                    val current = _sheet.value
                    if (current.copy(taskCount = form.taskCount) == form) {
                        _sheet.value = current.copy(busy = false)
                        prepareDictionaries()
                    }
                }
                .onFailure { error ->
                    val current = _sheet.value
                    if (current.copy(taskCount = form.taskCount) == form) {
                        _sheet.value = current.copy(busy = false, message = error.message)
                    }
                }
        }
    }

    fun rerunAfterSaved() {
        val form = _sheet.value
        if (form.recordId != null && !hasRunningDictionaryTest(form.ssid)) {
            _sheet.update { it.copy(page = SecurityAuditSheetPage.RECOVERY, message = null) }
        } else chooseDictionaries()
    }

    fun selectDictionary(id: String) {
        _sheet.update { form -> form.copy(selectedIds = if (id in form.selectedIds) form.selectedIds - id else form.selectedIds + id,
            sharedSourceRecordId = null, message = null) }
        prepareDictionaries()
    }

    fun changeTaskCount(value: Int) { _sheet.update { it.copy(taskCount = value.coerceIn(1, 8)) } }

    fun availableSharedSources(): List<AuditRecord.Dictionary> = runningTasks().flatMap { it.auditNetworks }
        .filterIsInstance<AuditRecord.Dictionary>().filter { it.active }
        .distinctBy { it.groupId ?: it.id }

    fun selectSharedSource(recordId: String) {
        val source = availableSharedSources().firstOrNull { it.id == recordId } ?: return
        preparation?.cancel()
        preparedPasswords = emptyList()
        _sheet.update { it.copy(sharedSourceRecordId = source.id, selectedIds = emptyList(),
            candidateCount = source.total, busy = false, message = null,
            preparationLog = listOf("共享 ${source.groupId ?: source.id} 的候选池，下一位：${(source.poolCompleted ?: source.completed) + 1}/${source.total}")) }
    }

    private fun prepareDictionaries() {
        preparation?.cancel()
        val form = _sheet.value.copy(busy = true)
        _sheet.value = form
        preparation = scope.launch(Dispatchers.IO) {
            val selected = form.selectedIds.mapNotNull { id -> _dictionaries.value.firstOrNull { it.id == id && it.type == 0 } }
            val lists = selected.map { it.content.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList() }
            val unique = linkedSetOf<String>()
            for (index in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
                lists.forEach { it.getOrNull(index)?.takeIf { value -> value.length >= 8 }?.let(unique::add) }
            }
            val result = unique.toList()
            withContext(Dispatchers.Main) {
                val current = _sheet.value
                if (current.copy(taskCount = form.taskCount) == form) {
                    preparedPasswords = result
                    _sheet.value = current.copy(busy = false, candidateCount = result.size,
                        preparationLog = selected.mapIndexed { index, item -> "${item.name ?: item.id}：${lists[index].size} 项" } +
                            "交错合并、去重及过滤短项后：${result.size} 项")
                }
            }
        }
    }

    fun useSavedPassword() {
        if (hasRunningDictionaryTest(_sheet.value.ssid)) { chooseDictionaries(); return }
        val config = controller.wifiList.savedWifiList.value?.networks.orEmpty().firstOrNull {
            it.SSID?.removeSurrounding("\"") == _sheet.value.ssid
        } ?: run { _sheet.update { it.copy(message = "暂无已保存配置") }; return }
        val password = config.preSharedKey?.removeSurrounding("\"").orEmpty()
        preparedPasswords = listOf(password.takeIf { it.length >= 8 }.orEmpty())
        _sheet.update { it.copy(useSavedPassword = true, resumeRecordId = null,
            sharedSourceRecordId = null, candidateCount = 1) }
        requestRun()
    }

    fun resumeHistory(recordId: String) {
        val record = history.value.filterIsInstance<AuditHistoryRecord.Dictionary>().firstOrNull { it.id == recordId } ?: return
        if (hasRunningDictionaryTest(record.ssid)) { openNetwork(record.ssid, saved = false); return }
        _sheet.update { it.copy(ssid = record.ssid, config = currentConfig(),
            saved = controller.wifiList.savedWifiList.value?.networks.orEmpty().any { network ->
                network.SSID?.removeSurrounding("\"") == record.ssid },
            resumeRecordId = recordId, sharedSourceRecordId = null,
            useSavedPassword = false, candidateCount = record.total) }
        requestRun()
    }
    fun requestRun() {
        val form = _sheet.value
        if (form.busy) return
        if (form.candidateCount == 0) { _sheet.value = form.copy(message = "请选择包含有效密码的普通字典"); return }
        _sheet.value = form.copy(authorizationShow = true, authorizationAgreed = false,
            authorizationReadyAt = SystemClock.elapsedRealtime() + 0, message = null)//TODO
    }
    fun agree(value: Boolean) { _sheet.update { it.copy(authorizationAgreed = value) } }
    fun dismissAuthorization() { _sheet.update { it.copy(authorizationShow = false) } }
    fun confirmRun() {
        val form = _sheet.value
        val passwords = preparedPasswords
        if (!form.authorizationShow || !form.authorizationAgreed || SystemClock.elapsedRealtime() < form.authorizationReadyAt || form.busy) return
        if ((form.resumeRecordId != null || form.useSavedPassword) && hasRunningDictionaryTest(form.ssid)) {
            chooseDictionaries()
            _sheet.update { it.copy(authorizationShow = false) }
            return
        }
        _sheet.value = form.copy(authorizationShow = false, busy = true,
            message = if (form.sharedSourceRecordId == null) "合并字典并去重" else "加入共享候选池")
        scope.launch {
            submission.withLock {
                var combined: File? = null
                try {
                    if (form.sharedSourceRecordId != null) {
                        val source = availableSharedSources().firstOrNull {
                            it.id == form.sharedSourceRecordId && it.active }
                            ?: error("共享来源记录已结束")
                        val id = controller.tasks.joinSecurityAuditPool(SecurityAuditTaskInput(currentConfig(),
                            SecurityAuditNetworkInput(form.ssid, source.input.dictionaryId,
                                source.input.dictionaryNames, source.input.resourceIds,
                                sharedWithRecordId = source.id)), form.taskCount)
                        controller.tasks.trackTask(id)
                        _sheet.update { it.copy(show = false, busy = false, message = null) }
                        return@withLock
                    }
                    withContext(Dispatchers.IO) {
                        val selected = form.selectedIds.mapNotNull { id -> _dictionaries.value.firstOrNull { it.id == id && it.type == 0 } }
                        val previous = form.resumeRecordId?.let { id ->
                            history.value.filterIsInstance<AuditHistoryRecord.Dictionary>().firstOrNull { it.id == id } }
                        val file = File(app.cacheDir, "security-audit-${UUID.randomUUID()}.txt")
                        combined = file
                        file.bufferedWriter().use { out -> (previous?.passwords ?: passwords).forEach { out.write(it); out.newLine() } }
                        val id = controller.tasks.runSecurityAudit(SecurityAuditTaskInput(currentConfig(),
                            SecurityAuditNetworkInput(form.ssid, UUID.randomUUID().toString(),
                                if (previous != null) listOf("历史候选字典") else if (form.useSavedPassword) listOf("已保存网络密码") else selected.map { it.name ?: it.id },
                                if (previous != null) emptyList() else selected.map { it.id }, previous?.completed ?: 0)), file, form.taskCount)
                        controller.tasks.trackTask(id)
                    }
                    _sheet.update { it.copy(show = false, busy = false, message = null) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    _sheet.update { it.copy(busy = false, message = error.message ?: error.javaClass.name) }
                } finally { withContext(Dispatchers.IO) { combined?.delete() } }
            }
        }
    }

    private fun runningTasks() = auditTasks.value.filter { it.snapshot.state == TaskExecutionState.RUNNING }
    private fun hasRunningDictionaryTest(ssid: String) = runningTasks().any { task ->
        task.auditNetworks.any { it is AuditRecord.Dictionary && it.active && it.ssid == ssid }
    }
    private fun runningWpsBatch() = wpsBatch.value?.takeIf { it.snapshot.state == TaskExecutionState.RUNNING }

    fun stopNetwork(ssid: String, mac: String? = null) {
        val task = runningWpsBatch() ?: return
        controller.tasks.updateTask(task.snapshot.taskId, TaskUpdateRequest(TaskUpdatePayload.SecurityAuditRemove(ssid, mac)))
    }
    fun stopDictionaryNetwork(ssid: String) {
        val task = runningTasks().firstOrNull { tracked ->
            tracked.auditNetworks.any { it is AuditRecord.Dictionary && it.ssid == ssid && it.active }
        } ?: return
        controller.tasks.updateTask(task.snapshot.taskId,
            TaskUpdateRequest(TaskUpdatePayload.SecurityAuditRemove(ssid)))
    }
    fun stopRecord(recordId: String) {
        val task = runningTasks().firstOrNull { tracked -> tracked.auditNetworks.any { it.id == recordId } } ?: return
        controller.tasks.updateTask(task.snapshot.taskId,
            TaskUpdateRequest(TaskUpdatePayload.SecurityAuditStopRecord(recordId)))
    }
    fun inspect(recordId: String) { _sheet.value = SecurityAuditSheetState(show = true,
        page = SecurityAuditSheetPage.DETAIL, recordId = recordId,
        ssid = history.value.filterIsInstance<AuditHistoryRecord.Dictionary>().firstOrNull { it.id == recordId }?.ssid.orEmpty()) }

    // ── WPS 安全测试 ────────────────────────────────────────────────────────
    fun configureWps() { _wpsForm.value = WpsAuditSheetState(show = true,
        page = WpsAuditSheetPage.CONFIGURATION, config = currentWpsConfig()) }
    fun changeWpsConfig(config: WpsSecurityAuditConfig) {
        controller.tasks.configureWpsSecurityAudit(config)
        _wpsForm.update { it.copy(config = config) }
    }

    fun openWpsNetwork(ssid: String, saved: Boolean, accessPoints: List<ScanResult>) {
        val candidates = accessPoints.filter { !it.BSSID.isNullOrBlank() && it.supportsWps() }
            .sortedWith(compareByDescending<ScanResult> { if (it.level == 0) Int.MIN_VALUE else it.level }
                .thenBy { it.BSSID.lowercase() })
            .distinctBy { it.BSSID.lowercase() }
        _wpsForm.value = WpsAuditSheetState(show = true,
            page = WpsAuditSheetPage.ACCESS_POINTS,
            ssid = ssid, saved = saved, supportsWps = candidates.isNotEmpty(), config = currentWpsConfig(),
            accessPoints = candidates)
    }

    fun selectWpsAccessPoint(mac: String) {
        val form = _wpsForm.value
        if (form.accessPoints.none { it.BSSID.equals(mac, true) }) return
        val previous = history.value.filterIsInstance<AuditHistoryRecord.Wps>().firstOrNull {
            it.mac.equals(mac, true) && it.password == null && it.phase != WpsSecurityAuditPhase.DONE
        }
        _wpsForm.value = form.copy(selectedMac = mac.lowercase(), recordId = previous?.id)
    }

    fun confirmWpsAccessPoint() {
        val form = _wpsForm.value
        if (form.accessPoints.none { it.BSSID.equals(form.selectedMac, true) }) return
        _wpsForm.update { it.copy(page = WpsAuditSheetPage.TARGET) }
    }

    fun requestWpsRun() {
        val form = _wpsForm.value
        if (form.accessPoints.none { it.BSSID.equals(form.selectedMac, true) }) {
            _wpsForm.update { it.copy(page = WpsAuditSheetPage.ACCESS_POINTS, message = "请先选择已授权的接入点") }; return
        }
        if (!form.supportsWps) { _wpsForm.update { it.copy(message = "此网络未广播 WPS 能力") }; return }
        _wpsForm.update { it.copy(authorizationShow = true, authorizationAgreed = false,
            authorizationReadyAt = SystemClock.elapsedRealtime() + 3000, message = null) }
    }
    fun agreeWps(value: Boolean) { _wpsForm.update { it.copy(authorizationAgreed = value) } }
    fun dismissWpsAuthorization() { _wpsForm.update { it.copy(authorizationShow = false) } }
    fun confirmWpsRun() {
        val form = _wpsForm.value
        if (!form.authorizationShow || !form.authorizationAgreed || form.busy) return
        if (SystemClock.elapsedRealtime() < form.authorizationReadyAt) return
        if (form.accessPoints.none { it.BSSID.equals(form.selectedMac, true) }) {
            _wpsForm.update { it.copy(page = WpsAuditSheetPage.ACCESS_POINTS,
                authorizationShow = false, message = "请先选择已授权的接入点") }
            return
        }
        _wpsForm.value = form.copy(authorizationShow = false, busy = true, message = null)
        scope.launch {
            try {
                val previous = form.recordId?.let { id ->
                    history.value.filterIsInstance<AuditHistoryRecord.Wps>().firstOrNull { it.id == id } }
                val id = controller.tasks.runWpsSecurityAudit(WpsSecurityAuditTaskInput(
                    currentWpsConfig(),
                    listOf(WpsSecurityAuditTarget(
                        ssid = form.ssid,
                        mac = form.selectedMac,
                        resumePhase = previous?.phase ?: WpsSecurityAuditPhase.FAIRY_DUST,
                        resumeFirstHalf = previous?.firstHalf,
                        resumeFirstHalfIndex = previous?.firstHalfIndex ?: 0,
                        resumeSecondHalfIndex = previous?.secondHalfIndex ?: 0,
                    )),
                ))
                controller.tasks.trackTask(id)
                _wpsForm.value = WpsAuditSheetState()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _wpsForm.update { it.copy(busy = false, message = error.message ?: error.javaClass.name) }
            }
        }
    }

    fun inspectWps(recordId: String) { _wpsForm.value = WpsAuditSheetState(show = true,
        page = WpsAuditSheetPage.DETAIL, recordId = recordId,
        ssid = history.value.filterIsInstance<AuditHistoryRecord.Wps>().firstOrNull { it.id == recordId }?.ssid.orEmpty()) }
    fun dismissWps() { _wpsForm.update { it.copy(show = false, authorizationShow = false) } }

    fun dismiss() { _sheet.update { it.copy(show = false, authorizationShow = false) }; dismissWps() }
    fun deleteHistory(id: String) = controller.tasks.deleteAuditHistory(id)
}
