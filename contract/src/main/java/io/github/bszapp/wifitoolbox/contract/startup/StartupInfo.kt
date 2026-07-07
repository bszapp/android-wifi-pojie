package io.github.bszapp.wifitoolbox.contract.startup

import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import kotlinx.parcelize.Parcelize

/**
 * App 与特权 service 之间共享的启动信息。
 *
 * App 创建启动信息时填写 trustedUid / startupMode / version；service 初始化时补齐
 * serviceUid / servicePid / serviceUidText 后返回给 App 查询状态。
 */
@Parcelize
data class StartupInfo(
    val trustedUid: Int,
    val trustedUidText: String,
    val startupMode: String,
    val versionName: String,
    val versionCode: Long,
    val serviceUid: Int = -1,
    val servicePid: Int = -1,
    val serviceUidText: String = "",
) : Parcelable {
    fun requireLaunchInfo(): StartupInfo {
        require(trustedUid > 0) { "缺少可信 App UID 启动参数" }
        require(startupMode == StartupMode.ROOT.name ||
                startupMode == StartupMode.SHIZUKU.name ||
                startupMode == StartupMode.SHIZUKU_TERMINAL.name
        ) { "未知启动模式：$startupMode" }
        require(versionName.isNotBlank()) { "缺少版本名称启动参数" }
        require(versionCode >= 0) { "缺少版本编号启动参数" }
        return this
    }

    fun isTrustedForAppUid(appUid: Int): Boolean = trustedUid == appUid && trustedUid > 0

    companion object {
        fun forAppLaunch(mode: StartupMode, uid: Int, versionName: String, versionCode: Long): StartupInfo =
            StartupInfo(
                trustedUid = uid,
                trustedUidText = "uid=$uid",
                startupMode = mode.name,
                versionName = versionName,
                versionCode = versionCode,
            ).requireLaunchInfo()
    }
}

object StartupInfoParcelCodec {
    fun encode(info: StartupInfo): String {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(info, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    fun decode(encoded: String): StartupInfo {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readParcelable<StartupInfo>(StartupInfo::class.java.classLoader)
                ?: throw IllegalArgumentException("启动信息参数无法解析")
        } finally {
            parcel.recycle()
        }
    }
}
