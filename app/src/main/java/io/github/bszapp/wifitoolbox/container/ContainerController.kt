package io.github.bszapp.wifitoolbox.container

import android.content.Context
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import io.github.bszapp.wifitoolbox.contract.container.ContainerEnvironment
import io.github.bszapp.wifitoolbox.contract.container.ContainerOperation
import io.github.bszapp.wifitoolbox.contract.container.ContainerOperationRequest
import io.github.bszapp.wifitoolbox.contract.container.ContainerState
import io.github.bszapp.wifitoolbox.contract.container.IContainerController
import io.github.bszapp.wifitoolbox.service.IContainerSystemCallback
import io.github.bszapp.wifitoolbox.service.IMainService
import io.github.bszapp.wifitoolbox.service.container.ContainerSystemManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Root 服务执行容器操作；非 Root 服务连接下由 App 进程操作自己的容器文件。 */
class ContainerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val reportError: (
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String?,
    ) -> Unit,
) : IContainerController {
    private val lock = Any()
    private val _state = MutableStateFlow(ContainerState())
    override val state: StateFlow<ContainerState> = _state.asStateFlow()
    private var activeBinding: Binding? = null
    private val localManager by lazy {
        ContainerSystemManager(
            appUid = context.applicationInfo.uid,
            terminalBinaryProvider = { File(context.applicationInfo.nativeLibraryDir, "libterminal.so") },
            onError = ::report,
        )
    }

    fun connect(service: IMainService) {
        lateinit var binding: Binding
        val callback = object : IContainerSystemCallback.Stub() {
            override fun onContainerStateChanged(state: ContainerState) { accept(binding, state) }
        }
        binding = Binding(service, callback)
        val previous = synchronized(lock) {
            activeBinding.also {
                activeBinding = binding
                _state.value = ContainerState()
            }
        }
        release(previous)
        binding.job = scope.launch(Dispatchers.IO) {
            try {
                val serviceUid = service.getStartupInfo().serviceUid
                check(serviceUid >= 0) { "服务 UID 尚未初始化" }
                synchronized(binding.registrationLock) {
                    if (!isCurrent(binding)) return@launch
                    val manager = if (serviceUid == 0) null else localManager.also {
                        it.initialize(ContainerEnvironment(context.applicationInfo.dataDir))
                    }
                    binding.localManager = manager
                    if (manager != null) manager.register(callback)
                    else service.registerContainerSystemCallback(callback)
                    binding.registered = true
                }
                accept(binding, binding.localManager?.state() ?: service.getContainerState())
                binding.ready.complete(Unit)
            } catch (error: Throwable) {
                binding.ready.completeExceptionally(error)
                if (isCurrent(binding) && error !is DeadObjectException) report("订阅容器系统状态", error)
            }
        }
    }

    fun disconnect() {
        val previous = synchronized(lock) {
            activeBinding.also {
                activeBinding = null
                _state.value = ContainerState()
            }
        }
        release(previous)
    }

    override fun install() = request(ContainerOperation.INSTALL)
    override fun update() = request(ContainerOperation.UPDATE)
    override fun reset() = request(ContainerOperation.RESET)
    override fun uninstall() = request(ContainerOperation.UNINSTALL)

    internal suspend fun readVersionCode(): Long = withContext(Dispatchers.IO) {
        val binding = synchronized(lock) { activeBinding }
        check(binding != null && isCurrent(binding)) { "service 未连接" }
        binding.ready.await()
        check(isCurrent(binding)) { "读取容器版本期间服务连接已改变" }
        val version = binding.localManager?.versionCode() ?: binding.service.getContainerVersionCode()
        check(isCurrent(binding)) { "读取容器版本期间服务连接已改变" }
        version
    }

    override fun interrupt(operationId: Long) {
        val binding = synchronized(lock) { activeBinding } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                binding.ready.await()
                if (isCurrent(binding)) {
                    val manager = binding.localManager
                    if (manager != null) manager.interrupt(operationId)
                    else binding.service.interruptContainerOperation(operationId)
                }
            } catch (error: Throwable) {
                if (isCurrent(binding)) report("强制中断容器操作", error)
            }
        }
    }

    private fun request(operation: ContainerOperation) {
        val binding = synchronized(lock) { activeBinding }
        scope.launch(Dispatchers.IO) {
            try {
                check(binding != null) { "service 未连接" }
                binding.ready.await()
                check(isCurrent(binding) && binding.service.asBinder().isBinderAlive) { "service 未连接" }
                if (operation == ContainerOperation.UNINSTALL) {
                    execute(binding, ContainerOperationRequest(operation), null)
                } else {
                    // .xz 在 APK 中以未压缩 asset 保存，执行端按 FD 范围流式读取，不写临时包。
                    context.assets.openFd(ROOTFS_ASSET).use { archive ->
                        execute(
                            binding,
                            ContainerOperationRequest(operation, archive.startOffset, archive.length),
                            archive.parcelFileDescriptor,
                        )
                    }
                }
            } catch (error: Throwable) {
                if (binding == null || isCurrent(binding)) report(operation.title, error)
            }
        }
    }

    private fun execute(binding: Binding, request: ContainerOperationRequest, archive: ParcelFileDescriptor?): Boolean =
        binding.localManager?.start(request, archive) ?: binding.service.executeContainerOperation(request, archive)

    private fun accept(binding: Binding, next: ContainerState) {
        synchronized(lock) {
            if (activeBinding === binding && next.revision >= _state.value.revision) _state.value = next
        }
    }

    private fun isCurrent(binding: Binding) = synchronized(lock) { activeBinding === binding }

    private fun release(binding: Binding?) {
        if (binding == null) return
        binding.ready.completeExceptionally(IllegalStateException("容器服务连接已改变"))
        binding.job?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(binding.registrationLock) {
                if (binding.registered) {
                    runCatching {
                        val manager = binding.localManager
                        if (manager != null) manager.unregister(binding.callback)
                        else binding.service.unregisterContainerSystemCallback(binding.callback)
                    }
                    binding.registered = false
                }
            }
        }
    }

    private fun report(operation: String, error: Throwable) = reportError("App.ContainerController", operation, error, null)

    private class Binding(val service: IMainService, val callback: IContainerSystemCallback) {
        val registrationLock = Any()
        val ready = CompletableDeferred<Unit>()
        @Volatile var localManager: ContainerSystemManager? = null
        var registered = false
        var job: Job? = null
    }

    private companion object {
        const val ROOTFS_ASSET = "rootfs.tar.xz"
    }
}
