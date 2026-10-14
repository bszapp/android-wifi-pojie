package io.github.bszapp.wifitoolbox.task

import android.util.AtomicFile
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.task.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * App 私有目录中的服务记录镜像；历史不参与任务调度。每条记录独立原子落盘。
 *
 * 字典破解与 WPS 破解共用同一目录，按 `"$kind|$ssid"` 键控，同一 SSID 的两类记录互不
 * 覆盖。schema 升到 2，读到旧格式直接跳过（不做兼容）。
 */
internal class SecurityAuditHistoryStore(private val directory: File) {
    private val records = linkedMapOf<String, AuditRecord>()
    private val deletedVersions = mutableMapOf<String, Long>()
    private val lock = Any()
    private val _state = MutableStateFlow<List<AuditRecord>>(emptyList())
    val state = _state.asStateFlow()

    private fun keyOf(record: AuditRecord) = "${record.kind}|${record.ssid}"

    fun load() = synchronized(lock) {
        directory.listFiles()?.filter { it.extension == "deleted" }?.forEach { file ->
            runCatching {
                UUID.fromString(file.nameWithoutExtension)
                deletedVersions[file.nameWithoutExtension] = AtomicFile(file).openRead().bufferedReader().use { it.readText().toLongOrNull() ?: 0 }
            }
        }
        directory.listFiles()?.filter { it.extension == "record" }?.forEach { file ->
            runCatching {
                val json = JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
                val record = decode(json)
                if (file.nameWithoutExtension == record.id && record.updatedAt > (deletedVersions[record.id] ?: Long.MIN_VALUE)) {
                    val key = keyOf(record)
                    val old = records[key]
                    if (old == null || old.updatedAt < record.updatedAt) records[key] = record
                }
            }.onFailure { Log.e("SecurityAuditHistory", "读取历史失败：${file.name}", it) }
        }
        publish()
    }

