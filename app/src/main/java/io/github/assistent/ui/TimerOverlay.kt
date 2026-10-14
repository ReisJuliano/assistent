package io.github.assistent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.assistent.data.TimerEntity
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TimerOverlay(timers: List<TimerEntity>, color: Color, modifier: Modifier = Modifier) {
    val timer = timers.filter { it.state == "active" }.minByOrNull { it.endsAt } ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val left = (timer.endsAt - now).coerceAtLeast(0)
    val progress = 1f - (left / timer.durationMs.toFloat()).coerceIn(0f, 1f)
    val seconds = left / 1000
    val text = "%d:%02d".format(seconds / 60, seconds % 60)
    val anxious = left <= 60_000

    Column(modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(118.dp)) {
                val stroke = size.minDimension * 0.07f
                drawCircle(color.copy(alpha = 0.18f), radius = size.minDimension * 0.42f, style = Stroke(stroke))
                drawArc(
                    color = if (anxious) Color.White else color,
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (anxious) {
                    val p = (now / 130L % 10) / 10f
                    val a = p * 2f * PI.toFloat()
                    val c = center
                    val r = size.minDimension * 0.32f
                    drawCircle(color, size.minDimension * 0.035f, Offset(c.x + cos(a) * r, c.y + sin(a) * r))
                }
            }
            Text(text, color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Text(
            timer.label.take(18),
            color = color.copy(alpha = 0.9f),
            fontSize = 18.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
        )
    }
}
