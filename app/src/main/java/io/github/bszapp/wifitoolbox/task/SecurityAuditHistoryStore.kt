package io.github.bszapp.wifitoolbox.task

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.bszapp.wifitoolbox.contract.task.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 单个数据库保存两类精简历史及完整候选字典。 */
internal class SecurityAuditHistoryStore(context: Context) {
    private val db = context.applicationContext.openOrCreateDatabase(
        "security_audit.db", Context.MODE_PRIVATE, null).apply {
        setForeignKeyConstraintsEnabled(true)
        prepareTables(this)
    }
    private val lock = Any()
    private val dictionaries = mutableMapOf<String, List<String>>()
    private val pendingDictionaries = mutableSetOf<String>()
    private val runningDictionaries = mutableSetOf<String>()
    private val _state = MutableStateFlow<List<AuditHistoryRecord>>(emptyList())
    val state = _state.asStateFlow()

    fun load() = synchronized(lock) { publish() }

    /** 候选字典自身不可变；同一 SSID 的历史行可以在不同字典之间切换引用。 */
    fun saveDictionary(id: String, source: File) = synchronized(lock) {
        check(!exists(db, "dictionary_sets", "id", id)) { "候选字典已经给定，不允许更新" }
        val passwords = source.readLines(Charsets.UTF_8)
        transaction(db) {
            db.insertOrThrow("dictionary_sets", null, ContentValues().apply { put("id", id) })
            db.compileStatement("INSERT INTO candidates(dictionary_id, position, password) VALUES(?, ?, ?)").use { statement ->
                passwords.forEachIndexed { index, password ->
                    statement.bindString(1, id); statement.bindLong(2, index.toLong()); statement.bindString(3, password)
                    statement.executeInsert()
                }
            }
        }
        dictionaries[id] = passwords
        pendingDictionaries.add(id)
        transaction(db) { cleanUnusedDictionaries(db) }
    }

    fun discardDictionary(id: String) = synchronized(lock) {
        pendingDictionaries.remove(id)
        transaction(db) { cleanUnusedDictionaries(db) }
    }

    /** 运行快照只向历史写入完成的尝试；返回已处理的服务事件顺序。 */
    fun merge(incoming: List<AuditRecord>, afterAttemptRevision: Long,
        activeDictionaryIds: Set<String>): Long = synchronized(lock) {
        runningDictionaries.clear()
        runningDictionaries.addAll(activeDictionaryIds)
        val ordered = incoming.filterIsInstance<AuditRecord.Dictionary>()
            .filter { it.historyRevision > afterAttemptRevision }
            .sortedWith(compareBy<AuditRecord.Dictionary> { it.historyRevision }
                .thenBy { it.password != null }) + incoming.filterIsInstance<AuditRecord.Wps>()
        transaction(db) {
            ordered.forEach { record ->
                val recordTime = if (record is AuditRecord.Dictionary) record.historyUpdatedAt else record.updatedAt
                val deletedAt = db.rawQuery("SELECT updated_at FROM deleted_snapshots WHERE id = ?", arrayOf(record.id)).use {
                    if (it.moveToFirst()) it.getLong(0) else Long.MIN_VALUE
                }
                if (recordTime <= deletedAt) return@forEach
                val table = if (record is AuditRecord.Dictionary) "dictionary_history" else "wps_history"
                val key = when (record) {
                    is AuditRecord.Dictionary -> record.ssid
                    is AuditRecord.Wps -> record.mac?.lowercase() ?: record.ssid
                }
                val old = db.rawQuery("SELECT id, updated_at FROM $table WHERE target = ?", arrayOf(key)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
                }
                if (old != null && old.second > recordTime) return@forEach
                val values = ContentValues().apply {
                    put("id", record.id); put("target", key); put("ssid", record.ssid)
                    put("updated_at", recordTime); put("password", record.password)
                }
                when (record) {
                    is AuditRecord.Dictionary -> {
                        val dictionaryId = record.input.dictionaryId
                        if (!exists(db, "dictionary_sets", "id", dictionaryId)) return@forEach
                        values.put("dictionary_id", dictionaryId); values.put("position", record.completed)
                        values.put("elapsed_ms", record.groupElapsedMillis)
                        values.put("average_ms", record.groupAverageAttemptMillis)
                        values.put("found_position", record.dictionaryPosition)
                        pendingDictionaries.remove(dictionaryId)
                    }
                    is AuditRecord.Wps -> {
                        values.put("bssid", record.mac); values.put("phase", record.phase.name)
                        values.put("first_half", record.firstHalf); values.put("first_index", record.firstHalfIndex)
                        values.put("second_index", record.secondHalfIndex); values.put("attempts", record.attempts)
                        values.put("elapsed_ms", record.elapsedMillis); values.put("average_ms", record.averageAttemptMillis)
                        values.put("pin", record.pin); values.put("found_phase", record.passwordFoundPhase?.name)
                    }
                }
                if (old != null && old.first != record.id) rememberDeleted(db, old.first, old.second)
                db.delete(table, "target = ?", arrayOf(key))
                db.delete(table, "id = ?", arrayOf(record.id))
                db.insertOrThrow(table, null, values)
                cleanUnusedDictionaries(db)
            }
        }
        publish()
        maxOf(afterAttemptRevision,
            incoming.filterIsInstance<AuditRecord.Dictionary>().maxOfOrNull { it.historyRevision } ?: 0L)
    }

