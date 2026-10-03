package io.github.bszapp.wifitoolbox.service.hashcat

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import io.github.bszapp.wifitoolbox.contract.hashcat.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** 独立准备过程，不创建字典评估任务；完成记录与可复用的内核缓存一起由服务持有。 */
internal class HashcatKernelCompiler(
    private val onChanged: () -> Unit,
    private val onError: (String, Throwable) -> Unit,
) {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "hashcat-kernel-compiler").apply { isDaemon = true } }
    private var current = HashcatKernelSnapshot()
    private var programHash = ""
    private var completion = CompletableFuture.completedFuture(Unit)
    private val cache get() = HashcatController.kernelCacheDirectory()
    private val marker get() = AtomicFile(File(cache, "compiled-kernels.json"))

    fun snapshot(hash: String): HashcatKernelSnapshot = synchronized(lock) {
        if (hash == programHash && current.state in listOf(HashcatKernelState.COMPILING, HashcatKernelState.FAILED)) return current
        if (valid(hash)) return if (programHash == hash && current.state == HashcatKernelState.READY) current
            else HashcatKernelSnapshot(HashcatKernelState.READY, "内核编译完成", revision = current.revision)
        HashcatKernelSnapshot()
    }

    fun start(hash: String, input: ParcelFileDescriptor) {
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Hashcat 程序摘要无效" }
        synchronized(lock) {
            if (!completion.isDone) {
                require(programHash == hash) { "另一版本的内核正在编译" }
                return
            }
            if (valid(hash)) return
            val owned = ParcelFileDescriptor.dup(input.fileDescriptor)
            programHash = hash
            current = HashcatKernelSnapshot(HashcatKernelState.COMPILING, "接收 Hashcat 程序", revision = current.revision + 1)
            completion = CompletableFuture()
            worker.execute { compile(hash, owned) }
        }
        onChanged()
    }

    fun awaitCompletion() { synchronized(lock) { completion }.get() }

    private fun compile(hash: String, input: ParcelFileDescriptor) {
        val runtime = File(HashcatController.runtimeBaseDirectory(), "hashcat-compile-${UUID.randomUUID()}")
        val caches = linkedSetOf<File>()
        var ready = false
        try {
            check(runtime.mkdirs()) { "无法创建内核编译目录" }
            val executable = File(runtime, "libhashcat.so")
            ParcelFileDescriptor.AutoCloseInputStream(input).use { source ->
                executable.outputStream().use { source.copyTo(it, 64 * 1024) }
            }
            check(digest(executable) == hash) { "Hashcat 程序传输校验失败" }
            val handshake = File(runtime, "handshake.hc22000").apply { writeText(SELF_TEST_HASH + "\n") }
            val dictionary = File(runtime, "dictionary.txt").apply { writeText("hashcat!\n") }
            val log = File(runtime, "run.log")
            val result = HashcatController().use { controller ->
                controller.run(HashcatRequest(executable, handshake, dictionary,
                    deviceMemoryLimitMiB = 1024, runtimeDirectory = runtime, precompile = true)) { event ->
                    when (event) {
                        is HashcatEvent.Output -> log.appendText(event.text)
                        is HashcatEvent.KernelStep -> {
                            val now = System.currentTimeMillis()
                            val id = "${event.device}:${event.kind}:${event.name}"
                            val prefix = when (event.kind) {
                                "PROGRAM" -> "编译程序"
                                "KERNEL" -> "创建内核"
                                "CACHE" -> "保存编译缓存"
                                else -> "计算设备自检"
                            }
                            val label = "${if (event.device > 0) "设备${event.device} · " else ""}$prefix · ${File(event.name).name}"
                            change {
                                val steps = steps.toMutableList()
                                val index = steps.indexOfFirst { it.id == id }
                                val previous = steps.getOrNull(index) ?: HashcatKernelStep(id, label, now)
                                val next = previous.copy(finishedAt = now.takeIf { event.finished })
                                if (index >= 0) steps[index] = next else steps.add(next)
                                copy(stage = label, steps = steps)
                            }
                            if (event.kind == "CACHE" && event.finished) {
                                val file = File(event.name).canonicalFile
                                require(file.parentFile == File(cache, "kernels").canonicalFile) { "内核缓存路径无效" }
                                caches.add(file)
                            }
                        }
                        HashcatEvent.KernelReady -> ready = true
                        is HashcatEvent.Phase -> if (event.phase == HashcatPhase.LOADING_DEVICES) change { copy(stage = "识别当前设备") }
                        is HashcatEvent.ParseError -> error("内核进度解析失败：${event.message}")
                        else -> Unit
                    }
                }
            }
            check(ready && result.exitCode in 0..1 && caches.size >= 3) {
                "内核编译未完成（退出码 ${result.exitCode}）\n${log.takeIf { it.isFile }?.readText()?.takeLast(8192).orEmpty()}"
            }
            check(current.steps.all { it.finishedAt != null }) { "存在未完成的内核编译步骤" }
            val record = JSONObject().put("programHash", hash).put("environment", environment())
                .put("files", JSONArray().apply { caches.forEach { file ->
                    check(file.isFile && file.length() > 0) { "编译缓存未保存" }
                    put(JSONObject().put("name", file.name).put("sha256", digest(file)))
                } })
            val out = marker.startWrite()
            try { out.write(record.toString().toByteArray()); marker.finishWrite(out) }
            catch (error: Throwable) { marker.failWrite(out); throw error }
        } catch (error: Throwable) {
            change { copy(state = HashcatKernelState.FAILED, stage = "内核编译失败", error = error.message) }
            onError("编译 Hashcat 内核", error)
        } finally {
            runCatching { input.close() }
            val cleanup = runCatching {
                if (runtime.exists()) runtime.walkBottomUp().forEach { check(it.delete()) { "无法清理编译临时文件：$it" } }
            }
            cleanup.onFailure { error ->
                change { copy(state = HashcatKernelState.FAILED, stage = "清理编译临时文件失败", error = error.message) }
                onError("清理 Hashcat 编译文件", error)
            }
            if (current.state == HashcatKernelState.COMPILING) change { copy(state = HashcatKernelState.READY, stage = "内核编译完成") }
            synchronized(lock) { completion }.complete(Unit)
        }
    }

    private fun change(update: HashcatKernelSnapshot.() -> HashcatKernelSnapshot) {
        synchronized(lock) { current = current.update().copy(revision = current.revision + 1) }
        onChanged()
    }

    private fun valid(hash: String): Boolean = runCatching {
        val record = marker.openRead().bufferedReader().use { JSONObject(it.readText()) }
        if (record.getString("programHash") != hash || record.getString("environment") != environment()) return false
        val files = record.getJSONArray("files")
        files.length() >= 3 && (0 until files.length()).all { index ->
            val entry = files.getJSONObject(index)
            val file = File(File(cache, "kernels"), entry.getString("name"))
            file.parentFile?.canonicalFile == File(cache, "kernels").canonicalFile &&
                file.isFile && file.length() > 0 && digest(file) == entry.getString("sha256")
        }
    }.getOrDefault(false)

    private fun environment(): String = Build.FINGERPRINT + ":" +
        listOf("/vendor/lib64/libOpenCL.so", "/vendor/lib/libOpenCL.so").joinToString { path ->
            File(path).let { "$path:${it.length()}:${it.lastModified()}" }
        }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        // The pinned upstream module's public self-test fixture, independent of user captures.
        private const val SELF_TEST_HASH = "WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4*686173686361742d6573736964***"
    }
}
