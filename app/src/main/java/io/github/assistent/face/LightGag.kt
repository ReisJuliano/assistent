package io.github.assistent.face

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Turning on the light": the screen becomes a white panel that lights the room / the face in front of the camera,
 * introduced by a little gag so it reads as the robot's own idea — pulling the chain of a desk lamp, fumbling with
 * a phone flashlight, or a bright-idea bulb that is *too* bright.
 *
 * Everything is a function of the scene time `t` (like [Life.frame]) plus [offT], the scene time at which the
 * runtime asked to switch off (set every frame by `RobotFace` from `AssistantState.lightOffAt`).
 * While lit, `RobotFace` paints the background white and the face in dark ink of the user's color.
 */
object LightGag {
    val SCENES = listOf(Scene.LIGHT_LAMP, Scene.LIGHT_PHONE, Scene.LIGHT_BULB)

    /** Scene time of the switch-off request; [Float.MAX_VALUE] while staying on. */
    @Volatile var offT = Float.MAX_VALUE

    fun isLight(scene: Scene?) = scene in SCENES

    fun sceneFor(style: String): Scene = when (style.lowercase()) {
        "lamp", "abajur" -> Scene.LIGHT_LAMP
        "phone", "flashlight", "lanterna" -> Scene.LIGHT_PHONE
        "bulb", "ideia", "lampada" -> Scene.LIGHT_BULB
        else -> SCENES.random()
    }

    /** When the light comes on in each gag (after the joke has landed). */
    fun onAt(scene: Scene) = when (scene) {
        Scene.LIGHT_LAMP -> 1.5f
        Scene.LIGHT_PHONE -> 2.5f
        else -> 1.9f
    }

    /** From the switch-off request to darkness (reaching for the chain, tapping the phone...). */
    fun offDelay(scene: Scene) = when (scene) {
        Scene.LIGHT_LAMP -> 0.8f
        Scene.LIGHT_PHONE -> 0.55f
        else -> 0.45f
    }

    /** From the switch-off request until the scene can be cleared (props faded out). */
    fun endAfterOff(scene: Scene) = offDelay(scene) + 1.3f

