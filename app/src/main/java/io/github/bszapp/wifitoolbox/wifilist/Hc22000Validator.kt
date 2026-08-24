package io.github.bszapp.wifitoolbox.wifilist

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** App 侧使用 HC22000 中的 EAPOL 材料校验 WPA/WPA2 密码。 */
internal object Hc22000Validator {
    fun validate(hc22000: String, password: String): Boolean {
        val record = parse(hc22000)
        val pmk = passwordToPmk(record.essid, password)
        val ptk = pairwiseKeyExpansion(
            pmk = pmk,
            accessPointMac = record.accessPointMac,
            deviceMac = record.deviceMac,
            anonce = record.anonce,
            snonce = record.snonce,
        )
        val eapol = record.eapol.copyOf()
        eapol.fill(0.toByte(), MIC_OFFSET, MIC_OFFSET + MIC_LENGTH)
        val keyInfo = ((eapol[5].toInt() and 0xff) shl 8) or
            (eapol[6].toInt() and 0xff)
        val calculatedMic = when (keyInfo and 0x7) {
            1 -> hmac("HmacMD5", ptk.copyOfRange(0, 16), eapol)
            2 -> hmac("HmacSHA1", ptk.copyOfRange(0, 16), eapol).copyOf(16)
            3 -> aesCmac(ptk.copyOfRange(0, 16), eapol)
            else -> throw IllegalArgumentException("不支持的 EAPOL 密钥描述符版本")
        }
        return MessageDigest.isEqual(calculatedMic, record.mic)
    }

    private fun parse(value: String): Hc22000Record {
        val parts = value.trim().split('*')
        require(parts.size == 9 && parts[0] == "WPA" && parts[1] == "02") {
            "HC22000 格式无效"
        }
        val mic = parts[2].hexBytes(expectedBytes = 16)
        val accessPointMac = parts[3].hexBytes(expectedBytes = 6)
        val deviceMac = parts[4].hexBytes(expectedBytes = 6)
        val essid = parts[5].hexBytes().also {
            require(it.size in 1..32) { "HC22000 网络名称长度无效" }
        }
        val anonce = parts[6].hexBytes(expectedBytes = 32)
        val eapol = parts[7].hexBytes().also {
            require(it.size >= MIC_OFFSET + MIC_LENGTH) { "HC22000 EAPOL 数据不完整" }
        }
        parts[8].hexBytes(expectedBytes = 1)
        val snonce = eapol.copyOfRange(17, 49)
        require(snonce.any { it.toInt() != 0 }) { "HC22000 SNonce 无效" }
        return Hc22000Record(
            mic = mic,
            accessPointMac = accessPointMac,
            deviceMac = deviceMac,
            essid = essid,
            anonce = anonce,
            snonce = snonce,
            eapol = eapol,
        )
    }

    private fun passwordToPmk(essid: ByteArray, password: String): ByteArray {
        if (password.length == 64) {
            return password.hexBytes(expectedBytes = 32)
        }
        require(password.length in 8..63) { "WPA/WPA2 密码长度无效" }
        return pbkdf2HmacSha1(
            password = password.toByteArray(Charsets.UTF_8),
            salt = essid,
            iterations = 4096,
            outputBytes = 32,
        )
    }

