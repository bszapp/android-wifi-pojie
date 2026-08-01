package io.github.bszapp.wifitoolbox.container

import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.tukaani.xz.XZInputStream

internal object RootfsArchiveExtractor {
    private const val TAR_BLOCK_SIZE = 512
    private const val BUFFER_SIZE = 8192

    suspend fun extract(
        compressedSize: Long,
        compressedInput: InputStream,
        destination: File,
        onProgress: suspend (entry: String, fraction: Float) -> Unit,
    ) {
        val counting = CountingInputStream(BufferedInputStream(compressedInput))
        XZInputStream(counting).use { input ->
            extractTar(input, counting, compressedSize, destination, onProgress)
        }
        onProgress("", 1f)
    }

    private suspend fun extractTar(
        input: InputStream,
        counting: CountingInputStream,
        compressedSize: Long,
        destination: File,
        onProgress: suspend (String, Float) -> Unit,
    ) {
        destination.mkdirs()
        val header = ByteArray(TAR_BLOCK_SIZE)
        var longName: String? = null
        var longLink: String? = null

        while (true) {
            val headerSize = readFullyOrEof(input, header)
            if (headerSize == 0) break
            if (headerSize != TAR_BLOCK_SIZE) throw EOFException("tar header 不完整")
            if (header.all { it == 0.toByte() }) break

            val size = parseOctal(header, 124, 12)
            val mode = parseOctal(header, 100, 8).toInt()
            val type = header[156].toInt().toChar().takeUnless { it == '\u0000' } ?: '0'
            if (type == 'L') {
                longName = readDataString(input, size)
                skipPadding(input, size)
                continue
            }
            if (type == 'K') {
                longLink = readDataString(input, size)
                skipPadding(input, size)
                continue
            }
            if (type == 'x' || type == 'g') {
                skipExactly(input, size)
                skipPadding(input, size)
                continue
            }

            val entryName = longName ?: readEntryName(header)
            val linkName = longLink ?: readString(header, 157, 100)
            longName = null
            longLink = null
            val normalized = normalize(entryName)
            val output = if (normalized == ".") destination else File(destination, normalized)

            when (type) {
                '5' -> {
                    ensureDirectory(output)
                    applyMode(output, mode, 493)
                    report(normalized, counting, compressedSize, onProgress)
                }
                '2' -> {
                    ensureParent(output)
                    replaceExisting(output)
                    Os.symlink(linkName, output.absolutePath)
                    report(normalized, counting, compressedSize, onProgress)
                }
                '1' -> {
                    val targetName = normalize(linkName)
                    val target = if (targetName == ".") destination else File(destination, targetName)
                    ensureParent(output)
                    replaceExisting(output)
                    Os.link(target.absolutePath, output.absolutePath)
                    report(normalized, counting, compressedSize, onProgress)
                }
                '0', '7' -> {
                    ensureParent(output)
                    replaceExisting(output)
                    output.outputStream().buffered().use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var remaining = size
                        while (remaining > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count <= 0) throw EOFException("tar entry 数据不完整: $normalized")
                            stream.write(buffer, 0, count)
                            remaining -= count
                            report(normalized, counting, compressedSize, onProgress)
                        }
                    }
                    applyMode(output, mode, 420)
                    skipPadding(input, size)
                    report(normalized, counting, compressedSize, onProgress)
                }
                else -> {
                    skipExactly(input, size)
                    skipPadding(input, size)
                }
            }
        }
    }

    private suspend fun report(
        entry: String,
        counting: CountingInputStream,
        compressedSize: Long,
        callback: suspend (String, Float) -> Unit,
    ) {
        val fraction = if (compressedSize > 0L) {
            (counting.bytesRead.toDouble() / compressedSize).toFloat().coerceIn(0f, 1f)
        } else 0f
        callback(entry, fraction)
    }

    private fun normalize(path: String): String {
        var value = path
        while (value.startsWith("./")) value = value.removePrefix("./")
        if (value.isEmpty() || value == ".") return "."
        if (value.startsWith('/')) throw IOException("不安全的归档路径: $path")
        val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("不安全的归档路径: $path")
        return parts.joinToString("/")
    }

    private fun readEntryName(header: ByteArray): String {
        val name = readString(header, 0, 100)
        val prefix = readString(header, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun readDataString(input: InputStream, size: Long): String {
        if (size > Int.MAX_VALUE) throw IOException("tar 长路径字段过大")
        val data = ByteArray(size.toInt())
        readFully(input, data)
        var end = data.size
        while (end > 0 && (data[end - 1] == 0.toByte() || data[end - 1] == '\n'.code.toByte())) end--
        return String(data, 0, end, Charsets.UTF_8)
    }

    private fun readString(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(data: ByteArray, offset: Int, length: Int): Long {
        val raw = readString(data, offset, length).trim()
        return if (raw.isEmpty()) 0 else raw.toLong(8)
    }

    private fun ensureDirectory(file: File) {
        if (!file.isDirectory && !file.mkdirs()) throw IOException("无法创建目录: ${file.absolutePath}")
    }

    private fun ensureParent(file: File) = file.parentFile?.let(::ensureDirectory) ?: Unit

    private fun replaceExisting(file: File) {
        val stat = runCatching { Os.lstat(file.absolutePath) }.getOrNull() ?: return
        val isDirectory = stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR
        val deleted = if (isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) throw IOException("无法覆盖已有路径: ${file.absolutePath}")
    }

    private fun applyMode(file: File, mode: Int, fallback: Int) {
        runCatching { Os.chmod(file.absolutePath, if (mode == 0) fallback else mode) }
            .getOrElse { throw IOException("无法设置权限: ${file.absolutePath}", it) }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (TAR_BLOCK_SIZE - size % TAR_BLOCK_SIZE) % TAR_BLOCK_SIZE
        if (padding > 0) skipExactly(input, padding)
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read <= 0) throw EOFException("归档数据提前结束")
            remaining -= read
        }
    }

    private fun readFully(input: InputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val count = input.read(data, offset, data.size - offset)
            if (count <= 0) throw EOFException("归档数据提前结束")
            offset += count
        }
    }

    private fun readFullyOrEof(input: InputStream, data: ByteArray): Int {
        var offset = 0
        while (offset < data.size) {
            val count = input.read(data, offset, data.size - offset)
            if (count < 0) return offset
            offset += count
        }
        return offset
    }
}

private class CountingInputStream(private val delegate: InputStream) : InputStream() {
    var bytesRead = 0L
        private set

    override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also { if (it > 0) bytesRead += it }

    override fun close() = delegate.close()
}
