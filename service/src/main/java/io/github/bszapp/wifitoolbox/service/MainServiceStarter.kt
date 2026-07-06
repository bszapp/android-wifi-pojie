package io.github.bszapp.wifitoolbox.service

import android.os.Looper
import android.util.Log
import androidx.annotation.Keep

/**
 * 独立 app_process 入口。
 *
 * 该入口只创建一次 MainService，然后进入 Looper 保持进程存活。
 * 服务 Binder 会由 MainService 自己通过 Provider 投递给当前应用进程。
 */
@Keep
object MainServiceStarter {

    @JvmStatic
    fun main(args: Array<String>) {
        val startupMode = args.getOrNull(0)?.takeIf { it.isNotBlank() }
        val versionCode = args.getOrNull(1)?.toLongOrNull()
        val versionName = args.getOrNull(2)?.takeIf { it.isNotBlank() }

        Log.d(TAG, "独立服务进程入口启动 mode=$startupMode version=${versionName ?: "?"}(${versionCode ?: -1})")

        val service = MainService(
            startupMode = startupMode,
            startupVersionName = versionName,
            startupVersionCode = versionCode
        )
        ServiceHolder.service = service

        prepareLooperIfNeeded()
        Log.d(TAG, "独立服务进程进入 Looper")
        Looper.loop()
    }

    private fun prepareLooperIfNeeded() {
        if (Looper.myLooper() != null) return

        runCatching {
            Looper.prepareMainLooper()
        }.onFailure {
            Looper.prepare()
        }
    }

    private object ServiceHolder {
        @Volatile
        var service: MainService? = null
    }

    private const val TAG = "ToolboxServiceMain"
}
