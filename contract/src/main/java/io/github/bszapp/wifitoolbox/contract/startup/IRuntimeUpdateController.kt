package io.github.bszapp.wifitoolbox.contract.startup

import kotlinx.coroutines.flow.StateFlow

/** 启动后的版本检查与用户确认；服务退出、重启和容器更新由 App 执行。 */
interface IRuntimeUpdateController {
    val prompt: StateFlow<RuntimeUpdatePrompt?>
    fun confirm(prompt: RuntimeUpdatePrompt)
    fun dismiss(prompt: RuntimeUpdatePrompt)
}

data class RuntimeUpdatePrompt(
    val connectionId: Long,
    val target: RuntimeUpdateTarget,
    val errorMessage: String? = null,
)

enum class RuntimeUpdateTarget {
    SERVICE,
    CONTAINER,
}
