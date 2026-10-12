package io.github.bszapp.wifitoolbox.contract.terminal

import android.os.ParcelFileDescriptor
import io.github.bszapp.wifitoolbox.contract.PipeTransport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors

object TerminalLogTransport {
    private val writerExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "terminal-log-pipe-writer").apply { isDaemon = true }
    }

    fun encode(batch: TerminalLogBatch): ParcelFileDescriptor {
        require(batch.entries.size <= MAX_ENTRY_COUNT) {
            "终端日志条数过多：${batch.entries.size}"
        }

        return PipeTransport.open(writerExecutor, "终端日志") { stream ->
            DataOutputStream(BufferedOutputStream(stream)).use { output ->
                output.writeInt(MAGIC)
                output.writeLong(batch.terminalId)
                output.writeLong(batch.generation)
                output.writeLong(batch.oldestAvailableId)
                output.writeLong(batch.latestId)
                output.writeInt(batch.lineCount)
                output.writeInt(batch.entries.size)
                batch.entries.forEach { entry ->
                    output.writeLong(entry.id)
                    output.writeText(entry.text)
                }
                output.flush()
            }
        }
    }

    fun decode(descriptor: ParcelFileDescriptor): TerminalLogBatch =
        DataInputStream(
            BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(descriptor)),
        ).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) { "终端日志 IPC magic 不匹配：$magic" }

            val terminalId = input.readLong()
            val generation = input.readLong()
            val oldestAvailableId = input.readLong()
            val latestId = input.readLong()
            val lineCount = input.readInt()
            require(lineCount >= 0) { "终端日志总行数非法：$lineCount" }
            val count = input.readInt()
            require(count in 0..MAX_ENTRY_COUNT) { "终端日志批次行数非法：$count" }

            val entries = ArrayList<TerminalLogEntry>(count)
            repeat(count) {
                entries += TerminalLogEntry(
                    id = input.readLong(),
                    text = input.readText(),
                )
            }
            require(input.read() == -1) { "终端日志包含多余数据" }
            TerminalLogBatch(
                terminalId = terminalId,
                generation = generation,
                oldestAvailableId = oldestAvailableId,
                latestId = latestId,
                lineCount = lineCount,
                entries = entries,
            )
        }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "终端日志行过长：${bytes.size}" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val length = readInt()
        require(length in 0..MAX_TEXT_BYTES) { "终端日志行长度非法：$length" }
        return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
    }

    private const val MAGIC = 0x544C4F47 // TLOG
    private const val MAX_ENTRY_COUNT = 5_000
    private const val MAX_TEXT_BYTES = 128 * 1024
}
