package io.github.bszapp.wifitoolbox.contract.settings

import io.github.bszapp.wifitoolbox.contract.task.*
import org.json.JSONObject
import org.json.JSONArray

/** 保存配置对象，不包含任务、字典、网络密码或历史记录。 */
class PskSecurityAuditSettings : SettingGroup("pskSecurityAudit") {
    val password = PskSecurityAuditConfigSetting()
    val wps = WpsAuditConfigSetting()
    override val children: List<SettingItem> = listOf(password, wps)
}

class PskSecurityAuditConfigSetting : ValueSetting<PskSecurityAuditConfig>("password", PskSecurityAuditConfig()) {
    override fun toJsonValue(): JSONObject = current.let { config ->
        JSONObject().apply {
            put("connectionMethod", config.connectionMethod.name)
            put("connection", JSONObject().apply {
                put("timeoutMillis", config.connection.timeoutMillis)
                put("failureFlags", JSONObject().apply {
                    put("passwordError", config.connection.failureFlags.passwordError)
                    put("handshakeAttemptsExceeded", config.connection.failureFlags.handshakeAttemptsExceeded?.let {
                        JSONObject().put("maxHandshakeAttempts", it.maxHandshakeAttempts)
                    } ?: JSONObject.NULL)
                    put("handshakeTimeout", config.connection.failureFlags.handshakeTimeout?.let {
                        JSONObject().put("handshakeStepTimeoutMillis", it.handshakeStepTimeoutMillis)
                    } ?: JSONObject.NULL)
                })
            })
            put("retryCount", config.retryCount)
            put("cooldownBaseMillis", config.cooldownBaseMillis)
            put("handshakeTimeoutMillis", config.handshakeTimeoutMillis)
            put("maxHandshakeAttempts", config.maxHandshakeAttempts)
            put("macMode", config.macMode.name)
            put("macChangeThreshold", config.macChangeThreshold)
            put("selectedInterfaces", JSONArray(config.selectedInterfaces))
            put("detailedLogging", config.detailedLogging)
        }
    }

    override fun loadFromJsonValue(value: Any?) {
        val json = value as? JSONObject ?: run { resetToDefault(); return }
        val defaults = PskSecurityAuditConfig()
        val connection = json.optJSONObject("connection")
        val flags = connection?.optJSONObject("failureFlags")
        val attempts = flags?.optJSONObject("handshakeAttemptsExceeded")
        val timeout = flags?.optJSONObject("handshakeTimeout")
        setLoadedValue(defaults.copy(
            connectionMethod = PskSecurityAuditConnectionMethod.entries.firstOrNull {
                it.name == json.optString("connectionMethod") } ?: defaults.connectionMethod,
            connection = ConnectWifiTaskConfig(
                timeoutMillis = connection?.optLong("timeoutMillis", defaults.connection.timeoutMillis)
                    ?: defaults.connection.timeoutMillis,
                failureFlags = ConnectWifiFailureFlags(
                    passwordError = flags?.optBoolean("passwordError", defaults.connection.failureFlags.passwordError)
                        ?: defaults.connection.failureFlags.passwordError,
                    handshakeAttemptsExceeded = if (flags?.has("handshakeAttemptsExceeded") == true)
                        attempts?.let { HandshakeAttemptsExceededFlag(it.optInt("maxHandshakeAttempts", 1)) }
                        else defaults.connection.failureFlags.handshakeAttemptsExceeded,
                    handshakeTimeout = if (flags?.has("handshakeTimeout") == true)
                        timeout?.let { HandshakeTimeoutFlag(it.optLong("handshakeStepTimeoutMillis", 1500)) }
                        else defaults.connection.failureFlags.handshakeTimeout,
                ),
            ),
            retryCount = json.optInt("retryCount", defaults.retryCount),
            cooldownBaseMillis = json.optLong("cooldownBaseMillis", defaults.cooldownBaseMillis),
            handshakeTimeoutMillis = json.optLong("handshakeTimeoutMillis", defaults.handshakeTimeoutMillis),
            maxHandshakeAttempts = json.optInt("maxHandshakeAttempts", defaults.maxHandshakeAttempts),
            macMode = PskSecurityAuditMacMode.entries.firstOrNull { it.name == json.optString("macMode") } ?: defaults.macMode,
            macChangeThreshold = json.optInt("macChangeThreshold", defaults.macChangeThreshold),
            selectedInterfaces = json.optJSONArray("selectedInterfaces")?.let { names ->
                (0 until names.length()).map { names.optString(it) }
                    .filter { it.matches(Regex("[A-Za-z0-9_.-]{1,15}")) }.distinct()
            } ?: defaults.selectedInterfaces,
            detailedLogging = json.optBoolean("detailedLogging", defaults.detailedLogging),
        ))
    }
}

class WpsAuditConfigSetting : ValueSetting<WpsSecurityAuditConfig>("wps", WpsSecurityAuditConfig()) {
    override fun toJsonValue(): JSONObject = current.let { config ->
        JSONObject().apply {
            put("macMode", config.macMode.name)
            put("macChangeThreshold", config.macChangeThreshold)
            put("retryCount", config.retryCount)
            put("cooldownBaseMillis", config.cooldownBaseMillis)
            put("attemptTimeoutMillis", config.attemptTimeoutMillis)
            put("maxAttemptsPerNetwork", config.maxAttemptsPerNetwork)
            put("detailedLogging", config.detailedLogging)
            put("interfaceName", config.interfaceName)
        }
    }

    override fun loadFromJsonValue(value: Any?) {
        val json = value as? JSONObject ?: run { resetToDefault(); return }
        val defaults = WpsSecurityAuditConfig()
        setLoadedValue(defaults.copy(
            macMode = PskSecurityAuditMacMode.entries.firstOrNull { it.name == json.optString("macMode") } ?: defaults.macMode,
            macChangeThreshold = json.optInt("macChangeThreshold", defaults.macChangeThreshold),
            retryCount = json.optInt("retryCount", defaults.retryCount),
            cooldownBaseMillis = json.optLong("cooldownBaseMillis", defaults.cooldownBaseMillis),
            attemptTimeoutMillis = json.optLong("attemptTimeoutMillis", defaults.attemptTimeoutMillis),
            maxAttemptsPerNetwork = json.optInt("maxAttemptsPerNetwork", defaults.maxAttemptsPerNetwork),
            detailedLogging = json.optBoolean("detailedLogging", defaults.detailedLogging),
            interfaceName = json.optString("interfaceName", defaults.interfaceName)
                .takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,15}")) } ?: defaults.interfaceName,
        ))
    }
}

