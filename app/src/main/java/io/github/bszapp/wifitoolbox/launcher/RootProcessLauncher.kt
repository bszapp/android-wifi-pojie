package io.github.bszapp.wifitoolbox.launcher

import android.content.Context
import android.os.IBinder
import android.util.Log
import io.github.bszapp.wifitoolbox.BuildConfig
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible

/**
 * Root 启动器只负责把特权服务进程拉起来。
 *
 * Binder 统一等待服务进程通过 Provider 主动投递。Root 服务进程使用固定 nice-name，
 * 生命周期不跟随应用侧 launcher；显式 shutdown、取消时强杀、系统重启或外部强杀才退出。
 */
internal class RootProcessLauncher(private val context: Context) : AutoCloseable {

    suspend fun getServiceBinder(className: String): IBinder = coroutineScope {
        ToolboxServiceProvider.clearBinder()

        val binderWaiter = async {
            ToolboxServiceProvider.binderFlow
                .filterNotNull()
                .first { it.isBinderAlive }
        }

        startDetachedProcess(className)

        ToolboxServiceProvider.getAliveBinder()?.let { return@coroutineScope it }
        binderWaiter.await()
    }

    private suspend fun startDetachedProcess(className: String) = runInterruptible(Dispatchers.IO) {
        val command = buildDetachedCommand(className)
        Log.d(TAG, "启动独立 root 服务：$className")

        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (_: java.io.IOException) {
            throw Exception("su命令执行失败，请确认设备已经root，然后在管理器授权本应用")
        }

        val output = StringBuilder()
        Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.take(64).forEach { line ->
                        if (output.length < 4096) output.appendLine(line)
                    }
                }
            }
        }, "toolbox-root-su-output").apply {
            isDaemon = true
            start()
        }

        val exitHolder = IntArray(1)
        val finishedHolder = booleanArrayOf(false)
        val waiter = Thread({
            runCatching {
                exitHolder[0] = process.waitFor()
                finishedHolder[0] = true
            }
        }, "toolbox-root-su-waiter").apply {
            isDaemon = true
            start()
        }

        waiter.join(1500L)
        if (finishedHolder[0]) {
            val exit = exitHolder[0]
            if (exit != 0) {
                throw Exception("su命令执行失败，情在管理器授权本应用")
            }
            Log.d(TAG, "root 启动命令已返回，等待服务 Binder")
        } else {
            Log.d(TAG, "root 启动命令已提交，继续等待服务 Binder")
        }
    }

    private fun buildDetachedCommand(className: String): String {
        val classPath = buildClassPath()
        val niceName = ServiceConfig.serviceProcessName(context)

        val appProcess = "/system/bin/app_process"
        val classPathQuoted = shellQuote(classPath)
        val niceNameQuoted = shellQuote(niceName)
        val classNameQuoted = shellQuote(className)
        val modeQuoted = shellQuote(StartupMode.ROOT.name)
        val versionCodeQuoted = shellQuote(BuildConfig.VERSION_CODE.toString())
        val versionNameQuoted = shellQuote(BuildConfig.VERSION_NAME)

        return """
            CLASSPATH=$classPathQuoted
            export CLASSPATH
            (
              if command -v nohup >/dev/null 2>&1; then
                nohup $appProcess /system/bin --nice-name=$niceNameQuoted $classNameQuoted $modeQuoted $versionCodeQuoted $versionNameQuoted >/dev/null 2>&1 < /dev/null
              else
                $appProcess /system/bin --nice-name=$niceNameQuoted $classNameQuoted $modeQuoted $versionCodeQuoted $versionNameQuoted >/dev/null 2>&1 < /dev/null
              fi
            ) &
        """.trimIndent()
    }

    fun forceStopService() {
        runCatching {
            ProcessBuilder("su", "-c", buildKillCommand())
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }.onFailure {
            Log.w(TAG, "root 强杀服务失败: ${it.message}")
        }
    }

    private fun buildKillCommand(): String {
        val processName = shellQuote(ServiceConfig.serviceProcessName(context))
        val packageName = shellQuote(context.packageName)
        return """
            for p in /proc/[0-9]*; do
              pid=${'$'}{p##*/}
              cmd=${'$'}(tr '\0' ' ' < "${'$'}p/cmdline" 2>/dev/null)
              case "${'$'}cmd" in
                *$processName*|*$packageName*MainServiceStarter*) kill -9 "${'$'}pid" 2>/dev/null ;;
              esac
            done
        """.trimIndent()
    }

    private fun buildClassPath(): String {
        val appInfo = context.applicationInfo
        return buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let { addAll(it) }
        }.joinToString(":")
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    override fun close() {
        // 独立 root 服务的生命周期不跟随应用侧启动器。
    }

    companion object {
        private const val TAG = "RootLauncher"
    }
}