    fun delete(id: String) = synchronized(lock) {
        val record = _state.value.firstOrNull { it.id == id }
        if (record == null) return@synchronized
        transaction(db) {
            rememberDeleted(db, record.id, record.updatedAt)
            db.delete("dictionary_history", "id = ?", arrayOf(id))
            if (record is AuditHistoryRecord.Wps) db.delete("wps_history", "id = ?", arrayOf(id))
            cleanUnusedDictionaries(db)
        }
        publish()
    }

    private fun cleanUnusedDictionaries(db: SQLiteDatabase) {
        val unused = db.rawQuery("SELECT id FROM dictionary_sets WHERE id NOT IN (SELECT dictionary_id FROM dictionary_history)", null).use {
            buildList { while (it.moveToNext()) if (it.getString(0) !in pendingDictionaries &&
                it.getString(0) !in runningDictionaries) add(it.getString(0)) }
        }
        unused.forEach { db.delete("dictionary_sets", "id = ?", arrayOf(it)); dictionaries.remove(it) }
    }

    private fun rememberDeleted(db: SQLiteDatabase, id: String, time: Long) {
        db.insertWithOnConflict("deleted_snapshots", null, ContentValues().apply {
            put("id", id); put("updated_at", time)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun publish() {
        val history = mutableListOf<AuditHistoryRecord>()
        db.rawQuery("SELECT * FROM dictionary_history", null).use { c ->
            while (c.moveToNext()) {
                val dictionaryId = c.string("dictionary_id")!!
                val passwords = dictionaries.getOrPut(dictionaryId) {
                    db.rawQuery("SELECT password FROM candidates WHERE dictionary_id = ? ORDER BY position", arrayOf(dictionaryId)).use { rows ->
                        buildList { while (rows.moveToNext()) add(rows.getString(0)) }
                    }
                }
                history += AuditHistoryRecord.Dictionary(c.string("id")!!, c.string("ssid")!!, passwords,
                    c.long("position").toInt(), c.string("password"), c.long("updated_at"),
                    c.long("elapsed_ms"), c.nullableLong("average_ms"),
                    c.nullableLong("found_position")?.toInt())
            }
        }
        db.rawQuery("SELECT * FROM wps_history", null).use { c ->
            while (c.moveToNext()) history += AuditHistoryRecord.Wps(
                c.string("id")!!, c.string("ssid")!!, c.string("bssid"), c.long("updated_at"),
                WpsSecurityAuditPhase.valueOf(c.string("phase")!!), c.string("first_half"),
                c.long("first_index").toInt(), c.long("second_index").toInt(), c.long("attempts"),
                c.long("elapsed_ms"), c.nullableLong("average_ms"), c.string("pin"), c.string("password"),
                c.string("found_phase")?.let(WpsSecurityAuditPhase::valueOf))
        }
        _state.value = history.sortedByDescending { it.updatedAt }
    }

    private inline fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun exists(db: SQLiteDatabase, table: String, column: String, id: String) =
        db.rawQuery("SELECT 1 FROM $table WHERE $column = ? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
    private fun Cursor.string(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }

    private fun prepareTables(db: SQLiteDatabase) {
        val expected = mapOf(
            "dictionary_sets" to setOf("id"),
            "candidates" to setOf("dictionary_id", "position", "password"),
            "dictionary_history" to setOf("target", "id", "ssid", "dictionary_id", "position",
                "password", "updated_at", "elapsed_ms", "average_ms", "found_position"),
            "wps_history" to setOf("target", "id", "ssid", "bssid", "updated_at", "phase",
                "first_half", "first_index", "second_index", "attempts", "elapsed_ms",
                "average_ms", "pin", "password", "found_phase"),
            "deleted_snapshots" to setOf("id", "updated_at"),
        )
        val knownTables = expected.keys + setOf("dictionary_groups", "dictionary_group_stats")
        val existing = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'", null).use { rows ->
            buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        check(existing.all { it in knownTables }) { "安全测试历史库包含未知数据表，无法清空" }
        val current = existing == expected.keys && expected.all { (table, columns) ->
            db.rawQuery("PRAGMA table_info($table)", null).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(1)) } == columns
            }
        }
        if (existing.isNotEmpty() && !current) {
            transaction(db) {
                listOf("dictionary_history", "dictionary_group_stats", "dictionary_groups",
                    "candidates", "dictionary_sets", "wps_history", "deleted_snapshots").forEach { table ->
                    db.execSQL("DROP TABLE IF EXISTS $table")
                }
            }
        }
        createTables(db)
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_sets(id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS candidates(dictionary_id TEXT NOT NULL REFERENCES dictionary_sets(id) ON DELETE CASCADE, " +
            "position INTEGER NOT NULL, password TEXT NOT NULL, PRIMARY KEY(dictionary_id, position))")
        db.execSQL("CREATE TABLE IF NOT EXISTS dictionary_history(target TEXT PRIMARY KEY, id TEXT NOT NULL UNIQUE, ssid TEXT NOT NULL, " +
            "dictionary_id TEXT NOT NULL REFERENCES dictionary_sets(id), position INTEGER NOT NULL, password TEXT, " +
            "updated_at INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, average_ms INTEGER, found_position INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS wps_history(target TEXT PRIMARY KEY, id TEXT NOT NULL UNIQUE, ssid TEXT NOT NULL, bssid TEXT, " +
            "updated_at INTEGER NOT NULL, phase TEXT NOT NULL, first_half TEXT, first_index INTEGER NOT NULL, " +
            "second_index INTEGER NOT NULL, attempts INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, average_ms INTEGER, " +
            "pin TEXT, password TEXT, found_phase TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_snapshots(id TEXT PRIMARY KEY, updated_at INTEGER NOT NULL)")
    }
}
