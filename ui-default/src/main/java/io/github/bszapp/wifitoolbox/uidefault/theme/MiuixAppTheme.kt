package io.github.bszapp.wifitoolbox.uidefault.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.dynamiccolor.ColorSpec
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.settings.ISettingsManager
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

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
        MONET_LIGHT -> LIGHT
        MONET_DARK -> DARK
        else -> this
    }

    fun toMonetMode(): ColorMode = when (this) {
        SYSTEM -> MONET_SYSTEM
        LIGHT -> MONET_LIGHT
        DARK -> MONET_DARK
        else -> this
    }
}

val LocalSettingsManager = compositionLocalOf<ISettingsManager> {
    error("ISettingsManager not provided")
}
val LocalColorMode = staticCompositionLocalOf { ColorMode.SYSTEM }
val LocalEnableBlur = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }



val keyColorOptions = listOf(
    0xFFF44336.toInt(),
    0xFFE91E63.toInt(),
    0xFF9C27B0.toInt(),
    0xFF673AB7.toInt(),
    0xFF3F51B5.toInt(),
    0xFF2196F3.toInt(),
    0xFF00BCD4.toInt(),
    0xFF009688.toInt(),
    0xFF4CAF50.toInt(),
    0xFFFFEB3B.toInt(),
    0xFFFFC107.toInt(),
    0xFFFF9800.toInt(),
    0xFF795548.toInt(),
    0xFF607D8B.toInt(),
    0xFFFFB7C5.toInt(),
)

@Composable
fun rememberSettingsManager(): ISettingsManager = remember { AppControllerProvider.get().settings }

@Composable
fun WifiToolboxMiuixTheme(
    settingsManager: ISettingsManager = rememberSettingsManager(),
    content: @Composable () -> Unit,
) {
    val theme = settingsManager.settings.theme
    val colorModeName by theme.colorMode.value.collectAsState()
    val miuixMonet by theme.miuixMonet.value.collectAsState()
    val keyColorValue by theme.keyColor.value.collectAsState()
    val colorStyleName by theme.colorStyle.value.collectAsState()
    val colorSpecName by theme.colorSpec.value.collectAsState()
    val enableBlur by theme.enableBlur.value.collectAsState()
    val enableFloatingBottomBar by theme.enableFloatingBottomBar.value.collectAsState()
    val enableFloatingBottomBarBlur by theme.enableFloatingBottomBarBlur.value.collectAsState()
    val pageScale by theme.pageScale.value.collectAsState()

    val colorMode = ColorMode.fromName(colorModeName).let { mode ->
        when {
            miuixMonet && !mode.isMonet -> mode.toMonetMode()
            !miuixMonet && mode.isMonet -> mode.toNonMonetMode()
            else -> mode
        }
    }
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = colorMode.isDark || (colorMode.isSystem && systemDark)
    val paletteStyle = runCatching { ThemePaletteStyle.valueOf(colorStyleName) }.getOrDefault(ThemePaletteStyle.TonalSpot)
    val colorSpec = runCatching { ColorSpec.SpecVersion.valueOf(colorSpecName) }.getOrDefault(ColorSpec.SpecVersion.SPEC_2025)
    val themeColorSpec = if (colorSpec == ColorSpec.SpecVersion.SPEC_2025) ThemeColorSpec.Spec2025 else ThemeColorSpec.Spec2021
    val keyColor = keyColorValue.takeIf { it != 0 }?.let { Color(it) }
    val controller = ThemeController(
        colorSchemeMode = when (colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK -> ColorSchemeMode.MonetDark
        },
        keyColor = keyColor,
        paletteStyle = paletteStyle,
        colorSpec = themeColorSpec,
    )

    MiuixTheme(controller = controller) {
        LaunchedEffect(dark) {
            val window = (context as? Activity)?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        val density = LocalDensity.current
        val scaledDensity = remember(density, pageScale) {
            Density(
                density = density.density * pageScale.coerceIn(0.8f, 1.1f),
                fontScale = density.fontScale,
            )
        }
        val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        CompositionLocalProvider(
            LocalSettingsManager provides settingsManager,
            LocalColorMode provides colorMode,
            LocalEnableBlur provides (enableBlur && blurSupported),
            LocalEnableFloatingBottomBar provides enableFloatingBottomBar,
            LocalEnableFloatingBottomBarBlur provides (enableFloatingBottomBarBlur && blurSupported),
            LocalDensity provides scaledDensity,
        ) {
            content()
        }
    }
}

@Composable
fun isInDarkTheme(): Boolean {
    val mode = LocalColorMode.current
    return mode.isDark || (mode.isSystem && isSystemInDarkTheme())
}
