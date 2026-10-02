package io.github.bszapp.wifitoolbox.service

import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import java.net.NetworkInterface
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 直接请求 nl80211 GET_INTERFACE；不依赖容器、iw 输出或 Android Wi-Fi 开关。 */
internal object WirelessInterfaceNetlink {
    fun readMode(): WifiMode {
        val index = NetworkInterface.getByName("wlan0")?.index ?: error("wlan0 不存在")
        val fd = Os.socket(16, OsConstants.SOCK_RAW, 16)
        try {
            val addressClass = Class.forName("android.system.NetlinkSocketAddress")
            fun address(port: Int) = addressClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(port, 0) as SocketAddress
            Os.bind(fd, address(0))
            Os.connect(fd, address(0))
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(1500))
            fun attribute(type: Int, value: ByteArray): ByteArray = ByteBuffer.allocate((value.size + 7) and -4)
                .order(ByteOrder.nativeOrder()).putShort((value.size + 4).toShort()).putShort(type.toShort()).put(value).array()
            fun request(type: Int, command: Int, seq: Int, attrs: ByteArray): List<Pair<Int, ByteArray>> {
                val data = ByteBuffer.allocate(20 + attrs.size).order(ByteOrder.nativeOrder())
                    .putInt(20 + attrs.size).putShort(type.toShort()).putShort(1).putInt(seq).putInt(0)
                    .put(command.toByte()).put(1).putShort(0).put(attrs).array()
                Os.write(fd, data, 0, data.size)
                val input = ByteArray(65536)
                while (true) {
                    val count = Os.read(fd, input, 0, input.size)
                    val buffer = ByteBuffer.wrap(input, 0, count).order(ByteOrder.nativeOrder())
                    var offset = 0
                    while (offset + 16 <= count) {
                        val length = buffer.getInt(offset)
                        require(length >= 16 && offset + length <= count) { "损坏的 Netlink 消息" }
                        if (buffer.getInt(offset + 8) == seq) {
                            val messageType = buffer.getShort(offset + 4).toInt() and 65535
                            if (messageType == 2) {
                                val errorCode = buffer.getInt(offset + 16)
                                check(errorCode == 0) { "Netlink 错误 $errorCode" }
                            } else if (messageType == type) {
                                val result = mutableListOf<Pair<Int, ByteArray>>()
                                var cursor = offset + 20
                                while (cursor + 4 <= offset + length) {
                                    val size = buffer.getShort(cursor).toInt() and 65535
                                    require(size >= 4 && cursor + size <= offset + length)
                                    result.add((buffer.getShort(cursor + 2).toInt() and 16383) to input.copyOfRange(cursor + 4, cursor + size))
                                    cursor += (size + 3) and -4
                                }
                                return result
                            }
                        }
                        offset += (length + 3) and -4
                    }
                }
            }
            val family = request(16, 3, 1, attribute(2, "nl80211\u0000".toByteArray()))
                .first { it.first == 1 }.second
            val familyId = ByteBuffer.wrap(family).order(ByteOrder.nativeOrder()).short.toInt() and 65535
            val mode = request(familyId, 5, 2, attribute(3, ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(index).array()))
                .first { it.first == 5 }.second
            return if (ByteBuffer.wrap(mode).order(ByteOrder.nativeOrder()).int == 6) WifiMode.MONITOR else WifiMode.NORMAL
        } finally {
            Os.close(fd)
        }
    }
}
