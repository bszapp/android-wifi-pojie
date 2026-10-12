package io.github.bszapp.wifitoolbox.contract

import android.os.Parcel
import android.os.Parcelable
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors

/** Binder 只传递管道句柄。累计数据必须先分页，每页最多 512 KiB，写入块最多 64 KiB。 */
object PagedDataTransport {
    const val MAX_PAGE_BYTES = 512 * 1024
    private val writers = Executors.newCachedThreadPool { r ->
        Thread(r, "paged-data-writer").apply { isDaemon = true }
    }

    fun bytes(value: Parcelable): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.marshall()
        } finally { parcel.recycle() }
    }

    fun encode(value: Parcelable): ParcelFileDescriptor {
        val data = bytes(value)
        require(data.size <= MAX_PAGE_BYTES) { "分页数据超出单页预算：${data.size}" }
        return PipeTransport.open(writers, "分页数据") { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(data.size)
                var offset = 0
                while (offset < data.size) {
                    val count = minOf(64 * 1024, data.size - offset)
                    out.write(data, offset, count)
                    offset += count
                }
            }
        }
    }

    fun <T : Parcelable> decode(fd: ParcelFileDescriptor, type: Class<T>): T {
        val data = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(fd)).use { input ->
            val length = input.readInt()
            require(length in 1..MAX_PAGE_BYTES) { "分页数据长度非法：$length" }
            ByteArray(length).also {
                input.readFully(it)
                require(input.read() == -1) { "分页数据包含多余内容" }
            }
        }
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            type.cast(parcel.readParcelable<Parcelable>(type.classLoader))
                ?: error("分页数据为空")
        } finally { parcel.recycle() }
    }
}
