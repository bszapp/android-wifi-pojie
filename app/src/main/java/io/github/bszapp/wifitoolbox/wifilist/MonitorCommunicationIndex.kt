package io.github.bszapp.wifitoolbox.wifilist

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Parcel
import io.github.bszapp.wifitoolbox.contract.wifilist.*
import java.io.File

/** 跟随 App 与当前服务连接的通信元数据镜像；页面是否存在不影响同步。 */
internal class MonitorCommunicationIndex(context: Context) {
    private val db by lazy {
        SQLiteDatabase.openOrCreateDatabase(File(context.cacheDir, "monitor-communication-index.db"), null).apply {
            execSQL("DROP TABLE IF EXISTS records")
            execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY, revision INTEGER, timestamp INTEGER, bssid TEXT, mac TEXT, ssid TEXT, name TEXT, type TEXT, content TEXT, status INTEGER, container INTEGER, matches INTEGER, data BLOB)")
            execSQL("CREATE INDEX communication_time ON records(timestamp DESC,id DESC)")
            execSQL("CREATE INDEX communication_revision ON records(revision)")
            execSQL("CREATE INDEX communication_ap ON records(bssid,mac,id)")
            execSQL("CREATE INDEX communication_type ON records(type,id)")
            execSQL("CREATE INDEX communication_filters ON records(container,matches,id)")
            execSQL("DROP TABLE IF EXISTS facets")
            execSQL("CREATE TABLE facets(kind TEXT,value TEXT,count INTEGER,PRIMARY KEY(kind,value))")
            execSQL("DROP TABLE IF EXISTS search_matches")
            execSQL("CREATE TABLE search_matches(id INTEGER PRIMARY KEY,version INTEGER)")
        }
    }
    private var scope: Pair<Long, Long>? = null
    private var oldest = 1L
    private var latest = 0L
    private var fetchedThrough = 0L
    private var updateSequence = 0L
    private val pendingUpdates = linkedMapOf<Long, Long>()
    private var keyword = ""
    private var searchCursor = 0L
    private var searchThrough = 0L

    data class Request(val generation: Long, val session: Long, val ids: List<Long>,
        val updateTokens: Map<Long, Long>, val keyword: String) {
        fun ranges(): LongArray {
            val result = ArrayList<Long>()
            ids.forEach { id ->
                if (result.isNotEmpty() && result.last() + 1 == id) result[result.lastIndex] = id
                else { result.add(id); result.add(id) }
            }
            return result.toLongArray()
        }
    }
    data class SearchRequest(val generation: Long, val session: Long, val keyword: String, val after: Long, val through: Long)

    @Synchronized fun reset(generation: Long, session: Long) {
        if ((scope?.first ?: -1L) > generation) return
        if (scope?.first == generation && (scope?.second ?: -1L) > session) return
        if (scope == (generation to session)) return
        db.execSQL("DELETE FROM records")
        db.execSQL("DELETE FROM facets")
        db.execSQL("DELETE FROM search_matches")
        scope = generation to session; oldest = 1; latest = 0; fetchedThrough = 0; keyword = ""
        pendingUpdates.clear(); searchCursor = 0; searchThrough = 0
    }

    @Synchronized fun isCurrent(generation: Long, session: Long) = scope == (generation to session)
    @Synchronized fun requestSession(generation: Long): Long? = scope?.takeIf { it.first == generation }?.second?.takeIf { it >= 0 }

    /** 范围裁剪与待更新 ID 由 App 保存，不向 ViewModel/UI 暴露同步阶段。 */
    @Synchronized fun announce(generation: Long, range: MonitorCommunicationRange, updated: LongArray): Boolean {
        val scopeChanged = scope != (generation to range.sessionGeneration)
        reset(generation, range.sessionGeneration)
        if (!isCurrent(generation, range.sessionGeneration)) return false
        require(range.oldestAvailableId > 0 && range.latestId >= 0 && updated.size <= 100)
        val nextOldest = maxOf(oldest, range.oldestAvailableId)
        val nextLatest = maxOf(latest, range.latestId)
        var changed = scopeChanged
        if (nextOldest > oldest || nextLatest < nextOldest) {
            db.rawQuery("SELECT data FROM records WHERE id<? OR id>?", arrayOf(nextOldest.toString(), nextLatest.toString())).use {
                while (it.moveToNext()) { updateFacets(decode(it.getBlob(0)), -1); changed = true }
            }
            db.execSQL("DELETE FROM records WHERE id<? OR id>?", arrayOf(nextOldest, nextLatest))
            db.execSQL("DELETE FROM search_matches WHERE id<? OR id>?", arrayOf(nextOldest, nextLatest))
        }
        oldest = nextOldest; latest = nextLatest
        fetchedThrough = maxOf(fetchedThrough, oldest - 1)
        pendingUpdates.keys.removeAll { it !in oldest..latest }
        for (id in updated) if (id in oldest..latest) pendingUpdates[id] = ++updateSequence
        return changed
    }

    @Synchronized fun requestKeyword(value: String): Boolean {
        require(value.length <= 512)
        if (keyword == value) return false
        keyword = value
        db.execSQL("DELETE FROM search_matches")
        searchCursor = oldest - 1; searchThrough = latest
        return true
    }

    @Synchronized fun request(): Request? {
        val owner = scope ?: return null
        val missing = fetchedThrough < latest
        val updates = pendingUpdates.entries.filter { it.key <= fetchedThrough }.take(if (missing) 50 else 100).map { it.key }
        val ids = ArrayList<Long>()
        // 缺失段与已更新的旧 ID 同批请求，双方总量不超过 100。
        if (missing) for (id in (fetchedThrough + 1)..minOf(latest, fetchedThrough + 100 - updates.size)) ids.add(id)
        ids.addAll(updates)
        if (ids.isEmpty()) return null
        return Request(owner.first, owner.second, ids, ids.mapNotNull { id -> pendingUpdates[id]?.let { id to it } }.toMap(), keyword)
    }

    @Synchronized fun apply(request: Request, page: MonitorCommunicationPage): Boolean {
        if (!isCurrent(request.generation, request.session) || page.range.sessionGeneration != request.session) return false
        require(page.processedIds.isNotEmpty() && page.processedIds.size <= 100 && page.processedIds.all { it in request.ids })
        require(page.records.all { it.id.toLong() in page.processedIds })
        announce(request.generation, page.range, longArrayOf())
        db.beginTransaction()
        try {
            for (record in page.records) {
                if (record.id.toLong() !in oldest..latest) continue
                val old = db.rawQuery("SELECT data FROM records WHERE id=?", arrayOf(record.id)).use {
                    if (it.moveToFirst()) decode(it.getBlob(0)) else null
                }
                if (old != null && old.revision > record.revision) continue
                if (old != null) updateFacets(old, -1)
                updateFacets(record, 1)
                val parcel = Parcel.obtain()
                val bytes = try { parcel.writeParcelable(record, 0); parcel.marshall() } finally { parcel.recycle() }
                check(db.insertWithOnConflict("records", null, ContentValues().apply {
                    put("id", record.id.toLong()); put("revision", record.revision); put("data", bytes)
                    put("timestamp", record.timestampUnixMillis)
                    put("bssid", record.bssid); put("mac", record.deviceMac); put("ssid", record.ssid); put("name", record.deviceName)
                    put("type", record.type.name); put("content", contentKind(record.contentType)); put("status", record.statusCode / 100)
                    put("container", if (record.streamContainer) 1 else 0); put("matches", if (record.matchesSearch) 1 else 0)
                }, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "保存通信元数据失败" }
                if (keyword == page.keyword && keyword.isNotBlank()) {
                    if (record.matchesSearch) setSearchMatch(record.id.toLong(), record.revision)
                    else db.execSQL("DELETE FROM search_matches WHERE id=? AND version<=?", arrayOf(record.id.toLong(), record.revision))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val processed = page.processedIds.toSet()
        while (fetchedThrough < latest && fetchedThrough + 1 in processed) fetchedThrough++
        request.updateTokens.forEach { (id, token) ->
            if (id in processed && pendingUpdates[id] == token) pendingUpdates.remove(id)
        }
        return true
    }

    @Synchronized fun searchRequest(): SearchRequest? {
        val owner = scope ?: return null
        if (keyword.isBlank() || searchCursor >= searchThrough) return null
        return SearchRequest(owner.first, owner.second, keyword, searchCursor, searchThrough)
    }

    @Synchronized fun applySearch(request: SearchRequest, page: MonitorCommunicationSearchPage): Boolean {
        if (!isCurrent(request.generation, request.session) || page.sessionGeneration != request.session ||
            keyword != request.keyword || page.keyword != keyword || searchCursor != request.after) return false
        require(page.nextId > request.after && page.nextId <= request.through && page.hits.size <= 100)
        for (hit in page.hits) {
            if (hit.id !in oldest..latest) continue
            val version = db.rawQuery("SELECT revision FROM records WHERE id=?", arrayOf(hit.id.toString())).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            if (version <= hit.version) setSearchMatch(hit.id, hit.version)
        }
        searchCursor = page.nextId
        return true
    }

    private fun setSearchMatch(id: Long, version: Long) {
        db.execSQL("INSERT OR IGNORE INTO search_matches(id,version) VALUES(?,?)", arrayOf(id, version))
        db.execSQL("UPDATE search_matches SET version=? WHERE id=? AND version<=?", arrayOf(version, id, version))
    }

    @Synchronized fun window(generation: Long, query: MonitorCommunicationQuery, first: Int, last: Int): MonitorCommunicationWindow {
        val session = scope?.takeIf { it.first == generation }?.second?.takeIf { it >= 0 }
            ?: return MonitorCommunicationWindow(null, 0, emptyMap())
        val conditions = mutableListOf("container=0")
        val args = mutableListOf<String>()
        if (query.keyword.isNotBlank()) conditions += "id IN (SELECT id FROM search_matches)"
        if (query.accessPoint.isNotBlank()) {
            conditions += "(bssid LIKE ? OR ssid LIKE ?)"; args += "%${query.accessPoint}%"; args += "%${query.accessPoint}%"
        }
        if (query.device.isNotBlank()) {
            conditions += "(mac LIKE ? OR name LIKE ?)"; args += "%${query.device}%"; args += "%${query.device}%"
        }
        fun options(column: String, values: Collection<String>) {
            if (values.isNotEmpty()) { conditions += "$column IN (${values.joinToString(",") { "?" }})"; args.addAll(values) }
        }
        options("type", query.protocols); options("content", query.contentKinds); options("status", query.statusGroups.map(Int::toString))
        val where = conditions.joinToString(" AND ")
        val count = db.rawQuery("SELECT COUNT(*) FROM records WHERE $where", args.toTypedArray()).use {
            it.moveToFirst(); it.getLong(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val start = ((first / 32 - 1).coerceAtLeast(0)) * 32
        val limit = (last - start + 64).coerceIn(64, 256)
        val rows = mutableMapOf<Int, MonitorCommunicationRecord>()
        db.rawQuery("SELECT data FROM records WHERE $where ORDER BY timestamp DESC,id DESC LIMIT ? OFFSET ?",
            (args + limit.toString() + start.toString()).toTypedArray()).use {
            var index = start
            while (it.moveToNext()) rows[index++] = decode(it.getBlob(0))
        }
        fun distinct(column: String): Set<String> = db.rawQuery("SELECT value FROM facets WHERE kind=?", arrayOf(column)).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }
        val availability = MonitorCommunicationAvailability(distinct("bssid"), distinct("mac"), distinct("type"), distinct("content"),
            distinct("status").mapNotNull { it.toIntOrNull()?.takeIf { status -> status > 0 } }.toSet(), distinct("pair"))
        return MonitorCommunicationWindow(session, count, rows, available = availability)
    }

    private fun updateFacets(record: MonitorCommunicationRecord, delta: Int) {
        if (record.streamContainer) return
        val values = mapOf("bssid" to record.bssid, "mac" to record.deviceMac, "type" to record.type.name,
            "content" to contentKind(record.contentType), "status" to (record.statusCode / 100).toString(),
            "pair" to "${record.bssid}/${record.deviceMac}")
        for ((kind, value) in values) {
            db.execSQL("INSERT OR IGNORE INTO facets(kind,value,count) VALUES(?,?,0)", arrayOf(kind, value))
            db.execSQL("UPDATE facets SET count=count+? WHERE kind=? AND value=?", arrayOf<Any>(delta, kind, value))
            db.execSQL("DELETE FROM facets WHERE kind=? AND value=? AND count<=0", arrayOf(kind, value))
        }
    }

    private fun decode(bytes: ByteArray): MonitorCommunicationRecord = Parcel.obtain().let {
        try { it.unmarshall(bytes, 0, bytes.size); it.setDataPosition(0)
            requireNotNull(it.readParcelable<MonitorCommunicationRecord>(MonitorCommunicationRecord::class.java.classLoader))
        } finally { it.recycle() }
    }

    private fun contentKind(type: String): String = when {
        type.contains("json", true) -> "JSON"
        type.contains("xml", true) -> "XML"
        type.contains("html", true) -> "HTML"
        type.contains("javascript", true) -> "JS"
        type.startsWith("image/", true) -> "图片"
        type.startsWith("audio/", true) || type.startsWith("video/", true) -> "媒体"
        type.startsWith("text/", true) || type.contains("form", true) -> "文本"
        else -> "二进制"
    }

}
