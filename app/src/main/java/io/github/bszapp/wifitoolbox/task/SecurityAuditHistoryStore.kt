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

/** App 私有目录中的服务记录镜像；历史不参与任务调度。每条记录独立原子落盘。 */
internal class SecurityAuditHistoryStore(private val directory: File) {
    private val records = linkedMapOf<String, SecurityAuditNetworkRecord>()
    private val deletedVersions = mutableMapOf<String, Long>()
    private val lock = Any()
    private val _state = MutableStateFlow<List<SecurityAuditNetworkRecord>>(emptyList())
    val state = _state.asStateFlow()

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
                    val old = records[record.input.ssid]
                    if (old == null || old.updatedAt < record.updatedAt) records[record.input.ssid] = record
                }
            }.onFailure { Log.e("SecurityAuditHistory", "读取历史失败：${file.name}", it) }
        }
        publish()
    }

    fun merge(incoming: List<SecurityAuditNetworkRecord>) = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安全测试历史目录" }
        incoming.forEach { record ->
            if (record.updatedAt <= (deletedVersions[record.id] ?: Long.MIN_VALUE)) return@forEach
            val old = records[record.input.ssid]
            if (old == record || (old != null && old.updatedAt > record.updatedAt)) return@forEach
            UUID.fromString(record.id)
            val file = AtomicFile(File(directory, "${record.id}.record"))
            val bytes = encode(record).toString().toByteArray(Charsets.UTF_8)
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) }
            catch (error: Throwable) { file.failWrite(out); throw error }
            records[record.input.ssid] = record
            if (old != null && old.id != record.id) AtomicFile(File(directory, "${old.id}.record")).delete()
        }
        publish()
    }

    fun delete(id: String) = synchronized(lock) {
        UUID.fromString(id)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建安全测试历史目录" }
        val version = records.values.firstOrNull { it.id == id }?.updatedAt ?: return@synchronized
        // 仅排除已经删除的这个快照；同一运行网络产生新结果后仍可重新写入历史。
        val marker = AtomicFile(File(directory, "$id.deleted"))
        val out = marker.startWrite()
        try { out.write(version.toString().toByteArray()); marker.finishWrite(out) }
        catch (error: Throwable) { marker.failWrite(out); throw error }
        deletedVersions[id] = version
        AtomicFile(File(directory, "$id.record")).delete()
        records.entries.removeAll { it.value.id == id }
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
        records.values.firstOrNull { it.id == recordId }?.let { record ->
            File(directory, "${UUID.fromString(record.input.dictionaryId)}.dictionary").takeIf { it.isFile }
        }
    }

    private fun publish() { _state.value = records.values.sortedByDescending { it.updatedAt } }

    private fun encode(record: SecurityAuditNetworkRecord) = JSONObject().apply {
        put("schema", 1)
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
            put("randomMac", record.config.randomMac)
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

    private fun decode(json: JSONObject): SecurityAuditNetworkRecord {
        require(json.getInt("schema") == 1) { "不支持的安全测试历史格式" }
        val config = json.getJSONObject("config")
        fun strings(name: String) = json.getJSONArray(name).let { array -> List(array.length()) { array.getString(it) } }
        return SecurityAuditNetworkRecord(
            id = json.getString("id"),
            input = SecurityAuditNetworkInput(json.getString("ssid"), json.getString("dictionaryId"),
                strings("dictionaryNames"), strings("resourceIds"), json.optInt("startIndex")),
            config = SecurityAuditConfig(SecurityAuditConnectionMethod.valueOf(config.getString("method")),
                ConnectWifiTaskConfig(config.getLong("timeout"), ConnectWifiFailureFlags(config.getBoolean("passwordError"),
                    if (config.isNull("handshakeAttempts")) null else HandshakeAttemptsExceededFlag(config.getInt("handshakeAttempts")),
                    if (config.isNull("handshakeTimeout")) null else HandshakeTimeoutFlag(config.getLong("handshakeTimeout")))),
                config.getInt("retryCount"), config.getLong("cooldownBase"),
                config.optLong("handshakeTimeoutValue", 1500), config.optInt("handshakeAttemptsValue", 1),
                config.optBoolean("randomMac", false)),
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
}
