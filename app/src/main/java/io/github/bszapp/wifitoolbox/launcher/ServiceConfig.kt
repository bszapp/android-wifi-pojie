package io.github.bszapp.wifitoolbox.launcher

import android.content.ComponentName
import android.content.Context
import io.github.bszapp.wifitoolbox.BuildConfig
import io.github.bszapp.wifitoolbox.service.BoundMainService
import rikka.shizuku.Shizuku

object ServiceConfig {
    const val PROVIDER_AUTHORITY = "io.github.bszapp.wifitoolbox.provider"
    const val PROVIDER_METHOD = "sendBinder"
    const val PROVIDER_BINDER_KEY = "binder"
    const val PROVIDER_RESULT_OK = "ok"

    const val SERVICE_PROCESS_SUFFIX = "service"
    const val SERVICE_VERSION = BuildConfig.VERSION_CODE

    fun serviceProcessName(context: Context): String =
        "${context.packageName}:$SERVICE_PROCESS_SUFFIX"

    fun buildArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, BoundMainService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix(SERVICE_PROCESS_SUFFIX)
            .version(SERVICE_VERSION)
}
