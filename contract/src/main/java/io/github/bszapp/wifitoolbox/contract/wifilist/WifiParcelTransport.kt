@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.contract.wifilist

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * 使用稳定快照和固定上限分片运输 Wi-Fi 数据。
 *
 * Binder 回调只通知 generation、分片数和完整快照长度。App 随后按分片序号通过
 * reliable pipe 拉取正文，每次分片传输连同片头最多 [MAX_TRANSFER_BYTES] 字节。完整
 * 快照在全部分片通过类型、generation、序号、长度和 CRC32 校验后才会反序列化并发布。
 */
object WifiParcelTransport {
    private val writerExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "wifi-parcel-pipe-writer").apply { isDaemon = true }
    }

    class EncodedSnapshot internal constructor(
        internal val type: Int,
        internal val payload: ByteArray,
    ) {
        val totalBytes: Int = payload.size
        val chunkCount: Int = WifiParcelTransport.chunkCountFor(totalBytes)

        fun openChunk(generation: Long, chunkIndex: Int): ParcelFileDescriptor =
            WifiParcelTransport.encodeChunk(
                type = type,
                generation = generation,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                totalBytes = totalBytes,
                payload = payload,
            )
    }

    fun encodeWifiState(value: WifiState): EncodedSnapshot =
        encode(TYPE_WIFI_STATE, value)

    fun decodeWifiState(
        generation: Long,
        chunkCount: Int,
        totalBytes: Int,
        chunkProvider: (Int) -> ParcelFileDescriptor,
    ): WifiState = decodeSnapshot(
        expectedType = TYPE_WIFI_STATE,
        generation = generation,
        chunkCount = chunkCount,
        totalBytes = totalBytes,
        classLoader = WifiState::class.java.classLoader,
        chunkProvider = chunkProvider,
    ) as? WifiState ?: throw IllegalStateException("传输数据不是 WifiState")

    fun encodeSavedWifiList(value: SavedWifiList): EncodedSnapshot =
        encode(TYPE_SAVED_WIFI_LIST, value)

    fun decodeSavedWifiList(
        generation: Long,
        chunkCount: Int,
        totalBytes: Int,
        chunkProvider: (Int) -> ParcelFileDescriptor,
    ): SavedWifiList = decodeSnapshot(
        expectedType = TYPE_SAVED_WIFI_LIST,
        generation = generation,
        chunkCount = chunkCount,
        totalBytes = totalBytes,
        classLoader = SavedWifiList::class.java.classLoader,
        chunkProvider = chunkProvider,
    ) as? SavedWifiList ?: throw IllegalStateException("传输数据不是 SavedWifiList")

    fun encodeWifiInformationSourceState(value: WifiInformationSourceState): EncodedSnapshot =
        encode(TYPE_WIFI_INFORMATION_SOURCE_STATE, value)

    fun decodeWifiInformationSourceState(
        generation: Long,
        chunkCount: Int,
        totalBytes: Int,
        chunkProvider: (Int) -> ParcelFileDescriptor,
    ): WifiInformationSourceState = decodeSnapshot(
        expectedType = TYPE_WIFI_INFORMATION_SOURCE_STATE,
        generation = generation,
        chunkCount = chunkCount,
        totalBytes = totalBytes,
        classLoader = WifiInformationSourceState::class.java.classLoader,
        chunkProvider = chunkProvider,
    ) as? WifiInformationSourceState
        ?: throw IllegalStateException("传输数据不是 WifiInformationSourceState")

    private fun encode(type: Int, value: Parcelable): EncodedSnapshot {
        val payload = marshall(value)
        require(payload.size in 1..MAX_SNAPSHOT_BYTES) {
            "Wi-Fi IPC 快照大小非法：${payload.size}，允许范围 1..$MAX_SNAPSHOT_BYTES"
        }
        return EncodedSnapshot(type, payload)
    }

    private fun decodeSnapshot(
        expectedType: Int,
        generation: Long,
        chunkCount: Int,
        totalBytes: Int,
        classLoader: ClassLoader?,
        chunkProvider: (Int) -> ParcelFileDescriptor,
    ): Parcelable {
        require(generation > 0L) { "Wi-Fi IPC generation 非法：$generation" }
        require(totalBytes in 1..MAX_SNAPSHOT_BYTES) {
            "Wi-Fi IPC 快照长度非法：$totalBytes"
        }
        val expectedChunkCount = chunkCountFor(totalBytes)
        require(chunkCount == expectedChunkCount) {
            "Wi-Fi IPC 分片数非法：$chunkCount != $expectedChunkCount"
        }

        val payload = ByteArray(totalBytes)
        var writeOffset = 0
        for (chunkIndex in 0 until chunkCount) {
            val chunk = decodeChunk(
                descriptor = chunkProvider(chunkIndex),
                expectedType = expectedType,
                expectedGeneration = generation,
                expectedChunkIndex = chunkIndex,
                expectedChunkCount = chunkCount,
                expectedTotalBytes = totalBytes,
            )
            chunk.copyInto(payload, destinationOffset = writeOffset)
            writeOffset += chunk.size
        }
        check(writeOffset == totalBytes) {
            "Wi-Fi IPC 快照组装长度不符：$writeOffset != $totalBytes"
        }
        return unmarshall(payload, classLoader)
    }

    private fun encodeChunk(
        type: Int,
        generation: Long,
        chunkIndex: Int,
        chunkCount: Int,
        totalBytes: Int,
        payload: ByteArray,
    ): ParcelFileDescriptor {
        require(generation > 0L) { "Wi-Fi IPC generation 非法：$generation" }
        require(totalBytes == payload.size && totalBytes in 1..MAX_SNAPSHOT_BYTES) {
            "Wi-Fi IPC 快照长度非法：$totalBytes"
        }
        require(chunkCount == chunkCountFor(totalBytes)) { "Wi-Fi IPC 分片数非法：$chunkCount" }
        require(chunkIndex in 0 until chunkCount) { "Wi-Fi IPC 分片序号非法：$chunkIndex" }

        val offset = chunkIndex * MAX_CHUNK_BYTES
        val length = minOf(MAX_CHUNK_BYTES, totalBytes - offset)
        val chunk = payload.copyOfRange(offset, offset + length)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        writerExecutor.execute {
            try {
                DataOutputStream(
                    BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(writeSide)),
                ).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(type)
                    output.writeLong(generation)
                    output.writeInt(chunkIndex)
                    output.writeInt(chunkCount)
                    output.writeInt(totalBytes)
                    output.writeInt(length)
                    output.writeLong(crc32(chunk))
                    output.write(chunk)
                    output.flush()
                }
            } catch (_: Throwable) {
                runCatching { writeSide.closeWithError("Wi-Fi IPC 分片写入失败") }
            }
        }

        return readSide
    }

    private fun decodeChunk(
        descriptor: ParcelFileDescriptor,
        expectedType: Int,
        expectedGeneration: Long,
        expectedChunkIndex: Int,
        expectedChunkCount: Int,
        expectedTotalBytes: Int,
    ): ByteArray = DataInputStream(
        BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(descriptor)),
    ).use { input ->
        val magic = input.readInt()
        require(magic == MAGIC) { "Wi-Fi IPC magic 不匹配：$magic" }

        val version = input.readInt()
        require(version == VERSION) { "Wi-Fi IPC 协议版本不匹配：$version != $VERSION" }

        val type = input.readInt()
        require(type == expectedType) { "Wi-Fi IPC 数据类型不匹配：$type != $expectedType" }

        val generation = input.readLong()
        require(generation == expectedGeneration) {
            "Wi-Fi IPC generation 不匹配：$generation != $expectedGeneration"
        }

        val chunkIndex = input.readInt()
        require(chunkIndex == expectedChunkIndex) {
            "Wi-Fi IPC 分片序号不匹配：$chunkIndex != $expectedChunkIndex"
        }

        val chunkCount = input.readInt()
        require(chunkCount == expectedChunkCount) {
            "Wi-Fi IPC 分片总数不匹配：$chunkCount != $expectedChunkCount"
        }

        val totalBytes = input.readInt()
        require(totalBytes == expectedTotalBytes) {
            "Wi-Fi IPC 快照长度不匹配：$totalBytes != $expectedTotalBytes"
        }

        val length = input.readInt()
        val expectedLength = minOf(
            MAX_CHUNK_BYTES,
            expectedTotalBytes - expectedChunkIndex * MAX_CHUNK_BYTES,
        )
        require(length == expectedLength && length in 1..MAX_CHUNK_BYTES) {
            "Wi-Fi IPC 分片长度非法：$length != $expectedLength"
        }

        val expectedCrc = input.readLong()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        require(crc32(bytes) == expectedCrc) { "Wi-Fi IPC 分片校验失败" }
        require(input.read() == -1) { "Wi-Fi IPC 分片包含多余数据" }
        bytes
    }

    private fun marshall(value: Parcelable): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            check(!parcel.hasFileDescriptors()) {
                "Wi-Fi IPC 原始对象包含文件描述符，不能 marshall"
            }
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun unmarshall(bytes: ByteArray, classLoader: ClassLoader?): Parcelable {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readParcelable(classLoader)
                ?: throw IllegalStateException("Wi-Fi IPC payload 解码结果为 null")
        } finally {
            parcel.recycle()
        }
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().run {
        update(bytes)
        value
    }

    private fun chunkCountFor(totalBytes: Int): Int =
        (totalBytes + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES

    private const val MAGIC = 0x57465443 // WFTC
    private const val VERSION = 1
    private const val TYPE_WIFI_STATE = 1
    private const val TYPE_SAVED_WIFI_LIST = 2
    private const val TYPE_WIFI_INFORMATION_SOURCE_STATE = 3
    private const val CHUNK_HEADER_BYTES = 44
    const val MAX_TRANSFER_BYTES = 64 * 1024
    const val MAX_CHUNK_BYTES = MAX_TRANSFER_BYTES - CHUNK_HEADER_BYTES
    private const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
}
