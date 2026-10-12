package io.github.bszapp.wifitoolbox.contract.task

import android.os.ParcelFileDescriptor
import io.github.bszapp.wifitoolbox.contract.PipeTransport
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.zip.CRC32

/** 元数据不含密码本；长度及 CRC 保证部分传输不能成为可运行的字典。 */
object SecurityAuditDictionaryTransport {
    private val writers = Executors.newCachedThreadPool { Thread(it, "audit-dictionary-writer").apply { isDaemon = true } }

    fun open(file: File): ParcelFileDescriptor = PipeTransport.open(writers, "安全测试字典") { stream ->
        val expected = file.length()
        val crc = CRC32()
        DataOutputStream(stream).use { out ->
            out.writeLong(expected)
            var copied = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    copied += count
                }
            }
            check(copied == expected) { "传输期间字典内容发生变化" }
            out.writeLong(crc.value)
        }
    }

    fun read(fd: ParcelFileDescriptor): List<String> =
        DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(fd)).use { source ->
            val length = source.readLong()
            require(length > 0) { "字典为空" }
            val crc = CRC32()
            var remaining = length
            val limited = object : InputStream() {
                override fun read(): Int {
                    if (remaining == 0L) return -1
                    val value = source.read()
                    check(value >= 0) { "字典传输未完成" }
                    remaining--
                    crc.update(value)
                    return value
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    if (count == 0) return 0
                    if (remaining == 0L) return -1
                    val read = source.read(buffer, offset, minOf(count.toLong(), remaining).toInt())
                    check(read > 0) { "字典传输未完成" }
                    remaining -= read
                    crc.update(buffer, offset, read)
                    return read
                }
            }
            val lines = limited.bufferedReader(Charsets.UTF_8).use { it.readLines() }
            require(remaining == 0L && source.readLong() == crc.value && source.read() == -1) { "字典传输校验失败" }
            lines
        }
}
