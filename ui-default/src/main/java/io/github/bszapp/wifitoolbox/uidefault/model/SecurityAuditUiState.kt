package io.github.bszapp.wifitoolbox.uidefault.model

import android.app.Application
import android.os.SystemClock
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

enum class NormalWifiSubmode { SCAN, SECURITY_AUDIT }
enum class SecurityAuditSheetPage { CONFIGURATION, DICTIONARIES, DETAIL, SAVED, RECOVERY, MANUAL }
data class SecurityAuditSheetState(
    val show: Boolean = false,
    val page: SecurityAuditSheetPage = SecurityAuditSheetPage.CONFIGURATION,
    val config: SecurityAuditConfig = SecurityAuditConfig(),
    val ssid: String = "",
    val saved: Boolean = false,
    val selectedIds: List<String> = emptyList(),
    val candidateCount: Int = 0,
    val preparationLog: List<String> = emptyList(),
    val resumeRecordId: String? = null,
    val useSavedPassword: Boolean = false,
    val recordId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val authorizationShow: Boolean = false,
    val authorizationAgreed: Boolean = false,
    val authorizationReadyAt: Long = 0,
)

/** 表单由 ViewModel 拥有；队列、任务 ID、结果全部从 App 的服务镜像读取。 */
class SecurityAuditUiState(
    private val app: Application,
    private val controller: IAppController,
    private val scope: CoroutineScope,
) {
    private val _submode = MutableStateFlow(NormalWifiSubmode.SCAN)
    val submode = _submode.asStateFlow()
    private val _sheet = MutableStateFlow(SecurityAuditSheetState())
    val sheet = _sheet.asStateFlow()
    private val _dictionaries = MutableStateFlow<List<DictionaryResource>>(emptyList())
    val dictionaries = _dictionaries.asStateFlow()
    val history = controller.tasks.auditHistory
    private val submission = Mutex()
    private var preparation: Job? = null
    private var preparedPasswords: List<String> = emptyList()

    val batch = controller.tasks.state.map { state ->
        state.currentTaskId?.let(state.tasks::get)?.takeIf { it.snapshot.request.payload is TaskRequestPayload.SecurityAudit }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun selectSubmode(value: NormalWifiSubmode) { _submode.value = value }
    private fun currentConfig() = (runningBatch()?.snapshot?.progress as? TaskProgress.SecurityAudit)?.config ?: controller.tasks.auditConfig.value
    fun configure() { _sheet.value = SecurityAuditSheetState(show = true, config = currentConfig()) }
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
        val existing = batch.value?.auditNetworks?.firstOrNull { it.active && it.input.ssid == ssid }
        val previous = history.value.firstOrNull { it.input.ssid == ssid && it.password == null && it.completed < it.total }
        val page = when { existing != null -> SecurityAuditSheetPage.DICTIONARIES
            saved -> SecurityAuditSheetPage.SAVED
            previous != null -> SecurityAuditSheetPage.RECOVERY
            else -> SecurityAuditSheetPage.DICTIONARIES }
        val form = SecurityAuditSheetState(show = true, page = SecurityAuditSheetPage.DICTIONARIES,
            config = currentConfig(), ssid = ssid, saved = saved,
            selectedIds = existing?.input?.resourceIds.orEmpty(), recordId = previous?.id)
            .copy(page = page)
        _sheet.value = form
        if (page == SecurityAuditSheetPage.DICTIONARIES) chooseDictionaries()
    }

    fun chooseDictionaries() {
        preparation?.cancel()
        val form = _sheet.value.copy(page = SecurityAuditSheetPage.DICTIONARIES,
            resumeRecordId = null, useSavedPassword = false, busy = true, message = null)
        _sheet.value = form
        scope.launch(Dispatchers.IO) {
            runCatching { DictionaryStore.getAll(app).filter { it.type == 0 } }
                .onSuccess {
                    _dictionaries.value = it
                    if (_sheet.compareAndSet(form, form.copy(busy = false))) prepareDictionaries()
                }
                .onFailure { error -> _sheet.compareAndSet(form, form.copy(busy = false, message = error.message)) }
        }
    }

    fun selectDictionary(id: String) {
        _sheet.update { form -> form.copy(selectedIds = if (id in form.selectedIds) form.selectedIds - id else form.selectedIds + id,
            message = null) }
        prepareDictionaries()
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
                if (_sheet.value == form) {
                    preparedPasswords = result
                    _sheet.value = form.copy(busy = false, candidateCount = result.size,
                        preparationLog = selected.mapIndexed { index, item -> "${item.name ?: item.id}：${lists[index].size} 项" } +
                            "交错合并、去重及过滤短项后：${result.size} 项")
                }
            }
        }
    }

    fun useSavedPassword() {
        val config = controller.wifiList.savedWifiList.value?.networks.orEmpty().firstOrNull {
            it.SSID?.removeSurrounding("\"") == _sheet.value.ssid
        } ?: run { _sheet.update { it.copy(message = "暂无已保存配置") }; return }
        val password = config.preSharedKey?.removeSurrounding("\"").orEmpty()
        preparedPasswords = listOf(password.takeIf { it.length >= 8 }.orEmpty())
        _sheet.update { it.copy(useSavedPassword = true, resumeRecordId = null, candidateCount = 1) }
        requestRun()
    }

    fun resumeHistory(recordId: String) {
        val record = history.value.firstOrNull { it.id == recordId } ?: return
        if (controller.tasks.auditDictionary(recordId) == null) {
            _sheet.update { it.copy(message = "此旧记录未保存完整字典，请重新选择字典") }; return
        }
        _sheet.update { it.copy(ssid = record.input.ssid, config = currentConfig(),
            saved = controller.wifiList.savedWifiList.value?.networks.orEmpty().any { network ->
                network.SSID?.removeSurrounding("\"") == record.input.ssid },
            resumeRecordId = recordId, useSavedPassword = false, candidateCount = record.total) }
        requestRun()
    }
    fun requestRun() {
        val form = _sheet.value
        if (form.busy) return
        if (form.candidateCount == 0) { _sheet.value = form.copy(message = "请选择包含有效密码的普通字典"); return }
        _sheet.value = form.copy(authorizationShow = true, authorizationAgreed = false,
            authorizationReadyAt = SystemClock.elapsedRealtime() + 3000, message = null)
    }
    fun agree(value: Boolean) { _sheet.update { it.copy(authorizationAgreed = value) } }
    fun dismissAuthorization() { _sheet.update { it.copy(authorizationShow = false) } }
    fun confirmRun() {
        val form = _sheet.value
        val passwords = preparedPasswords
        if (!form.authorizationShow || !form.authorizationAgreed || SystemClock.elapsedRealtime() < form.authorizationReadyAt || form.busy) return
        _sheet.value = form.copy(authorizationShow = false, busy = true, message = "合并字典并去重")
        scope.launch {
            submission.withLock {
                var combined: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        val selected = form.selectedIds.mapNotNull { id -> _dictionaries.value.firstOrNull { it.id == id && it.type == 0 } }
                        val previous = form.resumeRecordId?.let { id -> history.value.firstOrNull { it.id == id } }
                        val file = File(app.cacheDir, "security-audit-${UUID.randomUUID()}.txt")
                        combined = file
                        if (previous != null) {
                            requireNotNull(controller.tasks.auditDictionary(previous.id)) { "历史字典已不可用" }.copyTo(file)
                        } else file.bufferedWriter().use { out -> passwords.forEach { out.write(it); out.newLine() } }
                        val id = controller.tasks.runSecurityAudit(SecurityAuditTaskInput(currentConfig(),
                            SecurityAuditNetworkInput(form.ssid, UUID.randomUUID().toString(),
                                previous?.input?.dictionaryNames ?: if (form.useSavedPassword) listOf("已保存网络密码") else selected.map { it.name ?: it.id },
                                previous?.input?.resourceIds ?: selected.map { it.id }, previous?.completed ?: 0)), file)
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

    private fun runningBatch() = batch.value?.takeIf { it.snapshot.state == TaskExecutionState.RUNNING }
    fun stopNetwork(ssid: String) { runningBatch()?.let { controller.tasks.updateTask(it.snapshot.taskId,
        TaskUpdateRequest(TaskUpdatePayload.SecurityAuditRemove(ssid))) } }
    fun stopBatch() { runningBatch()?.let { controller.tasks.stopTask(it.snapshot.taskId) } }
    fun inspect(recordId: String) { _sheet.value = SecurityAuditSheetState(show = true,
        page = SecurityAuditSheetPage.DETAIL, recordId = recordId,
        ssid = history.value.firstOrNull { it.id == recordId }?.input?.ssid.orEmpty()) }
    fun dismiss() { _sheet.update { it.copy(show = false, authorizationShow = false) } }
    fun deleteHistory(id: String) = controller.tasks.deleteAuditHistory(id)
}
