package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo

/** 管理服务初始化：校验启动信息、补齐服务自身信息、清理旧实例、创建 AndroidApi。 */
class ServiceInitializer {
    private val lock = Any()

    @Volatile
    private var startupInfo: StartupInfo? = null

    @Volatile
    var androidApi: AndroidApi? = null
        private set

    fun initialize(input: StartupInfo, source: String): StartupInfo {
        val launchInfo = input.requireLaunchInfo()
        synchronized(lock) {
            startupInfo?.let { old ->
                require(old.trustedUid == launchInfo.trustedUid) {
                    "重复初始化可信 UID 不一致：old=${old.trustedUid} new=${launchInfo.trustedUid}"
                }
                require(old.startupMode == launchInfo.startupMode) {
                    "重复初始化启动模式不一致：old=${old.startupMode} new=${launchInfo.startupMode}"
                }
                require(old.versionName == launchInfo.versionName) {
                    "重复初始化版本名称不一致：old=${old.versionName} new=${launchInfo.versionName}"
                }
                require(old.versionCode == launchInfo.versionCode) {
                    "重复初始化版本编号不一致：old=${old.versionCode} new=${launchInfo.versionCode}"
                }
                return old
            }

            val completed = launchInfo.copy(
                serviceUid = Process.myUid(),
                servicePid = Process.myPid(),
                serviceUidText = readCurrentUidText(),
            )

            killOlderInstances()
            val api = AndroidApi(callerPackage = callerPackage())
            startupInfo = completed
            androidApi = api

            Log.d(
                TAG,
                "启动信息：mode=${completed.startupMode} trustedUid=${completed.trustedUid} " +
                        "serviceUid=${completed.serviceUid} servicePid=${completed.servicePid} " +
                        "version=${completed.versionName}(${completed.versionCode}) source=$source"
            )

            return completed
        }
    }

    fun requireStartupInfo(): StartupInfo =
        startupInfo ?: throw IllegalStateException("服务启动信息未初始化")

    private fun readCurrentUidText(): String = runCatching {
        Runtime.getRuntime()
            .exec("id")
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
    }.getOrElse { "uid=${Process.myUid()}" }

    private fun callerPackage(): String = when (Process.myUid()) {
        0, 1000 -> "android"
        else -> "com.android.shell"
    }

    private fun killOlderInstances() {
        val myPid = Process.myPid()

        val myName = try {
            java.io.File("/proc/self/cmdline")
                .readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
        } catch (e: Exception) {
            throw IllegalStateException("读取当前进程名失败", e)
        }

        if (myName.isEmpty()) throw IllegalStateException("当前进程名为空")

        (java.io.File("/proc").listFiles() ?: throw IllegalStateException("/proc 列表为空")).forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid >= myPid) return@forEach

            try {
                val name = java.io.File(dir, "cmdline")
                    .readBytes()
                    .takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .toString(Charsets.UTF_8)
                    .trim()

                if (name == myName) {
                    Log.w(TAG, "kill 旧实例 pid=$pid")
                    Process.killProcess(pid)
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查旧实例 pid=$pid 失败", e)
            }
        }
    }

    companion object {
        private const val TAG = "ServiceInitializer"
    }
}
