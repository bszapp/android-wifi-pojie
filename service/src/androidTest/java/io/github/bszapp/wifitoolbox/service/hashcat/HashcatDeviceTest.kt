package io.github.bszapp.wifitoolbox.service.hashcat

import android.os.Process
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.MessageDigest
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

/** app_process 运行的设备单元测试入口，不需要安装测试 APK 或操作图形界面。 */
object HashcatDeviceTest {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runTest(args)
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
    }

    private fun runTest(args: Array<String>) {
        require(args.size in 2..6) { "参数：libhashcat.so 路径、独立测试目录、可选设备内存预算 MiB、可选 OpenCL 驱动、可选候选数量（大于 3 时正确密码放末行）、可选复用的测试数据目录" }
        println("TEST uid=${Process.myUid()} pid=${Process.myPid()}")
        val work = File(args[1])
        check(work.isDirectory || work.mkdirs())
        val candidateCount = args.getOrNull(4)?.toInt() ?: 3
        require(candidateCount >= 3)
        val fixtureDirectory = args.getOrNull(5)?.let(::File) ?: work
        val reused = args.size == 6
        val fixture = if (reused) {
            val metadata = JSONObject(File(fixtureDirectory, "fixture.json").readText())
            Fixture(
                File(fixtureDirectory, "router.hc22000").readText().trim(),
                metadata.getString("ssid"), metadata.getString("password"),
                File(fixtureDirectory, "dictionary.txt").readLines(),
            ).also { check(it.candidates.size == candidateCount) }
        } else randomHandshake(candidateCount)
        println("FIXTURE reused=$reused ssid=${fixture.ssid} correctCandidate=${fixture.candidates.indexOf(fixture.password) + 1}/$candidateCount")
        if (candidateCount > 3) check(fixture.candidates.last() == fixture.password)
        val handshakeText = "${fixture.hash}\n"
        val dictionaryText = fixture.candidates.joinToString("\n", postfix = "\n")
        val handshake = File(fixtureDirectory, "router.hc22000")
        val dictionary = File(fixtureDirectory, "dictionary.txt")
        if (!reused) {
            handshake.writeText(handshakeText)
            dictionary.writeText(dictionaryText)
            File(fixtureDirectory, "fixture.json").writeText(
                JSONObject().put("ssid", fixture.ssid).put("password", fixture.password).toString(),
            )
        }
        println("INPUT SHA256 handshake=${MessageDigest.getInstance("SHA-256").digest(handshake.readBytes()).hex()} dictionary=${MessageDigest.getInstance("SHA-256").digest(dictionary.readBytes()).hex()}")
        val captured = StringBuilder()
        val steps = mutableListOf<HashcatEvent.StepProgress>()
        val events = mutableListOf<HashcatEvent>()
        val parser = HashcatOutputParser(events::add)
        val controller = HashcatController()
        val runStarted = System.nanoTime()
        val stepStarted = mutableMapOf<HashcatStep, Long>()
        val result = controller.use {
            it.run(HashcatRequest(
                executable = File(args[0]),
                handshakeFile = handshake,
                dictionaryFile = dictionary,
                deviceMemoryLimitMiB = args.getOrNull(2)?.toInt(),
                openClLibrary = args.getOrNull(3)?.let(::File),
            )) { event ->
                when (event) {
                    is HashcatEvent.StepProgress -> {
                        steps.add(event)
                        val started = stepStarted.getOrPut(event.step) { System.nanoTime() }
                        if (event.finished) println("STEP_TIME ${event.step} milliseconds=${(System.nanoTime() - started) / 1_000_000.0}")
                        if (event.finished || event.completed == 0L) println("STEP $event")
                    }
                    is HashcatEvent.Output -> {
                        print(event.text)
                        captured.append(event.text)
                        parser.consume(event.text)
                    }
                    is HashcatEvent.Phase -> println("\nPARSED phase=${event.phase} percent=${event.percent} detail=${event.detail}")
                    is HashcatEvent.Status -> {
                        val snapshot = event.snapshot
                        println("\nPARSED status=$snapshot")
                        snapshot.devices.forEach { device ->
                            println("ATTEMPT device=${device.name} passwords=${device.candidateRange} progress=${snapshot.progressCompleted}/${snapshot.progressTotal} percent=${snapshot.progressPercent} speed=${device.hashesPerSecond}H/s remaining=${snapshot.remainingSeconds}s")
                        }
                    }
                    is HashcatEvent.ParseError -> error("解析失败：$event")
                }
            }
        }
        parser.finish()
        println("RESULT $result")
        result.passwords.forEach { password ->
            println("FOUND line=${fixture.candidates.indexOf(password) + 1}/$candidateCount password=$password")
        }
        println("ELAPSED seconds=${(System.nanoTime() - runStarted) / 1_000_000_000.0}")
        check(result.exitCode == 0) { "Hashcat 退出码：${result.exitCode}" }
        check(result.passwords == listOf(fixture.password)) { "校验结果与随机生成的正确密码不一致" }
        HashcatStep.entries.forEach { step ->
            check(steps.any { it.step == step && it.finished && it.completed == it.total }) {
                "任务步骤未完成：$step"
            }
        }
        val runPath = steps.first { it.step == HashcatStep.COPYING_PROGRAM }.path
        check(!File(runPath).parentFile!!.exists()) { "任务目录未恢复" }
        check(handshake.readText() == handshakeText) { "握手输入原件被修改" }
        check(dictionary.readText() == dictionaryText) { "字典输入原件被修改" }
        val status = checkNotNull(result.lastStatus) { "未解析到状态 JSON" }
        check(status.status == HashcatStatus.CRACKED)
        check(status.recoveredHashes == 1 && status.totalHashes == 1)
        check(status.progressTotal > 0 && status.progressCompleted in 1..status.progressTotal)
        if (candidateCount > 3) {
            check(status.progressTotal == candidateCount.toLong() && status.progressCompleted == status.progressTotal)
        }
        check(status.startedAtEpochSeconds != null && status.estimatedStopEpochSeconds != null)
        check(status.remainingSeconds != null)
        check(status.devices.any { it.type == "GPU" && it.hashesPerSecond > 0 })
        val phases = events.filterIsInstance<HashcatEvent.Phase>()
        check(phases.any { it.phase == HashcatPhase.DICTIONARY_INDEX_READY })
        check(phases.any { it.phase == HashcatPhase.SELF_TEST })
        check(phases.any { it.phase == HashcatPhase.RUNNING })
        check(events.none { it is HashcatEvent.ParseError })
        // Replay the same real output with every possible small chunk size.
        for (chunkSize in 1..17) {
            val replay = mutableListOf<HashcatEvent>()
            val fragmented = HashcatOutputParser(replay::add)
            captured.toString().chunked(chunkSize).forEach(fragmented::consume)
            fragmented.finish()
            check(fragmented.lastStatus?.copy(remainingSeconds = null) == status.copy(remainingSeconds = null))
            check(replay.filterIsInstance<HashcatEvent.Phase>() == phases)
            check(replay.none { it is HashcatEvent.ParseError })
        }
        println("PASS: real GPU verification, status/progress/ETA/device parsing, fragmented output sizes 1..17, copy/cleanup steps, input originals preserved")
    }

    private data class Fixture(val hash: String, val ssid: String, val password: String, val candidates: List<String>)

    /** Generates M1/M2 verification material using JCE, independently from Hashcat's OpenCL kernel. */
    private fun randomHandshake(candidateCount: Int): Fixture {
        val random = SecureRandom()
        fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
        fun password() = buildString {
            repeat(16) { append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)]) }
        }
        val password = password()
        val candidates = linkedSetOf<String>()
        while (candidates.size < candidateCount - 1) {
            val wrongPassword = password()
            if (wrongPassword != password) candidates.add(wrongPassword)
        }
        candidates.add(password)
        val shuffled = candidates.toMutableList()
        if (candidateCount == 3) java.util.Collections.shuffle(shuffled, random)
        val ssid = "Hashcat-test-${bytes(4).hex()}"
        val ap = bytes(6).apply { this[0] = 2 }
        val station = bytes(6).apply { this[0] = 2 }
        val anonce = bytes(32)
        val snonce = bytes(32)
        // RSN IE: CCMP group/pairwise, WPA2-PSK AKM, zero RSN capabilities.
        val rsn = "30140100000fac040100000fac040100000fac020000".chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        val eapol = ByteBuffer.allocate(99 + rsn.size)
            .put(1.toByte()).put(3.toByte()).putShort((95 + rsn.size).toShort())
            .put(2.toByte()).putShort(0x010a.toShort()).putShort(0.toShort()).putLong(1L)
            .put(snonce).put(ByteArray(16 + 8 + 8 + 16))
            .putShort(rsn.size.toShort()).put(rsn).array()
        val keySpec = PBEKeySpec(password.toCharArray(), ssid.toByteArray(Charsets.US_ASCII), 4096, 256)
        val pmk = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(keySpec).encoded
        } finally { keySpec.clearPassword() }
        fun sortedPair(first: ByteArray, second: ByteArray): ByteArray {
            val different = first.indices.firstOrNull { first[it] != second[it] }
            return if (different == null || (first[different].toInt() and 255) < (second[different].toInt() and 255)) {
                first + second
            } else second + first
        }
        fun hmac(key: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(key, "HmacSHA1"))
            doFinal(message)
        }
        val prfInput = "Pairwise key expansion".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
            sortedPair(ap, station) + sortedPair(anonce, snonce) + byteArrayOf(0)
        val kck = hmac(pmk, prfInput).copyOf(16)
        val mic = hmac(kck, eapol).copyOf(16)
        val hash = "WPA*02*${mic.hex()}*${ap.hex()}*${station.hex()}*${ssid.toByteArray().hex()}*${anonce.hex()}*${eapol.hex()}*00"
        return Fixture(hash, ssid, password, shuffled)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
