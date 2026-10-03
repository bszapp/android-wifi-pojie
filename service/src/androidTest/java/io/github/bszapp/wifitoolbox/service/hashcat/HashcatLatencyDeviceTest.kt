package io.github.bszapp.wifitoolbox.service.hashcat

import android.os.Process
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

/** Offline random WPA2 fixture. Runs through the production controller in app_process. */
object HashcatLatencyDeviceTest {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
            exitProcess(0)
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        require(args.size >= 4) { "library, test directory, budget MiB, candidate count, optional repetitions" }
        val directory = File(args[1]).apply { check(isDirectory || mkdirs()) }
        val fixture = HashcatDeviceTest.randomHandshake(args[3].toInt())
        val handshake = File(directory, "fixture.hc22000").apply { writeText(fixture.hash + "\n") }
        val dictionary = File(directory, "dictionary.txt").apply {
            bufferedWriter().use { out -> fixture.candidates.forEach { out.write(it); out.newLine() } }
        }
        println("TEST uid=${Process.myUid()} candidates=${fixture.candidates.size}")
        repeat(args.getOrNull(4)?.toInt() ?: 1) { iteration ->
            val started = SystemClock.elapsedRealtimeNanos()
            fun elapsed() = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            val phaseTimes = JSONObject()
            val intervals = mutableListOf<Double>()
            val changes = mutableListOf<Double>()
            var lastStatusAt: Double? = null
            var lastCompleted = -1L
            var runningAt: Double? = null
            var logCharacters = 0L
            var statusCount = 0
            var lastIterations = -1L
            val iterationChanges = mutableListOf<Double>()
            val log = File(directory, "run-$iteration.log").bufferedWriter()
            val controller = HashcatController()
            val result = controller.use {
                it.run(HashcatRequest(
                    executable = File(args[0]), handshakeFile = handshake, dictionaryFile = dictionary,
                    deviceMemoryLimitMiB = args[2].toInt(),
                    openClLibrary = File("/vendor/lib64/libOpenCL.so").takeIf(File::isFile),
                )) { event ->
                    val at = elapsed()
                    when (event) {
                        is HashcatEvent.Phase -> if (!phaseTimes.has(event.phase.name)) {
                            phaseTimes.put(event.phase.name, at)
                            println("PHASE iteration=$iteration atMs=$at ${event.phase}")
                        }
                        is HashcatEvent.Status -> if (event.snapshot.status == HashcatStatus.RUNNING) {
                            if (runningAt == null) runningAt = at
                            lastStatusAt?.let { previous -> intervals.add(at - previous) }
                            lastStatusAt = at
                            if (lastCompleted != event.snapshot.progressCompleted) {
                                changes.add(at)
                                lastCompleted = event.snapshot.progressCompleted
                            }
                            val iterations = event.snapshot.devices.sumOf { device -> device.pbkdf2Completed }
                            if (iterations != lastIterations) { iterationChanges.add(at); lastIterations = iterations }
                            statusCount++
                            if (statusCount % 10 == 1) println("STATUS iteration=$iteration atMs=$at completed=${event.snapshot.progressCompleted}/${event.snapshot.progressTotal} pbkdf2=$iterations speed=${event.snapshot.devices.sumOf { device -> device.hashesPerSecond }}")
                        }
                        is HashcatEvent.Output -> {
                            logCharacters += event.text.length
                            log.write(event.text)
                        }
                        is HashcatEvent.StepProgress -> if (event.finished) println("STEP iteration=$iteration atMs=$at ${event.step}")
                        is HashcatEvent.ParseError -> error("Output parsing failed: $event")
                        is HashcatEvent.KernelStep, HashcatEvent.KernelReady -> Unit
                    }
                }
            }
            log.close()
            check(result.exitCode == 0 && result.passwords == listOf(fixture.password)) { "Wrong GPU result: $result" }
            check(result.dictionaryPositions[fixture.password] == fixture.candidates.size.toLong())
            val sorted = intervals.sorted()
            val summary = JSONObject().put("iteration", iteration).put("elapsedMs", elapsed())
                .put("firstStatusMs", runningAt).put("phasesMs", phaseTimes)
                .put("runningStatusCount", intervals.size + 1).put("statusIntervalMaxMs", sorted.lastOrNull())
                .put("statusIntervalMedianMs", sorted.getOrNull(sorted.size / 2))
                .put("progressChangesAtMs", JSONArray(changes)).put("logCharacters", logCharacters)
                .put("iterationChangesAtMs", JSONArray(iterationChanges))
                .put("foundPosition", result.dictionaryPositions[fixture.password])
            File(directory, "summary-$iteration.json").writeText(summary.toString(2))
            println("SUMMARY $summary")
        }
        println("PASS: random WPA2 GPU result and measured controller events")
    }
}
