package com.wifi.toolbox.tools

import android.content.Context
import android.os.Bundle
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.system.exitProcess

/**
 * This object's main function is designed to be called remotely by Shizuku.newProcess.
 * It runs inside the Shizuku server process, which is not subject to the app's
 * hidden API restrictions.
 */
object ShizukuRemoteProcess {

    @JvmStatic
    fun main(args: Array<String>) {
        // args[0] will be the command name, e.g., "setWifiEnabled"
        // args[1] will be the first parameter, e.g., "true" or "false"
        if (args.isEmpty()) {
            exitProcess(1)
        }

        when (args[0]) {
            "setWifiEnabled" -> {
                val enabled = args.getOrNull(1)?.toBoolean() ?: false
                val success = setWifiEnabled(enabled)
                exitProcess(if (success) 0 else 1)
            }
            // You can add more commands here in the future
            else -> {
                exitProcess(1)
            }
        }
    }

    private fun setWifiEnabled(enabled: Boolean): Boolean {
        return try {
            val binder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
            val service = Class.forName("android.net.wifi.IWifiManager\$Stub").run {
                getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, ShizukuBinderWrapper(binder))
            }
            val packageName = "com.wifi.toolbox"

            // The exact same multi-version logic as before, but now running in a privileged process.
            try {
                // Android 15+
                val method = service::class.java.getMethod(
                    "setWifiEnabled", String::class.java, java.lang.Boolean.TYPE, Bundle::class.java
                )
                method.invoke(service, packageName, enabled, Bundle())
            } catch (e: NoSuchMethodException) {
                try {
                    // Android 10 - 14
                    val method = service::class.java.getMethod(
                        "setWifiEnabled", String::class.java, java.lang.Boolean.TYPE
                    )
                    method.invoke(service, packageName, enabled)
                } catch (e2: NoSuchMethodException) {
                    // Android 9 and below
                    val method = service::class.java.getMethod(
                        "setWifiEnabled", java.lang.Boolean.TYPE
                    )
                    method.invoke(service, enabled)
                }
            }
            true // If we reached here without an exception, it was successful.
        } catch (t: Throwable) {
            // Log the error if possible, or just return false
            t.printStackTrace()
            false
        }
    }
}