package io.github.bszapp.wifitoolbox.service

import android.content.Context
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo
import io.github.bszapp.wifitoolbox.contract.container.ContainerEnvironment
import java.io.File

/** 管理服务初始化：校验身份、查询系统安装记录和容器路径、清理旧实例、创建 AndroidApi。 */
class ServiceInitializer(
    private val serviceContext: Context? = null,
) {
    private val lock = Any()

    @Volatile
    private var startupInfo: StartupInfo? = null

    @Volatile
    private var containerEnvironment: ContainerEnvironment? = null

    @Volatile
    var androidApi: AndroidApi? = null
        private set

    fun initialize(input: StartupInfo): StartupInfo {
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
                require(old.serviceCrashReportPath == launchInfo.serviceCrashReportPath) {
                    "重复初始化崩溃报告路径不一致"
                }
                return old
            }

            val completed = launchInfo.copy(
                serviceUid = Process.myUid(),
                servicePid = Process.myPid(),
                serviceUidText = readCurrentUidText(),
            )

            killOlderInstances()
            val api = AndroidApi(
                callerPackage = callerPackage(),
                serviceContext = serviceContext,
            )
            val application = api.getApplicationInfo(APP_PACKAGE, launchInfo.trustedUid / 100_000)
            require(application.uid == launchInfo.trustedUid) { "系统安装记录的应用 UID 与可信 UID 不一致" }
            val data = File(requireNotNull(application.dataDir) { "系统安装记录缺少应用数据目录" })
            containerEnvironment = ContainerEnvironment(
                appDataPath = if (Process.myUid() == 0) data.canonicalPath else data.absolutePath,
            )
            startupInfo = completed
            androidApi = api

            Log.d(
                TAG,
                "启动信息：mode=${completed.startupMode} trustedUid=${completed.trustedUid} " +
                        "serviceUid=${completed.serviceUid} servicePid=${completed.servicePid} " +
                        "version=${completed.versionName}(${completed.versionCode})"
            )

            return completed
        }
    }

    fun requireStartupInfo(): StartupInfo =
        startupInfo ?: throw IllegalStateException("服务启动信息未初始化")

    internal fun requireContainerEnvironment(): ContainerEnvironment =
        containerEnvironment ?: throw IllegalStateException("服务容器环境未初始化")

    fun close() {
        androidApi?.close()
        androidApi = null
    }

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

    /**
     * 强制清理当前包名下的其他服务进程。
     *
     * 不根据 PID 大小推断新旧；PID 只用于排除当前进程。匹配到固定的
     * `包名:service` 进程名，或者命令行中包含本包的 MainServiceStarter，
     * 就直接发送 SIGKILL。普通 App 主进程名仅为包名本身，不在匹配范围内。
     */
    private fun killOlderInstances() {
        val myPid = Process.myPid()
        val procRoot = java.io.File("/proc")
        val processDirectories = procRoot.listFiles()
            ?: throw IllegalStateException("/proc 列表为空")

        processDirectories.forEach { processDirectory ->
            val pid = processDirectory.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach

            val commandLine = runCatching {
                java.io.File(processDirectory, "cmdline")
                    .readBytes()
                    .toString(Charsets.UTF_8)
            }.getOrElse { exception ->
                // /proc 中的进程可能在遍历期间退出；这种情况不需要当作启动失败。
                Log.v(TAG, "读取进程命令行失败 pid=$pid: ${exception.message}")
                return@forEach
            }

            val processName = commandLine
                .substringBefore('\u0000')
                .trim()
            val printableCommandLine = commandLine
                .replace('\u0000', ' ')
                .trim()

            val samePackageService =
                processName.startsWith(SERVICE_PROCESS_PREFIX) ||
                    (
                        printableCommandLine.contains(APP_PACKAGE) &&
                            printableCommandLine.contains(SERVICE_STARTER_CLASS)
                    )

            if (!samePackageService) return@forEach

            Log.w(
                TAG,
                "强制结束同包名旧服务 pid=$pid name=$processName cmd=$printableCommandLine",
            )

            runCatching {
                Process.killProcess(pid)
            }.onFailure { exception ->
                Log.w(TAG, "强制结束同包名旧服务失败 pid=$pid", exception)
            }
        }
    }

    companion object {
        private const val TAG = "ServiceInitializer"
        private const val APP_PACKAGE = "io.github.bszapp.wifitoolbox"
        private const val SERVICE_PROCESS_PREFIX = "$APP_PACKAGE:"
        private const val SERVICE_STARTER_CLASS =
            "io.github.bszapp.wifitoolbox.service.MainServiceStarter"
    }
}
