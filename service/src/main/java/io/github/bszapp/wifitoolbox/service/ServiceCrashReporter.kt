package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/** 服务进程在未捕获异常退出前，将异常和服务侧日志直接落盘供 App 回收。 */
internal object ServiceCrashReporter {
    private val lock = Any()
    private val handling = AtomicBoolean(false)

    @Volatile
    private var reportBasePath: String? = null

    @Volatile
    private var writeLogs: (ZipOutputStream) -> Unit = {}

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var installed = false

    fun install(
        path: String?,
        logsWriter: (ZipOutputStream) -> Unit,
    ) {
        synchronized(lock) {
            if (!path.isNullOrBlank() && path != reportBasePath) {
                reportBasePath = path
                File("$path.txt").delete()
                File("$path.zip").delete()
                File("$path.zip.tmp").delete()
            }
            writeLogs = logsWriter
            if (installed) return
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(::handleUncaughtException)
            installed = true
        }
    }

    private fun handleUncaughtException(thread: Thread, error: Throwable) {
        if (!handling.compareAndSet(false, true)) {
            return
        }

        try {
            val report = formatReport(thread, error)
            val path = reportBasePath
            if (path.isNullOrBlank()) {
                Log.e(TAG, "未设置服务崩溃报告路径；异常由服务进程直接回传失败", error)
            } else {
                runCatching { saveText(File("$path.txt"), report) }
                    .onFailure { Log.e(TAG, "保存服务异常报告失败", it) }
                runCatching { saveZip(File("$path.zip"), report) }
                    .onFailure { Log.e(TAG, "打包服务崩溃日志失败", it) }
            }
        } finally {
            val handler = previousHandler
            if (handler != null) {
                handler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun formatReport(thread: Thread, error: Throwable): String = buildString {
        appendLine("服务未捕获异常")
        appendLine("pid=${Process.myPid()}")
        appendLine("thread=${thread.name} (id=${thread.id})")
        appendLine("exceptionType=${error.javaClass.name}")
        appendLine("exceptionMessageBase64=${encode(error.message.orEmpty())}")
        appendLine("stackTraceBase64=${encode(error.stackTraceToString())}")
        appendLine()
        appendLine("异常类型：${error.javaClass.name}")
        appendLine("异常消息：${error.message ?: "<无>"}")
        appendLine("线程：${thread.name} (id=${thread.id})")
        appendLine("PID：${Process.myPid()}")
        appendLine("调用栈：")
        append(error.stackTraceToString())
    }

    private fun saveText(file: File, text: String) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "无法创建服务崩溃报告目录"
        }
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun saveZip(file: File, report: String) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "无法创建服务崩溃日志目录"
        }
        val temporary = File("${file.path}.tmp")
        temporary.delete()
        ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
            zip.writeTextEntry("服务未捕获异常.log", report)
            runCatching { writeLogs(zip) }.onFailure { error ->
                runCatching {
                    zip.writeTextEntry("日志打包错误.log", error.stackTraceToString())
                }
            }
        }
        check(!file.exists() || file.delete()) { "无法替换旧的服务崩溃日志 ZIP" }
        check(temporary.renameTo(file)) { "无法提交服务崩溃日志 ZIP" }
    }

    private fun ZipOutputStream.writeTextEntry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private const val TAG = "ServiceCrashReporter"
}