    /** 0 = normal black screen, 1 = full white light. */
    fun level(scene: Scene, t: Float, offT: Float = this.offT): Float {
        if (!isLight(scene)) return 0f
        val on = onAt(scene)
        // The phone flashlight fails once before it works; the idea bulb stutters.
        val stutter = when (scene) {
            Scene.LIGHT_PHONE -> if (t in 1.45f..1.62f) 0.7f else 0f
            Scene.LIGHT_BULB -> if (t in 1.25f..1.33f || t in 1.52f..1.58f) 0.45f else 0f
            else -> 0f
        }
        val off = offT + offDelay(scene)
        val up = ease(prog(t, on, on + 0.3f))
        val down = 1f - ease(prog(t, off, off + 0.4f))
        return (maxOf(up, stutter) * down).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------------------------------ face

    fun frame(scene: Scene, t: Float, base: FaceParams, neutral: FaceParams, happy: FaceParams, joy: FaceParams, surprised: FaceParams): SceneFrame {
        val closedHappy = EyeShape(height = 0.45f, radiusTop = 1f, radiusBottom = 0.1f, crescent = 0.55f, offsetY = -0.04f)
        val squint = EyeShape(height = 0.32f, slopeTop = 0.12f, radiusTop = 0.3f, radiusBottom = 0.6f)
        val focus = neutral.eyes(EyeShape(height = 0.8f, slopeTop = 0.06f, radiusTop = 0.45f, radiusBottom = 0.6f))
        val on = onAt(scene)
        val dt = t - offT
        if (dt >= 0f) {
            // Switching off: look at the switch, then a contented little smile in the dark.
            return when (scene) {
                Scene.LIGHT_LAMP -> if (dt < 0.9f) SceneFrame(focus.copy(mouthCurve = 0.1f, mouthWidth = 0.4f), gaze = Offset(0.75f, -0.35f)) else SceneFrame(happy.copy(mouthCurve = 0.35f))
                Scene.LIGHT_PHONE -> if (dt < 0.7f) SceneFrame(focus.copy(tongue = 0.6f, mouthWidth = 0.4f), gaze = Offset(0.75f, 0f)) else SceneFrame(happy.copy(mouthCurve = 0.35f))
                else -> if (dt < 0.6f) SceneFrame(surprised.copy(mouthOpen = 0.15f), gaze = Offset(-0.75f, -0.7f)) else SceneFrame(happy.copy(mouthCurve = 0.3f))
            }
        }
        return when (scene) {
            Scene.LIGHT_LAMP -> when {
                t < on -> SceneFrame(focus.copy(tongue = 0.5f, mouthWidth = 0.4f, mouthCurve = 0.05f, browRaise = 0.15f), gaze = Offset(0.75f, -0.35f))
                t < on + 0.7f -> SceneFrame(neutral.eyes(squint).copy(browRaise = -0.25f, mouthCurve = -0.1f, mouthWidth = 0.4f), dy = -hopIn(t, on, 0.3f) * 0.03f, stiffness = 480f)
                t < on + 2.2f -> SceneFrame(joy.eyes(closedHappy).copy(mouthCurve = 0.8f, mouthWidth = 0.75f, tilt = 6f, bounce = 0f), gaze = Offset(0f, 0f))
                else -> SceneFrame(base)
            }
            Scene.LIGHT_PHONE -> when {
                t < 1f -> SceneFrame(focus.copy(mouthCurve = 0.2f), gaze = Offset(0.6f, 0.35f))
                t < 1.45f -> SceneFrame(focus.copy(tongue = 0.8f, mouthWidth = 0.35f, mouthOffsetX = 0.3f), gaze = Offset(0.7f, 0f))
                t < 1.75f -> SceneFrame(surprised.copy(mouthOpen = 0.2f), gaze = Offset(0.7f, 0f), stiffness = 520f)
                // It failed: an annoyed shake of the phone, then a decisive tap.
                t < on -> SceneFrame(
                    neutral.eyes(EyeShape(height = 0.7f, slopeTop = 0.18f)).copy(browAngle = 10f, browRaise = -0.2f, mouthCurve = -0.2f, mouthWidth = 0.4f),
                    gaze = Offset(0.7f, 0.05f), dx = sin(t * 40f) * 0.006f,
                )
                t < on + 0.6f -> SceneFrame(neutral.eyes(squint).copy(browRaise = -0.25f, mouthWidth = 0.4f), stiffness = 480f)
                t < on + 2.2f -> SceneFrame(happy.copy(mouthCurve = 0.75f, mouthWidth = 0.7f, browRaise = 0.3f, tilt = -5f), gaze = Offset(0f, 0f))
                else -> SceneFrame(base)
            }
            else -> when {
                // Thinking... then the idea lights up above the head, far too bright: sunglasses on.
                t < 0.7f -> SceneFrame(neutral.copy(dots = 1f, mouthCurve = 0f, mouthWidth = 0.4f, mouthOffsetX = -0.3f, browAngle = 6f), gaze = Offset(-0.3f, -0.7f))
                t < on -> SceneFrame(surprised.copy(mouthOpen = 0.25f, browRaise = 0.6f), gaze = Offset(-0.75f, -0.75f), dy = -hopIn(t, 0.7f, 0.35f) * 0.03f)
                t < on + 0.7f -> SceneFrame(neutral.eyes(squint).copy(browRaise = -0.3f, mouthCurve = -0.15f), stiffness = 480f)
                t < on + 3.4f -> SceneFrame(joy.copy(sunglasses = 1f, mouthCurve = 0.7f, mouthWidth = 0.7f, tilt = 7f, bounce = 0f), gaze = Offset(0f, 0f))
                else -> SceneFrame(base)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ arms

    /** Hand targets for the gag; null = normal arm behaviour (e.g. while just staying lit). */
    fun arms(scene: Scene, t: Float, time: Float): ArmPose? {
        val dt = t - offT
        val on = onAt(scene)
        return when (scene) {
            Scene.LIGHT_LAMP -> {
                val bead = Offset(LAMP_CHAIN_X, LAMP_BEAD_Y)
                when {
                    dt >= 0f -> when {
                        dt < 0.5f -> ArmPose(null, bead)
                        dt < 1.0f -> ArmPose(null, bead + Offset(0f, LAMP_PULL))
                        else -> ArmPose()
                    }
                    t < 1.15f -> ArmPose(null, bead)
                    t < on + 0.25f -> ArmPose(null, bead + Offset(0f, LAMP_PULL))
                    else -> null
                }
            }
            Scene.LIGHT_PHONE -> {
                // Thumb taps on the screen = little jolts of the holding hand (the other arm would cross the face).
                val tapping = (t in 1.05f..1.4f) || (t in 2.05f..on) || (dt in 0.1f..0.5f)
                val jolt = if (tapping && (time * 8f).toInt() % 2 == 0) 0.014f else 0f
                ArmPose(null, phoneHand(t, dt)?.let { it + Offset(0f, jolt) })
            }
            else -> null
        }
    }

    private fun phoneHand(t: Float, dt: Float): Offset? {
        val wobble = sin(t * 2.1f) * 0.006f
        return when {
            dt > 0.9f -> null
            t < 0.25f -> Offset(PHONE_X, 0.55f)
            else -> Offset(PHONE_X + wobble, PHONE_Y + 0.08f)
        }
    }

    // ------------------------------------------------------------------------------------------ props

    /** Draws the gag's prop in the face color [c] (already faded by the scene). */
    fun DrawScope.drawLightProp(scene: Scene, t: Float, g: FaceGeometry, c: Color, hands: Offset?) {
        val u = g.unit
        val dt = t - offT
        val off = offT + offDelay(scene)
        val fade = 1f - prog(t, off + 0.5f, off + 1.1f)
        if (fade <= 0.01f) return
        val col = c.copy(alpha = c.alpha * fade)
        val lit = level(scene, t)
        fun stroke(p: Path, w: Float = u * 0.011f, a: Float = 1f) {
            glow(p, col, a * 0.8f, w)
            drawPath(p, col.copy(alpha = col.alpha * a), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun fill(p: Path, a: Float) = drawPath(p, col.copy(alpha = col.alpha * a))
        fun at(x: Float, y: Float) = Offset(g.cx + x * u, g.cy + y * u)
        fun rays(c0: Offset, r0: Float, r1: Float, from: Float, to: Float, n: Int, a: Float) {
            if (a <= 0.01f) return
            for (i in 0 until n) {
                val ang = from + (to - from) * i / (n - 1).coerceAtLeast(1)
                stroke(Path().apply {
                    moveTo(c0.x + cos(ang) * r0, c0.y + sin(ang) * r0); lineTo(c0.x + cos(ang) * r1, c0.y + sin(ang) * r1)
                }, u * 0.008f, a)
            }
        }

        when (scene) {
            Scene.LIGHT_LAMP -> {
                // Classic desk lamp on the robot's right: round base, two-segment arm, bell shade, pull chain.
                val base = at(0.7f, 0.44f)
                stroke(Path().apply { addOval(Rect(Offset(base.x - u * 0.09f, base.y - u * 0.022f), Size(u * 0.18f, u * 0.044f))) })
                val elbow = at(0.76f, 0.05f)
                val joint = at(0.65f, -0.33f)
                stroke(Path().apply { moveTo(base.x, base.y - u * 0.02f); lineTo(elbow.x, elbow.y); lineTo(joint.x, joint.y) }, u * 0.012f)
                stroke(Path().apply { addOval(Rect(elbow, u * 0.014f)) }, u * 0.008f)
                val top = at(LAMP_SHADE_X, -0.36f)
                val shade = Path().apply {
                    moveTo(top.x - u * 0.035f, top.y)
                    quadraticTo(top.x, top.y - u * 0.03f, top.x + u * 0.035f, top.y)
                    lineTo(top.x + u * 0.1f, top.y + u * 0.13f)
                    quadraticTo(top.x, top.y + u * 0.15f, top.x - u * 0.1f, top.y + u * 0.13f)
                    close()
                }
                fill(shade, 0.18f + 0.5f * lit)
                stroke(shade)
                val bulb = Offset(top.x, top.y + u * 0.15f)
                val bulbPath = Path().apply { addOval(Rect(bulb, u * 0.028f)) }
                fill(bulbPath, 0.25f * (1f - lit))
                stroke(bulbPath, u * 0.008f)
                rays(bulb, u * 0.05f, u * 0.09f, (PI * 0.2).toFloat(), (PI * 0.8).toFloat(), 5, lit)
                // Chain: stretched while pulled, swinging after release.
                val pulled = (t in 1.15f..onAt(scene) + 0.25f) || (dt in 0.5f..1.0f)
                val swing = if (pulled) 0f else sin(t * 7f) * 0.012f * kotlin.math.exp(-((t - onAt(scene)).coerceAtLeast(0f)) * 1.2f)
                val beadY = LAMP_BEAD_Y + if (pulled) LAMP_PULL else 0f
                val anchor = at(LAMP_CHAIN_X, -0.24f)
                val bead = at(LAMP_CHAIN_X + swing, beadY)
                stroke(Path().apply { moveTo(anchor.x, anchor.y); lineTo(bead.x, bead.y) }, u * 0.004f, 0.8f)
                val beadPath = Path().apply { addOval(Rect(bead, u * 0.013f)) }
                fill(beadPath, 1f)
            }
            Scene.LIGHT_PHONE -> {
                // Phone held up with its back to the viewer: camera bump + flash LED that becomes a beam.
                val hand = hands ?: return
                val cx = g.cx + hand.x * u
                val cy = g.cy + hand.y * u - u * 0.1f
                val w = u * 0.11f
                val h = u * 0.2f
                val body = Path().apply { addRoundRect(RoundRect(Rect(Offset(cx - w / 2, cy - h / 2), Size(w, h)), androidx.compose.ui.geometry.CornerRadius(u * 0.018f))) }
                fill(body, 0.12f)
                stroke(body, u * 0.01f)
                val bump = Path().apply { addRoundRect(RoundRect(Rect(Offset(cx - w * 0.38f, cy - h * 0.42f), Size(w * 0.42f, w * 0.42f)), androidx.compose.ui.geometry.CornerRadius(u * 0.01f))) }
                stroke(bump, u * 0.006f)
                stroke(Path().apply { addOval(Rect(Offset(cx - w * 0.26f, cy - h * 0.36f), u * 0.009f)) }, u * 0.005f)
                stroke(Path().apply { addOval(Rect(Offset(cx - w * 0.26f, cy - h * 0.26f), u * 0.009f)) }, u * 0.005f)
                val flash = Offset(cx - w * 0.07f, cy - h * 0.31f)
                val led = Path().apply { addOval(Rect(flash, u * 0.006f + u * 0.008f * lit)) }
                fill(led, 0.6f * (1f - lit))
                rays(flash, u * 0.025f, u * 0.06f, 0f, (2 * PI).toFloat() * 7f / 8f, 8, lit)
            }
            else -> {
                // The bright idea: pops in above the head, stutters, then shines (and bobs while it stays on).
                val pop = prog(t, 0.7f, 0.95f)
                if (pop <= 0f) return
                val k = if (pop < 1f) ease(pop) * 1.15f else 1f + 0.04f * sin(t * 3f)
                val c0 = Offset(g.cx - g.sep * 2.2f, g.eyesY - g.eh * 1.35f + sin(t * 1.7f) * u * 0.006f)
                val r = u * 0.045f * k
                val glass = Path().apply { addOval(Rect(c0, r)) }
                fill(glass, 0.2f * (1f - lit))
                stroke(glass, u * 0.009f)
                stroke(Path().apply {
                    moveTo(c0.x - r * 0.45f, c0.y + r * 1.15f); lineTo(c0.x + r * 0.45f, c0.y + r * 1.15f)
                    moveTo(c0.x - r * 0.38f, c0.y + r * 1.38f); lineTo(c0.x + r * 0.38f, c0.y + r * 1.38f)
                    moveTo(c0.x - r * 0.2f, c0.y + r * 1.6f); lineTo(c0.x + r * 0.2f, c0.y + r * 1.6f)
                }, u * 0.008f)
                // Filament zig-zag.
                stroke(Path().apply {
                    moveTo(c0.x - r * 0.35f, c0.y + r * 0.2f); lineTo(c0.x - r * 0.15f, c0.y - r * 0.15f)
                    lineTo(c0.x, c0.y + r * 0.2f); lineTo(c0.x + r * 0.15f, c0.y - r * 0.15f); lineTo(c0.x + r * 0.35f, c0.y + r * 0.2f)
                }, u * 0.006f, 0.8f)
                rays(c0, r * 1.45f, r * 2.1f, (-PI * 0.95).toFloat(), (-PI * 0.05).toFloat(), 5, maxOf(lit, if (t < onAt(scene)) 0.5f * abs(sin(t * 12f)) else 0f))
            }
        }
    }

    private const val LAMP_SHADE_X = 0.6f
    private const val LAMP_CHAIN_X = 0.53f
    private const val LAMP_BEAD_Y = -0.1f
    private const val LAMP_PULL = 0.06f
    private const val PHONE_X = 0.57f
    private const val PHONE_Y = 0.02f

    private fun prog(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun ease(k: Float) = k * k * (3f - 2f * k)
    private fun hopIn(t: Float, start: Float, dur: Float) = if (t in start..start + dur) sin((t - start) / dur * PI.toFloat()) else 0f
}
