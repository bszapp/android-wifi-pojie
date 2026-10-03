package io.github.bszapp.wifitoolbox.service.hashcat

import android.os.ParcelFileDescriptor
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatFiles
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatFinding
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskState
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatMemorySnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HASHCAT_MIB
import io.github.bszapp.wifitoolbox.service.IHashcatCallback
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 每个 UUID 独立进程/工作目录，可并发；任务结束或暂停后将文件交给 App 保管。 */
internal class HashcatManager(
    private val onError: (String, Throwable) -> Unit,
    private val onActivityChanged: () -> Unit,
) {
    private class Record(var snapshot: HashcatTaskSnapshot, val directory: File) {
        val operationLock = Any()
        var controller = HashcatController()
        var completion = CompletableFuture<Unit>()
        @Volatile var backedUp = false
        var endState: HashcatTaskState? = null
        var lastNotify = 0L
        var notifyPending = false
    }
    private val lock = Any()
    private val records = linkedMapOf<String, Record>()
    private val workers = Executors.newCachedThreadPool { Thread(it, "hashcat-task").apply { isDaemon = true } }
    private val callbacks = Executors.newSingleThreadScheduledExecutor { Thread(it, "hashcat-callback").apply { isDaemon = true } }
    @Volatile private var callback: IHashcatCallback? = null
    private val kernelCompiler = HashcatKernelCompiler(
        onChanged = { callbacks.execute { runCatching { callback?.onHashcatKernelChanged() } } },
        onError = onError,
    )
    private var exiting = false
    private val memorySampler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "hashcat-memory").apply { isDaemon = true }
    }
    @Volatile private var latestMemory: HashcatMemorySnapshot? = null

    init {
        memorySampler.scheduleWithFixedDelay({
            val cb = callback
            if (cb != null && cb.asBinder().isBinderAlive) {
                latestMemory = readMemory()
                runCatching { cb.onHashcatMemoryChanged() }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun memory(): HashcatMemorySnapshot = latestMemory?.takeIf {
        System.currentTimeMillis() - it.capturedAt in 0..1500
    } ?: readMemory().also { latestMemory = it }
    private fun readMemory(): HashcatMemorySnapshot {
        val tasks = synchronized(lock) { records.values.filter { it.snapshot.active }.map { record ->
            val (pid, running) = record.controller.memoryProcess()
            HashcatMemoryReader.Task(record.snapshot.id, pid, record.snapshot.memoryLimitMiB, running)
        } }
        return HashcatMemoryReader.read(tasks)
    }

    fun activeCount(): Int = synchronized(lock) { records.values.count { it.snapshot.active } }
    fun kernelStatus(hash: String) = kernelCompiler.snapshot(hash)
    fun compileKernels(hash: String, program: ParcelFileDescriptor) {
        synchronized(lock) { check(!exiting) { "服务正在退出" }; kernelCompiler.start(hash, program) }
    }
    fun ids(offset: Int): Array<String> = synchronized(lock) {
        require(offset >= 0)
        records.keys.drop(offset).take(64).toTypedArray()
    }
    fun snapshot(id: String): HashcatTaskSnapshot = synchronized(lock) { requireRecord(id).snapshot }
    private fun requireRecord(id: String) = records[id] ?: error("Hashcat 任务不存在")

    fun register(cb: IHashcatCallback) {
        callback = cb
        synchronized(lock) { records.values.toList() }.forEach { record ->
            record.completion.thenRunAsync({
                synchronized(record.operationLock) {
                    if (!record.backedUp) runCatching { backup(record) }.onFailure { onError("保存 Hashcat 历史", it) }
                }
            }, workers)
        }
    }
    fun unregister(cb: IHashcatCallback) { if (callback?.asBinder() == cb.asBinder()) callback = null }

    fun start(id: String, input: ParcelFileDescriptor, resume: Boolean, initialRevision: Long = 0, createdAt: Long = System.currentTimeMillis(), memoryLimitMiB: Int? = null) {
        require(UUID.fromString(id).toString() == id) { "Hashcat 任务 ID 无效" }
        synchronized(lock) { check(!exiting) { "服务正在退出" } }
        val existing = synchronized(lock) { records[id] }
        check(existing == null || (resume && existing.snapshot.state == HashcatTaskState.PAUSED && existing.completion.isDone && existing.backedUp)) { "Hashcat 任务尚未完成暂停" }
        val directory = File(HashcatController.runtimeBaseDirectory(), "hashcat-$id")
        val record = Record((existing?.snapshot ?: HashcatTaskSnapshot(id, createdAt)).copy(
            state = HashcatTaskState.RUNNING, step = "接收运行文件", error = null,
            revision = maxOf(existing?.snapshot?.revision ?: 0, initialRevision) + 1,
            memoryLimitMiB = memoryLimitMiB,
        ), directory)
        val ownedInput = ParcelFileDescriptor.dup(input.fileDescriptor)
        try { synchronized(lock) {
            check(!exiting && records[id] === existing) { "Hashcat 任务启动状态已改变" }
            memoryLimitMiB?.let { budget ->
                require(budget > 0) { "内存预算必须大于 0 MiB" }
                val measured = readMemory()
                check(measured.error == null && measured.tasks.all { it.pssBytes != null }) { "无法读取实时内存，暂不能分配预算" }
                require(budget.toLong() * HASHCAT_MIB <= measured.assignableBytes(id)) { "内存预算超过当前可分配内存" }
            }
            records[id] = record
        } } catch (error: Throwable) { ownedInput.close(); throw error }
        notify(record, true)
        workers.execute { execute(record, resume, ownedInput) }
    }

    fun pause(id: String): Boolean {
        val record = synchronized(lock) { requireRecord(id) }
        synchronized(record.operationLock) {
            requestPause(record)
            notify(record, true)
            record.completion.get()
            if (!record.backedUp) backup(record)
            return record.backedUp
        }
    }

    fun prepareShutdown(): Boolean {
        val all = synchronized(lock) { exiting = true; records.values.toList() }
        all.forEach(::requestPause)
        return try {
            kernelCompiler.awaitCompletion()
            all.map { pause(it.snapshot.id) }.all { it }.also { if (!it) synchronized(lock) { exiting = false } }
        } catch (error: Throwable) {
            synchronized(lock) { exiting = false }
            onError("暂停 Hashcat 并保存历史", error)
            false
        }
    }

    private fun requestPause(record: Record) {
        synchronized(lock) {
            if (record.snapshot.active && record.endState == null) {
                record.snapshot = record.snapshot.copy(state = HashcatTaskState.PAUSING, step = "正在保存恢复点", revision = record.snapshot.revision + 1)
                record.controller.checkpointAndStop()
            }
        }
        notify(record, true)
    }

    private fun execute(record: Record, resume: Boolean, input: ParcelFileDescriptor) {
        val dir = record.directory
        try {
            val incoming = dir
            input.use { fd ->
                HashcatFiles.unpack(ParcelFileDescriptor.AutoCloseInputStream(fd), incoming) { name, copied, total ->
                    change(record) { copy(step = "接收 $name", stepCompleted = copied, stepTotal = total, stepUnit = "BYTES") }
                }
            }
            val saved = HashcatTaskSnapshot.fromJson(JSONObject(File(incoming, "snapshot.json").readText()))
            require(saved.id == record.snapshot.id)
            change(record) { copy(createdAt = saved.createdAt, dictionaryNames = saved.dictionaryNames, findings = saved.findings,
                computeDurationMillis = saved.computeDurationMillis, averageSpeed = saved.averageSpeed,
                revision = maxOf(revision, saved.revision)) }
            val previousDuration = saved.computeDurationMillis
            val result = record.controller.run(HashcatRequest(
                executable = File(incoming, "libhashcat.so"), handshakeFile = File(incoming, "handshake.hc22000"),
                dictionaryFile = File(incoming, "dictionary.txt"), runtimeDirectory = dir,
                restore = resume && File(dir, "session.restore").isFile,
                deviceMemoryLimitMiB = record.snapshot.memoryLimitMiB,
            )) { event ->
                when (event) {
                    is HashcatEvent.Output -> File(dir, "run.log").appendText(event.text)
                    is HashcatEvent.StepProgress -> change(record) { copy(
                        step = when (event.step) {
                            HashcatStep.COPYING_PROGRAM -> "复制 Hashcat 程序"
                            HashcatStep.COPYING_HANDSHAKE -> "复制握手记录"
                            HashcatStep.COPYING_DICTIONARY -> "复制组合字典"
                            HashcatStep.CLEANING_DICTIONARY -> "清理字典"
                            HashcatStep.CLEANING_PROGRAM -> "清理程序"
                            HashcatStep.CLEANING_RUNTIME -> "清理运行目录"
                        }, stepCompleted = event.completed, stepTotal = event.total, stepUnit = event.unit.name,
                    ) }
                    is HashcatEvent.Phase -> change(record) { copy(step = when (event.phase) {
                        HashcatPhase.LOADING_BACKEND -> "加载计算后端"
                        HashcatPhase.LOADING_DEVICES -> "识别计算设备"
                        HashcatPhase.LOADING_BRIDGES -> "加载计算桥接"
                        HashcatPhase.READING_HANDSHAKES -> "读取握手记录"
                        HashcatPhase.BUILDING_KERNELS -> "编译计算内核"
                        HashcatPhase.BUILDING_DICTIONARY_INDEX -> "建立字典索引"
                        HashcatPhase.DICTIONARY_INDEX_READY -> "字典索引完成"
                        HashcatPhase.SELF_TEST -> "计算设备自检"
                        HashcatPhase.AUTOTUNE -> "自动调优"
                        HashcatPhase.RUNNING -> "正在评估密码"
                    }, stepCompleted = ((event.percent ?: 0.0) * 100).toLong(), stepTotal = if (event.percent == null) 0 else 10000, stepUnit = "PERCENT") }
                    is HashcatEvent.Status -> change(record) { copy(
                        step = if (event.snapshot.status == HashcatStatus.RUNNING && state == HashcatTaskState.RUNNING) "正在评估密码" else step,
                        completed = event.snapshot.progressCompleted, total = event.snapshot.progressTotal,
                        progressUnit = "CANDIDATES", remainingSeconds = event.snapshot.remainingSeconds,
                        speed = event.snapshot.devices.sumOf { it.hashesPerSecond },
                        devices = event.snapshot.devices.joinToString { it.name },
                        candidates = event.snapshot.devices.mapNotNull { it.candidateRange }.joinToString("\n"),
                        stepCompleted = if (state == HashcatTaskState.RUNNING) event.snapshot.devices.sumOf { it.pbkdf2Completed } else stepCompleted,
                        stepTotal = if (state == HashcatTaskState.RUNNING) event.snapshot.devices.sumOf { it.pbkdf2Total } else stepTotal,
                        stepUnit = if (state == HashcatTaskState.RUNNING) "PBKDF2" else stepUnit,
                        computeDurationMillis = previousDuration + event.snapshot.runningMillis,
                        averageSpeed = if (previousDuration + event.snapshot.runningMillis > 0)
                            ((event.snapshot.progressCompleted - event.snapshot.rejectedCandidates).coerceAtLeast(0).toDouble() * 1000 /
                                (previousDuration + event.snapshot.runningMillis)).toLong() else 0,
                    ) }
                    is HashcatEvent.ParseError -> File(dir, "run.log").appendText("\n解析错误：${event.message}\n")
                    is HashcatEvent.KernelStep, HashcatEvent.KernelReady -> Unit
                }
            }
            val state = when {
                result.exitCode == 10 && snapshot(record.snapshot.id).state == HashcatTaskState.PAUSING -> HashcatTaskState.PAUSED
                result.passwords.isNotEmpty() -> HashcatTaskState.SUCCEEDED
                result.exitCode == 1 -> HashcatTaskState.EXHAUSTED
                File(dir, "session.restore").isFile && snapshot(record.snapshot.id).state == HashcatTaskState.PAUSING -> HashcatTaskState.PAUSED
                else -> HashcatTaskState.FAILED
            }
            record.endState = state
            change(record) { copy(
                state = if (state == HashcatTaskState.PAUSED) HashcatTaskState.PAUSING else state,
                step = when (state) {
                    HashcatTaskState.SUCCEEDED -> "已成功发现密码"
                    HashcatTaskState.EXHAUSTED -> "给定字典未发现密码"
                    HashcatTaskState.PAUSED -> "已暂停"
                    else -> "运行失败（退出码 ${result.exitCode}）"
                },
                findings = if (result.passwords.isEmpty() && state == HashcatTaskState.PAUSED) findings else result.passwords.distinct().map {
                    HashcatFinding(it, requireNotNull(result.dictionaryPositions[it]) { "发现的密码未能定位到字典位置" })
                },
                error = if (state == HashcatTaskState.FAILED) File(dir, "run.log").takeIf { it.isFile }?.let { tail(it) } else null,
            ) }
            if (state == HashcatTaskState.FAILED) onError("WPA Hashcat 运行", IllegalStateException(record.snapshot.error ?: record.snapshot.step))
        } catch (error: Throwable) {
            record.endState = HashcatTaskState.FAILED
            change(record) { copy(state = HashcatTaskState.FAILED, step = "运行失败", error = error.message) }
            onError("WPA Hashcat 运行", error)
        } finally {
            runCatching { input.close() }
            try { backup(record) }
            catch (error: Throwable) { onError("保存 Hashcat 任务", error) }
            finally { record.completion.complete(Unit); notify(record, true) }
        }
    }

    private fun backup(record: Record) {
        val cb = callback ?: return
        if (!cb.asBinder().isBinderAlive) return
        change(record) { copy(step = "备份任务数据") }
        val saved = snapshot(record.snapshot.id).copy(state = record.endState ?: record.snapshot.state)
        val jsonFile = File(record.directory, "snapshot.json")
        jsonFile.writeText(saved.toJson().toString())
        val names = listOf("handshake.hc22000", "dictionary.txt", "session.restore", "result.txt", "snapshot.json", "run.log")
        HashcatFiles.pipe { out -> HashcatFiles.pack(out, names.associateWith { File(record.directory, it) }) }.use { fd ->
            check(cb.saveHashcatBackup(saved.id, fd)) { "App 未确认 Hashcat 备份保存成功" }
        }
        record.backedUp = true
        val files = record.directory.walkBottomUp().toList()
        change(record) { copy(state = saved.state, step = "清理运行文件", stepCompleted = 0, stepTotal = files.size.toLong(), stepUnit = "FILES") }
        files.forEachIndexed { index, file ->
            check(file.delete()) { "无法清理 Hashcat 运行文件：$file" }
            change(record) { copy(step = when (file.name) { "dictionary.txt" -> "清理字典"; "libhashcat.so" -> "清理程序"; else -> "清理运行目录" }, stepCompleted = index + 1L) }
        }
        change(record) { copy(state = saved.state) }
        change(record) { copy(stepCompleted = 0, stepTotal = 0, stepUnit = "", step = when (state) {
            HashcatTaskState.PAUSED -> "已暂停"
            HashcatTaskState.SUCCEEDED -> "已成功发现密码"
            HashcatTaskState.EXHAUSTED -> "给定字典未发现密码"
            else -> "运行失败"
        }) }
    }

    private fun change(record: Record, update: HashcatTaskSnapshot.() -> HashcatTaskSnapshot) {
        synchronized(lock) {
            val next = record.snapshot.update()
            record.snapshot = next.copy(revision = maxOf(record.snapshot.revision, next.revision) + 1)
        }
        notify(record, false)
    }
    private fun notify(record: Record, force: Boolean) {
        synchronized(lock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (record.notifyPending) return
            record.notifyPending = true
            val delay = if (force) 0 else (50 - (now - record.lastNotify)).coerceAtLeast(0)
            callbacks.schedule({
                synchronized(lock) {
                    record.notifyPending = false
                    record.lastNotify = android.os.SystemClock.elapsedRealtime()
                }
                runCatching { callback?.onHashcatChanged(record.snapshot.id) }
                onActivityChanged()
            }, delay, TimeUnit.MILLISECONDS)
        }
    }
    private fun tail(file: File): String = java.io.RandomAccessFile(file, "r").use {
        it.seek((it.length() - 8192).coerceAtLeast(0))
        ByteArray((it.length() - it.filePointer).toInt()).also(it::readFully).toString(Charsets.UTF_8)
    }
}
