package com.wifi.toolbox.ui.items

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Composable
fun WifiIcon(
    modifier: Modifier = Modifier,
    level: Int = 0,
) {
    val baseIconColor = MaterialTheme.colorScheme.onSurface

    val activeAlpha = 1.0f
    val inactiveAlpha = 0.24f

    val wifiImageVector = remember(baseIconColor, level) {
        ImageVector.Builder(
            name = "ThemedWifiIcon",
            defaultWidth = 18.dp,
            defaultHeight = 13.dp,
            viewportWidth = 18.0f,
            viewportHeight = 13.0f
        ).apply {
            path(fill = SolidColor(baseIconColor.copy(alpha = if (level >= 4) activeAlpha else inactiveAlpha))) {
                moveTo(0.523f, 3.314f)
                curveTo(0.32f, 3.502f, 0.32f, 3.819f, 0.516f, 4.015f)
                lineTo(1.223f, 4.722f)
                curveTo(1.418f, 4.917f, 1.734f, 4.916f, 1.938f, 4.73f)
                curveTo(5.936f, 1.09f, 12.066f, 1.09f, 16.064f, 4.73f)
                curveTo(16.268f, 4.916f, 16.584f, 4.917f, 16.779f, 4.722f)
                lineTo(17.486f, 4.015f)
                curveTo(17.682f, 3.819f, 17.682f, 3.502f, 17.479f, 3.314f)
                curveTo(12.698f, -1.105f, 5.304f, -1.105f, 0.523f, 3.314f)
                close()
            }
            path(fill = SolidColor(baseIconColor.copy(alpha = if (level >= 3) activeAlpha else inactiveAlpha))) {
                moveTo(15.011f, 6.49f)
                curveTo(15.207f, 6.294f, 15.207f, 5.976f, 15.002f, 5.792f)
                curveTo(11.592f, 2.736f, 6.411f, 2.736f, 3f, 5.792f)
                curveTo(2.795f, 5.976f, 2.795f, 6.294f, 2.991f, 6.49f)
                lineTo(3.698f, 7.197f)
                curveTo(3.893f, 7.392f, 4.209f, 7.39f, 4.417f, 7.209f)
                curveTo(7.042f, 4.93f, 10.96f, 4.93f, 13.585f, 7.209f)
                curveTo(13.793f, 7.39f, 14.109f, 7.392f, 14.304f, 7.197f)
                lineTo(15.011f, 6.49f)
                close()
            }
            path(fill = SolidColor(baseIconColor.copy(alpha = if (level >= 2) activeAlpha else inactiveAlpha))) {
                moveTo(5.465f, 8.964f)
                curveTo(5.27f, 8.769f, 5.269f, 8.45f, 5.481f, 8.273f)
                curveTo(7.515f, 6.576f, 10.487f, 6.576f, 12.521f, 8.273f)
                curveTo(12.733f, 8.45f, 12.732f, 8.769f, 12.537f, 8.964f)
                lineTo(11.83f, 9.672f)
                curveTo(11.634f, 9.867f, 11.319f, 9.863f, 11.099f, 9.698f)
                curveTo(9.859f, 8.767f, 8.143f, 8.767f, 6.904f, 9.698f)
                curveTo(6.683f, 9.863f, 6.368f, 9.867f, 6.173f, 9.672f)
                lineTo(5.465f, 8.964f)
                close()
            }
            path(fill = SolidColor(baseIconColor.copy(alpha = if (level >= 1) activeAlpha else inactiveAlpha))) {
                moveTo(10.062f, 11.439f)
                curveTo(10.257f, 11.244f, 10.259f, 10.92f, 10.022f, 10.779f)
                curveTo(9.395f, 10.407f, 8.608f, 10.407f, 7.98f, 10.779f)
                curveTo(7.743f, 10.92f, 7.745f, 11.244f, 7.94f, 11.439f)
                lineTo(8.647f, 12.146f)
                curveTo(8.843f, 12.342f, 9.159f, 12.342f, 9.355f, 12.146f)
                lineTo(10.062f, 11.439f)
                close()
            }
        }.build()
    }

    Icon(
        imageVector = wifiImageVector,
        contentDescription = null,
        modifier = modifier,
        tint = Color.Unspecified
    )
}