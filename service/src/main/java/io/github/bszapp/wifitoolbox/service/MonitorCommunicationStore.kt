package io.github.bszapp.wifitoolbox.service

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.github.bszapp.wifitoolbox.contract.PagedDataTransport
import io.github.bszapp.wifitoolbox.contract.wifilist.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

/**
 * 元数据、修订号和全文索引在 SQLite；正文仅追加到文件。查询和分块读取有索引，
 * 不扫描 PCAP、不把历史正文装入内存。锁内不回调控制器。
 */
internal class MonitorCommunicationStore(private val directory: File) : java.io.Closeable {
    private var db: SQLiteDatabase
    private var body: RandomAccessFile
    private var dhcpBody: RandomAccessFile
    private var revision = 0L
    private var nextId = 0L
    private val updatedIds = linkedSetOf<Long>()
    private var closed = false
    private val summaries = mutableMapOf<Pair<String, String>, Pair<Long, Long>>()
    private val identities = mutableMapOf<Pair<String, String>, Pair<String, String>>()

    init {
        check(directory.isDirectory || directory.mkdirs())
        db = SQLiteDatabase.openOrCreateDatabase(File(directory, "communications.db"), null)
        body = RandomAccessFile(File(directory, "content.bin"), "rw")
        dhcpBody = RandomAccessFile(File(directory, "dhcp.bin"), "rw")
        schema()
    }

