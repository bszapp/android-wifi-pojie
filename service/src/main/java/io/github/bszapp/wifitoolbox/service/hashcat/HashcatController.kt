package io.github.bszapp.wifitoolbox.service.hashcat

import android.util.Log
import android.os.Process as AndroidProcess
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 服务持有的原生进程控制器。运行同步阻塞，调用者使用自己的任务线程管理生命周期。 */
class HashcatController : AutoCloseable {
    private class Run {
        var process: Process? = null
        var stopRequested = false
        var checkpointSent = false
        var readyForCheckpoint = false
        var preparationCheckpoint = false
    }

    private val lock = Any()
    private var current: Run? = null
    private var checkpointRequested = false

    @WorkerThread
    fun run(request: HashcatRequest, onEvent: (HashcatEvent) -> Unit): HashcatResult {
        val run = synchronized(lock) {
            check(current == null) { "Hashcat 正在运行" }
            Run().also { current = it }
        }
        var runDirectory: File? = null
        try {
            val sourceExecutable = request.executable.canonicalFile
            require(sourceExecutable.isFile && sourceExecutable.canRead()) { "Hashcat 库不可读：$sourceExecutable" }
            require(request.handshakeFile.isFile && request.handshakeFile.canRead()) { "握手文件不可读" }
            require(request.dictionaryFile.isFile && request.dictionaryFile.canRead()) { "字典文件不可读" }
            require(request.deviceMemoryLimitMiB == null || request.deviceMemoryLimitMiB > 0) {
                "deviceMemoryLimitMiB 必须大于 0"
            }
            val driver = request.openClLibrary?.canonicalFile
            require(driver == null || (driver.isFile && driver.canRead())) { "OpenCL 驱动不可读：$driver" }
            val session = request.runtimeDirectory?.name ?: "hashcat-${UUID.randomUUID()}"
            val runtime = request.runtimeDirectory ?: File(runtimeBaseDirectory(), session)
            check(runtime.isDirectory || runtime.mkdir()) { "无法创建 Hashcat 运行目录：$runtime，uid=${AndroidProcess.myUid()}" }
            runDirectory = runtime
            val executable = copyFile(sourceExecutable, File(runtime, "libhashcat.so"), HashcatStep.COPYING_PROGRAM, onEvent)
            check(executable.setExecutable(true, false)) { "无法设置 Hashcat 执行权限：$executable" }
            val handshake = copyFile(request.handshakeFile, File(runtime, "handshake.hc22000"), HashcatStep.COPYING_HANDSHAKE, onEvent)
            val dictionary = copyFile(request.dictionaryFile, File(runtime, "dictionary.txt"), HashcatStep.COPYING_DICTIONARY, onEvent)
            val output = File(runtime, "result.txt")
            val restoreFile = File(runtime, "session.restore")
            if (request.restore) relocateRestore(restoreFile, runtime)
            // Only rebuildable compute kernels are shared. Task inputs/results remain in the UUID directory.
            val cache = kernelCacheDirectory()
            check(cache.isDirectory || cache.mkdirs()) { "无法创建 Hashcat 内核缓存：$cache" }
            val command = mutableListOf(
                executable.path, "-m", "22000", "-a", "0", "-D", "2",
                "--status", "--status-json", "--status-timer=1",
                "--cache-path=${cache.path}",
                "--potfile-disable", "--session=$session",
                "--outfile=${output.path}", "--outfile-format=3",
            )
            if (request.runtimeDirectory == null) command.add("--restore-disable") else {
                command.add("--restore-file-path=${restoreFile.path}")
                if (request.restore) command.add("--restore-position")
            }
            command.addAll(listOf(handshake.path, dictionary.path))
            if (request.runtimeDirectory != null && synchronized(lock) { checkpointRequested }) {
                return HashcatResult(10, emptyList(), null, false)
            }
            fun report(message: String) {
                Log.i(TAG, message)
                onEvent(HashcatEvent.Output("$message\n"))
            }
            val parser = HashcatOutputParser(onEvent = { event ->
                if ((event is HashcatEvent.Phase && event.phase == HashcatPhase.RUNNING) ||
                    (event is HashcatEvent.Status && event.snapshot.status == HashcatStatus.RUNNING)) {
                    synchronized(lock) {
                        run.readyForCheckpoint = true
                        if (checkpointRequested && !run.checkpointSent && sendKey('c')) run.checkpointSent = true
                    }
                }
                onEvent(event)
                if (event is HashcatEvent.Status) {
                    val snapshot = event.snapshot
                    snapshot.devices.forEach { device ->
                        report("当前尝试密码批次：${device.candidateRange ?: "等待候选数据"}；" +
                            "进度：${snapshot.progressCompleted}/${snapshot.progressTotal} " +
                            "(${snapshot.progressPercent?.let { percent -> "%.2f".format(java.util.Locale.ROOT, percent) } ?: "未知"}%)；" +
                            "GPU：${device.name}；速度：${device.hashesPerSecond} H/s；剩余：${snapshot.remainingSeconds ?: "未知"}秒")
                    }
                }
            })
            val process = synchronized(lock) {
                if (run.stopRequested) return HashcatResult(-1, emptyList(), null, true)
                if (request.runtimeDirectory != null && checkpointRequested) return HashcatResult(10, emptyList(), null, false)
                Log.i(TAG, "启动 Hashcat：session=$session runtime=$runtime uid=${AndroidProcess.myUid()} GPU-only=true deviceMemoryLimitMiB=${request.deviceMemoryLimitMiB} driver=$driver")
                ProcessBuilder(command).directory(runtime).redirectErrorStream(true).apply {
                    environment()["HASHCAT_HOME"] = runtime.path
                    environment()["HASHCAT_RESOURCE_HOME"] = cache.path
                    environment()["HASHCAT_STATUS_INTERVAL_MS"] = "100"
                    environment()["HASHCAT_FAST_CHECKPOINT"] = "1"
                    if (request.precompile) environment()["HASHCAT_PRECOMPILE"] = "1"
                    request.deviceMemoryLimitMiB?.let {
                        environment()["HASHCAT_DEVICE_MEM_LIMIT"] = it.toString()
                    }
                    driver?.let { environment()["HASHCAT_OPENCL_LIBRARY"] = it.path }
                }.start().also { run.process = it }
            }
            try {
                process.inputStream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        parser.consume(String(buffer, 0, count))
                    }
                }
            } catch (error: IOException) {
                // Android destroy() closes the reader on the pause caller's thread.
                if (!synchronized(lock) { run.preparationCheckpoint }) throw error
            }
            parser.finish()
            val nativeExitCode = process.waitFor()
            val passwords = if (output.isFile) output.readLines(Charsets.UTF_8)
                .filter(String::isNotEmpty).map(::decodeHexPassword) else emptyList()
            val firstLines = mutableMapOf<String, Long>()
            if (passwords.isNotEmpty()) {
                val wantedPasswords = passwords.toHashSet()
                var totalLines = 0L
                dictionary.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        totalLines++
                        val candidate = if (line.startsWith("\$HEX[") && line.endsWith("]")) {
                            decodeHexPassword(line.substring(5, line.length - 1))
                        } else line
                        if (candidate in wantedPasswords && candidate !in firstLines) firstLines[candidate] = totalLines
                    }
                }
                passwords.forEach { password ->
                    report("发现密码：$password；字典位置：第${firstLines[password] ?: "未知"}行/$totalLines")
                }
            }
            val stopped = synchronized(lock) { run.stopRequested }
            val exitCode = if (nativeExitCode != 0 && nativeExitCode != 1 && synchronized(lock) { run.preparationCheckpoint }) 10 else nativeExitCode
            Log.i(TAG, "Hashcat 已退出：session=$session exitCode=$exitCode nativeExitCode=$nativeExitCode recovered=${passwords.size} stopRequested=$stopped")
            return HashcatResult(exitCode, passwords, parser.lastStatus, stopped, firstLines)
        } finally {
            try {
                val process = synchronized(lock) { run.process }
                process?.let {
                    runCatching { it.outputStream.close() }
                    it.destroy()
                    var interrupted = false
                    while (true) {
                        try {
                            it.waitFor()
                            break
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }
                // Only this internally created UUID directory is removed. Input originals remain untouched.
                runDirectory?.takeIf { request.runtimeDirectory == null }?.let { directory ->
                    val cleanupEvent: (HashcatEvent) -> Unit = { event ->
                        runCatching { onEvent(event) }.onFailure { error ->
                            Log.e(TAG, "清理进度回调失败", error)
                        }
                    }
                    cleanFiles(listOf(File(directory, "dictionary.txt")), HashcatStep.CLEANING_DICTIONARY, cleanupEvent)
                    cleanFiles(listOf(File(directory, "libhashcat.so")), HashcatStep.CLEANING_PROGRAM, cleanupEvent)
                    cleanFiles(directory.walkBottomUp().toList(), HashcatStep.CLEANING_RUNTIME, cleanupEvent)
                }
            } finally {
                synchronized(lock) { if (current === run) current = null }
            }
        }
    }

    /** 单字符控制直接写入 hashcat 的 stdin，无 shell、PTY 或容器转发。 */
    fun requestStatus(): Boolean = sendKey('s')
    fun pause(): Boolean = sendKey('p')
    fun resume(): Boolean = sendKey('r')

    /** 计算中在内核分段边界保存恢复点；准备阶段保留已有恢复点并结束子进程。 */
    fun checkpointAndStop() = synchronized(lock) {
        checkpointRequested = true
        current?.let { run ->
            if (run.readyForCheckpoint) {
                if (!run.checkpointSent && sendKey('c')) run.checkpointSent = true
            } else {
                run.preparationCheckpoint = true
                run.process?.destroy()
            }
        }
    }

    fun stop() = synchronized(lock) {
        current?.let { run ->
            run.stopRequested = true
            run.process?.destroy()
        }
        Unit
    }

    override fun close() = stop()

    /** Android UNIXProcess 的 PID；只用于服务读取自己启动的子进程内存。 */
    internal fun memoryProcess(): Pair<Int?, Boolean> = synchronized(lock) {
        val process = current?.process ?: return@synchronized null to false
        val running = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        if (!running) return@synchronized null to false
        val pid = runCatching {
            process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
        }.getOrNull()
        pid to true
    }

    private fun sendKey(key: Char): Boolean = synchronized(lock) {
        val process = current?.process ?: return false
        runCatching {
            process.outputStream.write(key.code)
            process.outputStream.flush()
        }.isSuccess
    }

    private fun decodeHexPassword(hex: String): String {
        require(hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }) {
            "Hashcat 结果包含无效的 hex_plain 数据"
        }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }.toString(Charsets.UTF_8)
    }

    private fun copyFile(source: File, target: File, step: HashcatStep, onEvent: (HashcatEvent) -> Unit): File {
        val total = source.length()
        var copied = 0L
        fun publish(finished: Boolean) = onEvent(
            HashcatEvent.StepProgress(step, copied, total, HashcatProgressUnit.BYTES, finished, target.path),
        )
        publish(false)
        if (source.canonicalFile == target.canonicalFile) {
            copied = total
            publish(true)
            return target
        }
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    publish(false)
                }
            }
        }
        publish(true)
        return target
    }

    private fun cleanFiles(files: List<File>, step: HashcatStep, onEvent: (HashcatEvent) -> Unit) {
        var completed = 0L
        val existing = files.filter { it.exists() }
        fun publish(finished: Boolean, path: String) = onEvent(
            HashcatEvent.StepProgress(step, completed, existing.size.toLong(), HashcatProgressUnit.FILES, finished, path),
        )
        publish(false, existing.firstOrNull()?.path.orEmpty())
        existing.forEach { file ->
            if (file.delete()) {
                completed++
                publish(false, file.path)
            } else {
                Log.e(TAG, "Hashcat 清理失败：$file")
                onEvent(HashcatEvent.Output("Hashcat cleanup failed: $file\n"))
            }
        }
        publish(completed == existing.size.toLong(), existing.lastOrNull()?.path.orEmpty())
    }

    companion object {
        private const val TAG = "HashcatController"

        internal fun kernelCacheDirectory() = File(runtimeBaseDirectory(), "hashcat-cache-${AndroidProcess.myUid()}")

        /** system 使用 Android 系统应用的缓存，shell/root 使用 Android 临时目录。 */
        internal fun runtimeBaseDirectory(): File = if (AndroidProcess.myUid() == 1000) {
            File("/data/user/0/android/cache")
        } else {
            File("/data/local/tmp")
        }
    }

    /** 固定上游 restore_data_t 的 version + cwd[256]；只重定位工作目录，参数由 Kotlin 重建。 */
    private fun relocateRestore(file: File, runtime: File) {
        val bytes = file.readBytes()
        require(bytes.size >= 260 && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int == 720) {
            "恢复文件版本与当前 Hashcat 不兼容"
        }
        val path = runtime.canonicalPath.toByteArray(Charsets.UTF_8)
        require(path.size < 256) { "Hashcat 恢复目录过长" }
        bytes.fill(0, 4, 260)
        path.copyInto(bytes, 4)
        file.outputStream().use { it.write(bytes); it.fd.sync() }
    }
}
