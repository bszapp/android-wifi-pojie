package io.github.bszapp.wifitoolbox.service.hashcat

import org.json.JSONObject

/** 解析固定上游版本的控制台输出。准备日志常无换行，不能等 readLine 才发布阶段。 */
class HashcatOutputParser(
    private val onEvent: (HashcatEvent) -> Unit,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val pending = StringBuilder()
    private var scannedUntil = 0
    private var runningPublished = false
    var lastStatus: HashcatStatusSnapshot? = null
        private set

    fun consume(text: String) {
        onEvent(HashcatEvent.Output(text))
        text.forEach { character ->
            if (character == '\n' || character == '\r') {
                scanPhases()
                parseLine(pending.toString())
                pending.clear()
                scannedUntil = 0
            } else {
                pending.append(character)
            }
        }
        scanPhases()
    }

    fun finish() {
        scanPhases()
        parseLine(pending.toString())
        pending.clear()
        scannedUntil = 0
    }

    private fun scanPhases() {
        val text = pending.toString()
        phasePattern.findAll(text, scannedUntil).forEach { match ->
            val detail = match.value
            val phase = when {
                detail.startsWith("Initializing backend runtimes") -> HashcatPhase.LOADING_BACKEND
                detail.startsWith("Initializing backend devices") -> HashcatPhase.LOADING_DEVICES
                detail.startsWith("Initializing bridges") -> HashcatPhase.LOADING_BRIDGES
                detail.startsWith("Counting lines") || detail.startsWith("Parsed Hashes") ->
                    HashcatPhase.READING_HANDSHAKES
                detail.startsWith("Initializing device kernels") ||
                    detail.startsWith("Initializing backend runtime for device") -> HashcatPhase.BUILDING_KERNELS
                detail.startsWith("Dictionary cache building") -> HashcatPhase.BUILDING_DICTIONARY_INDEX
                detail.startsWith("Dictionary cache built") || detail.startsWith("Dictionary cache hit") ->
                    HashcatPhase.DICTIONARY_INDEX_READY
                detail.startsWith("Starting self-test") -> HashcatPhase.SELF_TEST
                detail.startsWith("Starting autotune") -> HashcatPhase.AUTOTUNE
                else -> HashcatPhase.RUNNING
            }
            val percent = indexPercent.find(detail)?.groupValues?.get(1)?.toDoubleOrNull()
            scannedUntil = match.range.last + 1
            if (phase == HashcatPhase.RUNNING) {
                if (runningPublished) return@forEach
                runningPublished = true
            }
            onEvent(HashcatEvent.Phase(phase, detail, percent))
        }
    }

    private fun parseLine(line: String) {
        kernelStep.find(line)?.let { match ->
            onEvent(HashcatEvent.KernelStep(match.groupValues[1].toInt(), match.groupValues[2],
                match.groupValues[3], match.groupValues[4] == "DONE"))
            return
        }
        if (line.trim() == "Hashcat kernel preparation complete") {
            onEvent(HashcatEvent.KernelReady)
            return
        }
        // The upstream may concatenate a final non-newline diagnostic and the JSON record.
        val start = line.indexOf('{')
        if (start < 0) return
        val jsonText = line.substring(start).trim()
        if (!jsonText.startsWith("{ \"session\"") && !jsonText.startsWith("{\"session\"")) return
        try {
            val json = JSONObject(jsonText)
            val progress = json.getJSONArray("progress")
            val recovered = json.getJSONArray("recovered_hashes")
            val statusCode = json.getInt("status")
            val startTime = json.getLong("time_start").takeIf { it > 0 }
            // Upstream uses 1 as the overflow sentinel for estimated_stop.
            val estimatedStop = json.getLong("estimated_stop").takeIf { it > 1 && startTime != null }
            val remaining = estimatedStop?.let { (it - epochSeconds()).coerceAtLeast(0) }
            val guess = json.getJSONObject("guess")
            val devices = json.getJSONArray("devices")
            val snapshot = HashcatStatusSnapshot(
                session = json.getString("session"),
                statusCode = statusCode,
                progressCompleted = progress.getLong(0),
                progressTotal = progress.getLong(1),
                recoveredHashes = recovered.getInt(0),
                totalHashes = recovered.getInt(1),
                rejectedCandidates = json.getLong("rejected"),
                restorePoint = json.getLong("restore_point"),
                startedAtEpochSeconds = startTime,
                estimatedStopEpochSeconds = estimatedStop,
                remainingSeconds = remaining,
                dictionary = if (guess.isNull("guess_base")) null else guess.getString("guess_base"),
                dictionaryPercent = guess.optDouble("guess_base_percent", Double.NaN)
                    .takeIf { it.isFinite() },
                runningMillis = json.optLong("running_millis", 0),
                devices = List(devices.length()) { index ->
                    val device = devices.getJSONObject(index)
                    HashcatDeviceStatus(
                        id = device.getInt("device_id"),
                        name = device.getString("device_name"),
                        type = device.getString("device_type"),
                        hashesPerSecond = device.getLong("speed"),
                        temperatureCelsius = device.optInt("temp", -1).takeIf { it >= 0 },
                        utilizationPercent = device.optInt("util", -1).takeIf { it >= 0 },
                        candidateRange = if (!device.has("candidates") || device.isNull("candidates")) {
                            null
                        } else device.getString("candidates"),
                        pbkdf2Completed = device.optLong("pbkdf2_completed", 0),
                        pbkdf2Total = device.optLong("pbkdf2_total", 0),
                    )
                },
            )
            lastStatus = snapshot
            onEvent(HashcatEvent.Status(snapshot))
        } catch (error: Exception) {
            onEvent(HashcatEvent.ParseError(jsonText, error.message ?: error.javaClass.simpleName))
        }
    }

    private companion object {
        val kernelStep = Regex("Hashcat kernel step: ([0-9]+)\\|([A-Z]+)\\|([^|\\r\\n]+)\\|(START|DONE)")
        val indexPercent = Regex("\\(([0-9.]+)%\\)")
        val phasePattern = Regex(
            "Initializing backend runtimes\\. Please be patient\\.\\.\\.|" +
                "Initializing backend devices\\. Please be patient\\.\\.\\.|" +
                "Initializing bridges\\. Please be patient\\.\\.\\.|" +
                "Counting lines in .*?\\. Please be patient\\.\\.\\.|" +
                "Parsed Hashes: [^\\r\\n]*?\\([0-9.]+%\\)|" +
                "Initializing device kernels and memory\\. Please be patient\\.\\.\\.|" +
                "Initializing backend runtime for device #[0-9]+\\. Please be patient\\.\\.\\.|" +
                "Dictionary cache building .*?: [0-9]+ bytes \\([0-9.]+%\\), [0-9]+ MiB/s|" +
                "Dictionary cache (?:built|hit):|" +
                "Starting self-test\\. Please be patient\\.\\.\\.|" +
                "Starting autotune\\. Please be patient\\.\\.\\.|" +
                "Starting attack in stdin mode|\\[s\\]tatus \\[p\\]ause",
        )
    }
}
