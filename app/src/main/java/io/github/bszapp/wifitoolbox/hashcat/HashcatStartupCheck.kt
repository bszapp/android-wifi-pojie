package io.github.bszapp.wifitoolbox.hashcat

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File

/** 每个 App 进程启动时检查一次 JNI 加载及计算后端枚举，只向 logcat 输出诊断。 */
internal object HashcatStartupCheck {
    private const val TAG = "HashcatStartupCheck"
    private val gpuLine = Regex("^\\s*Type\\.+:\\s*GPU\\s*$")

    @WorkerThread
    fun run(context: Context) {
        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "首次加载检查开始：pid=${Process.myPid()} uid=${Process.myUid()} " +
            "thread=${Thread.currentThread().name}")
        Log.i(TAG, "设备：${Build.MANUFACTURER} ${Build.MODEL} " +
            "Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} " +
            "ABI=${Build.SUPPORTED_ABIS.joinToString()}")
        val library = File(context.applicationInfo.nativeLibraryDir, "libhashcat.so")
        val runtime = File(context.noBackupFilesDir, "hashcat")
        Log.i(TAG, "库文件：${library.absolutePath} exists=${library.exists()} " +
            "readable=${library.canRead()} bytes=${library.length()}")
        Log.i(TAG, "运行目录：${runtime.absolutePath}")
        try {
            Log.i(TAG, "运行目录真实路径：${runtime.canonicalPath}")
            Log.i(TAG, "JNI 版本调用成功：${NativeHashcat.version}")
            var gpuCount = 0
            Log.i(TAG, "JNI 后端检查开始：--backend-info；仅枚举，不执行 GPU 计算")
            val exitCode = NativeHashcat.run(
                arguments = listOf("--backend-info"),
                runtimeDirectory = runtime,
                listener = NativeHashcat.EventListener { eventId, bytes ->
                    if (eventId in NativeHashcat.EVENT_LOG_ERROR..NativeHashcat.EVENT_LOG_ADVICE) {
                        val priority = when (eventId) {
                            NativeHashcat.EVENT_LOG_ERROR -> Log.ERROR
                            NativeHashcat.EVENT_LOG_WARNING -> Log.WARN
                            else -> Log.INFO
                        }
                        bytes.toString(Charsets.UTF_8).lineSequence().forEach { line ->
                            if (gpuLine.matches(line)) gpuCount++
                            if (line.isNotEmpty()) Log.println(priority, TAG, line)
                        }
                    } else {
                        Log.d(TAG, "原生事件：id=0x${eventId.toString(16)} bytes=${bytes.size}")
                    }
                },
            )
            val summary = "JNI 后端检查返回：exitCode=$exitCode GPU数量=$gpuCount " +
                "耗时=${SystemClock.elapsedRealtime() - started}ms"
            if (exitCode == 0 && gpuCount > 0) {
                Log.i(TAG, "$summary；GPU 枚举成功，尚未验证计算内核")
            } else {
                Log.w(TAG, "$summary；本次未成功枚举 GPU，请查看前面的驱动诊断")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "首次加载检查异常：耗时=${SystemClock.elapsedRealtime() - started}ms", error)
        }
        Log.i(TAG, "首次加载检查结束")
    }
}
