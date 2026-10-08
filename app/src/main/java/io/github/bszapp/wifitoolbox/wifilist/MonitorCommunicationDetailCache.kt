package io.github.bszapp.wifitoolbox.wifilist

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.github.bszapp.wifitoolbox.contract.wifilist.*
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** App 所有的只读详情快照。正文全部落盘，每块与单次 IPC 限制为 64 KiB。 */
internal class MonitorCommunicationDetailCache(private val context: Context) {
    private data class Channel(val size: Long, val complete: Boolean)
    private data class Snapshot(val directory: File, val session: Long, @Volatile var record: MonitorCommunicationRecord,
        @Volatile var channels: Map<MonitorCommunicationChannel, Channel>, val valid: () -> Boolean,
        @Volatile var needsRefresh: Boolean = true, @Volatile var released: Boolean = false,
        var refreshVersion: Long = 0L)
    private val snapshots = mutableMapOf<String, Snapshot>()
    private val root by lazy {
        File(context.cacheDir, "capture-details").apply {
            // 上次应用进程留下的临时快照没有可恢复的服务连接所有权。
            if (exists()) check(deleteRecursively())
            check(mkdirs())
        }
    }
    private val viewed by lazy {
        SQLiteDatabase.openOrCreateDatabase(File(root, "viewed.db"), null).apply {
            execSQL("CREATE TABLE viewed(id TEXT PRIMARY KEY)")
        }
    }
    private var viewedScope: Pair<Long, Long>? = null

    suspend fun prepare(session: Long, id: String, valid: () -> Boolean,
        read: (String, Long, MonitorCommunicationChannel) -> MonitorCommunicationDetailPage,
        progress: (Long, Long) -> Unit): String {
        val token = UUID.randomUUID().toString()
        val directory = File(root, token).apply { check(mkdir()) }
        var committed = false
        try {
            fun checkCurrent() { check(valid()) { "抓包数据已重置" } }
            checkCurrent()
            val initial = read(id, 0, MonitorCommunicationChannel.DETAIL)
            val record = requireNotNull(initial.record) { "通信记录不存在" }
            // 先冻结每个通道的长度，再逐块复制；录制继续增长不会延长本次预取。
            val heads = MonitorCommunicationChannel.entries.associateWith { channel ->
                currentCoroutineContext().ensureActive()
                checkCurrent()
                val sourceId = record.tcpStreamId.takeIf { it.isNotEmpty() && channel in STREAM_CHANNELS } ?: id
                if (channel == MonitorCommunicationChannel.DETAIL) initial
                else if (record.handshake != null) initial.copy(channel = channel, bytes = byteArrayOf(), totalBytes = 0, nextCursor = -1)
                else read(sourceId, 0, channel)
            }
            val total = heads.values.sumOf { it.totalBytes }
            var copied = 0L
            progress(0, total)
            for ((channel, head) in heads) {
                val sourceId = record.tcpStreamId.takeIf { it.isNotEmpty() && channel in STREAM_CHANNELS } ?: id
                File(directory, channel.name).outputStream().buffered().use { output ->
                    DataOutputStream(File(directory, channel.name + ".lines").outputStream().buffered()).use { lines ->
                        var cursor = 0L
                        var page = head
                        while (cursor < head.totalBytes) {
                            currentCoroutineContext().ensureActive()
                            checkCurrent()
                            val count = minOf(page.bytes.size.toLong(), head.totalBytes - cursor).toInt()
                            check(count > 0 && page.cursor == cursor && page.sessionGeneration == session) { "通信详情预取未前进" }
                            lines.writeLong(page.startLine)
                            output.write(page.bytes, 0, count)
                            cursor += count; copied += count
                            progress(copied, total)
                            if (cursor < head.totalBytes) page = read(sourceId, cursor, channel)
                        }
                    }
                }
            }
            checkCurrent()
            synchronized(snapshots) {
                snapshots[token] = Snapshot(directory, session, record, heads.mapValues { Channel(it.value.totalBytes, it.value.complete) }, valid)
            }
            committed = true
            return token
        } finally {
            if (!committed) directory.deleteRecursively()
        }
    }

    private fun snapshot(token: String): Snapshot = synchronized(snapshots) {
        requireNotNull(snapshots[token]) { "详情快照已释放" }.also { check(it.valid()) { "抓包数据已重置" } }
    }

    fun hasPendingRefresh(): Boolean = synchronized(snapshots) { snapshots.values.any { it.needsRefresh && it.valid() && !it.released } }

    fun invalidate(changedIds: Set<String>) = synchronized(snapshots) {
        snapshots.values.forEach { snapshot ->
            if (snapshot.record.id in changedIds || snapshot.record.tcpStreamId in changedIds) synchronized(snapshot) {
                snapshot.refreshVersion++; snapshot.needsRefresh = true
            }
        }
    }