    private fun pbkdf2HmacSha1(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        outputBytes: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password, "HmacSHA1"))
        val result = ByteArray(outputBytes)
        var outputOffset = 0
        var blockIndex = 1
        while (outputOffset < outputBytes) {
            val blockSalt = salt + byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            )
            var current = mac.doFinal(blockSalt)
            val accumulated = current.copyOf()
            repeat(iterations - 1) {
                current = mac.doFinal(current)
                for (index in accumulated.indices) {
                    accumulated[index] = (accumulated[index].toInt() xor current[index].toInt())
                        .toByte()
                }
            }
            val copyLength = minOf(accumulated.size, outputBytes - outputOffset)
            accumulated.copyInto(result, outputOffset, 0, copyLength)
            outputOffset += copyLength
            blockIndex += 1
        }
        return result
    }

    private fun pairwiseKeyExpansion(
        pmk: ByteArray,
        accessPointMac: ByteArray,
        deviceMac: ByteArray,
        anonce: ByteArray,
        snonce: ByteArray,
    ): ByteArray {
        val data = minBytes(accessPointMac, deviceMac) +
            maxBytes(accessPointMac, deviceMac) +
            minBytes(anonce, snonce) +
            maxBytes(anonce, snonce)
        val prefix = "Pairwise key expansion".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + data
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk, "HmacSHA1"))
        val output = ArrayList<Byte>(80)
        var counter = 0
        while (output.size < 64) {
            mac.doFinal(prefix + counter.toByte()).forEach { output.add(it) }
            counter += 1
        }
        return ByteArray(64) { output[it] }
    }

    private fun aesCmac(key: ByteArray, message: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val firstSubkey = cmacSubkey(cipher.doFinal(ByteArray(AES_BLOCK_BYTES)))
        val secondSubkey = cmacSubkey(firstSubkey)
        val blockCount = maxOf(1, (message.size + AES_BLOCK_BYTES - 1) / AES_BLOCK_BYTES)
        val completeLastBlock = message.isNotEmpty() && message.size % AES_BLOCK_BYTES == 0
        val lastBlock = if (completeLastBlock) {
            xor(
                message.copyOfRange(
                    (blockCount - 1) * AES_BLOCK_BYTES,
                    blockCount * AES_BLOCK_BYTES,
                ),
                firstSubkey,
            )
        } else {
            val remainderStart = (blockCount - 1) * AES_BLOCK_BYTES
            val padded = ByteArray(AES_BLOCK_BYTES)
            message.copyOfRange(remainderStart, message.size).copyInto(padded)
            padded[message.size - remainderStart] = 0x80.toByte()
            xor(padded, secondSubkey)
        }
        var state = ByteArray(AES_BLOCK_BYTES)
        for (blockIndex in 0 until blockCount - 1) {
            val block = message.copyOfRange(
                blockIndex * AES_BLOCK_BYTES,
                (blockIndex + 1) * AES_BLOCK_BYTES,
            )
            state = cipher.doFinal(xor(state, block))
        }
        return cipher.doFinal(xor(state, lastBlock))
    }

    private fun cmacSubkey(value: ByteArray): ByteArray {
        val carry = value[0].toInt() and 0x80 != 0
        val shifted = ByteArray(value.size)
        for (index in value.indices) {
            val next = if (index + 1 < value.size) value[index + 1].toInt() and 0xff else 0
            shifted[index] = (((value[index].toInt() and 0xff) shl 1) or (next ushr 7)).toByte()
        }
        if (carry) shifted[shifted.lastIndex] = (shifted.last().toInt() xor 0x87).toByte()
        return shifted
    }

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(algorithm).run {
            init(SecretKeySpec(key, algorithm))
            doFinal(data)
        }

    private fun minBytes(first: ByteArray, second: ByteArray): ByteArray =
        if (compareBytes(first, second) <= 0) first else second

    private fun maxBytes(first: ByteArray, second: ByteArray): ByteArray =
        if (compareBytes(first, second) >= 0) first else second

    private fun compareBytes(first: ByteArray, second: ByteArray): Int {
        for (index in 0 until minOf(first.size, second.size)) {
            val comparison = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return first.size - second.size
    }

    private fun xor(first: ByteArray, second: ByteArray): ByteArray {
        require(first.size == second.size)
        return ByteArray(first.size) { index ->
            (first[index].toInt() xor second[index].toInt()).toByte()
        }
    }

    private fun String.hexBytes(expectedBytes: Int? = null): ByteArray {
        require(length % 2 == 0) { "十六进制数据长度无效" }
        val bytes = ByteArray(length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(this[index * 2], 16)
            val low = Character.digit(this[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "十六进制数据包含非法字符" }
            bytes[index] = ((high shl 4) or low).toByte()
        }
        if (expectedBytes != null) {
            require(bytes.size == expectedBytes) { "十六进制数据长度无效" }
        }
        return bytes
    }

    private data class Hc22000Record(
        val mic: ByteArray,
        val accessPointMac: ByteArray,
        val deviceMac: ByteArray,
        val essid: ByteArray,
        val anonce: ByteArray,
        val snonce: ByteArray,
        val eapol: ByteArray,
    )

    private const val MIC_OFFSET = 81
    private const val MIC_LENGTH = 16
    private const val AES_BLOCK_BYTES = 16
}
