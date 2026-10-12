package io.github.bszapp.wifitoolbox.service.wifilog

import io.github.bszapp.wifitoolbox.service.CapturedLogEntry
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
    private val recorder: LogcatRecorder,
) : AutoCloseable {
    private val lock = Any()
    private val sessions = mutableMapOf<Long, Session>()
    private val listeners = linkedSetOf<(WifiLogEvent) -> Unit>()
    private val attemptListeners = linkedSetOf<(WifiLogObservation) -> Unit>()
    private val recorderSubscription = recorder.subscribeCapturedEntries(::onLogEntry)

    fun subscribe(listener: (WifiLogEvent) -> Unit): AutoCloseable {
        synchronized(lock) { listeners += listener }
        return AutoCloseable {
            synchronized(lock) { listeners -= listener }
        }
    }

    fun subscribeAttempt(listener: (WifiLogObservation) -> Unit): AutoCloseable {
        synchronized(lock) { attemptListeners += listener }
        return AutoCloseable { synchronized(lock) { attemptListeners -= listener } }
    }

    fun latestId(): Long = recorder.latestId()

    override fun close() {
        recorderSubscription.close()
        synchronized(lock) {
            sessions.clear()
            listeners.clear()
            attemptListeners.clear()
        }
    }

    private fun onLogEntry(record: CapturedLogEntry) {
        if (record.tag != WPA_TAG) return
        val entry = record.entry
        val message = record.message
        val starting = ASSOCIATION_STARTED_PATTERN.find(message)
        val session = synchronized(lock) {
            if (starting != null) {
                Session(decodeHexSsid(starting.groupValues[1]), entry.id, record.timestampMillis)
                    .also { sessions[record.pid] = it }
            } else {
                sessions[record.pid]
            }
        } ?: return
        val event = if (starting != null) WifiLogEvent.AssociationStarted(session.ssid, entry.rawLine)
            else recognize(session.ssid, entry.rawLine, message) ?: return
        val observation = WifiLogObservation(event, entry.id, record.timestampMillis,
            record.receivedAtElapsed, session.startId, session.startedAtMillis)
        val targets = synchronized(lock) { attemptListeners.toList() to listeners.toList() }
        targets.first.forEach { listener -> runCatching { listener(observation) } }
        targets.second.forEach { listener -> runCatching { listener(event) } }
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

    private data class Session(val ssid: String, val startId: Long, val startedAtMillis: Long)
}

/** 轮次过滤所需的内部边界；PID 只保留在分析器的会话表中。 */
internal data class WifiLogObservation(
    val event: WifiLogEvent,
    val logId: Long,
    val timestampMillis: Long,
    val receivedAtElapsed: Long,
    val associationStartId: Long,
    val associationStartedAtMillis: Long,
)
