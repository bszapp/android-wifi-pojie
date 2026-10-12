package io.github.bszapp.wifitoolbox.contract.hashcat

import android.os.ParcelFileDescriptor
import io.github.bszapp.wifitoolbox.contract.PipeTransport
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Binder 只传 FD；输入、备份及查询都以至多 64 KiB 的块流式读写。 */
object HashcatFiles {
    private val writers = Executors.newCachedThreadPool { Thread(it, "hashcat-file-writer").apply { isDaemon = true } }
    private val allowed = setOf("libhashcat.so", "handshake.hc22000", "dictionary.txt", "session.restore", "result.txt", "snapshot.json", "run.log")

    fun pipe(write: (OutputStream) -> Unit): ParcelFileDescriptor =
        PipeTransport.open(writers, "Hashcat 文件/状态", write)

    fun pack(output: OutputStream, files: Map<String, File>) {
        ZipOutputStream(output).use { zip ->
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            files.forEach { (name, file) ->
                require(name in allowed)
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun unpack(input: InputStream, directory: File, onCopy: (String, Long, Long) -> Unit = { _, _, _ -> }) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建任务目录" }
        val names = mutableSetOf<String>()
        val checkedInput = ArchiveInputStream(input)
        ZipInputStream(checkedInput).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in allowed && !entry.isDirectory && names.add(entry.name)) { "非法 Hashcat 文件" }
                var copied = 0L
                onCopy(entry.name, copied, entry.size.coerceAtLeast(0))
                File(directory, entry.name).outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        copied += count
                        onCopy(entry.name, copied, entry.size.coerceAtLeast(0))
                    }
                }
                onCopy(entry.name, copied, copied)
                zip.closeEntry()
            }
            // ZipInputStream 在中央目录处停止。继续读到 EOF，核对发送端确实写出了 ZIP 结束记录。
            val remaining = ByteArray(64 * 1024)
            while (checkedInput.read(remaining) != -1) { /* 只排空 ZIP 中央目录，不保存额外副本。 */ }
            checkedInput.requireComplete(names.size)
        }
    }

    /** 本模块生成的 ZIP 无注释；只保留最后 22 字节，避免大字典备份占用额外内存。 */
    private class ArchiveInputStream(input: InputStream) : FilterInputStream(input) {
        private val tail = ByteArray(22)
        private var tailSize = 0

        override fun read(): Int = super.read().also { value ->
            if (value >= 0) remember(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) remember(buffer, offset, count)
            }

        private fun remember(buffer: ByteArray, offset: Int, count: Int) {
            val kept = minOf(count, tail.size)
            val previous = minOf(tailSize, tail.size - kept)
            tail.copyInto(tail, 0, tailSize - previous, tailSize)
            buffer.copyInto(tail, previous, offset + count - kept, offset + count)
            tailSize = previous + kept
        }

        fun requireComplete(entryCount: Int) {
            fun word(offset: Int): Int =
                (tail[offset].toInt() and 0xff) or ((tail[offset + 1].toInt() and 0xff) shl 8)
            if (tailSize != tail.size || word(0) != 0x4b50 || word(2) != 0x0605 ||
                word(4) != 0 || word(6) != 0 || word(8) != entryCount ||
                word(10) != entryCount || word(20) != 0
            ) {
                throw IOException("Hashcat 文件传输未完成：ZIP 结束记录缺失或不匹配")
            }
        }
    }
}
