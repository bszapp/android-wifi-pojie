package com.wifi.toolbox.ui.theme

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicMaterialThemeState

val defaultColorSeed = Color(0xFF008B8A)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    dynamicColorSeed: Color = defaultColorSeed,
    content: @Composable () -> Unit
) {
    Log.d("AppTheme", "Load Theme")

    val useSystemColor = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false //注:去除导航栏白色半透明背景（找了半天才找到）
        }
    }

    val context = LocalContext.current

    val seedColor = remember(darkTheme, dynamicColor, dynamicColorSeed) {
        if (useSystemColor) {
            val scheme =
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            scheme.primary
        } else {
            dynamicColorSeed
        }
    }

    val dynamicThemeState = rememberDynamicMaterialThemeState(
        isDark = darkTheme,
        style = PaletteStyle.TonalSpot,
        seedColor = seedColor,
        modifyColorScheme = { generatedScheme ->
            if (dynamicColorSeed.alpha == 1f && !useSystemColor && !darkTheme)
                generatedScheme.copy(primary = dynamicColorSeed)
            else generatedScheme
        }
    )

    DynamicMaterialTheme(
        state = dynamicThemeState,
        animate = true,
        content = content,
    )
}