    fun merge(incoming: List<AuditRecord>) = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安全测试历史目录" }
        incoming.forEach { record ->
            if (record.updatedAt <= (deletedVersions[record.id] ?: Long.MIN_VALUE)) return@forEach
            val key = keyOf(record)
            val old = records[key]
            if (old == record || (old != null && old.updatedAt > record.updatedAt)) return@forEach
            UUID.fromString(record.id)
            val file = AtomicFile(File(directory, "${record.id}.record"))
            val bytes = encode(record).toString().toByteArray(Charsets.UTF_8)
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) }
            catch (error: Throwable) { file.failWrite(out); throw error }
            records[key] = record
            if (old != null && old.id != record.id) AtomicFile(File(directory, "${old.id}.record")).delete()
        }
        publish()
    }

    fun delete(id: String) = synchronized(lock) {
        UUID.fromString(id)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安全测试历史目录" }
        val entry = records.entries.firstOrNull { it.value.id == id } ?: return@synchronized
        val version = entry.value.updatedAt
        // 仅排除已经删除的这个快照；同一运行网络产生新结果后仍可重新写入历史。
        val marker = AtomicFile(File(directory, "$id.deleted"))
        val out = marker.startWrite()
        try { out.write(version.toString().toByteArray()); marker.finishWrite(out) }
        catch (error: Throwable) { marker.failWrite(out); throw error }
        deletedVersions[id] = version
        AtomicFile(File(directory, "$id.record")).delete()
        records.remove(entry.key)
        publish()
    }

    fun saveDictionary(id: String, source: File) = synchronized(lock) {
        UUID.fromString(id)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安全测试历史目录" }
        val file = AtomicFile(File(directory, "$id.dictionary"))
        val out = file.startWrite()
        try { source.inputStream().use { it.copyTo(out) }; file.finishWrite(out) }
        catch (error: Throwable) { file.failWrite(out); throw error }
    }

    fun dictionary(recordId: String): File? = synchronized(lock) {
        (records.values.firstOrNull { it.id == recordId } as? AuditRecord.Dictionary)?.let { record ->
            File(directory, "${UUID.fromString(record.input.dictionaryId)}.dictionary").takeIf { it.isFile }
        }
    }

    private fun publish() { _state.value = records.values.sortedByDescending { it.updatedAt } }

    private fun encodeDictionary(record: AuditRecord.Dictionary) = JSONObject().apply {
        put("schema", SCHEMA)
        put("kind", AuditRecordKind.DICTIONARY.name)
        put("id", record.id)
        put("ssid", record.input.ssid)
        put("dictionaryId", record.input.dictionaryId)
        put("dictionaryNames", JSONArray(record.input.dictionaryNames))
        put("resourceIds", JSONArray(record.input.resourceIds))
        put("startIndex", record.input.startIndex)
        put("config", JSONObject().apply {
            put("method", record.config.connectionMethod.name)
            put("timeout", record.config.connection.timeoutMillis)
            val flags = record.config.connection.failureFlags
            put("passwordError", flags.passwordError)
            put("handshakeAttempts", flags.handshakeAttemptsExceeded?.maxHandshakeAttempts ?: JSONObject.NULL)
            put("handshakeTimeout", flags.handshakeTimeout?.handshakeStepTimeoutMillis ?: JSONObject.NULL)
            put("retryCount", record.config.retryCount)
            put("cooldownBase", record.config.cooldownBaseMillis)
            put("handshakeTimeoutValue", record.config.handshakeTimeoutMillis)
            put("handshakeAttemptsValue", record.config.maxHandshakeAttempts)
            put("macMode", record.config.macMode.name)
            put("macChangeThreshold", record.config.macChangeThreshold)
        })
        put("startedAt", record.startedAt)
        put("updatedAt", record.updatedAt)
        put("state", record.state.name)
        put("total", record.total)
        put("completed", record.completed)
        put("attempts", record.attempts)
        put("retryCount", record.retryCount)
        put("elapsedMillis", record.elapsedMillis)
        put("averageAttemptMillis", record.averageAttemptMillis ?: JSONObject.NULL)
        put("nextAttemptAt", record.nextAttemptAt ?: JSONObject.NULL)
        put("stage", record.stage?.name ?: JSONObject.NULL)
        put("message", record.message)
        put("password", record.password ?: JSONObject.NULL)
        put("dictionaryPosition", record.dictionaryPosition ?: JSONObject.NULL)
    }

    private fun encodeWps(record: AuditRecord.Wps) = JSONObject().apply {
        put("schema", SCHEMA)
        put("kind", AuditRecordKind.WPS.name)
        put("id", record.id)
        put("ssid", record.ssid)
        put("config", JSONObject().apply {
            put("macMode", record.config.macMode.name)
            put("macChangeThreshold", record.config.macChangeThreshold)
            put("retryCount", record.config.retryCount)
            put("cooldownBase", record.config.cooldownBaseMillis)
            put("maxAttemptsPerNetwork", record.config.maxAttemptsPerNetwork)
        })
        put("startedAt", record.startedAt)
        put("updatedAt", record.updatedAt)
        put("state", record.state.name)
        put("attempts", record.attempts)
        put("message", record.message)
        put("password", record.password ?: JSONObject.NULL)
        put("mac", record.mac ?: JSONObject.NULL)
        put("phase", record.phase.name)
        put("vulnPin", record.vulnPin ?: JSONObject.NULL)
        put("firstHalf", record.firstHalf ?: JSONObject.NULL)
        put("firstHalfIndex", record.firstHalfIndex)
        put("secondHalfIndex", record.secondHalfIndex)
        put("retryCount", record.retryCount)
        put("nextAttemptAt", record.nextAttemptAt ?: JSONObject.NULL)
        put("averageAttemptMillis", record.averageAttemptMillis ?: JSONObject.NULL)
        put("nDayVerdict", record.nDayVerdict ?: JSONObject.NULL)
        put("pin", record.pin ?: JSONObject.NULL)
        put("passwordFoundPhase", record.passwordFoundPhase?.name ?: JSONObject.NULL)
        put("elapsedMillis", record.elapsedMillis)
        put("handshake", record.handshake?.let { hs ->
            JSONObject().apply {
                put("pke", hs.pke); put("pkr", hs.pkr); put("eNonce", hs.eNonce)
                put("authKey", hs.authKey); put("eHash1", hs.eHash1)
                put("eHash2", hs.eHash2); put("essid", hs.essid)
            }
        } ?: JSONObject.NULL)
    }

    private fun encode(record: AuditRecord): JSONObject = when (record) {
        is AuditRecord.Dictionary -> encodeDictionary(record)
        is AuditRecord.Wps -> encodeWps(record)
    }

    private fun decodeDictionary(json: JSONObject): AuditRecord.Dictionary {
        val config = json.getJSONObject("config")
        fun strings(name: String) = json.getJSONArray(name).let { array -> List(array.length()) { array.getString(it) } }
        return AuditRecord.Dictionary(
            id = json.getString("id"),
            input = SecurityAuditNetworkInput(json.getString("ssid"), json.getString("dictionaryId"),
                strings("dictionaryNames"), strings("resourceIds"), json.optInt("startIndex")),
            config = SecurityAuditConfig(SecurityAuditConnectionMethod.valueOf(config.getString("method")),
                ConnectWifiTaskConfig(config.getLong("timeout"), ConnectWifiFailureFlags(config.getBoolean("passwordError"),
                    if (config.isNull("handshakeAttempts")) null else HandshakeAttemptsExceededFlag(config.getInt("handshakeAttempts")),
                    if (config.isNull("handshakeTimeout")) null else HandshakeTimeoutFlag(config.getLong("handshakeTimeout")))),
                config.getInt("retryCount"), config.getLong("cooldownBase"),
                config.optLong("handshakeTimeoutValue", 1500), config.optInt("handshakeAttemptsValue", 1),
                SecurityAuditMacMode.valueOf(config.getString("macMode")),
                config.optInt("macChangeThreshold", 1).coerceIn(1, 5)),
            startedAt = json.getLong("startedAt"), updatedAt = json.getLong("updatedAt"),
            state = SecurityAuditNetworkState.valueOf(json.getString("state")), total = json.getInt("total"),
            completed = json.getInt("completed"), attempts = json.getLong("attempts"), retryCount = json.getInt("retryCount"),
            elapsedMillis = json.getLong("elapsedMillis"),
            averageAttemptMillis = if (json.isNull("averageAttemptMillis")) null else json.getLong("averageAttemptMillis"),
            nextAttemptAt = if (json.isNull("nextAttemptAt")) null else json.getLong("nextAttemptAt"),
            stage = if (json.isNull("stage")) null else ConnectWifiStage.valueOf(json.getString("stage")),
            message = json.getString("message"), password = if (json.isNull("password")) null else json.getString("password"),
            dictionaryPosition = if (json.isNull("dictionaryPosition")) null else json.getInt("dictionaryPosition"),
        )
    }

    private fun decodeWps(json: JSONObject): AuditRecord.Wps {
        val config = json.getJSONObject("config")
        return AuditRecord.Wps(
            id = json.getString("id"),
            ssid = json.getString("ssid"),
            config = WpsSecurityAuditConfig(
                macMode = SecurityAuditMacMode.valueOf(config.getString("macMode")),
                macChangeThreshold = config.optInt("macChangeThreshold", 1).coerceIn(1, 5),
                retryCount = config.optInt("retryCount", 6),
                cooldownBaseMillis = config.optLong("cooldownBase", 0),
                maxAttemptsPerNetwork = config.optInt("maxAttemptsPerNetwork", 0).coerceAtLeast(0),
            ),
            startedAt = json.getLong("startedAt"),
            updatedAt = json.getLong("updatedAt"),
            state = SecurityAuditNetworkState.valueOf(json.getString("state")),
            attempts = json.optLong("attempts"),
            message = json.getString("message"),
            password = if (json.isNull("password")) null else json.getString("password"),
            mac = if (json.isNull("mac")) null else json.getString("mac"),
            phase = WpsSecurityAuditPhase.valueOf(json.getString("phase")),
            vulnPin = if (json.isNull("vulnPin")) null else json.getString("vulnPin"),
            firstHalf = if (json.isNull("firstHalf")) null else json.getString("firstHalf"),
            firstHalfIndex = json.optInt("firstHalfIndex"),
            secondHalfIndex = json.optInt("secondHalfIndex"),
            retryCount = json.optInt("retryCount"),
            nextAttemptAt = if (json.isNull("nextAttemptAt")) null else json.getLong("nextAttemptAt"),
            averageAttemptMillis = if (json.isNull("averageAttemptMillis")) null else json.getLong("averageAttemptMillis"),
            nDayVerdict = if (json.isNull("nDayVerdict")) null else json.getBoolean("nDayVerdict"),
            pin = if (json.isNull("pin")) null else json.getString("pin"),
            passwordFoundPhase = if (json.isNull("passwordFoundPhase")) null
                else WpsSecurityAuditPhase.valueOf(json.getString("passwordFoundPhase")),
            elapsedMillis = json.optLong("elapsedMillis"),
            handshake = if (json.isNull("handshake")) null else json.getJSONObject("handshake").let { hs ->
                WpsHandshake(
                    pke = hs.getString("pke"), pkr = hs.getString("pkr"), eNonce = hs.getString("eNonce"),
                    authKey = hs.getString("authKey"), eHash1 = hs.getString("eHash1"),
                    eHash2 = hs.getString("eHash2"), essid = hs.optString("essid"),
                )
            },
        )
    }

    private fun decode(json: JSONObject): AuditRecord {
        require(json.getInt("schema") == SCHEMA) { "不支持的安全测试历史格式" }
        return when (AuditRecordKind.valueOf(json.getString("kind"))) {
            AuditRecordKind.DICTIONARY -> decodeDictionary(json)
            AuditRecordKind.WPS -> decodeWps(json)
        }
    }

    private companion object {
        const val SCHEMA = 2
    }
}
