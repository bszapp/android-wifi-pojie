package io.github.bszapp.wifitoolbox.ui.about.misc

import androidx.compose.runtime.staticCompositionLocalOf

data class VersionInfo(
    val versionName: String,
    val versionCode: Int,
)

val LocalVersionInfo = staticCompositionLocalOf<VersionInfo> {
    error("未提供应用版本信息")
}