    /** 服务通知后只追加各通道新增的字节；全部落盘后原子发布新长度和元数据。 */
    suspend fun refresh(changedIds: Set<String>, read: (String, String, String, Long, MonitorCommunicationChannel) -> MonitorCommunicationDetailPage) {
        val targets = synchronized(snapshots) { snapshots.values.toList() }
        for (snapshot in targets) {
            if (!snapshot.valid() || snapshot.released || (!snapshot.needsRefresh &&
                snapshot.record.id !in changedIds && snapshot.record.tcpStreamId !in changedIds)) continue
            snapshot.needsRefresh = true
            val refreshVersion = synchronized(snapshot) { snapshot.refreshVersion }
            try {
                val record = snapshot.record
                val channels = snapshot.channels.toMutableMap()
                var metadata = record
                for (channel in MonitorCommunicationChannel.entries) {
                    if (record.handshake != null && channel != MonitorCommunicationChannel.DETAIL) continue
                    currentCoroutineContext().ensureActive()
                    check(snapshot.valid() && !snapshot.released) { "详情快照已失效" }
                    val id = record.tcpStreamId.takeIf { it.isNotEmpty() && channel in STREAM_CHANNELS } ?: record.id
                    val head = read(record.bssid, record.deviceMac, id, 0, channel)
                    if (channel == MonitorCommunicationChannel.DETAIL) metadata = requireNotNull(head.record)
                    val old = channels.getValue(channel)
                    check(head.totalBytes >= old.size) { "通信通道长度发生回退" }
                    RandomAccessFile(File(snapshot.directory, channel.name), "rw").use { output ->
                        RandomAccessFile(File(snapshot.directory, channel.name + ".lines"), "rw").use { lines ->
                            var cursor = old.size
                            output.seek(cursor)
                            while (cursor < head.totalBytes) {
                                currentCoroutineContext().ensureActive()
                                check(snapshot.valid() && !snapshot.released) { "详情快照已失效" }
                                val offset = cursor / 65536 * 65536
                                val page = if (offset == 0L) head else read(record.bssid, record.deviceMac, id, offset, channel)
                                val skip = (cursor - offset).toInt()
                                val count = minOf((page.bytes.size - skip).toLong(), head.totalBytes - cursor).toInt()
                                check(count > 0) { "通信详情增量同步未前进" }
                                lines.seek(offset / 65536 * 8); lines.writeLong(page.startLine)
                                output.write(page.bytes, skip, count); cursor += count
                            }
                        }
                    }
                    channels[channel] = Channel(head.totalBytes, head.complete)
                }
                check(snapshot.valid() && !snapshot.released) { "详情快照已失效" }
                synchronized(snapshot) {
                    snapshot.record = metadata; snapshot.channels = channels
                    snapshot.needsRefresh = snapshot.refreshVersion != refreshVersion
                }
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (snapshot.valid() && !snapshot.released) throw error
            }
        }
    }

    fun read(token: String, cursor: Long, channel: MonitorCommunicationChannel): MonitorCommunicationDetailPage {
        val snapshot = snapshot(token)
        val (info, record) = synchronized(snapshot) { snapshot.channels.getValue(channel) to snapshot.record }
        require(cursor in 0..info.size && cursor % 65536 == 0L)
        val bytes = ByteArray(minOf(65536L, info.size - cursor).toInt())
        RandomAccessFile(File(snapshot.directory, channel.name), "r").use { it.seek(cursor); it.readFully(bytes) }
        val line = if (bytes.isEmpty()) 1L else RandomAccessFile(File(snapshot.directory, channel.name + ".lines"), "r").use {
            it.seek(cursor / 65536 * 8); it.readLong()
        }
        check(snapshot.valid()) { "抓包数据已重置" }
        return MonitorCommunicationDetailPage(snapshot.session, record.id, cursor,
            if (cursor == 0L) -1 else cursor - 65536, if (cursor + bytes.size >= info.size) -1 else cursor + bytes.size,
            bytes, info.size, info.complete, channel, record, line)
    }

    suspend fun save(token: String, channel: MonitorCommunicationChannel, destination: Uri) {
        val snapshot = snapshot(token)
        val limit = snapshot.channels.getValue(channel).size
        File(snapshot.directory, channel.name).inputStream().buffered().use { input ->
            requireNotNull(context.contentResolver.openOutputStream(destination, "wt")).buffered().use { output ->
                val buffer = ByteArray(65536)
                var remaining = limit
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    check(snapshot.valid()) { "抓包数据已重置" }
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "本地详情文件不完整" }
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    fun release(token: String) {
        synchronized(snapshots) { snapshots.remove(token)?.also { it.released = true } }?.directory?.deleteRecursively()
    }

    @Synchronized private fun setViewedScope(generation: Long, session: Long) {
        if (viewedScope != (generation to session)) {
            viewed.execSQL("DELETE FROM viewed")
            viewedScope = generation to session
        }
    }

    @Synchronized fun viewed(generation: Long, session: Long, ids: List<String>): Set<String> {
        setViewedScope(generation, session)
        if (ids.isEmpty()) return emptySet()
        require(ids.size <= 256)
        return viewed.rawQuery("SELECT id FROM viewed WHERE id IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray()).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }
    }

    @Synchronized fun markViewed(generation: Long, session: Long, id: String) {
        setViewedScope(generation, session)
        viewed.execSQL("INSERT OR IGNORE INTO viewed(id) VALUES(?)", arrayOf(id))
    }

    private companion object {
        val STREAM_CHANNELS = setOf(MonitorCommunicationChannel.RAW_UPLOAD, MonitorCommunicationChannel.RAW_DOWNLOAD)
    }
}
