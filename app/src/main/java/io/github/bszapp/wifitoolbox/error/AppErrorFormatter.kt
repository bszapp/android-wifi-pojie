package io.github.bszapp.wifitoolbox.error

import android.os.Build
import android.os.Process
import io.github.bszapp.wifitoolbox.BuildConfig
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Snackbar copy action and the XML error page use this single report format. */
internal object AppErrorFormatter {
    fun format(
        source: String,
        operation: String,
        error: Throwable,
        remoteDetails: String? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        thread: Thread = Thread.currentThread(),
    ): ErrorReport {
        val details = buildString {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(Date(timestampMillis))
            appendLine("时间：$time")
            appendLine("来源：$source")
            appendLine("操作：$operation")
            appendLine("线程：${thread.name} (id=${thread.id})")
            appendLine("异常类型：${error.javaClass.name}")
            appendLine("异常消息：${error.message ?: "<无>"}")

            val androidApiRemoteStack = (error as? AndroidApiException)?.remoteStackTrace
            if (!androidApiRemoteStack.isNullOrBlank()) {
                appendLine()
                appendLine("Service AndroidApi 调用栈：")
                appendLine(androidApiRemoteStack)
            }
            if (!remoteDetails.isNullOrBlank()) {
                appendLine()
                appendLine("Service 远端调用栈：")
                appendLine(remoteDetails)
            }
            appendLine()
            appendLine("App 调用栈：")
            append(error.stackTraceToString())
        }
        val device = buildString {
            appendLine("制造商：${Build.MANUFACTURER}")
            appendLine("品牌：${Build.BRAND}")
            appendLine("型号：${Build.MODEL}")
            appendLine("设备：${Build.DEVICE}")
            appendLine("产品：${Build.PRODUCT}")
            appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("系统版本：${Build.DISPLAY}")
            appendLine("系统指纹：${Build.FINGERPRINT}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("构建日期：${BuildConfig.BUILD_DATE}")
            appendLine("构建提交：${BuildConfig.GIT_ID}")
            appendLine("进程：${Process.myPid()}")
            append("UID：${Process.myUid()}")
        }
        return ErrorReport(details, device)
    }
}

data class ErrorReport(val details: String, val deviceInformation: String) {
    val fullText: String get() = details + DEVICE_SEPARATOR + deviceInformation

    companion object {
        private const val DEVICE_SEPARATOR = "\n\n设备信息：\n"

        fun fromText(text: String): ErrorReport {
            val index = text.lastIndexOf(DEVICE_SEPARATOR)
            return if (index < 0) ErrorReport(text, "") else ErrorReport(
                details = text.substring(0, index),
                deviceInformation = text.substring(index + DEVICE_SEPARATOR.length),
            )
        }
    }
}
