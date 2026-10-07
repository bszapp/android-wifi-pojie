package io.github.bszapp.wifitoolbox.service

import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCommunicationDetailPage
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCommunicationPage
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCommunicationRecord
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorCommunicationType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/**
 * 正文/索引追加落盘，内存只留每个设备的计数。固定长记录槽 + 正文双向链，
 * 记录分页 O(页大小)，正文翻页 O(页大小)，与 PCAP 和历史正文总量无关。
 * 所有读写由自身锁串行化，调用方不得在持有本对象锁时回调控制器。
 */
internal class MonitorCommunicationStore(private val directory: File) : java.io.Closeable {
    private val summaries = mutableMapOf<Pair<String, String>, Pair<Long, Long>>()
    private val owners = LinkedHashMap<Long, Pair<String, String>>(64, 0.75f, true)
    private val slots get() = File(directory, "records.index")
    private val metadata get() = File(directory, "records.json")
    private val content get() = File(directory, "content.bin")

    init { require(directory.isDirectory || directory.mkdirs()) }
    private var tableFile = RandomAccessFile(slots, "rw")
    private var metadataFile = RandomAccessFile(metadata, "rw")
    private var contentFile = RandomAccessFile(content, "rw")
    private var closed = false

    private inline fun <T> table(block: (RandomAccessFile) -> T): T { check(!closed); return block(tableFile) }
    private inline fun <T> data(block: (RandomAccessFile) -> T): T { check(!closed); return block(metadataFile) }
    private inline fun <T> body(block: (RandomAccessFile) -> T): T { check(!closed); return block(contentFile) }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { tableFile.close() } finally {
            try { metadataFile.close() } finally { contentFile.close() }
        }
    }

    @Synchronized fun summary(bssid: String, mac: String): Pair<Long, Long> =
        summaries[bssid to mac] ?: (0L to 0L)

    @Synchronized fun reset() {
        close()
        check(!directory.exists() || directory.deleteRecursively()) { "无法清理通信记录" }
        check(directory.mkdirs()) { "无法创建通信记录目录" }
        summaries.clear()
        owners.clear()
        tableFile = RandomAccessFile(slots, "rw")
        metadataFile = RandomAccessFile(metadata, "rw")
        contentFile = RandomAccessFile(content, "rw")
        closed = false
    }

    private fun deviceIndex(bssid: String, mac: String): File {
        require(MAC_PATTERN.matches(bssid))
        require(MAC_PATTERN.matches(mac))
        return File(directory, bssid.replace(":", "") + mac.replace(":", "") + ".index")
    }

    @Synchronized fun record(event: JSONObject) {
        val id = event.getString("id").toLong()
        val bssid = event.getString("bssid")
        val mac = event.getString("deviceMac")
        val index = deviceIndex(bssid, mac)
        val bytes = event.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8192) { "通信元数据超出预算" }
        table { table ->
            require(id in 1..(table.length() / SLOT_BYTES + 1))
            val position = (id - 1) * SLOT_BYTES
            val isNew = position == table.length()
            if (!isNew) readMetadata(table, id).also {
                require(it.getString("bssid") == bssid && it.getString("deviceMac") == mac)
            }
            if (isNew) {
                table.setLength(position + SLOT_BYTES)
                RandomAccessFile(index, "rw").use { rows -> rows.seek(rows.length()); rows.writeLong(id) }
            }
            data { data ->
                val offset = data.length()
                data.seek(offset); data.write(bytes)
                table.seek(position); table.writeLong(offset); table.writeInt(bytes.size)
            }
            val previous = summary(bssid, mac)
            summaries[bssid to mac] = (previous.first + if (isNew) 1 else 0) to (previous.second + 1)
            owners[id] = bssid to mac
            if (owners.size > 4096) owners.remove(owners.keys.first())
        }
    }

    @Synchronized fun append(event: JSONObject, bytes: ByteArray) {
        require(bytes.size in 1..8192)
        val id = event.getString("id").toLong()
        val bssid = event.getString("bssid")
        val mac = event.getString("deviceMac")
        table { table ->
            val position = position(table, id)
            val owner = owners[id] ?: readMetadata(table, id).let { it.getString("bssid") to it.getString("deviceMac") }
            require(owner == (bssid to mac))
            table.seek(position + 12)
            val parts = table.readInt()
            val part = event.getInt("index")
            if (part < parts) return
            require(part == parts) { "通信正文分块不连续" }
            val head = table.readLong()
            val tail = table.readLong()
            val size = table.readLong()
            body { data ->
                if (data.length() == 0L) data.writeLong(0L) // 0 保留为初始游标。
                val offset = data.length()
                data.seek(offset)
                data.writeLong(id); data.writeLong(tail); data.writeLong(0L); data.writeInt(bytes.size); data.write(bytes)
                if (tail != 0L) { data.seek(tail + 16); data.writeLong(offset) }
                table.seek(position + 12); table.writeInt(parts + 1)
                table.writeLong(if (head == 0L) offset else head)
                table.writeLong(offset); table.writeLong(size + bytes.size)
            }
        }
        val previous = summary(bssid, mac)
        summaries[bssid to mac] = previous.first to (previous.second + 1)
    }

    @Synchronized fun page(session: Long, bssid: String, mac: String, from: Long): MonitorCommunicationPage {
        val count = summary(bssid, mac).first
        require(from in 0..count)
        val next = minOf(count, from + RECORDS_PER_PAGE)
        val records = ArrayList<MonitorCommunicationRecord>()
        if (next > from) RandomAccessFile(deviceIndex(bssid, mac), "r").use { rows ->
            table { table ->
                rows.seek(from * 8)
                repeat((next - from).toInt()) { records.add(toRecord(readMetadata(table, rows.readLong()))) }
            }
        }
        return MonitorCommunicationPage(session, from, next, count, records)
    }

    @Synchronized fun detail(session: Long, bssid: String, mac: String, recordId: String, cursor: Long): MonitorCommunicationDetailPage {
        require(cursor >= 0)
        deviceIndex(bssid, mac) // 校验路径字段，正文游标还须匹配记录 ID。
        val id = recordId.toLong()
        return table { table ->
            val entry = readMetadata(table, id)
            require(entry.getString("bssid") == bssid && entry.getString("deviceMac") == mac)
            table.seek(position(table, id) + 16)
            val head = table.readLong()
            table.readLong()
            val size = table.readLong()
            if (head == 0L) return@table MonitorCommunicationDetailPage(session, recordId, 0, -1, -1, byteArrayOf(), size, entry.getBoolean("complete"))
            val start = if (cursor == 0L) head else cursor
            val output = ByteArrayOutputStream()
            body { data ->
                fun previous(offset: Long): Long {
                    require(offset in 8..(data.length() - CHUNK_HEADER_BYTES))
                    data.seek(offset); require(data.readLong() == id)
                    return data.readLong()
                }
                var back = previous(start)
                var previousCursor = -1L
                repeat(CHUNKS_PER_PAGE) {
                    if (back != 0L) { previousCursor = back; back = previous(back) }
                }
                var next = start
                repeat(CHUNKS_PER_PAGE) {
                    if (next != 0L) {
                        previous(next)
                        data.seek(next + 16)
                        next = data.readLong()
                        val length = data.readInt()
                        require(length in 1..8192)
                        val bytes = ByteArray(length); data.readFully(bytes); output.write(bytes)
                    }
                }
                MonitorCommunicationDetailPage(session, recordId, start, previousCursor,
                    if (next == 0L) -1 else next, output.toByteArray(), size, entry.getBoolean("complete"))
            }
        }
    }

    private fun position(table: RandomAccessFile, id: Long): Long {
        require(id > 0 && id <= table.length() / SLOT_BYTES) { "通信记录不存在" }
        return (id - 1) * SLOT_BYTES
    }

    private fun readMetadata(table: RandomAccessFile, id: Long): JSONObject {
        table.seek(position(table, id))
        val offset = table.readLong()
        val length = table.readInt()
        require(length in 1..8192)
        return data { file ->
            file.seek(offset)
            val bytes = ByteArray(length); file.readFully(bytes)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun toRecord(value: JSONObject) = MonitorCommunicationRecord(
        id = value.getString("id"), timestampUnixMillis = value.getLong("timestampUnixMillis"),
        type = when (value.getString("kind")) {
            "dns" -> MonitorCommunicationType.DNS
            "http" -> MonitorCommunicationType.HTTP
            "https" -> MonitorCommunicationType.HTTPS
            "dhcp" -> MonitorCommunicationType.DHCP
            else -> error("未知通信记录类型")
        }, source = value.getString("sourceAddress"), destination = value.getString("destinationAddress"),
        summary = value.getString("summary"), complete = value.getBoolean("complete"),
    )

    private companion object {
        val MAC_PATTERN = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
        const val SLOT_BYTES = 40L
        const val CHUNK_HEADER_BYTES = 28L
        const val RECORDS_PER_PAGE = 32L
        const val CHUNKS_PER_PAGE = 8
    }
}
