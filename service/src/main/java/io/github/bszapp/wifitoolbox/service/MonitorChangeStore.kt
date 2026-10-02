package io.github.bszapp.wifitoolbox.service

import io.github.bszapp.wifitoolbox.contract.PagedDataTransport
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChange
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorChangesPage
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorModeStatistics
import java.util.TreeMap

/** 由 MonitorModeController 的锁保护。每个实体只留最新版本；补拉游标沿版本前进。 */
internal class MonitorChangeStore {
    private val versions = mutableMapOf<String, Long>()
    private val latest = TreeMap<Long, MonitorChange>()
    var revision = 0L
        private set

    fun clear() { versions.clear(); latest.clear(); revision = 0L }

    fun fork(): MonitorChangeStore = MonitorChangeStore().also {
        it.versions.putAll(versions)
        it.latest.putAll(latest)
        it.revision = revision
    }

    fun put(key: String, value: MonitorChange) {
        val previous = versions[key]
        if (previous != null && latest[previous] == value) return
        previous?.let(latest::remove)
        revision++
        versions[key] = revision
        latest[revision] = value
    }

    fun page(header: MonitorModeStatistics, after: Long): MonitorChangesPage {
        val selected = ArrayList<MonitorChange>()
        var cursor = after
        var bytes = PagedDataTransport.bytes(MonitorChangesPage(header, cursor, emptyList())).size
        for ((version, change) in latest.tailMap(after, false)) {
            val size = PagedDataTransport.bytes(change).size + 32
            require(size + 1024 <= PagedDataTransport.MAX_PAGE_BYTES) { "单个监听实体超出分页预算" }
            if (selected.size >= 100 || bytes + size > PagedDataTransport.MAX_PAGE_BYTES - 1024) break
            selected.add(change)
            bytes += size
            cursor = version
        }
        if (selected.isEmpty()) cursor = revision
        return MonitorChangesPage(header, cursor, selected)
    }
}
