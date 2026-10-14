package io.github.assistent.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** A small desk-lamp gag: light blooms from the shade instead of flashing the whole screen. */
@Composable
fun LightAssistOverlay(visible: Boolean) {
    val target = if (visible) 1f else 0f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(1_200, easing = FastOutSlowInEasing),
        label = "lamp-light",
    )
    if (progress <= 0.001f) return

    Canvas(Modifier.fillMaxSize()) {
        // Keep the lamp above the speech bubble area, on the robot's right.
        val lamp = Offset(size.width * 0.80f, size.height * 0.27f)
        val radius = min(size.width, size.height) * 1.35f * progress
        val glow = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 1.0f * progress),
                Color.White.copy(alpha = 0.96f * progress),
                Color.White.copy(alpha = 0.66f * progress),
            ),
            center = lamp,
            radius = radius.coerceAtLeast(1f),
        )
        drawRect(glow)

        // Desk-lamp silhouette at the source of the glow.
        val shadeTop = lamp + Offset(0f, -34.dp.toPx())
        val shadeBottom = lamp + Offset(0f, 8.dp.toPx())
        val shade = Path().apply {
            moveTo(shadeTop.x - 19.dp.toPx(), shadeTop.y)
            lineTo(shadeTop.x + 19.dp.toPx(), shadeTop.y)
            lineTo(shadeBottom.x + 31.dp.toPx(), shadeBottom.y)
            lineTo(shadeBottom.x - 31.dp.toPx(), shadeBottom.y)
            close()
        }
        drawPath(shade, Color.White.copy(alpha = 0.88f * progress))
        drawPath(shade, Color.Black.copy(alpha = 0.55f * progress), style = Stroke(2.dp.toPx()))
        drawLine(Color.Black.copy(alpha = 0.55f * progress), shadeBottom + Offset(0f, 8.dp.toPx()), lamp + Offset(0f, 48.dp.toPx()), 2.dp.toPx())
        drawLine(Color.Black.copy(alpha = 0.55f * progress), lamp + Offset(-22.dp.toPx(), 48.dp.toPx()), lamp + Offset(22.dp.toPx(), 48.dp.toPx()), 3.dp.toPx())
    }
}
