package io.github.bszapp.wifitoolbox.wifilist

import io.github.bszapp.wifitoolbox.contract.wifilist.*

/** 单个服务连接内的增量镜像，不跨连接复用。 */
internal class MonitorMirror {
    var session = -1L
        private set
    var revision = 0L
        private set
    private val points = linkedMapOf<String, MonitorAccessPoint>()
    private val devices = linkedMapOf<String, LinkedHashMap<String, MonitorDevice>>()
    private val handshakes = mutableMapOf<Pair<String, String>, LinkedHashMap<String, MonitorHandshakeRecord>>()
    private val disconnected = linkedMapOf<String, MonitorDisconnectionRecord>()
    private val dirtyPoints = linkedSetOf<String>()

    fun apply(page: MonitorChangesPage) {
        if (session != page.header.sessionGeneration) {
            points.clear(); devices.clear(); handshakes.clear(); disconnected.clear(); dirtyPoints.clear()
            session = page.header.sessionGeneration
            revision = 0L
        }
        for (change in page.changes) when (change) {
            is MonitorChange.AccessPoint -> {
                points[change.value.bssid] = change.value
                dirtyPoints.add(change.value.bssid)
            }
            is MonitorChange.Device -> {
                devices.getOrPut(change.bssid) { linkedMapOf() }[change.value.mac] = change.value
                dirtyPoints.add(change.bssid)
            }
            is MonitorChange.Handshake -> {
                handshakes.getOrPut(change.bssid to change.deviceMac) { linkedMapOf() }[change.value.id] = change.value
                dirtyPoints.add(change.bssid)
            }
            is MonitorChange.Disconnection -> {
                val record = change.value
                disconnected["${record.bssid}:${record.deviceMac}:${record.id}"] = record
            }
        }
        revision = page.nextRevision
    }

    fun snapshot(header: MonitorModeStatistics): MonitorModeStatistics {
        dirtyPoints.forEach { bssid ->
            val point = points[bssid] ?: return@forEach
            points[bssid] = point.copy(devices = devices[bssid]?.values.orEmpty().map { device ->
                device.copy(handshakes = handshakes[bssid to device.mac]?.values.orEmpty().toList())
            })
        }
        dirtyPoints.clear()
        return header.copy(accessPoints = points.values.toList(), disconnections = disconnected.values.toList())
    }
}
