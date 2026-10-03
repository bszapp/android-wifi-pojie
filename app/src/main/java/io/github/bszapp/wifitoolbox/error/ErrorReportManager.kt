package io.github.bszapp.wifitoolbox.error

import android.app.AlarmManager
import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import io.github.bszapp.wifitoolbox.MainActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Owns only the App process. Cached reports are opened only by an explicit report Intent. */
class ErrorReportManager(private val application: Application) {
    private val handling = AtomicBoolean(false)
    private val reportDirectory = File(application.cacheDir, "error_reports")

    fun install(captureLogs: (File) -> Unit, onFatalError: () -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (!handling.compareAndSet(false, true)) return@setDefaultUncaughtExceptionHandler
            try {
                val report = AppErrorFormatter.format(
                    source = "App.GlobalExceptionHandler",
                    operation = "应用进程未捕获异常",
                    error = error,
                    thread = thread,
                )
                val file = save(report)
                // Keep the fatal error in the same App logcat source as all preceding
                // App logs. Chunking avoids logcat truncating a long report message.
                report.fullText.chunked(3_000).forEach { part -> Log.e(TAG, part) }
                runCatching { captureLogs(capturedLogFile(file)) }
                    .onFailure { Log.e(TAG, "保存已捕获的应用日志失败", it) }
                runCatching(onFatalError)
                Log.e(TAG, "应用错误报告已保存：${file.name}", error)
                restartIntoReport(file)
            } catch (reportError: Throwable) {
                Log.e(TAG, "无法启动错误报告界面", reportError)
                previous?.uncaughtException(thread, error)
            } finally {
                // The privileged service is a separate process and receives no stop request.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun save(report: ErrorReport): File {
        check(reportDirectory.isDirectory || reportDirectory.mkdirs()) {
            "无法创建错误报告缓存目录"
        }
        val file = File(reportDirectory, "${System.currentTimeMillis()}_${UUID.randomUUID()}.txt")
        val reportFile = AtomicFile(file)
        val output = reportFile.startWrite()
        try {
            output.write(report.fullText.toByteArray(Charsets.UTF_8))
            reportFile.finishWrite(output)
        } catch (error: Throwable) {
            reportFile.failWrite(output)
            throw error
        }
        return file
    }

    fun reportFor(intent: Intent): File? {
        if (!intent.getBooleanExtra(EXTRA_ERROR_REPORT, false)) return null
        val name = intent.getStringExtra(EXTRA_REPORT_FILE) ?: return null
        // Intent extras can never redirect report reading outside the private cache directory.
        if (!REPORT_NAME.matches(name)) return null
        return File(reportDirectory, name).takeIf { it.isFile }
    }

    fun capturedLogFor(reportFile: File): File? {
        if (reportFile.parentFile != reportDirectory || !REPORT_NAME.matches(reportFile.name)) {
            return null
        }
        return capturedLogFile(reportFile).takeIf { it.isFile }
    }

    private fun capturedLogFile(reportFile: File): File =
        File(reportDirectory, "${reportFile.nameWithoutExtension}.log")

    fun cancelScheduledRestart() {
        val existing = PendingIntent.getActivity(
            application,
            RESTART_REQUEST,
            Intent(application, MainActivity::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        application.getSystemService(AlarmManager::class.java)?.cancel(existing)
        existing.cancel()
    }

    fun restartApplication() {
        cancelScheduledRestart()
        // Hand off while the report Activity is still in the foreground. The
        // independent restart process stays visible after the old App dies.
        application.startActivity(
            Intent(application, AppRestartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(AppRestartActivity.EXTRA_PREVIOUS_PROCESS_ID, Process.myPid())
            },
            ActivityOptions.makeCustomAnimation(application, 0, 0).toBundle(),
        )
    }

    private fun restartIntoReport(file: File) {
        val intent = Intent(application, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_ERROR_REPORT, true)
            putExtra(EXTRA_REPORT_FILE, file.name)
        }
        scheduleRestart(intent)
        // Request the foreground transition immediately as well.
        application.startActivity(intent)
    }

    private fun scheduleRestart(intent: Intent) {
        val alarmManager = checkNotNull(application.getSystemService(AlarmManager::class.java))
        val options = if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            ).toBundle()
        } else null
        val pending = PendingIntent.getActivity(
            application,
            RESTART_REQUEST,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options,
        )
        // Keep a system-owned restart after the crashed process has gone away. No alarm
        // permission is required; the Activity cancels this fallback as soon as it starts.
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 500L,
            pending,
        )
    }

    companion object {
        const val EXTRA_ERROR_REPORT = "io.github.bszapp.wifitoolbox.ERROR_REPORT"
        const val EXTRA_REPORT_FILE = "io.github.bszapp.wifitoolbox.ERROR_REPORT_FILE"
        private const val TAG = "ErrorReport"
        private const val RESTART_REQUEST = 7301
        private val REPORT_NAME = Regex("[0-9]+_[a-f0-9-]+\\.txt")
    }
}