    private fun schema() {
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=NORMAL")
        db.execSQL("CREATE TABLE IF NOT EXISTS records(id INTEGER PRIMARY KEY, bssid TEXT, mac TEXT, metadata TEXT, revision INTEGER)")
        fun hasColumn(table: String, column: String) = db.rawQuery("PRAGMA table_info($table)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == column) found = true
            found
        }
        if (!hasColumn("records", "retainName")) db.execSQL("ALTER TABLE records ADD COLUMN retainName INTEGER DEFAULT 0")
        if (!hasColumn("records", "sourceId")) {
            db.execSQL("ALTER TABLE records ADD COLUMN sourceId TEXT")
            db.execSQL("UPDATE records SET sourceId='communication:' || id")
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS record_source ON records(sourceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS record_retention ON records(retainName,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS record_revision ON records(revision)")
        db.execSQL("CREATE INDEX IF NOT EXISTS record_device ON records(bssid,mac,id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chunks(id INTEGER, channel TEXT, part INTEGER, start INTEGER, offset INTEGER, size INTEGER, lineEnd INTEGER DEFAULT 0, PRIMARY KEY(id,channel,part))")
        val hasLineEnd = db.rawQuery("PRAGMA table_info(chunks)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "lineEnd") found = true
            found
        }
        if (!hasLineEnd) db.execSQL("ALTER TABLE chunks ADD COLUMN lineEnd INTEGER DEFAULT 0")
        if (!hasColumn("chunks", "storage")) db.execSQL("ALTER TABLE chunks ADD COLUMN storage INTEGER DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS chunk_cursor ON chunks(id,channel,start)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS search USING fts4(recordId, content, notindexed=recordId, tokenize=unicode61)")
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { db.close() } finally { try { body.close() } finally { dhcpBody.close() } }
    }

    @Synchronized fun reset(retainDhcpNames: Boolean = false) {
        // Clearing must not walk an accumulated full-text index document by document.
        close()
        val database = File(directory, "communications.db")
        val previous = File(directory, "${UUID.randomUUID()}.db")
        if (retainDhcpNames) check(database.renameTo(previous)) { "无法保留 DHCP 通信索引" }
        else check(SQLiteDatabase.deleteDatabase(database) || !database.exists()) { "无法清理通信索引" }
        db = SQLiteDatabase.openOrCreateDatabase(database, null)
        body = RandomAccessFile(File(directory, "content.bin"), "rw").also { it.setLength(0) }
        dhcpBody = RandomAccessFile(File(directory, "dhcp.bin"), "rw").also { if (!retainDhcpNames) it.setLength(0) }
        closed = false
        schema()
        revision = 0; nextId = 0; updatedIds.clear(); summaries.clear()
        if (!retainDhcpNames) identities.clear()
        if (retainDhcpNames) {
            db.execSQL("ATTACH DATABASE ? AS previous", arrayOf(previous.path))
            try {
                db.beginTransaction()
                try {
                    // 新会话重新编码连续 ID，避免保留少量握手后产生数百万个空 ID 的补拉。
                    db.execSQL("CREATE TEMP TABLE retained_ids(id INTEGER PRIMARY KEY AUTOINCREMENT,oldId INTEGER UNIQUE)")
                    db.execSQL("INSERT INTO retained_ids(oldId) SELECT id FROM previous.records WHERE retainName<>0 ORDER BY id")
                    db.execSQL("INSERT INTO records(id,bssid,mac,metadata,revision,retainName,sourceId) SELECT m.id,r.bssid,r.mac,r.metadata,r.revision,r.retainName,r.sourceId FROM retained_ids m JOIN previous.records r ON r.id=m.oldId")
                    // 保留记录作外层、原 chunks 的主键索引查找作内层，不遍历数 GB 普通正文。
                    db.execSQL("INSERT INTO chunks SELECT m.id,c.channel,c.part,c.start,c.offset,c.size,c.lineEnd,c.storage FROM retained_ids m CROSS JOIN previous.chunks c WHERE c.id=m.oldId")
                    db.execSQL("DROP TABLE retained_ids")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { db.execSQL("DETACH DATABASE previous") }
            SQLiteDatabase.deleteDatabase(previous)
            db.rawQuery("SELECT id,bssid,mac,metadata,revision FROM records", null).use { records ->
                while (records.moveToNext()) {
                    val id = records.getLong(0)
                    nextId = maxOf(nextId, id)
                    val metadata = JSONObject(records.getString(3)).put("id", id.toString())
                    db.execSQL("UPDATE records SET metadata=? WHERE id=?", arrayOf<Any>(metadata.toString(), id))
                    revision = maxOf(revision, records.getLong(4))
                    val key = records.getString(1) to records.getString(2)
                    val old = summaries[key] ?: (0L to 0L)
                    summaries[key] = (old.first + 1) to maxOf(old.second, records.getLong(4))
                    index(id, metadata.toString())
                    db.rawQuery("SELECT channel,offset,size FROM chunks WHERE id=? ORDER BY channel,part", arrayOf(id.toString())).use { chunks ->
                        while (chunks.moveToNext()) {
                            val channel = MonitorCommunicationChannel.valueOf(chunks.getString(0))
                            if (channel in TEXT_CHANNELS) {
                                val bytes = ByteArray(chunks.getInt(2))
                                dhcpBody.seek(chunks.getLong(1)); dhcpBody.readFully(bytes)
                                index(id, bytes.toString(Charsets.UTF_8))
                            }
                        }
                    }
                }
            }
            identities.keys.retainAll(summaries.keys)
        }
    }

    @Synchronized fun summary(bssid: String, mac: String) = summaries[bssid to mac] ?: (0L to 0L)

    @Synchronized fun identity(bssid: String, mac: String, ssid: String?, name: String?) {
        val key = bssid to mac
        val value = ssid.orEmpty() to name.orEmpty()
        if (identities[key] == value) return
        identities[key] = value
        // Only identity changes touch old records; packet counters never cause this pass.
        db.rawQuery("SELECT id,metadata FROM records WHERE bssid=? AND mac=?", arrayOf(bssid, mac)).use { rows ->
            db.beginTransaction()
            try {
                while (rows.moveToNext()) {
                    val id = rows.getLong(0)
                    val metadata = JSONObject(rows.getString(1)).put("ssid", value.first).put("deviceName", value.second)
                    db.execSQL("UPDATE records SET metadata=?,revision=? WHERE id=?", arrayOf<Any>(metadata.toString(), ++revision, id))
                    updatedIds.add(id)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    private fun bump(bssid: String, mac: String, added: Boolean = false) {
        val previous = summary(bssid, mac)
        summaries[bssid to mac] = (previous.first + if (added) 1 else 0) to (previous.second + 1)
    }

    @Synchronized fun record(event: JSONObject) {
        val sourceId = (if (event.optString("kind") == "wpa_handshake") "handshake:" else "communication:") + event.getString("id")
        val existingId = sourceRecordId(sourceId)
        val id = existingId ?: (nextId + 1)
        val bssid = event.getString("bssid")
        val mac = event.getString("deviceMac")
        require(MAC.matches(bssid) && MAC.matches(mac))
        val previous = metadata(id)
        require(previous == null || (previous.getString("bssid") == bssid && previous.getString("deviceMac") == mac))
        val metadata = JSONObject(event.toString()).put("id", id.toString())
        identities[bssid to mac]?.let { metadata.put("ssid", it.first).put("deviceName", it.second) }
        val text = metadata.toString()
        require(text.toByteArray().size <= 8192)
        if (previous?.toString() == text) return
        val values = ContentValues().apply {
            put("id", id); put("bssid", bssid); put("mac", mac); put("metadata", text); put("revision", ++revision)
            put("sourceId", sourceId)
            put("retainName", if (event.optString("kind") == "wpa_handshake") 2 else if (event.optBoolean("retainDeviceName")) 1 else 0)
        }
        check(db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "保存通信索引失败" }
        if (existingId == null) nextId = id
        updatedIds.add(id)
        if (previous == null) index(id, event.optString("summary") + " " + event.optString("sourceAddress") + " " + event.optString("destinationAddress"))
        bump(bssid, mac, previous == null)
    }

    /** 握手逻辑 ID 与脚本 ID 仅作内部来源键；对外统一由服务分配正向递增 ID。 */
    @Synchronized fun handshake(bssid: String, mac: String, value: MonitorHandshakeRecord) {
        require(!value.hc22000.isNullOrBlank())
        val handshake = JSONObject().put("id", value.id).put("startUnixMillis", value.startUnixMillis)
            .put("durationMillis", value.durationMillis).put("status", value.status.name)
            .put("captureQuality", value.captureQuality.name)
            .put("capturedSteps", org.json.JSONArray(value.capturedSteps.map { it.name }))
            .put("capturedPacketTypes", org.json.JSONArray(value.capturedPacketTypes.map { it.name }))
            .put("failedAtStep", value.failedAtStep?.name).put("failureReason", value.failureReason?.name)
            .put("m2AttemptCount", value.m2AttemptCount).put("exportPacketCount", value.exportPacketCount)
            .put("hc22000", value.hc22000)
        record(JSONObject().put("id", value.id).put("bssid", bssid).put("deviceMac", mac)
            .put("kind", "wpa_handshake").put("timestampUnixMillis", value.startUnixMillis)
            .put("sourceAddress", mac).put("destinationAddress", bssid).put("summary", "WPA/WPA2 握手")
            .put("protocol", "WPA/WPA2握手").put("complete", value.status != MonitorHandshakeStatus.IN_PROGRESS)
            .put("handshake", handshake))
    }

    private fun metadata(id: Long): JSONObject? = db.rawQuery("SELECT metadata FROM records WHERE id=?", arrayOf(id.toString())).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    private fun sourceRecordId(sourceId: String): Long? = db.rawQuery("SELECT id FROM records WHERE sourceId=?", arrayOf(sourceId)).use {
        if (it.moveToFirst()) it.getLong(0) else null
    }

    @Synchronized fun range(session: Long) = MonitorCommunicationRange(session, 1L, nextId)

    @Synchronized fun takeUpdatedIds(): LongArray = updatedIds.toLongArray().also { updatedIds.clear() }

    private fun index(id: Long, text: String) {
        if (text.isBlank()) return
        db.insertOrThrow("search", null, ContentValues().apply { put("recordId", id); put("content", text) })
    }

    @Synchronized fun append(event: JSONObject, bytes: ByteArray) {
        require(bytes.size in 1..8192)
        val id = requireNotNull(sourceRecordId("communication:" + event.getString("id"))) { "通信来源记录不存在" }
        val entry = requireNotNull(metadata(id))
        require(entry.getString("bssid") == event.getString("bssid") && entry.getString("deviceMac") == event.getString("deviceMac"))
        val channel = MonitorCommunicationChannel.valueOf(event.optString("channel", "DETAIL"))
        val part = event.getInt("index")
        val (count, start) = db.rawQuery("SELECT part,start+size FROM chunks WHERE id=? AND channel=? ORDER BY part DESC LIMIT 1",
            arrayOf(id.toString(), channel.name)).use { if (it.moveToFirst()) (it.getInt(0) + 1) to it.getLong(1) else 0 to 0L }
        if (part < count) return
        require(part == count) { "通信正文分块不连续" }
        val storage = if (entry.getString("kind") == "dhcp") 1 else 0
        val target = if (storage == 1) dhcpBody else body
        val offset = target.length()
        target.seek(offset); target.write(bytes)
        val previousLines = db.rawQuery("SELECT lineEnd FROM chunks WHERE id=? AND channel=? ORDER BY part DESC LIMIT 1",
            arrayOf(id.toString(), channel.name)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        db.beginTransaction()
        try {
            db.insertOrThrow("chunks", null, ContentValues().apply {
                put("id", id); put("channel", channel.name); put("part", part); put("start", start); put("offset", offset); put("size", bytes.size)
                put("lineEnd", previousLines + bytes.count { it == 10.toByte() })
                put("storage", storage)
            })
            if (channel in TEXT_CHANNELS || event.optBoolean("searchable")) {
                // Overlap makes words split between FIFO chunks searchable.
                val previous = if (start > 0) readBytes(id, channel, (start - 1024).coerceAtLeast(0), minOf(start, 1024).toInt()) else byteArrayOf()
                index(id, (previous + bytes).toString(Charsets.UTF_8))
            }
            db.execSQL("UPDATE records SET revision=? WHERE id=?", arrayOf<Any>(++revision, id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        bump(entry.getString("bssid"), entry.getString("deviceMac"))
        updatedIds.add(id)
    }

    @Synchronized fun records(session: Long, ranges: LongArray, keyword: String): MonitorCommunicationPage {
        require(ranges.size in 2..200 && ranges.size % 2 == 0 && keyword.length <= 512)
        val requested = linkedSetOf<Long>()
        for (index in ranges.indices step 2) {
            val from = ranges[index]; val to = ranges[index + 1]
            require(from > 0 && to >= from && to - from < 100)
            for (id in from..to) { requested.add(id); require(requested.size <= 100) }
        }
        val records = ArrayList<MonitorCommunicationRecord>()
        val processed = ArrayList<Long>()
        val range = range(session)
        var bytes = PagedDataTransport.bytes(MonitorCommunicationPage(range, longArrayOf(), emptyList(), keyword)).size
        for (id in requested) {
            val record = db.rawQuery("SELECT metadata,revision FROM records WHERE id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) {
                    val entry = JSONObject(it.getString(0))
                    toRecord(entry, it.getLong(1)).copy(matchesSearch = matches(id, entry, keyword))
                } else null
            }
            val size = 16 + (record?.let(PagedDataTransport::bytes)?.size ?: 0)
            if (bytes + size > PagedDataTransport.MAX_PAGE_BYTES && processed.isNotEmpty()) break
            require(bytes + size <= PagedDataTransport.MAX_PAGE_BYTES) { "单条通信元数据超出传输预算" }
            bytes += size; processed.add(id)
            if (record != null) records.add(record)
        }
        return MonitorCommunicationPage(range, processed.toLongArray(), records, keyword)
    }

    @Synchronized fun search(session: Long, keyword: String, after: Long, through: Long): MonitorCommunicationSearchPage {
        require(keyword.length in 1..512 && after >= 0 && through >= after)
        // FTS 与元数据搜索只返回匹配 ID，不重新传送全部通信索引。
        val tokens = keyword.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val fields = listOf("ssid", "deviceName", "bssid", "deviceMac", "summary", "sourceAddress", "destinationAddress", "domain", "url", "method", "protocol", "contentType")
        val condition = tokens.joinToString(" AND ") {
            "(lower(r.metadata) LIKE ? ESCAPE '\\' OR r.id IN (SELECT CAST(recordId AS INTEGER) FROM search WHERE search MATCH ?))"
        }
        val args = mutableListOf(after.toString(), through.toString())
        tokens.forEach { token ->
            args.add("%" + token.lowercase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            args.add("\"" + token.replace("\"", "\"\"") + "\"")
        }
        val hits = ArrayList<MonitorCommunicationSearchHit>()
        // metadata 搜索还覆盖身份名称；正文保留原有 FTS 全文搜索。
        db.rawQuery("SELECT r.id,r.revision FROM records r WHERE r.id>? AND r.id<=? AND ${condition.ifEmpty { "1" }} ORDER BY r.id LIMIT 100", args.toTypedArray()).use {
            while (it.moveToNext()) hits.add(MonitorCommunicationSearchHit(it.getLong(0), it.getLong(1)))
        }
        return MonitorCommunicationSearchPage(session, keyword, if (hits.size < 100) through else hits.last().id, through, hits)
    }

    private fun matches(id: Long, value: JSONObject, keyword: String): Boolean {
        if (keyword.isBlank()) return true
        val tokens = keyword.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val identity = listOf("ssid", "deviceName", "bssid", "deviceMac", "summary", "sourceAddress", "destinationAddress", "domain", "url", "method", "protocol", "contentType")
            .joinToString(" ") { value.optString(it) }
        return tokens.all { token ->
            identity.contains(token, ignoreCase = true) || db.rawQuery(
                "SELECT 1 FROM search WHERE recordId=? AND search MATCH ? LIMIT 1",
                arrayOf(id.toString(), "\"" + token.replace("\"", "\"\"") + "\""),
            ).use { it.moveToFirst() }
        }
    }

    private fun readBytes(id: Long, channel: MonitorCommunicationChannel, cursor: Long, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val start = db.rawQuery("SELECT start FROM chunks WHERE id=? AND channel=? AND start<=? ORDER BY start DESC LIMIT 1",
            arrayOf(id.toString(), channel.name, cursor.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        db.rawQuery("SELECT start,offset,size,storage FROM chunks WHERE id=? AND channel=? AND start>=? AND start<? ORDER BY start",
            arrayOf(id.toString(), channel.name, start.toString(), (cursor + limit).toString())).use {
            while (it.moveToNext() && output.size() < limit) {
                val skip = (cursor - it.getLong(0)).coerceAtLeast(0).toInt()
                val size = minOf(it.getInt(2) - skip, limit - output.size())
                val input = if (it.getInt(3) == 1) dhcpBody else body
                input.seek(it.getLong(1) + skip)
                val bytes = ByteArray(size); input.readFully(bytes); output.write(bytes)
            }
        }
        return output.toByteArray()
    }

    @Synchronized fun detail(session: Long, bssid: String, mac: String, recordId: String, cursor: Long,
        channel: MonitorCommunicationChannel = MonitorCommunicationChannel.DETAIL): MonitorCommunicationDetailPage {
        val id = recordId.toLong()
        val entry = requireNotNull(metadata(id)) { "通信记录不存在" }
        require(entry.getString("bssid") == bssid && entry.getString("deviceMac") == mac)
        val total = db.rawQuery("SELECT start+size FROM chunks WHERE id=? AND channel=? ORDER BY part DESC LIMIT 1",
            arrayOf(recordId, channel.name)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        require(cursor in 0..total)
        val bytes = readBytes(id, channel, cursor, 65536)
        var startLine = 1L
        db.rawQuery("SELECT start,size,lineEnd FROM chunks WHERE id=? AND channel=? AND start<=? ORDER BY start DESC LIMIT 1",
            arrayOf(recordId, channel.name, cursor.toString())).use {
            if (it.moveToFirst()) {
                val chunkStart = it.getLong(0)
                val chunk = readBytes(id, channel, chunkStart, it.getInt(1))
                startLine += it.getLong(2) - chunk.count { byte -> byte == 10.toByte() } +
                    chunk.take((cursor - chunkStart).toInt()).count { byte -> byte == 10.toByte() }
            }
        }
        val next = cursor + bytes.size
        return MonitorCommunicationDetailPage(session, recordId, cursor, if (cursor == 0L) -1 else (cursor - 65536).coerceAtLeast(0),
            if (next >= total) -1 else next, bytes, total, entry.optBoolean("complete"), channel,
            toRecord(entry, db.rawQuery("SELECT revision FROM records WHERE id=?", arrayOf(recordId)).use { it.moveToFirst(); it.getLong(0) }), startLine)
    }

    private fun toRecord(value: JSONObject, revision: Long) = MonitorCommunicationRecord(
        id = value.getString("id"), timestampUnixMillis = value.getLong("timestampUnixMillis"),
        type = MonitorCommunicationType.valueOf(value.getString("kind").uppercase()),
        source = value.getString("sourceAddress"), destination = value.getString("destinationAddress"),
        summary = value.getString("summary"), complete = value.getBoolean("complete"),
        bssid = value.getString("bssid"), deviceMac = value.getString("deviceMac"),
        ssid = value.optString("ssid"), deviceName = value.optString("deviceName"),
        uploadBytes = value.optLong("uploadBytes"), downloadBytes = value.optLong("downloadBytes"), revision = revision,
        tcpStreamId = value.optString("tcpStreamId").takeIf(String::isNotEmpty)?.let { sourceRecordId("communication:$it")?.toString() }.orEmpty(),
        transport = MonitorCommunicationTransport.valueOf(value.optString("transport", "other").uppercase()),
        method = value.optString("method"), url = value.optString("url"), domain = value.optString("domain"),
        protocol = value.optString("protocol"), statusCode = value.optInt("statusCode"),
        contentType = value.optString("contentType"), requestHeaderCount = value.optInt("requestHeaderCount"),
        responseHeaderCount = value.optInt("responseHeaderCount"), streamContainer = value.optBoolean("streamContainer"),
        handshake = value.optJSONObject("handshake")?.let { data ->
            MonitorHandshakeRecord(
                id = data.getString("id"), startUnixMillis = data.getLong("startUnixMillis"),
                durationMillis = if (data.isNull("durationMillis")) null else data.getLong("durationMillis"), status = MonitorHandshakeStatus.valueOf(data.getString("status")),
                canValidate = true, hc22000 = data.getString("hc22000"),
                captureQuality = MonitorHandshakeCaptureQuality.valueOf(data.getString("captureQuality")),
                capturedSteps = data.getJSONArray("capturedSteps").let { steps ->
                    List(steps.length()) { MonitorHandshakeStep.valueOf(steps.getString(it)) }
                },
                capturedPacketTypes = data.getJSONArray("capturedPacketTypes").let { types ->
                    List(types.length()) { MonitorHandshakePacketType.valueOf(types.getString(it)) }
                },
                failedAtStep = data.optString("failedAtStep").takeIf(String::isNotBlank)?.let(MonitorHandshakeStep::valueOf),
                failureReason = data.optString("failureReason").takeIf(String::isNotBlank)?.let(MonitorHandshakeFailureReason::valueOf),
                m2AttemptCount = data.getInt("m2AttemptCount"), exportPacketCount = data.getInt("exportPacketCount"),
            )
        },
    )

    private companion object {
        val MAC = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        val TEXT_CHANNELS = setOf(MonitorCommunicationChannel.DETAIL, MonitorCommunicationChannel.REQUEST,
            MonitorCommunicationChannel.RESPONSE, MonitorCommunicationChannel.REQUEST_HEADERS, MonitorCommunicationChannel.RESPONSE_HEADERS,
            MonitorCommunicationChannel.REQUEST_BODY, MonitorCommunicationChannel.RESPONSE_BODY)
    }
}
