package io.github.bszapp.wifitoolbox.contract.log

/** 由 Service 和 UI 共用的 threadtime 日志解析结果。 */
data class ParsedServiceLogLine(
    val timestamp: String,
    val pid: Long,
    val tid: Long,
    val priority: String,
    val tag: String,
    val message: String,
) {
    companion object {
        fun parse(line: String): ParsedServiceLogLine? {
            val lines = line.lineSequence().toList()
            val values = THREADTIME_PATTERN.matchEntire(lines.firstOrNull().orEmpty())
                ?.groupValues
                ?: return null
            val message = buildString {
                append(values[7])
                lines.drop(1).forEach { continuationLine ->
                    append('\n')
                    val continuationValues = THREADTIME_PATTERN
                        .matchEntire(continuationLine)
                        ?.groupValues
                    append(continuationValues?.get(7) ?: continuationLine)
                }
            }
            return ParsedServiceLogLine(
                timestamp = "${values[1]} ${values[2].substringBeforeLast('.')}",
                pid = values[3].toLongOrNull() ?: return null,
                tid = values[4].toLongOrNull() ?: return null,
                priority = values[5],
                tag = values[6].trim(),
                message = message,
            )
        }

        private val THREADTIME_PATTERN = Regex(
            """^\s*(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+(.+?)\s*:\s?(.*)$""",
        )
    }
}
