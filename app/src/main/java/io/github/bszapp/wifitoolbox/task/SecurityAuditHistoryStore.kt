package io.github.bszapp.wifitoolbox.task

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.bszapp.wifitoolbox.contract.task.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 单个数据库保存两类精简历史及完整候选字典；不读取旧文件。 */
internal class SecurityAuditHistoryStore(context: Context) {
    private val helper = Database(context.applicationContext)
    private val lock = Any()
    private val dictionaries = mutableMapOf<String, List<String>>()
    private val pendingDictionaries = mutableSetOf<String>()
    private val _state = MutableStateFlow<List<AuditHistoryRecord>>(emptyList())
    val state = _state.asStateFlow()

    fun load() = synchronized(lock) { publish() }

    /** 每个字典只在首次给定时插入，运行更新不改动候选数据。 */
    fun saveDictionary(id: String, source: File) = synchronized(lock) {
        val db = helper.writableDatabase
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
    }

    fun discardDictionary(id: String) = synchronized(lock) {
        pendingDictionaries.remove(id)
        if (!exists(helper.writableDatabase, "dictionary_history", "dictionary_id", id)) {
            helper.writableDatabase.delete("dictionary_sets", "id = ?", arrayOf(id))
            dictionaries.remove(id)
        }
    }

    fun merge(incoming: List<AuditRecord>) = synchronized(lock) {
        val db = helper.writableDatabase
        transaction(db) {
            incoming.forEach { record ->
                val deletedAt = db.rawQuery("SELECT updated_at FROM deleted_snapshots WHERE id = ?", arrayOf(record.id)).use {
                    if (it.moveToFirst()) it.getLong(0) else Long.MIN_VALUE
                }
                if (record.updatedAt <= deletedAt) return@forEach
                val table = if (record is AuditRecord.Dictionary) "dictionary_history" else "wps_history"
                val key = when (record) {
                    is AuditRecord.Dictionary -> record.ssid
                    is AuditRecord.Wps -> record.mac?.lowercase() ?: record.ssid
                }
                val old = db.rawQuery("SELECT id, updated_at FROM $table WHERE target = ?", arrayOf(key)).use {
                    if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
                }
                if (old != null && old.second > record.updatedAt) return@forEach
                val values = ContentValues().apply {
                    put("id", record.id); put("target", key); put("ssid", record.ssid)
                    put("updated_at", record.updatedAt); put("password", record.password)
                }
                when (record) {
                    is AuditRecord.Dictionary -> {
                        val dictionaryId = db.rawQuery("SELECT dictionary_id FROM dictionary_history WHERE id = ?", arrayOf(record.id)).use {
                            if (it.moveToFirst()) it.getString(0) else record.input.dictionaryId
                        }
                        check(dictionaryId == record.input.dictionaryId) { "运行中的候选字典不可更换" }
                        if (!exists(db, "dictionary_sets", "id", dictionaryId)) return@forEach
                        values.put("dictionary_id", dictionaryId); values.put("position", record.completed)
                        values.put("elapsed_ms", record.elapsedMillis); values.put("average_ms", record.averageAttemptMillis)
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
                db.delete(table, "id = ? AND target <> ?", arrayOf(record.id, key))
                check(db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            cleanUnusedDictionaries(db)
        }
        publish()
    }

    fun delete(id: String) = synchronized(lock) {
        val record = _state.value.firstOrNull { it.id == id } ?: return@synchronized
        val db = helper.writableDatabase
        transaction(db) {
            rememberDeleted(db, id, record.updatedAt)
            db.delete("dictionary_history", "id = ?", arrayOf(id))
            db.delete("wps_history", "id = ?", arrayOf(id))
            cleanUnusedDictionaries(db)
        }
        publish()
    }

    private fun cleanUnusedDictionaries(db: SQLiteDatabase) {
        val unused = db.rawQuery("SELECT id FROM dictionary_sets WHERE id NOT IN (SELECT dictionary_id FROM dictionary_history)", null).use {
            buildList { while (it.moveToNext()) if (it.getString(0) !in pendingDictionaries) add(it.getString(0)) }
        }
        unused.forEach { db.delete("dictionary_sets", "id = ?", arrayOf(it)); dictionaries.remove(it) }
    }

    private fun rememberDeleted(db: SQLiteDatabase, id: String, time: Long) {
        db.insertWithOnConflict("deleted_snapshots", null, ContentValues().apply {
            put("id", id); put("updated_at", time)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun publish() {
        val db = helper.readableDatabase
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
                    c.long("position").toInt(), c.string("password"), c.long("updated_at"), c.long("elapsed_ms"),
                    c.nullableLong("average_ms"), c.nullableLong("found_position")?.toInt())
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

    private class Database(context: Context) : SQLiteOpenHelper(context, "security_audit.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE dictionary_sets(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE candidates(dictionary_id TEXT NOT NULL REFERENCES dictionary_sets(id) ON DELETE CASCADE, " +
                "position INTEGER NOT NULL, password TEXT NOT NULL, PRIMARY KEY(dictionary_id, position))")
            db.execSQL("CREATE TABLE dictionary_history(target TEXT PRIMARY KEY, id TEXT NOT NULL UNIQUE, ssid TEXT NOT NULL, " +
                "dictionary_id TEXT NOT NULL REFERENCES dictionary_sets(id), position INTEGER NOT NULL, password TEXT, " +
                "updated_at INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, average_ms INTEGER, found_position INTEGER)")
            db.execSQL("CREATE TABLE wps_history(target TEXT PRIMARY KEY, id TEXT NOT NULL UNIQUE, ssid TEXT NOT NULL, bssid TEXT, " +
                "updated_at INTEGER NOT NULL, phase TEXT NOT NULL, first_half TEXT, first_index INTEGER NOT NULL, " +
                "second_index INTEGER NOT NULL, attempts INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, average_ms INTEGER, " +
                "pin TEXT, password TEXT, found_phase TEXT)")
            db.execSQL("CREATE TABLE deleted_snapshots(id TEXT PRIMARY KEY, updated_at INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("不支持历史格式迁移")
    }
}
