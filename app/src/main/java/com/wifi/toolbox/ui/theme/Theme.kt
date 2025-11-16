package com.wifi.toolbox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val lightColorScheme = ColorScheme(
    background = Color(0xfffaf9f8),
    error = Color(0xffba1a1a),
    errorContainer = Color(0xffffdad6),
    inverseOnSurface = Color(0xfff2f0ef),
    inversePrimary = Color(0xffb8cac7),
    inverseSurface = Color(0xff2f3130),
    onBackground = Color(0xff1a1c1b),
    onError = Color(0xffffffff),
    onErrorContainer = Color(0xff93000a),
    onPrimary = Color(0xffffffff),
    onPrimaryContainer = Color(0xff394a48),
    onSecondary = Color(0xffffffff),
    onSecondaryContainer = Color(0xff3f4947),
    onSurface = Color(0xff1a1c1b),
    onSurfaceVariant = Color(0xff464746),
    onTertiary = Color(0xffffffff),
    onTertiaryContainer = Color(0xff324b49),
    outline = Color(0xff777776),
    outlineVariant = Color(0xffc6c9c8),
    primary = Color(0xff506260),
    primaryContainer = Color(0xffd3e6e3),
    scrim = Color(0xff000000),
    secondary = Color(0xff56605f),
    secondaryContainer = Color(0xffdae5e2),
    surface = Color(0xfffaf9f8),
    surfaceTint = Color(0xff506260),
    surfaceVariant = Color(0xffe3e2e1),
    tertiary = Color(0xff4a6360),
    tertiaryContainer = Color(0xffcce8e4),
    surfaceBright = Color(0xfffaf9f8),
    surfaceDim = Color(0xffdbdad9),
    surfaceContainer = Color(0xffefeeec),
    surfaceContainerHigh = Color(0xffe9e8e7),
    surfaceContainerHighest = Color(0xffe3e2e1),
    surfaceContainerLow = Color(0xfff4f3f2),
    surfaceContainerLowest = Color(0xffffffff),
    primaryFixed = Color(0xffd3e6e3),
    onPrimaryFixed = Color(0xff394a48),
    primaryFixedDim = Color(0xffb8cac7),
    onPrimaryFixedVariant = Color(0xff394a48),
    secondaryFixed = Color(0xffdae5e2),
    onSecondaryFixed = Color(0xff3f4947),
    secondaryFixedDim = Color(0xffbec9c7),
    onSecondaryFixedVariant = Color(0xff3f4947),
    tertiaryFixed = Color(0xffcce8e4),
    onTertiaryFixed = Color(0xff324b49),
    tertiaryFixedDim = Color(0xffb0ccc8),
    onTertiaryFixedVariant = Color(0xff324b49),
)

private val darkColorScheme = ColorScheme(
    background = Color(0xff121413),
    error = Color(0xffffb4ab),
    errorContainer = Color(0xff93000a),
    inverseOnSurface = Color(0xff2f3130),
    inversePrimary = Color(0xff506260),
    inverseSurface = Color(0xffe3e2e1),
    onBackground = Color(0xffe3e2e1),
    onError = Color(0xff690005),
    onErrorContainer = Color(0xffffdad6),
    onPrimary = Color(0xff233332),
    onPrimaryContainer = Color(0xffd3e6e3),
    onSecondary = Color(0xff293231),
    onSecondaryContainer = Color(0xffdae5e2),
    onSurface = Color(0xffe3e2e1),
    onSurfaceVariant = Color(0xffc7c6c5),
    onTertiary = Color(0xff1c3532),
    onTertiaryContainer = Color(0xffcce8e4),
    outline = Color(0xff919190),
    outlineVariant = Color(0xff484d4c),
    primary = Color(0xffb8cac7),
    primaryContainer = Color(0xff394a48),
    scrim = Color(0xff000000),
    secondary = Color(0xffbec9c7),
    secondaryContainer = Color(0xff3f4947),
    surface = Color(0xff121413),
    surfaceTint = Color(0xffb8cac7),
    surfaceVariant = Color(0xff464746),
    tertiary = Color(0xffb0ccc8),
    tertiaryContainer = Color(0xff324b49),
    surfaceBright = Color(0xff383939),
    surfaceDim = Color(0xff121413),
    surfaceContainer = Color(0xff1f201f),
    surfaceContainerHigh = Color(0xff292a2a),
    surfaceContainerHighest = Color(0xff343534),
    surfaceContainerLow = Color(0xff1a1c1b),
    surfaceContainerLowest = Color(0xff0d0e0e),
    primaryFixed = Color(0xffd3e6e3),
    onPrimaryFixed = Color(0xff394a48),
    primaryFixedDim = Color(0xffb8cac7),
    onPrimaryFixedVariant = Color(0xff394a48),
    secondaryFixed = Color(0xffdae5e2),
    onSecondaryFixed = Color(0xff3f4947),
    secondaryFixedDim = Color(0xffbec9c7),
    onSecondaryFixedVariant = Color(0xff3f4947),
    tertiaryFixed = Color(0xffcce8e4),
    onTertiaryFixed = Color(0xff324b49),
    tertiaryFixedDim = Color(0xffb0ccc8),
    onTertiaryFixedVariant = Color(0xff324b49),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkColorScheme
        else -> lightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }

    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
