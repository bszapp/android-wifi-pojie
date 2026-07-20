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
 * 使用 Linux reliable pipe 运输完整的原始 @Parcelize 对象。
 *
 * Binder 事务中只携带文件描述符；WifiState/SavedWifiList 的完整 Parcel 字节通过
 * pipe 传输，从而不占用 Binder 的大事务缓冲区。解码结果仍是原始业务类型。
 */
object WifiParcelTransport {
    private val writerExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "wifi-parcel-pipe-writer").apply { isDaemon = true }
    }

    fun encodeWifiState(value: WifiState): ParcelFileDescriptor =
        encode(TYPE_WIFI_STATE, value)

    fun decodeWifiState(descriptor: ParcelFileDescriptor): WifiState =
        decode(descriptor, TYPE_WIFI_STATE, WifiState::class.java.classLoader) as? WifiState
            ?: throw IllegalStateException("传输数据不是 WifiState")

    fun encodeSavedWifiList(value: SavedWifiList): ParcelFileDescriptor =
        encode(TYPE_SAVED_WIFI_LIST, value)

    fun decodeSavedWifiList(descriptor: ParcelFileDescriptor): SavedWifiList =
        decode(descriptor, TYPE_SAVED_WIFI_LIST, SavedWifiList::class.java.classLoader)
            as? SavedWifiList
            ?: throw IllegalStateException("传输数据不是 SavedWifiList")

    private fun encode(type: Int, value: Parcelable): ParcelFileDescriptor {
        val payload = marshall(value)
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Wi-Fi IPC payload 过大：${payload.size} > $MAX_PAYLOAD_BYTES"
        }

        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        writerExecutor.execute {
            try {
                DataOutputStream(
                    BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(writeSide)),
                ).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(PROTOCOL_VERSION)
                    output.writeInt(type)
                    output.writeInt(payload.size)
                    output.writeLong(crc32(payload))
                    output.write(payload)
                    output.flush()
                }
            } catch (_: Throwable) {
                runCatching { writeSide.close() }
            }
        }

        return readSide
    }

    private fun decode(
        descriptor: ParcelFileDescriptor,
        expectedType: Int,
        classLoader: ClassLoader?,
    ): Parcelable {
        val payload = DataInputStream(
            BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(descriptor)),
        ).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) { "Wi-Fi IPC magic 不匹配：$magic" }

            val version = input.readInt()
            require(version == PROTOCOL_VERSION) {
                "Wi-Fi IPC 协议版本不匹配：$version != $PROTOCOL_VERSION"
            }

            val type = input.readInt()
            require(type == expectedType) {
                "Wi-Fi IPC 数据类型不匹配：$type != $expectedType"
            }

            val length = input.readInt()
            require(length in 0..MAX_PAYLOAD_BYTES) {
                "Wi-Fi IPC payload 长度非法：$length"
            }

            val expectedCrc = input.readLong()
            val bytes = ByteArray(length)
            input.readFully(bytes)
            require(crc32(bytes) == expectedCrc) { "Wi-Fi IPC payload 校验失败" }
            bytes
        }

        return unmarshall(payload, classLoader)
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

    private const val MAGIC = 0x57465450 // WFTP
    private const val PROTOCOL_VERSION = 1
    private const val TYPE_WIFI_STATE = 1
    private const val TYPE_SAVED_WIFI_LIST = 2
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
}
