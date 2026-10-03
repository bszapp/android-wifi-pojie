package io.github.bszapp.wifitoolbox.hashcat

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import java.io.File

/** 同一个 libhashcat.so 同时提供 JNI 原生调用和独立执行入口，无容器依赖。 */
@Keep
object NativeHashcat {
    const val EVENT_LOG_ERROR = 0x80
    const val EVENT_LOG_INFO = 0x81
    const val EVENT_LOG_WARNING = 0x82
    const val EVENT_LOG_ADVICE = 0x83
    init {
        Log.i("HashcatNative", "System.loadLibrary(hashcat) 开始")
        try {
            System.loadLibrary("hashcat")
            Log.i("HashcatNative", "System.loadLibrary(hashcat) 成功")
        } catch (error: Throwable) {
            Log.e("HashcatNative", "System.loadLibrary(hashcat) 失败", error)
            throw error
        }
    }

    val version: String get() = nativeVersion()

    /**
     * 参数与 hashcat 命令行一致，不含 argv[0]。输入、字典和输出文件请使用绝对路径。
     * 同步执行，调用方负责放在工作线程。运行目录跟随传入目录的所有者权限。
     * 原生层串行执行调用，避免 upstream getopt 等进程级状态相互干扰。
     * GPU 使用设备实际提供的 OpenCL 驱动；没有可用计算后端时返回 hashcat 原始错误。
     */
    @WorkerThread
    fun run(
        arguments: List<String>,
        runtimeDirectory: File,
        openClLibrary: String? = null,
        listener: EventListener? = null,
    ): Int {
        require(arguments.none { '\u0000' in it }) { "hashcat 参数不能包含 NUL 字符" }
        require(openClLibrary?.contains('\u0000') != true) { "OpenCL 路径不能包含 NUL 字符" }
        return nativeRun(
            arguments.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            runtimeDirectory.canonicalPath.toByteArray(Charsets.UTF_8),
            openClLibrary?.toByteArray(Charsets.UTF_8),
            listener,
        )
    }

    @WorkerThread
    fun run(
        context: Context,
        arguments: List<String>,
        openClLibrary: String? = null,
        listener: EventListener? = null,
    ): Int = run(arguments, File(context.noBackupFilesDir, "hashcat"), openClLibrary, listener)

    /** 停止已经进入执行阶段的当前原生会话；没有执行中的会话时返回 false。 */
    fun quit(): Boolean = nativeQuit()

    @Keep
    fun interface EventListener {
        /**
         * eventId 为 upstream event_identifier_t；data 是事件原始字节。
         * EVENT_LOG_* 的 data 是 UTF-8 日志，保留原始换行（含 --status-json 输出）。
         * 回调在原生工作线程执行，请自行转发至 UI；请勿在回调中再次同步调用 run。
         */
        fun onEvent(eventId: Int, data: ByteArray)
    }

    private external fun nativeVersion(): String
    private external fun nativeRun(
        arguments: Array<ByteArray>,
        runtimeDirectory: ByteArray,
        openClLibrary: ByteArray?,
        listener: EventListener?,
    ): Int
    private external fun nativeQuit(): Boolean
}
