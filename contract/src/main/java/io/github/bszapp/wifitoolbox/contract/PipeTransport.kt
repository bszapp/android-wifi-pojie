package io.github.bszapp.wifitoolbox.contract

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.Executor

/** 普通管道只传数据 FD，避免可靠管道附带的 Unix socket 跨 SELinux 域被拒绝。 */
internal object PipeTransport {
    fun open(executor: Executor, description: String, write: (OutputStream) -> Unit): ParcelFileDescriptor {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        try {
            executor.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use(write)
                } catch (error: Throwable) {
                    // 保留发送端的原始原因；接收端依据现有长度、CRC、JSON/ZIP 格式拒绝残缺数据。
                    Log.e("PipeTransport", "$description 写入失败", error)
                } finally {
                    runCatching { writeSide.close() }
                }
            }
        } catch (error: Throwable) {
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
            throw error
        }
        return readSide
    }
}
