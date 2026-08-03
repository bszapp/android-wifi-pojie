package io.github.bszapp.wifitoolbox.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.graphics.toColorInt
import io.github.bszapp.wifitoolbox.BuildConfig


@Composable
fun DebugWatermark() {
    val isDebug = BuildConfig.DEBUG
    val isAlpha = BuildConfig.VERSION_NAME.contains("alpha", ignoreCase = true)

    if (!isDebug && !isAlpha) return

    val bgColor = if (isDebug) "#77FF0000".toColorInt() else "#774477FF".toColorInt()
    val label = if (isDebug) "DEBUG" else "ALPHA"
    val context = "${BuildConfig.VERSION_NAME}_${BuildConfig.GIT_ID}"

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.translate(drawContext.size.width, 0f)
            canvas.nativeCanvas.rotate(45f)
            val paint = android.graphics.Paint().apply { color = bgColor }
            canvas.nativeCanvas.drawRect(-200f, 90f, 200f, 170f, paint)
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 32f
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.nativeCanvas.drawText(label, 0f, 130f, textPaint)
            textPaint.textSize = 24f
            textPaint.isFakeBoldText = false
            canvas.nativeCanvas.drawText(
                context,
                0f,
                160f,
                textPaint
            )
            canvas.nativeCanvas.restore()
        }
    }
}