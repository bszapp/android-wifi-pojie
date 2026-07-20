package io.github.bszapp.wifitoolbox.service

import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfoParcelCodec

/** 独立 app_process 入口。启动参数只有一个：StartupInfo 的 Parcel/Base64 字符串。 */
//TODO:Base64？是不是写错了
@Keep
object MainServiceStarter {

    @JvmStatic
    fun main(args: Array<String>) {
        val startupInfoArg = args.getOrNull(0)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少启动信息参数")
        val startupInfo = StartupInfoParcelCodec.decode(startupInfoArg).requireLaunchInfo()

        Log.d(
            TAG,
            "独立服务进程入口启动 mode=${startupInfo.startupMode} trustedUid=${startupInfo.trustedUid} " +
                    "version=${startupInfo.versionName}(${startupInfo.versionCode})"
        )

        prepareLooperIfNeeded()

        val service = MainService(startupInfo)
        ServiceHolder.service = service
        Log.d(TAG, "独立服务进程进入 Looper")
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
