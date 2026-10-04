package io.github.bszapp.wifitoolbox.service.container

import android.os.Process as AndroidProcess
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.container.ContainerEnvironment
import io.github.bszapp.wifitoolbox.contract.container.isContainerSystemInstalled
import java.io.File
import java.io.IOException
import org.json.JSONObject

/** 服务持有唯一的私有挂载命名空间；终端加入该空间，终端退出不会解除挂载。 */
internal class ContainerMountManager(
    private val beforeUnmount: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var environment: ContainerEnvironment? = null
    private var session: MountSession? = null
    private var available = false
    private var closed = false

    fun refresh(next: ContainerEnvironment) = synchronized(lock) {
        check(!closed) { "容器挂载管理器已关闭" }
        if (environment != next) unmountLocked()
        environment = next
        val shouldMount = AndroidProcess.myUid() == 0 &&
            isContainerSystemInstalled(File(next.appDataPath, "rootfs"))
        if (!shouldMount) {
            unmountLocked()
            return@synchronized
        }
        session?.let {
            check(runCatching { it.process.exitValue() }.isFailure) { "容器挂载进程已退出" }
            available = true
            return@synchronized
        }
        val process = ProcessBuilder(next.terminalPath, "container", "mount",
            "--rootfs", File(next.appDataPath, "rootfs").absolutePath,
            "--host-path", HOST_TOOL_PATH).redirectErrorStream(true).start()
        val reader = process.inputStream.bufferedReader()
        try {
            val output = StringBuilder()
            val pid = run {
                var mountedPid: Int? = null
                while (mountedPid == null) {
                    val line = reader.readLine() ?: throw IOException("挂载容器失败：$output")
                    val event = runCatching { JSONObject(line) }.getOrNull()
                    if (event?.optString("event") == "mounted") mountedPid = event.getInt("pid")
                    else {
                        if (output.length < 8192) output.appendLine(line)
                        Log.d(TAG, line)
                    }
                }
                requireNotNull(mountedPid)
            }
            check(pid > 0) { "容器挂载进程 PID 无效" }
            val mounted = MountSession(process, pid)
            session = mounted
            available = true
            Log.i(TAG, "容器已挂载：rootfs=${next.appDataPath}/rootfs namespacePid=$pid")
            Thread({
                runCatching {
                    reader.useLines { lines -> lines.forEach { Log.d(TAG, it) } }
                }.onFailure { Log.w(TAG, "读取挂载进程输出失败", it) }
                val exit = waitForExit(process)
                val unexpected = synchronized(lock) {
                    if (session !== mounted) false else {
                        available = false
                        session = null
                        true
                    }
                }
                if (unexpected) {
                    runCatching { beforeUnmount(mounted.pid) }.onFailure { Log.e(TAG, "停止容器终端失败", it) }
                    onError(IOException("容器挂载进程异常退出：exit=$exit"))
                }
            }, "container-mount-owner").apply { isDaemon = true; start() }
        } catch (error: Throwable) {
            runCatching { reader.close() }
            runCatching { process.outputStream.close() }
            process.destroy()
            waitForExit(process)
            throw error
        }
    }

    /** 与终端创建串行，禁止在删除目录或卸载过程中产生新的 chroot 使用者。 */
    fun <T> withMounted(rootfsPath: String, terminalPath: String, action: (Int) -> T): T = synchronized(lock) {
        require(AndroidProcess.myUid() == 0) { "Chroot 终端要求 Root 工作模式" }
        val env = requireNotNull(environment) { "容器环境尚未配置" }
        require(File(rootfsPath).canonicalPath == File(env.appDataPath, "rootfs").canonicalPath &&
            terminalPath == env.terminalPath) { "终端环境与服务持有的容器环境不一致" }
        val mounted = session
        check(!closed && available && mounted != null) { "容器系统尚未挂载" }
        check(runCatching { mounted.process.exitValue() }.isFailure) { "容器挂载进程已退出" }
        action(mounted.pid)
    }

    fun unmount() = synchronized(lock) { unmountLocked() }

    private fun unmountLocked() {
        available = false
        val mounted = session ?: return
        beforeUnmount(mounted.pid)
        // 先结束所有 chroot 使用者，再要求持有者解除 rootfs 的整棵私有挂载树。
        mounted.process.outputStream.close()
        val exit = waitForExit(mounted.process)
        session = null
        check(exit == 0) { "容器卸载失败：exit=$exit" }
        Log.i(TAG, "容器已解除挂载：namespacePid=${mounted.pid}")
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        unmountLocked()
        closed = true
    }

    private data class MountSession(val process: Process, val pid: Int)

    private companion object {
        const val TAG = "ContainerMountManager"
        const val HOST_TOOL_PATH =
            "/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/odm/bin:/apex/com.android.runtime/bin"

        fun waitForExit(process: Process): Int {
            var interrupted = false
            try {
                while (true) {
                    try { return process.waitFor() }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
