package io.github.bszapp.wifitoolbox.service.hashcat

import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 速度测试任务素材：随机的 SpeedTest 网络握手与内置字典。
 * 字典前段为99999个从00000001递增的8位数字候选，末段为唯一正确密码；
 * PMKID 与密码自洽，本机可完整校验并通过。
 */
internal object HashcatSpeedTest {
    /** 密码固定位于第100000行，评估规模固定、首尾可控。 */
    const val DICTIONARY_LINES = 99999L
    private const val PASSWORD_LENGTH = 16

    data class Fixture(
        val id: String,
        val createdAt: Long,
        val ssid: String,
        val handshake: String,
        val dictionary: String,
    )

    fun generate(): Fixture {
        val random = SecureRandom()
        fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

        val suffix = hexOf(bytes(3))
        val ssid = "SpeedTest-$suffix"
        val ap = byteArrayOf(0, 0, 0) + suffix.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val station = byteArrayOf(2, 0, 0) + suffix.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val password = buildString {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            repeat(PASSWORD_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) }
        }

        val keySpec = PBEKeySpec(password.toCharArray(), ssid.toByteArray(Charsets.US_ASCII), 4096, 256)
        val pmk = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(keySpec).encoded
        } finally { keySpec.clearPassword() }
        fun hmacSha1(key: ByteArray, message: ByteArray) = Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(key, "HmacSHA1"))
            doFinal(message)
        }
        // PMKID = HMAC-SHA1(PMK, "PMK Name" + MAC_AP + MAC_STA)，只取前16字节。
        val pmkid = hmacSha1(pmk, "PMK Name".toByteArray(Charsets.US_ASCII) + ap + station).copyOf(16).hex()
        val handshake = listOf(
            "WPA*01*", pmkid, "*", hexOf(ap), "*", hexOf(station), "*", ssid.toByteArray(Charsets.US_ASCII).hex(), "***",
        ).joinToString("") + "\n"

        val dictionary = buildString(capacity = (DICTIONARY_LINES + 1).toInt() * 10) {
            for (index in 1..DICTIONARY_LINES) {
                append("%08d".format(index))
                append('\n')
            }
            append(password)
            append('\n')
        }
        return Fixture(UUID.randomUUID().toString(), System.currentTimeMillis(), ssid, handshake, dictionary)
    }

    private fun hexOf(source: ByteArray) = source.joinToString("") { "%02x".format(it.toInt() and 255) }
}
