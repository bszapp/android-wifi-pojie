package io.github.bszapp.wifitoolbox.service.wifilog

import io.github.bszapp.wifitoolbox.contract.log.ParsedServiceLogLine
import io.github.bszapp.wifitoolbox.contract.log.ServiceLogEntry
import io.github.bszapp.wifitoolbox.service.LogcatRecorder
import java.io.ByteArrayOutputStream

internal fun decodeHexSsid(hexSsid: String): String {
    if (!hexSsid.contains("\\x")) return hexSsid
    return try {
        val output = ByteArrayOutputStream()
        var index = 0
        while (index < hexSsid.length) {
            if (
                hexSsid[index] == '\\' &&
                index + 3 < hexSsid.length &&
                hexSsid[index + 1] == 'x'
            ) {
                output.write(hexSsid.substring(index + 2, index + 4).toInt(16))
                index += 4
            } else {
                output.write(hexSsid[index].code)
                index++
            }
        }
        output.toString(Charsets.UTF_8.name())
    } catch (_: Exception) {
        hexSsid
    }
}

internal enum class WifiLogEventType {
    ASSOCIATION_STARTED,
    ROUTER_ASSOCIATED,
    WPA_HANDSHAKE_1_OF_4,
    WPA_HANDSHAKE_2_OF_4,
    WPA_HANDSHAKE_3_OF_4,
    WPA_HANDSHAKE_4_OF_4,
    KEY_NEGOTIATION_COMPLETED,
    HANDSHAKE_FAILED,
    PASSWORD_ERROR,
    ASSOCIATION_REJECTED,
    DISCONNECTED,
}

internal sealed interface WifiLogEvent {
    val ssid: String
    val rawLine: String
    val type: WifiLogEventType

    data class AssociationStarted(
        override val ssid: String,
        override val rawLine: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.ASSOCIATION_STARTED
    }

    data class RouterAssociated(
        override val ssid: String,
        override val rawLine: String,
        val bssid: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.ROUTER_ASSOCIATED
    }

    data class Handshake1Of4(
        override val ssid: String,
        override val rawLine: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.WPA_HANDSHAKE_1_OF_4
    }

    data class Handshake2Of4(
        override val ssid: String,
        override val rawLine: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.WPA_HANDSHAKE_2_OF_4
    }

    data class Handshake3Of4(
        override val ssid: String,
        override val rawLine: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.WPA_HANDSHAKE_3_OF_4
    }

    data class Handshake4Of4(
        override val ssid: String,
        override val rawLine: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.WPA_HANDSHAKE_4_OF_4
    }

    data class KeyNegotiationCompleted(
        override val ssid: String,
        override val rawLine: String,
        val bssid: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.KEY_NEGOTIATION_COMPLETED
    }

    data class HandshakeFailed(
        override val ssid: String,
        override val rawLine: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.HANDSHAKE_FAILED
    }

    data class PasswordError(
        override val ssid: String,
        override val rawLine: String,
        val reason: String,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.PASSWORD_ERROR
    }

    data class AssociationRejected(
        override val ssid: String,
        override val rawLine: String,
        val reason: String?,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.ASSOCIATION_REJECTED
    }

    data class Disconnected(
        override val ssid: String,
        override val rawLine: String,
        val bssid: String?,
        val reason: String?,
    ) : WifiLogEvent {
        override val type = WifiLogEventType.DISCONNECTED
    }
}

/**
 * Service 全生命周期运行的 wpa_supplicant 日志分析器。
 *
 * 每个 pid 必须先出现 Trying to associate with SSID，后续识别事件才会被发布。不同 pid
 * 的会话彼此隔离；订阅者收到的事件已经带有该 pid 当前正在处理的 SSID。
 */
