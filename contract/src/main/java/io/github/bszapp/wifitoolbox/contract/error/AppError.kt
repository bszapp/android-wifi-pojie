package io.github.bszapp.wifitoolbox.contract.error

/** App 进程统一发布给 UI 的错误信息。 */
data class AppError(
    val source: String,
    val operation: String,
    val message: String,
    val details: String,
    val timestampMillis: Long,
)
