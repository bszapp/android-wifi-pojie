package io.github.bszapp.wifitoolbox.contract.hashcat

import android.os.ParcelFileDescriptor
import java.io.File
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

    fun pipe(write: (OutputStream) -> Unit): ParcelFileDescriptor {
        val ends = ParcelFileDescriptor.createReliablePipe()
        writers.execute {
            try { ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use(write) }
            catch (error: Throwable) { runCatching { ends[1].closeWithError(error.message ?: "Hashcat 传输失败") } }
        }
        return ends[0]
    }

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
        ZipInputStream(input).use { zip ->
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
        }
    }
}
