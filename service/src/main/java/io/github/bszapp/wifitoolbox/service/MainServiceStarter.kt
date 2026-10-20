package io.github.bszapp.wifitoolbox.service

import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfoParcelCodec
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/** 独立 app_process 入口。启动参数只有一个：StartupInfo 的 Parcel/Base64 字符串。 */
//TODO:Base64？是不是写错了
@Keep
object MainServiceStarter {

    @JvmStatic
    fun main(args: Array<String>) {
        val showProgress = System.getenv("WIFITOOLBOX_STARTUP_PROGRESS") == "1"
        // 使用脚本提供的标准输出描述符，将进度直接写入脚本日志。
        val progressOutput = if (showProgress) {
            PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        } else null
        val progressError = if (showProgress) {
            PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8")
        } else null
        fun progress(message: String) {
            progressOutput?.println(message)
        }
        try {
            progress("server entry started")
            val startupInfoArg = args.getOrNull(0)
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("缺少启动信息参数")
            val startupInfo = StartupInfoParcelCodec.decode(startupInfoArg).requireLaunchInfo()

            ServiceCrashReporter.install(startupInfo.serviceCrashReportPath) { }
            ServiceLogRecorders.start()
            Log.d(
                TAG,
                "独立服务进程入口启动 mode=${startupInfo.startupMode} trustedUid=${startupInfo.trustedUid} " +
                        "version=${startupInfo.versionName}(${startupInfo.versionCode})"
            )

            progress("preparing looper...")
            prepareLooperIfNeeded()

            progress("initializing server...")
            val service = MainService(startupInfo)
            ServiceHolder.service = service
            Log.d(TAG, "独立服务进程进入 Looper")
            progress("server initialized")
        } catch (error: Throwable) {
            if (progressError != null) {
                progressError.println("server startup failed: ${error.message ?: error.javaClass.name}")
                error.printStackTrace(progressError)
                progressError.flush()
            }
            throw error
        }
        Looper.loop()
    }

    private fun prepareLooperIfNeeded() {
        if (Looper.myLooper() != null) return
        runCatching { Looper.prepareMainLooper() }.onFailure { Looper.prepare() }
    }

    private object ServiceHolder {
        @Volatile
        var service: MainService? = null
    }

    private const val TAG = "ToolboxServiceMain"
}
