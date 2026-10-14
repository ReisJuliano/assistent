package io.github.assistent.face

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.assistent.core.Activity
import io.github.assistent.core.Emotion
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Little rubber-hose arms with round hands. They appear only when there is something to hold or a gesture
 * to make (waving, hand on chin, shrug, cheering…) and spring smoothly between poses.
 * Hand positions are in face units relative to the face center (x right, y down); null = arm tucked away.
 */
data class ArmPose(val left: Offset? = null, val right: Offset? = null)

/** Shoulders sit below the face, as if there were a little body. */
internal val SHOULDER = Offset(0.34f, 0.5f)

object Arms {
    fun pose(scene: Scene?, t: Float, activity: Activity, emotion: Emotion, waving: Boolean, time: Float): ArmPose {
        val wiggle = sin(time * 9f) * 0.03f
        if (scene != null) when (scene) {
            Scene.DANCE -> {
                val b = Life.beat(t)
                val up = sin(b * PI.toFloat())
                val bar = ((t * Life.danceBpm / 60f).toInt() % 2)
                return if (bar == 0) ArmPose(Offset(-0.46f, -0.2f - up * 0.12f), Offset(0.44f, 0.2f + up * 0.04f))
                else ArmPose(Offset(-0.44f, 0.2f + up * 0.04f), Offset(0.46f, -0.2f - up * 0.12f))
            }
            Scene.HEARD -> return ArmPose(Offset(-0.28f, 0.02f), Offset(0.28f, 0.02f))
            Scene.LIGHT_ASSIST -> {
                val reach = ((t - 0.15f) / 1.35f).coerceIn(0f, 1f)
                val x = 0.16f + (0.64f - 0.16f) * reach
                val y = 0.34f + (-0.24f - 0.34f) * reach
                return ArmPose(null, Offset(x, y))
            }
            Scene.WAVE_BACK -> return ArmPose(null, Offset(0.44f + sin(time * 13f) * 0.06f, -0.16f + abs(sin(time * 13f)) * 0.02f))
            Scene.GIGGLE -> return ArmPose(Offset(-0.05f, 0.22f), Offset(0.05f, 0.22f))
            Scene.SHY -> return if (t < 1.6f) ArmPose(Offset(-0.19f, -0.06f), Offset(0.19f, -0.06f)) else ArmPose(Offset(-0.14f, 0.3f), Offset(0.14f, 0.3f))
            Scene.TOO_CLOSE -> return ArmPose(Offset(-0.2f, 0.12f), Offset(0.2f, 0.12f))
            Scene.FOUND_YOU -> return ArmPose(Offset(-0.44f, -0.22f + wiggle), Offset(0.44f, -0.22f - wiggle))
            Scene.CHARGE -> return ArmPose(Offset(-0.4f, -0.12f), Offset(0.4f, -0.12f))
            Scene.TIMER_DONE -> return ArmPose(null, Offset(0.5f, -0.25f + sin(time * 18f) * 0.025f))
            Scene.MESSAGE_DELIVERY -> return ArmPose(Offset(-0.15f, 0.25f), Offset(0.15f, 0.25f))
            Scene.INTERCOM -> return ArmPose(null, Offset(0.36f, 0.04f))
            Scene.DRINK_WATER -> return ArmPose(null, if (t in 1.2f..3.4f) Offset(0.11f, 0.25f) else Offset(0.11f, 0.32f))
            Scene.TAKE_PILL -> return if (t < 2.1f) ArmPose(Offset(-0.14f, 0.2f), Offset(0.12f, 0.3f)) else ArmPose(null, Offset(0.12f, 0.25f))
            Scene.EAT_FOOD -> return ArmPose(Offset(-0.1f, 0.28f), if (t in 1.8f..4.3f) Offset(0.04f, 0.2f) else Offset(0.1f, 0.28f))
            Scene.STRETCH_BREAK -> {
                val k = if (t < 4.5f) 1f else 0f
                return if (k > 0f) ArmPose(Offset(-0.56f, -0.38f + sin(t * 2f) * 0.03f), Offset(0.56f, -0.38f - sin(t * 2f) * 0.03f)) else ArmPose()
            }
            Scene.WHERE_ARE_YOU -> return ArmPose(Offset(-0.38f, 0.2f), Offset(0.38f, 0.2f))
            Scene.SNACK -> {
                val near = listOf(2f, 5f, 8f).any { t in it - 0.2f..it + 0.6f }
                if (t > 10.3f) return ArmPose()
                val y = if (near) 0.27f else 0.4f
                return ArmPose(Offset(-0.1f, y), Offset(0.1f, y))
            }
            Scene.WX_COLD -> {
                val sip = (t in 4f..5.6f) || (t in 10.5f..12.1f)
                val y = if (sip) 0.27f else 0.42f
                return ArmPose(Offset(-0.06f, y), Offset(0.06f, y))
            }
            Scene.WX_RAIN -> return ArmPose(null, Offset(UmbrellaGeo.SHAFT_X, UmbrellaGeo.HANDLE_Y - 0.03f))
            Scene.WX_HOT -> return if (t < 6f || t > 10f) ArmPose(null, Offset(0.42f + sin(time * 14f) * 0.03f, 0.08f)) else ArmPose()
            Scene.GAMING -> {
                val press = if ((time * 5f).toInt() % 3 == 0) 0.01f else 0f
                return ArmPose(Offset(-0.15f, 0.37f + press), Offset(0.15f, 0.37f - press))
            }
            Scene.READING -> return ArmPose(Offset(-0.18f, 0.37f), Offset(0.18f, 0.37f))
            Scene.STUDYING -> {
                if (t in 9.5f..13f) return ArmPose(Offset(-0.3f, -0.1f + wiggle), Offset(0.3f, -0.1f - wiggle))
                val px = -0.09f + ((t * 0.12f) % 1f) * 0.18f + sin(t * 25f) * 0.006f
                return ArmPose(Offset(-0.14f, 0.38f), Offset(px + 0.03f, 0.33f))
            }
            Scene.WALKING_DOG -> return ArmPose(Offset(-0.5f, 0.36f + sin(t * 11f) * 0.01f), null)
            Scene.MUSIC -> return ArmPose()
            Scene.COFFEE -> {
                val sip = (t in 2f..3.6f) || (t in 6.5f..8.1f)
                return ArmPose(null, if (sip) Offset(0.12f, 0.3f) else Offset(0.13f, 0.42f))
            }
            Scene.BUBBLEGUM -> if (t > 7.5f) return ArmPose(Offset(-0.3f, 0.05f + wiggle), Offset(0.3f, 0.05f - wiggle))
            Scene.FLY -> if (t in 8f..9f) return ArmPose(Offset(-0.03f, 0.05f), Offset(0.03f, 0.05f)) // clap!
            Scene.YAWN -> if (t in 1f..3.4f) return ArmPose(null, Offset(0.03f, 0.22f))
            Scene.SNEEZE -> if (t in 2f..2.8f) return ArmPose(null, Offset(0.02f, 0.21f))
            Scene.STRETCH -> {
                val k = if (t < 4.5f) 1f else 0f
                return if (k > 0f) ArmPose(Offset(-0.56f, -0.38f + sin(t * 2f) * 0.03f), Offset(0.56f, -0.38f - sin(t * 2f) * 0.03f)) else ArmPose()
            }
            Scene.WORKOUT -> {
                if (t > 9.5f) return ArmPose(Offset(-0.5f, -0.3f + wiggle), Offset(0.5f, -0.3f - wiggle))
                val y = -0.36f - sin(((t % 1.6f) / 1.6f) * PI.toFloat()) * 0.06f
                return ArmPose(Offset(-0.52f, y), Offset(0.52f, y))
            }
            Scene.SELFIE -> return ArmPose(null, Offset(0.42f, 0.13f))
            Scene.MOVIE -> {
                val eat = (time % 2.2f) < 0.6f
                return ArmPose(Offset(-0.07f, 0.42f), if (eat) Offset(0.02f, 0.22f) else Offset(0.04f, 0.33f))
            }
            Scene.MEDITATE -> return ArmPose(Offset(-0.3f, 0.3f), Offset(0.3f, 0.3f))
            Scene.WAKE_GROGGY -> if (t < 1.6f) return ArmPose(Offset(-0.19f + sin(time * 14f) * 0.012f, -0.04f), null) // rubbing an eye
            Scene.WAKE_STARTLED -> if (t in 0.14f..1.5f) return ArmPose(Offset(-0.42f, -0.12f), Offset(0.42f, -0.12f))
            Scene.PEEKABOO -> if (t < 1.2f || t > 4.6f) return ArmPose(Offset(-0.32f, -0.1f), Offset(0.32f, -0.1f))
            else -> Unit
        }
        if (waving) return ArmPose(null, Offset(0.38f + wiggle * 1.5f, -0.08f + abs(wiggle)))
        return when (activity) {
            Activity.THINKING -> ArmPose(null, Offset(0.06f, 0.29f)) // hand on the chin
            Activity.SEARCHING -> ArmPose(Offset(-0.12f, 0.36f + wiggle), Offset(0.12f, 0.36f - wiggle)) // typing
            else -> when (emotion) {
                Emotion.JOY -> ArmPose(Offset(-0.34f, -0.2f + wiggle), Offset(0.34f, -0.2f - wiggle))
                Emotion.CONFUSED -> ArmPose(Offset(-0.36f, 0.2f), Offset(0.36f, 0.2f)) // shrug
                Emotion.THINKING -> ArmPose(null, Offset(0.06f, 0.29f))
                Emotion.LOVE -> ArmPose(Offset(-0.04f, 0.3f), Offset(0.04f, 0.3f)) // hands together under the chin
                Emotion.SURPRISED -> ArmPose(Offset(-0.32f, 0f), Offset(0.32f, 0f))
                Emotion.ANGRY -> ArmPose(Offset(-0.36f, 0.4f + sin(time * 30f) * 0.004f), Offset(0.36f, 0.4f))
                else -> ArmPose()
            }
        }
    }
}

