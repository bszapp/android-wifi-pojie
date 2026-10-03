package io.github.bszapp.wifitoolbox.uidefault.hashcat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskState
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelState
import io.github.bszapp.wifitoolbox.contract.hashcat.HASHCAT_MIB
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HashcatSheetPage { PREPARATION, INPUT, DICTIONARIES, DETAIL }
data class HashcatSheetState(
    val show: Boolean = false,
    val page: HashcatSheetPage = HashcatSheetPage.INPUT,
    val input: String = "",
    val selectedDictionaryIds: Set<String> = emptySet(),
    val taskId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val memoryLimitMiB: Int? = 1024,
    val agreed: Boolean = false,
)

class HashcatViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = AppControllerProvider.get().hashcat
    val history = controller.history
    val connected = controller.connected
    val memory = controller.memory
    val kernels = controller.kernels
    private val _sheet = MutableStateFlow(HashcatSheetState())
    val sheet = _sheet.asStateFlow()
    private val _dictionaries = MutableStateFlow<List<DictionaryResource>>(emptyList())
    val dictionaries = _dictionaries.asStateFlow()
    private var incomingOpened = false

    init {
        viewModelScope.launch {
            kernels.collect { snapshot ->
                val form = _sheet.value
                if (form.page == HashcatSheetPage.PREPARATION && !form.busy && snapshot?.state == HashcatKernelState.READY) {
                    _sheet.compareAndSet(form, form.copy(page = HashcatSheetPage.INPUT, message = null))
                }
            }
        }
    }

    fun open(handshake: String = "") {
        _sheet.value = HashcatSheetState(show = true, page = HashcatSheetPage.PREPARATION, input = handshake, busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                controller.refreshKernels()
                val form = _sheet.value
                if (form.page == HashcatSheetPage.PREPARATION) _sheet.compareAndSet(form, form.copy(busy = false, page =
                    if (kernels.value?.state == HashcatKernelState.READY) HashcatSheetPage.INPUT else HashcatSheetPage.PREPARATION))
            } catch (error: Throwable) { error(error) }
            runCatching { DictionaryStore.getAll(getApplication()).filter { it.type == 0 } }
                .onSuccess { _dictionaries.value = it }
                .onFailure { error(it) }
        }
    }
    fun openIncoming(handshake: String) { if (!incomingOpened) { incomingOpened = true; open(handshake) } }
    fun agree(value: Boolean) { _sheet.value = _sheet.value.copy(agreed = value) }
    fun compileKernels() {
        if (!_sheet.value.agreed) return
        viewModelScope.launch {
            _sheet.value = _sheet.value.copy(busy = true, message = null)
            try { controller.compileKernels() }
            catch (error: Throwable) { error(error) }
            finally {
                val form = _sheet.value
                _sheet.compareAndSet(form, form.copy(busy = false, page =
                    if (kernels.value?.state == HashcatKernelState.READY) HashcatSheetPage.INPUT else form.page))
            }
        }
    }
    fun inspect(id: String) { _sheet.value = HashcatSheetState(show = true, page = HashcatSheetPage.DETAIL, taskId = id, memoryLimitMiB = null) }
    fun dismiss() { _sheet.value = _sheet.value.copy(show = false) }
    fun input(value: String) { _sheet.value = _sheet.value.copy(input = value, message = null) }
    fun readFile(uri: Uri) {
        viewModelScope.launch {
            _sheet.value = _sheet.value.copy(busy = true)
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取 hc22000 文件")
                }
                _sheet.value = _sheet.value.copy(input = text, busy = false, message = null)
            } catch (error: Throwable) { error(error) }
        }
    }
    fun next() {
        val lines = _sheet.value.input.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty() || lines.any { !it.startsWith("WPA*01*") && !it.startsWith("WPA*02*") }) {
            _sheet.value = _sheet.value.copy(message = "请选择 hc22000 文件或粘贴有效文本")
        } else _sheet.value = _sheet.value.copy(page = HashcatSheetPage.DICTIONARIES, message = null)
    }
    fun previous() { _sheet.value = _sheet.value.copy(page = HashcatSheetPage.INPUT, message = null) }
    fun select(id: String) {
        val selected = _sheet.value.selectedDictionaryIds
        _sheet.value = _sheet.value.copy(selectedDictionaryIds = if (id in selected) selected - id else selected + id, message = null)
    }
    fun allocateMemory(value: Int) { _sheet.value = _sheet.value.copy(memoryLimitMiB = value, message = null) }
    private fun selectedBudget(taskId: String? = null): Int {
        val measured = memory.value ?: error("正在加载实时内存")
        val maximum = (measured.assignableBytes(taskId) / HASHCAT_MIB).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        check(maximum > 0) { "当前没有可分配内存，或任务内存无法读取" }
        val budget = _sheet.value.memoryLimitMiB
            ?: taskId?.let { id -> history.value.firstOrNull { it.id == id }?.memoryLimitMiB }
            ?: error("请使用滑块选择本任务的内存预算")
        require(budget in 1..maximum) { "内存预算超过当前可分配内存，请调整滑块" }
        return budget
    }
    fun run() {
        val form = _sheet.value
        val selected = _dictionaries.value.filter { it.id in form.selectedDictionaryIds && it.type == 0 }
        if (selected.isEmpty()) { _sheet.value = form.copy(message = "请选择至少一个普通字典"); return }
        val memoryBudget = try { selectedBudget() } catch (error: Throwable) { error(error); return }
        viewModelScope.launch {
            _sheet.value = form.copy(busy = true, message = "合并字典并去重")
            var combined: File? = null
            try {
                val id = withContext(Dispatchers.IO) {
                    val unique = LinkedHashSet<String>()
                    selected.forEach { resource -> resource.content.lineSequence().forEach { unique.add(it) } }
                    val file = File(getApplication<Application>().cacheDir, "hashcat-dictionary-${UUID.randomUUID()}.txt")
                    combined = file
                    file.bufferedWriter().use { out -> unique.forEach { out.write(it); out.newLine() } }
                    controller.start(form.input, file, selected.map { it.name ?: it.id }, memoryBudget)
                }
                _sheet.value = form.copy(page = HashcatSheetPage.DETAIL, taskId = id, busy = false, message = null)
            } catch (error: Throwable) { error(error) }
            finally { withContext(Dispatchers.IO) { combined?.let { check(it.delete()) { "无法清理组合字典临时文件" } } } }
        }
    }
    fun toggleTask(id: String) {
        viewModelScope.launch {
            _sheet.value = _sheet.value.copy(busy = true, message = null)
            try {
                val task = history.value.first { it.id == id }
                if (task.state == HashcatTaskState.PAUSED) {
                    if (_sheet.value.memoryLimitMiB != null) controller.setMemoryLimit(id, selectedBudget(id))
                    controller.resume(id)
                } else controller.pause(id)
                _sheet.value = _sheet.value.copy(busy = false)
            } catch (error: Throwable) { error(error) }
        }
    }
    fun applyMemory(id: String) {
        val budget = try { selectedBudget(id) } catch (error: Throwable) { error(error); return }
        viewModelScope.launch {
            val applying = _sheet.value.copy(busy = true, message = "应用内存预算；运行中的任务会保存恢复点后继续")
            _sheet.value = applying
            try {
                controller.setMemoryLimit(id, budget)
                _sheet.compareAndSet(applying, applying.copy(busy = false, memoryLimitMiB = null, message = null))
            } catch (error: Throwable) {
                _sheet.compareAndSet(applying, applying.copy(busy = false, message = error.message ?: error.javaClass.simpleName))
            }
        }
    }
    private fun error(error: Throwable) { _sheet.value = _sheet.value.copy(busy = false, message = error.message ?: error.javaClass.simpleName) }
}
