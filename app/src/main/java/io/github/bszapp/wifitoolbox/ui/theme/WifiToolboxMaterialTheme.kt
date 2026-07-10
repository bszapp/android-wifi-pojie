package io.github.bszapp.wifitoolbox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import io.github.bszapp.wifitoolbox.contract.settings.ISettingsManager
import io.github.bszapp.wifitoolbox.uidefault.theme.ColorMode

private val PaletteStyle.supportsSpec2025: Boolean
    get() = this == PaletteStyle.TonalSpot ||
        this == PaletteStyle.Neutral ||
        this == PaletteStyle.Vibrant ||
        this == PaletteStyle.Expressive

private fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && !style.supportsSpec2025) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

/**
 * Material theme used by the app module (startup/error UI).
 *
 * When Miuix Monet is enabled, the Material UI follows the selected seed color,
 * palette style, color-spec version and light/dark mode. A zero seed uses the
 * system dynamic primary color. When Monet is disabled, custom seed/style/spec
 * settings are intentionally ignored and the platform dynamic color scheme is
 * used instead, matching the theme-setting contract.
 */
@Composable
fun WifiToolboxMaterialTheme(
    settingsManager: ISettingsManager,
    content: @Composable () -> Unit,
) {
    val theme = settingsManager.settings.theme
    val colorModeName by theme.colorMode.value.collectAsState()
    val monetEnabled by theme.miuixMonet.value.collectAsState()
    val keyColorValue by theme.keyColor.value.collectAsState()
    val colorStyleName by theme.colorStyle.value.collectAsState()
    val colorSpecName by theme.colorSpec.value.collectAsState()

    val colorMode = ColorMode.fromName(colorModeName)
    val systemDark = isSystemInDarkTheme()
    val isDark = colorMode.isDark || (colorMode.isSystem && systemDark)
    val context = LocalContext.current

    val selectedStyle = runCatching { PaletteStyle.valueOf(colorStyleName) }
        .getOrDefault(PaletteStyle.TonalSpot)
    val selectedSpec = runCatching { ColorSpec.SpecVersion.valueOf(colorSpecName) }
        .getOrDefault(ColorSpec.SpecVersion.SPEC_2025)

    val systemDynamicScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }

    val systemSeed = systemDynamicScheme?.primary ?: Color(0xFF6750A4)
    val generatedScheme = rememberDynamicColorScheme(
        seedColor = if (monetEnabled && keyColorValue != 0) Color(keyColorValue) else systemSeed,
        isDark = isDark,
        style = if (monetEnabled) selectedStyle else PaletteStyle.TonalSpot,
        specVersion = if (monetEnabled) selectedSpec.effectiveFor(selectedStyle) else ColorSpec.SpecVersion.Default,
    )

    val colorScheme = if (!monetEnabled && systemDynamicScheme != null) {
        systemDynamicScheme
    } else {
        generatedScheme
    }

    LaunchedEffect(isDark) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