/**
 * Draws one soft, chubby arm from the shoulder to [hand] (absolute px): a curved tube that is plump near the
 * shoulder and tapers into a round droplet-shaped hand with a little thumb. Never straight — it always arcs
 * outward, like a cartoon noodle arm. Filled and lit like the eyes.
 */
internal fun DrawScope.drawArm(shoulder: Offset, hand: Offset, side: Int, unit: Float, color: Color, alpha: Float, withHand: Boolean = true) {
    if (alpha < 0.02f || !shoulder.isValid() || !hand.isValid()) return
    if (!shoulder.x.isFinite() || !shoulder.y.isFinite() || !hand.x.isFinite() || !hand.y.isFinite()) return
    val c = color.copy(alpha = color.alpha * alpha)
    val light = lerp(color, Color.White, 0.4f).copy(alpha = color.alpha * alpha)

    val dx = hand.x - shoulder.x
    val dy = hand.y - shoulder.y
    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    // Outward perpendicular: the arm bulges away from the face.
    var px = -dy / d
    var py = dx / d
    if (px * side < 0f) { px = -px; py = -py }
    val bend = unit * 0.1f + d * 0.12f
    val ctrl = Offset((shoulder.x + hand.x) / 2f + px * bend, (shoulder.y + hand.y) / 2f + py * bend + unit * 0.02f)

    fun pointAt(t: Float): Offset {
        val u = 1f - t
        return Offset(u * u * shoulder.x + 2 * u * t * ctrl.x + t * t * hand.x, u * u * shoulder.y + 2 * u * t * ctrl.y + t * t * hand.y)
    }
    fun tangentAt(t: Float): Offset {
        val tx = 2 * (1 - t) * (ctrl.x - shoulder.x) + 2 * t * (hand.x - ctrl.x)
        val ty = 2 * (1 - t) * (ctrl.y - shoulder.y) + 2 * t * (hand.y - ctrl.y)
        val l = sqrt(tx * tx + ty * ty).coerceAtLeast(1e-3f)
        return Offset(tx / l, ty / l)
    }

    // Tapered tube as one filled outline.
    val n = 28
    val w0 = unit * 0.085f
    val w1 = unit * 0.042f
    val leftPts = ArrayList<Offset>(n + 1)
    val rightPts = ArrayList<Offset>(n + 1)
    for (i in 0..n) {
        val t = i / n.toFloat() * (if (withHand) 0.92f else 1f)
        val p = pointAt(t)
        val tg = tangentAt(t)
        val w = (w0 + (w1 - w0) * t) / 2f * (1f + 0.12f * sin(t * PI.toFloat()))
        leftPts += Offset(p.x - tg.y * w, p.y + tg.x * w)
        rightPts += Offset(p.x + tg.y * w, p.y - tg.x * w)
    }
    val tube = Path().apply {
        moveTo(leftPts[0].x, leftPts[0].y)
        for (p in leftPts.drop(1)) lineTo(p.x, p.y)
        for (p in rightPts.asReversed()) lineTo(p.x, p.y)
        close()
    }
    // Droplet hand: a plump ball the tube melts into, plus a small thumb.
    val r = unit * 0.05f
    val tg = tangentAt(1f)
    val handC = Offset(hand.x + tg.x * r * 0.2f, hand.y + tg.y * r * 0.2f)
    val thumbC = Offset(handC.x - tg.y * r * 0.85f * -side - tg.x * r * 0.15f, handC.y + tg.x * r * 0.85f * -side - tg.y * r * 0.15f)
    var shape = if (withHand) {
        Path.combine(androidx.compose.ui.graphics.PathOperation.Union, tube, Path().apply { addOval(androidx.compose.ui.geometry.Rect(handC, r)) }).let {
            Path.combine(androidx.compose.ui.graphics.PathOperation.Union, it, Path().apply { addOval(androidx.compose.ui.geometry.Rect(thumbC, r * 0.42f)) })
        }
    } else {
        // No hand: the tube just ends in a soft rounded tip.
        Path.combine(androidx.compose.ui.graphics.PathOperation.Union, tube, Path().apply { addOval(androidx.compose.ui.geometry.Rect(hand, w1 / 2f)) })
    }

    glow(shape, c, 0.7f)
    drawPath(shape, androidx.compose.ui.graphics.Brush.radialGradient(listOf(light, c), center = ctrl, radius = d * 0.9f + unit * 0.1f))
    // Soft highlight along the arm and on the hand, for a rounded, squishy look.
    val hl = Path().apply {
        val a = pointAt(0.15f); val b = pointAt(0.55f); val m = pointAt(0.35f)
        val o = w0 * 0.18f
        moveTo(a.x - px * o, a.y - py * o)
        quadraticTo(m.x - px * o * 1.4f, m.y - py * o * 1.4f, b.x - px * o, b.y - py * o)
    }
    drawPath(hl, Color.White.copy(alpha = 0.35f * alpha), style = Stroke(unit * 0.012f, cap = StrokeCap.Round))
    if (withHand) drawCircle(Color.White.copy(alpha = 0.35f * alpha), r * 0.28f, Offset(handC.x - r * 0.3f, handC.y - r * 0.35f))
}
