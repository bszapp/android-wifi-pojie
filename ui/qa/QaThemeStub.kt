// QA 无头渲染专用主题桩：MiuixAppTheme（Android 专属：LocalContext/edgeToEdge）整编编译外，
// 但被整编源引用的主题局部与判定以该桩提供，语义与生产文件保持一致。

package io.github.bszapp.wifitoolbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK,
    MONET_SYSTEM,
    MONET_LIGHT,
    MONET_DARK;

    companion object {
        fun fromName(value: String) = entries.find { it.name == value } ?: SYSTEM
    }

    val baseIndex: Int get() = when (this) {
        SYSTEM, MONET_SYSTEM -> 0
        LIGHT, MONET_LIGHT -> 1
        DARK, MONET_DARK -> 2
    }
    val isSystem: Boolean get() = this == SYSTEM || this == MONET_SYSTEM
    val isDark: Boolean get() = this == DARK || this == MONET_DARK
    val isMonet: Boolean get() = this == MONET_SYSTEM || this == MONET_LIGHT || this == MONET_DARK

    fun toNonMonetMode(): ColorMode = when (this) {
        MONET_SYSTEM -> SYSTEM
        else -> this
    }
}

val LocalColorMode = staticCompositionLocalOf { ColorMode.SYSTEM }
val LocalEnableBlur = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

@Composable
fun isInDarkTheme(): Boolean {
    val mode = LocalColorMode.current
    return mode.isDark || (mode.isSystem && isSystemInDarkTheme())
}
