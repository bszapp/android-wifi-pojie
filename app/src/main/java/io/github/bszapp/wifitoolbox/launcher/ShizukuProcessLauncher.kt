package io.github.bszapp.wifitoolbox.launcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Method
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shizuku 启动器。
 *
 * Shizuku 模式：绑定 UserService。
 * Shizuku Terminal 模式：通过 Shizuku 获取 shell/root 执行环境，
 * 只把独立 app_process 服务“发射”出去。服务不再挂在 App 侧持有的
 * ShizukuRemoteProcess 上，因此 App 强杀/重启后仍可由服务主动投递 Binder 重连。
 */
class ShizukuProcessLauncher(private val context: Context) : AutoCloseable {

    private var directArgs: Shizuku.UserServiceArgs? = null
    private var directConnection: ServiceConnection? = null
    private var terminalLaunchProcess: ShizukuRemoteProcess? = null

    suspend fun ensurePermission() {
        if (!isShizukuInstalled()) {
            throw Exception("Shizuku未安装，请<a href=\"https://apt.izzysoft.de/fdroid/repo/moe.shizuku.privileged.api_1086.apk\">下载Shizuku应用</a>并启动")
        }

        if (!Shizuku.pingBinder()) {
            throw Exception("Shizuku未运行，请<a open=\"moe.shizuku.privileged.api\">启动Shizuku服务</a>")
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return

        if (Shizuku.shouldShowRequestPermissionRationale())
            throw Exception("Shizuku授权已被永久拒绝，请手动授权")

        val granted = suspendCancellableCoroutine { cont ->
            val requestCode = 1001

            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(reqCode: Int, grantResult: Int) {
                    if (reqCode == requestCode) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }

            Shizuku.requestPermission(requestCode)
        }

        if (!granted)
            throw Exception("Shizuku授权被拒绝")
    }


    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)

    /**
     * 直接绑定用户服务。
     */
    suspend fun getDirectServiceBinder(): IBinder {
        ensurePermission()

        return suspendCancellableCoroutine { cont ->
            val args = ServiceConfig.buildArgs(context)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    Log.d(TAG, "onServiceConnected (direct)")
                    if (service != null && !cont.isCompleted) cont.resume(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.d(TAG, "onServiceDisconnected (direct)")
                    if (!cont.isCompleted) cont.resumeWithException(Exception("服务连接意外断开"))
                }
            }
            directArgs = args
            directConnection = conn

            runCatching {
                Shizuku.bindUserService(args, conn)
            }.onFailure {
                if (!cont.isCompleted) cont.resumeWithException(it)
            }

            cont.invokeOnCancellation {
                runCatching { Shizuku.unbindUserService(args, conn, false) }
            }
        }
    }

    /**
     * Terminal 模式：Shizuku newProcess -> sh -> detached app_process。
     *
     * Shizuku 只负责提供本次 shell/root 启动能力；真正的服务进程
     * 由 sh 后台启动并脱离 App 侧 ShizukuRemoteProcess 生命周期。
     */
    suspend fun getTerminalServiceBinder(className: String): IBinder = coroutineScope {
        ensurePermission()
        ToolboxServiceProvider.clearBinder()

        val binderWaiter = async {
            ToolboxServiceProvider.binderFlow
                .filterNotNull()
                .first { it.isBinderAlive }
        }

        startTerminalProcess(className)

        ToolboxServiceProvider.getAliveBinder()?.let { return@coroutineScope it }
        binderWaiter.await()
    }

    private suspend fun startTerminalProcess(className: String) = runInterruptible(Dispatchers.IO) {
        val command = buildTerminalCommand(className)
        Log.d(TAG, "通过 Shizuku Terminal 发射独立服务：$className")

        val process = shizukuNewProcess(arrayOf("sh", "-c", command), null, null)
        terminalLaunchProcess = process

        val output = StringBuilder()
        Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.take(64).forEach { line ->
                        if (output.length < 4096) output.appendLine(line)
                    }
                }
            }
        }, "toolbox-shizuku-terminal-output").apply {
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
        }, "toolbox-shizuku-terminal-waiter").apply {
            isDaemon = true
            start()
        }

        waiter.join(1500L)
        if (finishedHolder[0]) {
            val exit = exitHolder[0]
            if (exit != 0) {
                throw Exception("Shizuku Terminal进程启动失败")
            }
            Log.d(TAG, "Shizuku Terminal 发射命令已返回，等待服务 Binder")
        } else {
            Log.d(TAG, "Shizuku Terminal 发射命令已提交，继续等待服务 Binder")
        }
    }

    private fun buildTerminalCommand(className: String): String {
        val classPath = shellQuote(buildClassPath())
        val appProcess = "/system/bin/app_process"
        val niceName = shellQuote(ServiceConfig.serviceProcessName(context))
        val starter = shellQuote(className)
        val mode = shellQuote(StartupMode.SHIZUKU_TERMINAL.name)
        val versionCode = shellQuote(BuildConfig.VERSION_CODE.toString())
        val versionName = shellQuote(BuildConfig.VERSION_NAME)

        /*
         * 不能 exec app_process。
         * exec 会让 app_process 成为 ShizukuRemoteProcess 本身，App 被强杀时
         * remote process 控制链路断开，服务也可能一起被清理。
         *
         * 这里让 sh 只负责启动后台 app_process 后退出。优先使用 setsid，
         * 其次 nohup，最后退化为普通后台进程。stdio 全部重定向，避免
         * 服务进程继续持有 remote process 管道。
         */
        return """
            CLASSPATH=$classPath
            export CLASSPATH
            if command -v setsid >/dev/null 2>&1; then
              setsid $appProcess /system/bin --nice-name=$niceName $starter $mode $versionCode $versionName >/dev/null 2>&1 < /dev/null &
            elif command -v nohup >/dev/null 2>&1; then
              nohup $appProcess /system/bin --nice-name=$niceName $starter $mode $versionCode $versionName >/dev/null 2>&1 < /dev/null &
            else
              $appProcess /system/bin --nice-name=$niceName $starter $mode $versionCode $versionName >/dev/null 2>&1 < /dev/null &
            fi
        """.trimIndent()
    }

    private fun shizukuNewProcess(
        cmd: Array<String>, env: Array<String>?, dir: String?
    ): ShizukuRemoteProcess {
        val method: Method = Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).also { it.isAccessible = true }
        return method.invoke(null, cmd, env, dir) as ShizukuRemoteProcess
    }

    fun forceStopService(mode: StartupMode? = null) {
        runCatching {
            directArgs?.let { args ->
                directConnection?.let { conn ->
                    Shizuku.unbindUserService(args, conn, true)
                }
            }
        }
        runCatching { terminalLaunchProcess?.destroy() }
        terminalLaunchProcess = null

        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        val command = buildKillCommand()
        runCatching {
            val proc = shizukuNewProcess(arrayOf("sh", "-c", command), null, null)
            proc.waitFor()
        }.onFailure {
            Log.w(TAG, "Shizuku 强杀服务失败: ${it.message}")
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    override fun close() {
        runCatching {
            directArgs?.let { args ->
                directConnection?.let { conn ->
                    Shizuku.unbindUserService(args, conn, false)
                }
            }
        }
        directArgs = null
        directConnection = null

        // Terminal 模式的服务已经由 shell/root 环境独立发射，close() 不能再销毁它。
        // 需要终止服务时走 forceStopService()/service.shutdown()。
        terminalLaunchProcess = null
    }

    companion object {
        private const val TAG = "ShizukuLauncher"
        private const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    }
}
