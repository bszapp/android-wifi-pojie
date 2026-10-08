package io.github.bszapp.wifitoolbox.launcher

import io.github.bszapp.wifitoolbox.BuildConfig
import io.github.bszapp.wifitoolbox.container.ContainerController
import io.github.bszapp.wifitoolbox.contract.container.ContainerSystemStatus
import io.github.bszapp.wifitoolbox.contract.startup.IRuntimeUpdateController
import io.github.bszapp.wifitoolbox.contract.startup.RuntimeUpdatePrompt
import io.github.bszapp.wifitoolbox.contract.startup.RuntimeUpdateTarget
import io.github.bszapp.wifitoolbox.contract.startup.StartupState
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 每次服务连接先检查服务版本，再检查容器；取消后本次连接不重复询问。 */
internal class RuntimeUpdateController(
    private val scope: CoroutineScope,
    private val startup: StateFlow<StartupState>,
    private val containers: ContainerController,
    private val restartService: suspend (force: Boolean) -> Unit,
    private val reportError: (String, String, Throwable, String?) -> Unit,
) : IRuntimeUpdateController {
    private val _prompt = MutableStateFlow<RuntimeUpdatePrompt?>(null)
    override val prompt: StateFlow<RuntimeUpdatePrompt?> = _prompt.asStateFlow()
    private var connectionId: Long? = null
    private var serviceChecked = false
    private var containerChecked = false

    init {
        scope.launch {
            combine(startup, containers.state) { service, container -> service to container }
                .collectLatest { (service, container) ->
                    if (service.status != StartupStatus.RUNNING) {
                        connectionId = null
                        _prompt.value = null
                        return@collectLatest
                    }
                    if (connectionId != service.connectionId) {
                        connectionId = service.connectionId
                        serviceChecked = false
                        containerChecked = false
                        _prompt.value = null
                    }
                    val info = service.serviceInfo ?: return@collectLatest
                    if (info.versionCode < BuildConfig.VERSION_CODE.toLong()) {
                        if (!serviceChecked) {
                            serviceChecked = true
                            _prompt.value = RuntimeUpdatePrompt(service.connectionId, RuntimeUpdateTarget.SERVICE)
                        }
                        return@collectLatest
                    }
                    serviceChecked = true
                    if (containerChecked || container.systemStatus == ContainerSystemStatus.CHECKING || container.isBusy) {
                        return@collectLatest
                    }
                    try {
                        val version = if (container.installed) containers.readVersionCode() else -1L
                        if (startup.value.status != StartupStatus.RUNNING ||
                            startup.value.connectionId != service.connectionId) return@collectLatest
                        containerChecked = true
                        if (!container.installed || version < BuildConfig.VERSION_CODE.toLong()) {
                            _prompt.value = RuntimeUpdatePrompt(service.connectionId, RuntimeUpdateTarget.CONTAINER)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (startup.value.connectionId == service.connectionId) {
                            containerChecked = true
                            reportError("App.RuntimeUpdateController", "检查容器系统版本", error, null)
                        }
                    }
                }
        }
    }

    override fun dismiss(prompt: RuntimeUpdatePrompt) {
        if (_prompt.value == prompt) _prompt.value = null
    }

    override fun confirm(prompt: RuntimeUpdatePrompt) {
        if (_prompt.value != prompt) return
        _prompt.value = null
        val current = startup.value
        if (current.status != StartupStatus.RUNNING || current.connectionId != prompt.connectionId) return
        scope.launch {
            try {
                when (prompt.target) {
                    RuntimeUpdateTarget.SERVICE -> restartService(prompt.errorMessage != null)
                    RuntimeUpdateTarget.CONTAINER -> containers.update()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (prompt.target == RuntimeUpdateTarget.SERVICE &&
                    startup.value.status == StartupStatus.RUNNING &&
                    startup.value.connectionId == prompt.connectionId) {
                    _prompt.value = prompt.copy(errorMessage = error.message ?: error.javaClass.name)
                } else {
                    reportError("App.RuntimeUpdateController", "更新${if (prompt.target == RuntimeUpdateTarget.SERVICE) "服务" else "容器系统"}", error, null)
                }
            }
        }
    }
}
