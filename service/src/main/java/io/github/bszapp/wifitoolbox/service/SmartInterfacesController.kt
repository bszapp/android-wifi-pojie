package io.github.bszapp.wifitoolbox.service

import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.SmartInterfacesState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** 网卡存在性始终从系统读取；服务退出和模式切换不撤销用户创建的接口。 */
internal class SmartInterfacesController(
    private val terminalManager: TerminalManager,
    private val canChange: () -> Boolean,
    private val onStateChanged: (SmartInterfacesState) -> Unit,
    private val onError: (String, Throwable) -> Unit,
) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smart-managed-interfaces").apply { isDaemon = true }
    }
    private var running = false
    private var closed = false
    @Volatile private var state = SmartInterfacesState()

    fun snapshot(): SmartInterfacesState = state

    /** 无需容器或旧服务记录，重启服务后同样能识别仍然存在的标记网卡。 */
    fun refresh(): SmartInterfacesState = synchronized(lock) {
        val devices = File("/sys/class/net").listFiles()
            ?: throw IOException("无法读取系统网卡列表")
        val added = devices.filter {
            MARK.matches(it.name) && File(it, "phy80211").exists()
        }.map { it.name }.sorted()
        val managed = runCatching {
            WirelessInterfaceNetlink.managedInterfaceNames()
        }.getOrElse { error ->
            // 标记网卡仍按实际存在性更新；枚举失败时保留最后一次成功读取的managed列表。
            Log.w(TAG, "读取managed网卡列表失败", error)
            state.managedInterfaces
        }
        val count = if (added.isEmpty()) 0 else managed.size
        val next = SmartInterfacesState(added, count, running, managed)
        if (next != state) {
            state = next
            onStateChanged(next)
        }
        next
    }

    fun toggle() = synchronized(lock) {
        check(!closed) { "网卡管理器已关闭" }
        if (running) return@synchronized
        require(Process.myUid() == 0) { "智能增加网卡要求 Root 工作模式" }
        check(canChange() && WirelessInterfaceNetlink.readMode() == WifiMode.NORMAL) {
            "智能增加网卡需要处于普通模式且网卡切换已完成"
        }
        val action = if (refresh().addedInterfaces.isEmpty()) "add" else "remove"
        running = true
        refresh()
        executor.execute {
            try {
                runScript(action)
            } catch (error: Exception) {
                onError(if (action == "add") "智能增加网卡" else "撤销新增网卡", error)
            } finally {
                synchronized(lock) {
                    running = false
                    // 退出码不用于决定勾选状态，创建/删除失败或只完成部分时同样重新检查。
                    runCatching { refresh() }.onFailure { error ->
                        state = state.copy(isRunning = false)
                        if (!closed) onStateChanged(state)
                        onError("检查新增网卡", error)
                    }
                }
            }
        }
    }

    private fun runScript(action: String) {
        check(terminalManager.hasRootfsFile("wlantool/smart_interfaces.py")) {
            "容器缺少智能增加网卡脚本，请更新容器系统"
        }
        val exited = CompletableFuture<Int>()
        val id = terminalManager.createChrootTerminal(onExit = { _, code -> exited.complete(code) })
        try {
            terminalManager.writeInput(id,
                "exec python3 /wlantool/smart_interfaces.py $action --prefix $PREFIX")
        } catch (error: Exception) {
            terminalManager.stopTerminal(id, "智能网卡脚本启动失败")
            throw error
        }
        check(exited.get() == 0) {
            if (action == "add") "驱动未允许新增网卡，请查看对应终端日志"
            else "部分新增网卡未能撤销，请查看对应终端日志"
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        executor.shutdown()
    }

    private companion object {
        const val TAG = "SmartInterfaces"
        const val PREFIX = "wtb_sta"
        val MARK = Regex("${PREFIX}[0-9a-f]{8}")
    }
}
