package io.github.bszapp.wifitoolbox.error

import android.util.Base64
import java.io.File

/** 服务 uncaught handler 写入并由 App 在 Binder 死亡后读取的报告。 */
data class ServiceCrashReport(
    val processId: String,
    val thread: String,
    val exceptionType: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val archiveFile: File,
) {
    companion object {
        fun read(basePath: File): ServiceCrashReport? {
            val textFile = File("${basePath.path}.txt")
            val archive = File("${basePath.path}.zip")
            if (!textFile.isFile || !archive.isFile) return null
            val fields = textFile.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            if (fields["exceptionType"].isNullOrBlank()) return null
            fun decode(name: String): String = fields[name]
                ?.let { runCatching { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() }
                .orEmpty()
            return ServiceCrashReport(
                processId = fields["pid"].orEmpty(),
                thread = fields["thread"].orEmpty(),
                exceptionType = fields.getValue("exceptionType"),
                exceptionMessage = decode("exceptionMessageBase64"),
                stackTrace = decode("stackTraceBase64"),
                archiveFile = archive,
            )
        }
    }
}

class ServiceCrashException(
    val report: ServiceCrashReport,
) : Exception(buildString {
    append("未捕获异常：")
    append(report.exceptionType)
    if (report.exceptionMessage.isNotBlank()) append("\n").append(report.exceptionMessage)
})
