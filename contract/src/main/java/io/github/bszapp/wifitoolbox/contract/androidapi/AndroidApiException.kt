package io.github.bszapp.wifitoolbox.contract.androidapi

/** App 侧收到 AndroidApiResponse 失败结果后抛出的异常，保留 Service 端完整堆栈。 */
class AndroidApiException(
    val remoteErrorClass: String?,
    val remoteErrorMessage: String?,
    val remoteStackTrace: String?,
) : IllegalStateException(
    remoteErrorMessage ?: remoteErrorClass ?: "AndroidApi 调用失败",
)