internal class WifiLogAnalyzer(
    recorder: LogcatRecorder,
) : AutoCloseable {
    private val lock = Any()
    private val sessions = mutableMapOf<Long, String>()
    private val listeners = linkedSetOf<(WifiLogEvent) -> Unit>()
    private val recorderSubscription = recorder.subscribeEntries(::onLogEntry)

    fun subscribe(listener: (WifiLogEvent) -> Unit): AutoCloseable {
        synchronized(lock) { listeners += listener }
        return AutoCloseable {
            synchronized(lock) { listeners -= listener }
        }
    }

    override fun close() {
        recorderSubscription.close()
        synchronized(lock) {
            sessions.clear()
            listeners.clear()
        }
    }

    private fun onLogEntry(entry: ServiceLogEntry) {
        if (entry.tag != WPA_TAG) return
        val parsed = ParsedServiceLogLine.parse(entry.rawLine) ?: return
        if (parsed.tag != WPA_TAG) return
        val message = parsed.message

        val starting = ASSOCIATION_STARTED_PATTERN.find(message)
        if (starting != null) {
            val ssid = decodeHexSsid(starting.groupValues[1])
            val event = WifiLogEvent.AssociationStarted(
                ssid = ssid,
                rawLine = entry.rawLine,
            )
            val targets = synchronized(lock) {
                sessions[parsed.pid] = ssid
                listeners.toList()
            }
            targets.forEach { listener -> runCatching { listener(event) } }
            return
        }

        val ssid = synchronized(lock) { sessions[parsed.pid] } ?: return

        val event = recognize(
            ssid = ssid,
            rawLine = entry.rawLine,
            message = message,
        ) ?: return
        val targets = synchronized(lock) { listeners.toList() }
        targets.forEach { listener -> runCatching { listener(event) } }
    }

    private fun recognize(
        ssid: String,
        rawLine: String,
        message: String,
    ): WifiLogEvent? {
        ROUTER_ASSOCIATED_PATTERN.find(message)?.let { match ->
            return WifiLogEvent.RouterAssociated(
                ssid = ssid,
                rawLine = rawLine,
                bssid = match.groupValues[1],
            )
        }
        if (message.contains("WPA: RX message 1 of 4-Way Handshake from")) {
            return WifiLogEvent.Handshake1Of4(ssid, rawLine)
        }
        if (message.contains("WPA: Sending EAPOL-Key 2/4")) {
            return WifiLogEvent.Handshake2Of4(ssid, rawLine)
        }
        if (message.contains("RX message 3 of 4-Way Handshake from")) {
            return WifiLogEvent.Handshake3Of4(ssid, rawLine)
        }
        if (message.contains("WPA: Sending EAPOL-Key 4/4")) {
            return WifiLogEvent.Handshake4Of4(ssid, rawLine)
        }
        KEY_NEGOTIATION_PATTERN.find(message)?.let { match ->
            return WifiLogEvent.KeyNegotiationCompleted(
                ssid = ssid,
                rawLine = rawLine,
                bssid = match.groupValues[1],
            )
        }
        if (message.contains("reason=WRONG_KEY") ||
            message.contains("pre-shared key may be incorrect")
        ) {
            return WifiLogEvent.PasswordError(
                ssid = ssid,
                rawLine = rawLine,
                reason = "WRONG_KEY",
            )
        }
        if (message.contains("WPA: 4-Way Handshake failed")) {
            return WifiLogEvent.HandshakeFailed(ssid, rawLine)
        }
        if (message.contains("CTRL-EVENT-ASSOC-REJECT")) {
            return WifiLogEvent.AssociationRejected(
                ssid = ssid,
                rawLine = rawLine,
                reason = STATUS_CODE_PATTERN.find(message)?.groupValues?.get(1),
            )
        }
        if (message.contains("CTRL-EVENT-DISCONNECTED")) {
            return WifiLogEvent.Disconnected(
                ssid = ssid,
                rawLine = rawLine,
                bssid = BSSID_PATTERN.find(message)?.groupValues?.get(1),
                reason = REASON_PATTERN.find(message)?.groupValues?.get(1),
            )
        }
        return null
    }

    private companion object {
        const val WPA_TAG = "wpa_supplicant"
        val ASSOCIATION_STARTED_PATTERN = Regex("Trying to associate with SSID '(.*?)'")
        val ROUTER_ASSOCIATED_PATTERN = Regex("Associated with ([0-9a-fA-F:]{17})")
        val KEY_NEGOTIATION_PATTERN = Regex(
            "Key negotiation completed with ([0-9a-fA-F:]{17})",
        )
        val BSSID_PATTERN = Regex("bssid=([0-9a-fA-F:]{17})")
        val REASON_PATTERN = Regex("reason=([^\\s]+)")
        val STATUS_CODE_PATTERN = Regex("status_code=([^\\s]+)")

    }
}
