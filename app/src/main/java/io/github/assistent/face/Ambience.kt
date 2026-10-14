package io.github.assistent.face

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.assistent.core.Ambient
import kotlin.math.sin

const val HOT_CELSIUS = 30f
const val COLD_CELSIUS = 12f

val Ambient.isHot get() = (temperature ?: 20f) >= HOT_CELSIUS
val Ambient.isCold get() = (temperature ?: 20f) <= COLD_CELSIUS
val Ambient.isWet get() = condition in setOf("rain", "drizzle", "storm")

/** Stable pseudo-random 0..1 per particle index. */
private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777215f
}

/** Weather behind the face: rain streaks, snowflakes, fog bands, lightning. Dim so the face stays the star. */
internal fun DrawScope.drawWeatherBackground(amb: Ambient, t: Float, color: Color, shelter: androidx.compose.ui.geometry.Rect? = null) {
    val w = size.width
    val h = size.height
    val dim = color.copy(alpha = color.alpha * 0.34f)
    when (amb.condition) {
        "rain", "drizzle", "storm" -> {
            val count = when (amb.condition) { "drizzle" -> 35; "storm" -> 110; else -> 75 }
            val len = h * if (amb.condition == "drizzle") 0.025f else 0.045f
            for (i in 0 until count) {
                val speed = 0.55f + rnd(i, 1) * 0.5f
                val x0 = rnd(i, 2) * w * 1.1f
                val y = ((t * speed + rnd(i, 3)) % 1f) * (h + len) - len
                val x = x0 - y * 0.12f
                // Under the umbrella it stays dry.
                if (shelter != null && x in shelter.left..shelter.right && y + len > shelter.top) continue
                drawLine(dim, Offset(x, y), Offset(x - len * 0.12f, y + len), h * 0.003f, StrokeCap.Round)
            }
            if (amb.condition == "storm") {
                val cycle = t % 7.3f
                if (cycle < 0.22f) {
                    val k = 1f - cycle / 0.22f
                    drawRect(Color.White.copy(alpha = 0.18f * k))
                    val bolt = Path().apply {
                        var x = w * (0.15f + rnd((t / 7.3f).toInt(), 9) * 0.7f)
                        var y = 0f
                        moveTo(x, y)
                        for (s in 0 until 6) {
                            x += (rnd(s, (t / 7.3f).toInt()) - 0.5f) * w * 0.06f
                            y += h * 0.07f
                            lineTo(x, y)
                        }
                    }
                    drawPath(bolt, color.copy(alpha = 0.6f * k), style = Stroke(h * 0.004f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        "snow" -> for (i in 0 until 60) {
            val speed = 0.06f + rnd(i, 1) * 0.07f
            val y = ((t * speed + rnd(i, 3)) % 1f) * h
            val x = rnd(i, 2) * w + sin(t * (0.6f + rnd(i, 4)) + i) * w * 0.015f
            if (shelter != null && x in shelter.left..shelter.right && y > shelter.top) continue
            drawCircle(dim.copy(alpha = dim.alpha * 1.4f), h * (0.003f + rnd(i, 5) * 0.004f), Offset(x, y))
        }
        "fog" -> for (i in 0 until 4) {
            val y = h * (0.2f + i * 0.2f)
            val x = ((t * 0.02f * (i + 1) + rnd(i, 1)) % 1f) * w * 1.6f - w * 0.3f
            drawOval(dim.copy(alpha = 0.06f), topLeft = Offset(x - w * 0.4f, y - h * 0.05f), size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.1f))
        }
    }
}

/** Sweat drop sliding down the side of the head when it is hot. */
internal fun DrawScope.drawHeatSweat(g: FaceGeometry, t: Float, color: Color) {
    val ph = (t % 2.6f) / 2.6f
    if (ph > 0.85f) return
    val k = ph / 0.75f
    val p = Offset(g.cx + g.sep + g.ew * 0.8f, g.eyesY - g.eh * 0.55f + k * g.unit * 0.12f)
    val r = g.unit * 0.022f
    val drop = Path().apply {
        moveTo(p.x, p.y - r * 1.9f)
        quadraticTo(p.x + r * 1.1f, p.y, p.x, p.y + r)
        quadraticTo(p.x - r * 1.1f, p.y, p.x, p.y - r * 1.9f)
        close()
    }
    val a = color.alpha * (if (k < 0.15f) k / 0.15f else 1f - (k - 0.15f) / 0.85f * 0.6f)
    glow(drop, color, a * 0.6f)
    drawPath(drop, color.copy(alpha = a))
}

/** Little puffs of breath from the mouth when it is cold. */
internal fun DrawScope.drawColdBreath(mouth: Offset, unit: Float, t: Float, color: Color) {
    val ph = (t % 3f) / 3f
    if (ph > 0.6f) return
    val k = ph / 0.6f
    for (i in 0..2) {
        val r = unit * (0.022f + 0.04f * k) * (1f - i * 0.2f)
        val c = Offset(mouth.x + unit * (0.05f + k * 0.12f) + i * unit * 0.025f, mouth.y - k * unit * 0.05f - i * unit * 0.012f)
        drawCircle(color.copy(alpha = color.alpha * 0.45f * (1f - k)), r, c, style = Stroke(unit * 0.007f))
    }
}

