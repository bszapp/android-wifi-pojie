package io.github.bszapp.wifitoolbox.ui.about

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.bszapp.wifitoolbox.ui.about.utils.shouldShowSplitPane
import io.github.bszapp.wifitoolbox.ui.theme.LocalColorMode

// 示例关于页依赖的宿主选项，保留示例默认值。
internal data class AboutAppState(
    val enableBlur: Boolean = true,
    val enableScrollEndHaptic: Boolean = true,
    val showTopAppBar: Boolean = true,
)

internal val LocalAppState = staticCompositionLocalOf { AboutAppState() }
internal val LocalIsWideScreen = staticCompositionLocalOf { false }

@Composable
internal fun isInDarkTheme(): Boolean {
    val mode = LocalColorMode.current
    return if (mode.isSystem) isSystemInDarkTheme() else mode.isDark
}

@Composable
fun MiuixAboutScreen() {
    CompositionLocalProvider(LocalIsWideScreen provides shouldShowSplitPane()) {
        AboutPage(padding = PaddingValues())
    }
}
