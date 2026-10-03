package io.github.bszapp.wifitoolbox.service.hashcat

import java.io.File
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

/** Independent offline WPA1/WPA2/PMKID vectors exercise selective kernel creation. */
object HashcatKernelDeviceTest {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val directory = File(args[1]).apply { check(isDirectory || mkdirs()) }
            val fixture = HashcatDeviceTest.randomHandshake(1025)
            val fields = fixture.hash.split('*')
            fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
            fun hmac(algorithm: String, key: ByteArray, input: ByteArray) = Mac.getInstance(algorithm).run {
                init(SecretKeySpec(key, algorithm)); doFinal(input)
            }
            fun sorted(a: ByteArray, b: ByteArray): ByteArray {
                val index = a.indices.firstOrNull { a[it] != b[it] }
                return if (index == null || (a[index].toInt() and 255) < (b[index].toInt() and 255)) a + b else b + a
            }
            val ap = fields[3].bytes()
            val station = fields[4].bytes()
            val ssid = fields[5].bytes()
            val pmk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(PBEKeySpec(fixture.password.toCharArray(), ssid, 4096, 256)).encoded
            val eapol = fields[7].bytes().apply { this[4] = 0xfe.toByte(); this[6] = 9 }
            val kck = hmac("HmacSHA1", pmk, "Pairwise key expansion".toByteArray() + byteArrayOf(0) +
                sorted(ap, station) + sorted(fields[6].bytes(), eapol.copyOfRange(17, 49)) + byteArrayOf(0)).copyOf(16)
            val wpa1 = fields.toMutableList().apply { this[2] = hmac("HmacMD5", kck, eapol).hex(); this[7] = eapol.hex() }.joinToString("*")
            val pmkid = "WPA*01*${hmac("HmacSHA1", pmk, "PMK Name".toByteArray() + ap + station).copyOf(16).hex()}*${fields[3]}*${fields[4]}*${fields[5]}***"
            val dictionary = File(directory, "dictionary.txt").apply { writeText(fixture.candidates.joinToString("\n", postfix = "\n")) }
            val cases = listOf("wpa1" to listOf(wpa1), "pmkid" to listOf(pmkid), "mixed" to listOf(wpa1, pmkid, fixture.hash))
            cases.forEach { (name, hashes) ->
                val handshake = File(directory, "$name.hc22000").apply { writeText(hashes.joinToString("\n", postfix = "\n")) }
                val log = File(directory, "$name.log").bufferedWriter()
                val result = HashcatController().use { controller ->
                    controller.run(HashcatRequest(File(args[0]), handshake, dictionary, deviceMemoryLimitMiB = 2048,
                        openClLibrary = File("/vendor/lib64/libOpenCL.so"))) { event ->
                        if (event is HashcatEvent.Output) log.write(event.text)
                        check(event !is HashcatEvent.ParseError)
                    }
                }
                log.close()
                check(result.exitCode == 0 && result.passwords.toSet() == setOf(fixture.password)) { "$name: $result" }
                check(result.lastStatus?.recoveredHashes == hashes.size) { "$name did not verify every record" }
                check(result.dictionaryPositions[fixture.password] == 1025L)
                println("PASS $name: ${hashes.size} random records recovered, candidate 1025")
            }
            dictionary.writeText(fixture.candidates.dropLast(1).joinToString("\n", postfix = "\n"))
            val rejected = HashcatController().use { controller ->
                controller.run(HashcatRequest(File(args[0]), File(directory, "mixed.hc22000"), dictionary,
                    deviceMemoryLimitMiB = 2048, openClLibrary = File("/vendor/lib64/libOpenCL.so"))) { event ->
                    check(event !is HashcatEvent.ParseError)
                }
            }
            check(rejected.exitCode == 1 && rejected.passwords.isEmpty())
            println("PASS wrong dictionary: all three records exhausted without false positives")
            exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
    }
}
