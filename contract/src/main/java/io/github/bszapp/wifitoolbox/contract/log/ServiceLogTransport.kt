package io.github.bszapp.wifitoolbox.contract.log

import android.os.ParcelFileDescriptor
import io.github.bszapp.wifitoolbox.contract.PipeTransport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors

object ServiceLogTransport {
    private val writerExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "service-log-pipe-writer").apply { isDaemon = true }
    }

    fun encode(batch: ServiceLogBatch): ParcelFileDescriptor {
        require(batch.entries.size <= MAX_ENTRY_COUNT) {
            "Service 日志条数过多：${batch.entries.size}"
        }

        return PipeTransport.open(writerExecutor, "Service 日志") { stream ->
            DataOutputStream(BufferedOutputStream(stream)).use { output ->
                output.writeInt(MAGIC)
                output.writeLong(batch.oldestAvailableId)
                output.writeLong(batch.latestId)
                output.writeInt(batch.entries.size)
                batch.entries.forEach { entry ->
                    output.writeLong(entry.id)
                    output.writeText(entry.tag)
                    output.writeText(entry.rawLine)
                }
                output.flush()
            }
        }
    }

    fun decode(descriptor: ParcelFileDescriptor): ServiceLogBatch =
        DataInputStream(
            BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(descriptor)),
        ).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) { "Service 日志 IPC magic 不匹配：$magic" }

            val oldestAvailableId = input.readLong()
            val latestId = input.readLong()
            val count = input.readInt()
            require(count in 0..MAX_ENTRY_COUNT) { "Service 日志条数非法：$count" }

            val entries = ArrayList<ServiceLogEntry>(count)
            repeat(count) {
                entries += ServiceLogEntry(
                    id = input.readLong(),
                    tag = input.readText(),
                    rawLine = input.readText(),
                )
            }
            require(input.read() == -1) { "Service 日志包含多余数据" }

            ServiceLogBatch(
                oldestAvailableId = oldestAvailableId,
                latestId = latestId,
                entries = entries,
            )
        }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Service 日志文本过长：${bytes.size}" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        require(length in 0..MAX_TEXT_BYTES) { "Service 日志文本长度非法：$length" }
        return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
    }

    private const val MAGIC = 0x534C4F47 // SLOG
    private const val MAX_ENTRY_COUNT = 4_000
    private const val MAX_TEXT_BYTES = 128 * 1024
}
