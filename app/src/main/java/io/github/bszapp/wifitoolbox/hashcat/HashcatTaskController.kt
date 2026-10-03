package io.github.bszapp.wifitoolbox.hashcat

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import io.github.bszapp.wifitoolbox.contract.hashcat.*
import io.github.bszapp.wifitoolbox.service.IHashcatCallback
import io.github.bszapp.wifitoolbox.service.IMainService
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** App 保管持久文件，运行状态只镜像服务；Compose/ViewModel 不持有第二份任务数据。 */
class HashcatTaskController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val reportError: (String, String, Throwable, String?) -> Unit,
) : IHashcatController {
    private val lock = Any()
    private val storeLock = Any()
    private val root = File(context.filesDir, "hashcat-tasks")
    private val snapshots = linkedMapOf<String, HashcatTaskSnapshot>()
    private val _history = MutableStateFlow<List<HashcatTaskSnapshot>>(emptyList())
    override val history = _history.asStateFlow()
    private val _connected = MutableStateFlow(false)
    override val connected = _connected.asStateFlow()
    private val _memory = MutableStateFlow<HashcatMemorySnapshot?>(null)
    override val memory = _memory.asStateFlow()
    private var binding: Binding? = null
    private class Binding(val service: IMainService, val callback: IHashcatCallback) {
        val ready = CompletableFuture<Unit>()
    }

    init {
        scope.launch(Dispatchers.IO) {
            synchronized(storeLock) {
                root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    val file = File(dir, "snapshot.json")
                    if (file.isFile) runCatching {
                        val snapshot = readSnapshot(file)
                        synchronized(lock) { if (snapshot.id !in snapshots) snapshots[snapshot.id] = snapshot }
                    }.onFailure { report("读取 Hashcat 历史", it) }
                }
                publish()
            }
        }
    }

    fun connect(service: IMainService) {
        lateinit var next: Binding
        val callback = object : IHashcatCallback.Stub() {
            override fun onHashcatMemoryChanged() {
                scope.launch(Dispatchers.IO) {
                    if (isCurrent(next)) runCatching { fetchMemory(next) }.onFailure { report("读取 Hashcat 内存", it) }
                }
            }
            override fun onHashcatChanged(taskId: String) {
                scope.launch(Dispatchers.IO) { if (isCurrent(next)) runCatching { fetch(next, taskId) }.onFailure { report("读取 Hashcat 进度", it) } }
            }
            override fun saveHashcatBackup(taskId: String, backup: ParcelFileDescriptor): Boolean {
                return try {
                    check(isCurrent(next)) { "服务会话已改变" }
                    synchronized(storeLock) {
                        val dir = taskDirectory(taskId)
                        val staging = File(root, ".backup-${UUID.randomUUID()}")
                        try {
                            HashcatFiles.unpack(ParcelFileDescriptor.AutoCloseInputStream(backup), staging)
                            val snapshot = readSnapshot(File(staging, "snapshot.json"))
                            require(snapshot.id == taskId && !snapshot.active) { "任务备份状态无效" }
                            staging.listFiles().orEmpty().filter { it.name != "snapshot.json" }.forEach { source ->
                                atomicCopy(source, File(dir, source.name))
                            }
                            check(File(dir, "handshake.hc22000").isFile && File(dir, "dictionary.txt").isFile)
                            save(snapshot)
                        } finally {
                            if (staging.exists()) staging.walkBottomUp().forEach { check(it.delete()) }
                        }
                    }
                    true
                } catch (error: Throwable) { report("保存 Hashcat 恢复数据", error); false }
                finally { runCatching { backup.close() } }
            }
        }
        next = Binding(service, callback)
        val old = synchronized(lock) { binding.also { binding = next; _connected.value = false; _memory.value = null } }
        old?.ready?.completeExceptionally(IllegalStateException("服务会话已改变"))
        old?.let { scope.launch(Dispatchers.IO) { runCatching { it.service.unregisterHashcatCallback(it.callback) } } }
        scope.launch(Dispatchers.IO) {
            try {
                service.registerHashcatCallback(callback)
                if (!isCurrent(next)) {
                    next.ready.completeExceptionally(IllegalStateException("服务会话已改变"))
                    service.unregisterHashcatCallback(callback)
                    return@launch
                }
                next.ready.complete(Unit)
                _connected.value = true
                fetchMemory(next)
                var offset = 0
                while (isCurrent(next)) {
                    val ids = service.getHashcatTaskIds(offset)
                    ids.forEach { fetch(next, it) }
                    if (ids.size < 64) break
                    offset += ids.size
                }
            } catch (error: Throwable) {
                next.ready.completeExceptionally(error)
                if (isCurrent(next)) report("订阅 Hashcat 任务", error)
            }
        }
    }

    fun disconnect() {
        val previous = synchronized(lock) { binding.also { binding = null; _connected.value = false; _memory.value = null } }
        previous?.ready?.completeExceptionally(IllegalStateException("服务已断开"))
        previous?.let { scope.launch(Dispatchers.IO) { runCatching { it.service.unregisterHashcatCallback(it.callback) } } }
        // 历史属于 App；正常退出服务的任务已经由服务备份为 PAUSED 或结束态。
    }

    override suspend fun start(handshake: String, dictionary: File, dictionaryNames: List<String>, memoryLimitMiB: Int): String = withContext(Dispatchers.IO) {
        val active = requireBinding()
        val lines = handshake.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty() && lines.all { it.startsWith("WPA*01*") || it.startsWith("WPA*02*") }) { "请输入有效的 hc22000 文本" }
        require(dictionary.isFile && dictionary.length() > 0) { "字典为空" }
        val id = UUID.randomUUID().toString()
        require(memoryLimitMiB > 0) { "内存预算必须大于 0 MiB" }
        val snapshot = HashcatTaskSnapshot(id, System.currentTimeMillis(), dictionaryNames = dictionaryNames, memoryLimitMiB = memoryLimitMiB)
        synchronized(storeLock) {
            val dir = taskDirectory(id)
            AtomicFile(File(dir, "handshake.hc22000")).let { atomic ->
                val out = atomic.startWrite()
                try { out.write((lines.joinToString("\n") + "\n").toByteArray()); atomic.finishWrite(out) }
                catch (error: Throwable) { atomic.failWrite(out); throw error }
            }
            atomicCopy(dictionary, File(dir, "dictionary.txt"))
            save(snapshot)
        }
        try {
            upload(active, id, resume = false)
            fetch(active, id)
        } catch (error: Throwable) {
            synchronized(storeLock) { save(snapshot.copy(state = HashcatTaskState.FAILED, step = "启动失败", error = error.message, revision = 1)) }
            throw error
        }
        id
    }

    override suspend fun pause(taskId: String) = withContext(Dispatchers.IO) {
        val active = requireBinding()
        check(active.service.pauseHashcat(taskId)) { "暂停任务的备份尚未保存成功" }
        fetch(active, taskId)
    }
    override suspend fun resume(taskId: String) = withContext(Dispatchers.IO) {
        val active = requireBinding()
        val snapshot = synchronized(lock) { snapshots[taskId] } ?: error("任务不存在")
        check(snapshot.state == HashcatTaskState.PAUSED) { "任务未处于暂停状态" }
        upload(active, taskId, resume = true)
        fetch(active, taskId)
    }

    override suspend fun setMemoryLimit(taskId: String, memoryLimitMiB: Int) = withContext(Dispatchers.IO) {
        require(memoryLimitMiB > 0) { "内存预算必须大于 0 MiB" }
        val active = requireBinding()
        fetchMemory(active)
        val measured = requireNotNull(memory.value) { "加载中" }
        require(memoryLimitMiB.toLong() * HASHCAT_MIB <= measured.assignableBytes(taskId)) { "内存预算超过当前可分配内存" }
        val before = synchronized(lock) { snapshots[taskId] } ?: error("任务不存在")
        val continueRunning = before.state == HashcatTaskState.RUNNING
        check(continueRunning || before.state == HashcatTaskState.PAUSED) { "当前任务不能调整内存预算" }
        if (continueRunning) pause(taskId)
        synchronized(storeLock) {
            val paused = synchronized(lock) { snapshots[taskId] } ?: error("任务不存在")
            check(paused.state == HashcatTaskState.PAUSED) { "任务已经结束，无需调整内存预算" }
            save(paused.copy(memoryLimitMiB = memoryLimitMiB, revision = paused.revision + 1))
        }
        if (continueRunning) resume(taskId)
    }

    suspend fun prepareServiceShutdown(service: IMainService) = withContext(Dispatchers.IO) {
        val active = requireBinding()
        check(active.service.asBinder() == service.asBinder())
        check(service.prepareHashcatShutdown()) { "Hashcat 任务保存未完成，服务仍在运行" }
        var offset = 0
        while (true) {
            val ids = service.getHashcatTaskIds(offset)
            ids.forEach { fetch(active, it) }
            if (ids.size < 64) break
            offset += ids.size
        }
    }

    private fun upload(active: Binding, id: String, resume: Boolean) {
        val dir = taskDirectory(id)
        val files = listOf("handshake.hc22000", "dictionary.txt", "snapshot.json", "session.restore", "result.txt", "run.log")
            .associateWith { File(dir, it) } + ("libhashcat.so" to File(context.applicationInfo.nativeLibraryDir, "libhashcat.so"))
        HashcatFiles.pipe { out -> HashcatFiles.pack(out, files) }.use { fd ->
            val snapshot = synchronized(storeLock) { readSnapshot(File(dir, "snapshot.json")) }
            val request = HashcatLaunch(id, snapshot.createdAt, snapshot.revision, snapshot.memoryLimitMiB)
            if (resume) active.service.resumeHashcat(request, fd) else active.service.startHashcat(request, fd)
        }
    }
    private fun fetch(active: Binding, id: String) {
        val snapshot = active.service.getHashcatTask(id).let { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { HashcatTaskSnapshot.fromJson(JSONObject(it.readText())) }
        }
        synchronized(storeLock) { if (isCurrent(active)) save(snapshot) }
    }
    private fun fetchMemory(active: Binding) {
        val measured = active.service.getHashcatMemory().let { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { HashcatMemorySnapshot.fromJson(JSONObject(it.readText())) }
        }
        synchronized(lock) { if (binding === active && measured.capturedAt >= (_memory.value?.capturedAt ?: 0)) _memory.value = measured }
    }
    private fun save(snapshot: HashcatTaskSnapshot) {
        synchronized(lock) {
            val old = snapshots[snapshot.id]
            if (old != null && old.revision > snapshot.revision) return
            val file = AtomicFile(File(taskDirectory(snapshot.id), "snapshot.json"))
            val out = file.startWrite()
            try { out.write(snapshot.toJson().toString().toByteArray()); file.finishWrite(out) }
            catch (error: Throwable) { file.failWrite(out); throw error }
            snapshots[snapshot.id] = snapshot
            publish()
        }
    }
    private fun publish() = synchronized(lock) { _history.value = snapshots.values.sortedByDescending { it.createdAt } }
    private fun taskDirectory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, id).also { check(it.isDirectory || it.mkdirs()) }
    }
    private fun atomicCopy(source: File, target: File) {
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try { source.inputStream().use { it.copyTo(out, 64 * 1024) }; atomic.finishWrite(out) }
        catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
    private fun readSnapshot(file: File) = HashcatTaskSnapshot.fromJson(JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }))
    private fun requireBinding(): Binding {
        val active = synchronized(lock) { binding } ?: error("服务未连接")
        active.ready.get()
        check(isCurrent(active)) { "服务会话已改变" }
        return active
    }
    private fun isCurrent(active: Binding) = synchronized(lock) { binding === active }
    private fun report(operation: String, error: Throwable) = reportError("App.Hashcat", operation, error, null)
}
