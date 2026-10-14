package io.github.assistent.face

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.github.assistent.config.FaceConfig
import io.github.assistent.core.Activity
import io.github.assistent.core.Ambient
import io.github.assistent.core.Emotion
import io.github.assistent.core.Gaze
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import androidx.compose.ui.graphics.lerp as lerpColor

/**
 * The whole screen: a black canvas with the robot face.
 * Works in any orientation; the face is sized from the smaller screen dimension.
 *
 * @param audioLevel 0..1 loudness of the current speech output (lip sync) or mic input (listening pulse).
 */
@Composable
fun RobotFace(
    activity: Activity,
    emotion: Emotion,
    gaze: Gaze,
    face: FaceConfig,
    audioLevel: () -> Float,
    modifier: Modifier = Modifier,
    scene: String? = null,
    sceneStartedAt: Long = 0L,
    ambient: Ambient = Ambient(),
    mimic: Boolean = false,
    /** True while a speech bubble is up: waves hello. */
    waving: Boolean = false,
) {
    val animator = remember { FaceAnimator() }
    val baseColor = remember(face.color) { parseHexColor(face.color) }

    val colorState = rememberUpdatedState(baseColor)
    val ambientState = rememberUpdatedState(ambient)
    val mimicState = rememberUpdatedState(mimic)
    val wavingState = rememberUpdatedState(waving)
    val sceneState = rememberUpdatedState(Scene.of(scene) to sceneStartedAt)
    val keyState = rememberUpdatedState(activity to emotion)
    val gazeState = rememberUpdatedState(gaze)
    val faceState = rememberUpdatedState(face)
    val levelState = rememberUpdatedState(audioLevel)
    var tick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(animator) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // Power save: skip frames (30 fps awake, 12 fps asleep). Physics uses real dt, so motion stays the same.
                val minGap = if (keyState.value.first == Activity.SLEEPING) 83_000_000L else 33_000_000L
                if (last != 0L && now - last < minGap) return@withFrameNanos
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val (act, emo) = keyState.value
                val cfg = faceState.value
                val color = colorState.value
                val amb = ambientState.value
                animator.ambient = amb
                // Feels the weather (shivers when cold) and the time of day (heavy-lidded at night and early morning).
                val base = FacePresets.resolve(act, emo, color).let {
                    if (amb.isCold && act != Activity.SLEEPING) it.copy(tremble = max(it.tremble, 0.35f)) else it
                }.let { daytimeMood(it, act, emo) }.let {
                    if (act == Activity.LISTENING) {
                        // A small, slow brow asymmetry makes listening feel curious rather than frozen.
                        it.copy(browAsym = sin(animator.time * 1.35f) * 0.16f, browAngle = -3f)
                    } else it
                }
                // Life scenes play on top of the current state: hobbies only while idle, dreams only while
                // sleeping, wake-up reactions while awake (they may overlap the start of listening).
                val (sc, startedAt) = sceneState.value
                val applies = when (sc?.kind) {
                    Scene.Kind.AWAKE -> act == Activity.IDLE
                    Scene.Kind.DREAM -> act == Activity.SLEEPING
                    Scene.Kind.WAKE -> act != Activity.SLEEPING
                    Scene.Kind.REACT -> act != Activity.SLEEPING && (sc != Scene.WAVE_BACK || act == Activity.IDLE)
                    null -> false
                }
                val sceneT = (System.currentTimeMillis() - startedAt) / 1000f
                val frame = if (applies && sc != null && sceneT in 0f..sc.seconds) Life.frame(sc, sceneT, startedAt, color, base) else null
                // Mirror the user: copy head tilt and winks while idle.
                val user = gazeState.value
                // Camera tracking follows the eyes continuously, but head tilt is an occasional mimic beat.
                // Copying roll every frame makes a steady face look permanently crooked and can keep the
                // expression in a constant transition.
                val mimicTilt = sin(animator.time * 0.16f) > 0.88f
                val mirrored = if (frame == null && mimicState.value && user.present && act == Activity.IDLE) {
                    val closedEye = EyeShape(height = 0.08f, radiusTop = 1f, radiusBottom = 1f)
                    val lWink = user.leftEyeOpen < 0.25f && user.rightEyeOpen > 0.6f
                    val rWink = user.rightEyeOpen < 0.25f && user.leftEyeOpen > 0.6f
                    base.copy(
                        tilt = base.tilt + if (mimicTilt && abs(user.tilt) > 7f) user.tilt * 0.55f else 0f,
                        left = if (lWink) closedEye else base.left,
                        right = if (rWink) closedEye else base.right,
                    )
                } else base
                animator.scene = if (frame != null) sc else null
                animator.arms = if (cfg.showArms) Arms.pose(if (frame != null) sc else null, sceneT, act, emo, wavingState.value, animator.time) else ArmPose()
                animator.sceneT = sceneT
                animator.sceneSeed = startedAt
                animator.step(
                    dt, (frame?.params ?: mirrored).withRoundness(cfg.eyeRoundness / 0.45f), act, emo,
                    gazeState.value, levelState.value(), cfg, frame?.gaze, frame?.dx ?: 0f, frame?.dy ?: 0f, frame?.stiffness,
                )
                tick = now
            }
        }
    }

    Canvas(modifier.fillMaxSize().background(Color.Black)) {
        @Suppress("UNUSED_EXPRESSION") tick // redraw every frame without recomposing
        drawFace(animator, faceState.value, activity)
    }
}

fun parseHexColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex.trim()))
}.getOrDefault(Color(0xFF3DE8FF))

/** Heavier eyelids late at night and early in the morning, a post-lunch slump; only on the neutral idle face. */
private fun daytimeMood(p: FaceParams, act: Activity, emo: Emotion): FaceParams {
    if (act != Activity.IDLE || emo != Emotion.NEUTRAL) return p
    val h = java.time.LocalTime.now().hour
    val drowsy = when (h) {
        in 23..23, in 0..5 -> 0.4f
        22, 6, 7 -> 0.28f
        8, 14 -> 0.12f
        else -> 0f
    }
    if (drowsy <= 0f) return p
    fun e(s: EyeShape) = s.copy(height = s.height * (1f - drowsy), slopeTop = s.slopeTop - drowsy * 0.15f, offsetY = s.offsetY + drowsy * 0.1f)
    return p.copy(left = e(p.left), right = e(p.right), browRaise = p.browRaise - drowsy * 0.4f, mouthCurve = p.mouthCurve * (1f - drowsy))
}

private fun EyeShape.round(k: Float) = copy(
    radiusTop = (radiusTop * k).coerceIn(0f, 1f),
    radiusBottom = (radiusBottom * k).coerceIn(0f, 1f),
)

private fun FaceParams.withRoundness(k: Float) = copy(left = left.round(k), right = right.round(k))

// ---------------------------------------------------------------------------------------------
// Physics: every parameter is an independent damped spring. Retargeting mid-motion keeps the
// current velocity, so expressions blend organically instead of restarting a tween.
// ---------------------------------------------------------------------------------------------

private const val EYE_FIELDS = 9
private const val FIELDS = 45

private fun EyeShape.writeTo(a: FloatArray, o: Int) {
    a[o] = height; a[o + 1] = width; a[o + 2] = slopeTop; a[o + 3] = slopeBottom; a[o + 4] = radiusTop
    a[o + 5] = radiusBottom; a[o + 6] = crescent; a[o + 7] = offsetX; a[o + 8] = offsetY
}

private fun eyeOf(a: FloatArray, o: Int) = EyeShape(
    height = a[o], width = a[o + 1], slopeTop = a[o + 2], slopeBottom = a[o + 3], radiusTop = a[o + 4],
    radiusBottom = a[o + 5], crescent = a[o + 6], offsetX = a[o + 7], offsetY = a[o + 8],
)

private fun FaceParams.writeTo(a: FloatArray) {
    left.writeTo(a, 0); right.writeTo(a, EYE_FIELDS)
    a[18] = heart; a[19] = pupil; a[20] = browRaise; a[21] = browAngle; a[22] = browCurve; a[23] = browAsym
    a[24] = mouthCurve; a[25] = mouthOpen; a[26] = mouthWidth; a[27] = mouthWave; a[28] = mouthOffsetX
    a[29] = tilt; a[30] = bounce; a[31] = tremble; a[32] = pulse; a[33] = dots; a[34] = zzz; a[35] = brightness
    a[36] = color.red; a[37] = color.green; a[38] = color.blue; a[39] = color.alpha
    a[40] = tongue; a[41] = blush; a[42] = dizzy; a[43] = glasses; a[44] = sunglasses
}

private fun paramsOf(a: FloatArray) = FaceParams(
    left = eyeOf(a, 0), right = eyeOf(a, EYE_FIELDS),
    heart = a[18], pupil = a[19], browRaise = a[20], browAngle = a[21], browCurve = a[22], browAsym = a[23],
    mouthCurve = a[24], mouthOpen = a[25], mouthWidth = a[26], mouthWave = a[27], mouthOffsetX = a[28],
    tilt = a[29], bounce = a[30], tremble = a[31], pulse = a[32], dots = a[33], zzz = a[34], brightness = a[35],
    tongue = a[40], blush = a[41], dizzy = a[42], glasses = a[43], sunglasses = a[44],
    color = Color(a[36].coerceIn(0f, 1f), a[37].coerceIn(0f, 1f), a[38].coerceIn(0f, 1f), a[39].coerceIn(0f, 1f)),
)

internal class Spring(var x: Float = 0f, var v: Float = 0f) {
    /** Sub-stepped (≤ 1/120 s) so stiff springs stay stable at low frame rates (power save draws at 12–30 fps). */
    fun step(target: Float, dt: Float, k: Float, zeta: Float) {
        val c = 2f * zeta * sqrt(k)
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 1f / 120f)
            v += (k * (target - x) - c * v) * h
            x += v * h
            remaining -= h
        }
        if (!x.isFinite() || !v.isFinite()) { x = target; v = 0f } // never let a glitch blow up the face
    }
}

internal class FaceAnimator {
    private val cur = FloatArray(FIELDS)
    private val vel = FloatArray(FIELDS)
    private val tgt = FloatArray(FIELDS)
    private var initialized = false
    private var lastKey: Any? = null

    var params = FaceParams(); private set
    var time = 0f; private set
    /** Eye openness multiplier from blinking, 0..1. */
    var blink = 1f; private set
    val gazeX = Spring(); val gazeY = Spring()
    /** Smoothed speech loudness → mouth. */
    val speech = Spring()
    /** Smoothed mic loudness → listening pulse. */
    val listen = Spring()
    /** Slow idle head sway. */
    val sway = Spring()
    /** Underdamped "pop" when an expression enters. */
    val pop = Spring()
    /** Current life scene (null = none) and its local time, for drawing props. */
    var scene: Scene? = null
    var sceneT = 0f
    var sceneSeed = 0L
    var ambient = Ambient()
    /** Target arm pose and smoothed hand positions / visibility. */
    var arms = ArmPose()
    val lhx = Spring(-SHOULDER.x); val lhy = Spring(SHOULDER.y); val lha = Spring()
    val rhx = Spring(SHOULDER.x); val rhy = Spring(SHOULDER.y); val rha = Spring()

    /** Whole-face offset requested by the scene, smoothed (face units). */
    val sceneDx = Spring()
    val sceneDy = Spring()

    private var shakeStart = -10f
    private var shakeAmp = 0f
    private var nextBlinkAt = 1.5f
    private var blinkStart = -1f
    private var pendingDouble = false
    private var nextSaccadeAt = 1f
    private var idleGx = 0f
    private var idleGy = 0f
    private var nextSwayAt = 3f
    private var swayTarget = 0f

    /** Decaying side-to-side head shake ("hm?"), in units of face size. */
    val shake: Float
        get() {
            val t = time - shakeStart
            return if (t > 1.2f) 0f else sin(t * 2f * PI.toFloat() * 5.5f) * exp(-t * 4f) * shakeAmp
        }

    fun step(
        dt: Float, target: FaceParams, activity: Activity, emotion: Emotion, userGaze: Gaze, audio: Float, cfg: FaceConfig,
        gazeOverride: Offset? = null, dx: Float = 0f, dy: Float = 0f, stiffness: Float? = null,
    ) {
        time += dt
        target.writeTo(tgt)
        val key = Triple(activity, emotion, scene)
        if (!initialized) {
            tgt.copyInto(cur); initialized = true; lastKey = key
        }
        if (key != lastKey) {
            onExpressionChange(target, activity, lastKey)
            lastKey = key
        }
        // Integrate in small sub-steps so stiff springs stay stable at low frame rates.
        val k = (stiffness ?: cfg.animationStiffness).coerceIn(20f, 1000f)
        val z = if (stiffness != null) 0.75f else cfg.animationDamping.coerceIn(0.3f, 1.2f)
        val c = 2f * z * sqrt(k)
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 1f / 120f)
            for (i in 0 until FIELDS) {
                vel[i] += (k * (tgt[i] - cur[i]) - c * vel[i]) * h
                cur[i] += vel[i] * h
            }
            remaining -= h
        }
        if (cur.any { !it.isFinite() } || vel.any { !it.isFinite() }) { tgt.copyInto(cur); vel.fill(0f) }
        params = paramsOf(cur)

        stepBlink(activity)
        stepGaze(dt, activity, userGaze, cfg, gazeOverride)
        // Hands spring to their targets; a tucked arm retracts to the shoulder and fades out.
        arms.left.let { h -> lhx.step(h?.x ?: -SHOULDER.x, dt, 220f, 0.72f); lhy.step(h?.y ?: SHOULDER.y, dt, 220f, 0.72f); lha.step(if (h != null) 1f else 0f, dt, 120f, 0.9f) }
        arms.right.let { h -> rhx.step(h?.x ?: SHOULDER.x, dt, 220f, 0.72f); rhy.step(h?.y ?: SHOULDER.y, dt, 220f, 0.72f); rha.step(if (h != null) 1f else 0f, dt, 120f, 0.9f) }
        sceneDx.step(dx, dt, 400f, 0.9f)
        sceneDy.step(dy, dt, 400f, 0.9f)

        speech.step(if (activity == Activity.SPEAKING) audio.coerceIn(0f, 1f) else 0f, dt, 900f, 0.85f)
        listen.step(if (activity == Activity.LISTENING) audio.coerceIn(0f, 1f) else 0f, dt, 300f, 0.9f)
        pop.step(0f, dt, 260f, 0.32f)

        if (cfg.idleMotion && time > nextSwayAt) {
            // Mostly upright; only now and then a subtle head tilt.
            swayTarget = if (activity == Activity.IDLE && Random.nextFloat() < 0.35f) Random.nextFloat() * 2.4f - 1.2f else 0f
            nextSwayAt = time + 4f + Random.nextFloat() * 5f
        }
        sway.step(if (cfg.idleMotion) swayTarget else 0f, dt, 8f, 0.9f)
    }

    /** Acting beats: a blink to "cut" between expressions (like Cozmo), plus pops and shakes. */
    private fun onExpressionChange(target: FaceParams, activity: Activity, previous: Any?) {
        val wasSleeping = (previous as? Triple<*, *, *>)?.first == Activity.SLEEPING
        if (activity != Activity.SLEEPING && !wasSleeping && blinkStart < 0f) blinkStart = time
        if (target.enterPop > 0f) pop.v += 2.2f * target.enterPop
        if (target.enterShake > 0f) {
            shakeStart = time; shakeAmp = target.enterShake
        }
    }

    private fun stepBlink(activity: Activity) {
        if (activity == Activity.SLEEPING) {
            blink = 1f; blinkStart = -1f; return
        }
        if (blinkStart < 0f && time >= nextBlinkAt) blinkStart = time
        if (blinkStart >= 0f) {
            val d = 0.17f
            val p = (time - blinkStart) / d
            if (p >= 1f) {
                blink = 1f
                blinkStart = -1f
                if (pendingDouble) {
                    pendingDouble = false
                    nextBlinkAt = time + 0.12f
                } else {
                    pendingDouble = Random.nextFloat() < 0.18f
                    nextBlinkAt = time + 2f + Random.nextFloat() * 4.5f
                }
            } else {
                // fast close, slower open
                blink = if (p < 0.4f) 1f - p / 0.4f else (p - 0.4f) / 0.6f
                blink = blink * blink * (3 - 2 * blink)
            }
        }
    }

    private fun stepGaze(dt: Float, activity: Activity, user: Gaze, cfg: FaceConfig, override: Offset?) {
        val (tx, ty) = when {
            override != null -> override.x to override.y
            activity == Activity.SLEEPING -> 0f to 0.2f
            activity == Activity.THINKING || params.dots > 0.5f && activity != Activity.SEARCHING -> 0.6f to -0.7f
            activity == Activity.SEARCHING -> sin(time * 2.2f) * 0.75f to -0.1f
            activity == Activity.LISTENING && user.present && cfg.cameraTracking ->
                (user.x * 0.84f + sin(time * 1.35f) * 0.10f) to (user.y * 0.84f + kotlin.math.cos(time * 1.1f) * 0.05f)
            user.present && cfg.cameraTracking -> user.x to user.y
            else -> {
                if (cfg.idleMotion && time > nextSaccadeAt) {
                    val center = Random.nextFloat() < 0.4f
                    // More readable idle glances: the eyes should visibly scan the room, but still return to center often.
                    idleGx = if (center) 0f else Random.nextFloat() * 1.8f - 0.9f
                    idleGy = if (center) 0f else Random.nextFloat() * 1.1f - 0.55f
                    nextSaccadeAt = time + 0.9f + Random.nextFloat() * 2.2f
                }
                if (cfg.idleMotion) idleGx to idleGy else 0f to 0f
            }
        }
        // Saccades are quick and snappy, unlike expressions.
        gazeX.step(tx.coerceIn(-1f, 1f), dt, 260f, 0.78f)
        gazeY.step(ty.coerceIn(-1f, 1f), dt, 260f, 0.78f)
    }
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private var glowRadius = -1f

private fun DrawScope.drawFace(a: FaceAnimator, cfg: FaceConfig, activity: Activity) {
    val p = a.params
    val w = size.width
    val h = size.height
    val unit = min(w * 0.75f, h * 1.05f) * cfg.scale.coerceIn(0.4f, 1.8f)
    val color = p.color.copy(alpha = (p.color.alpha * p.brightness).coerceIn(0f, 1f))
    val t = a.time

    val breathPeriod = if (p.zzz > 0.5f) 5.5f else 4f
    val breath = sin(t * 2f * PI.toFloat() / breathPeriod)
    // Laugh: quick little hops. Anger: tiny jitter. Confusion: decaying head shake.
    val bounceY = -abs(sin(t * 2f * PI.toFloat() * 1.6f)) * unit * 0.03f * p.bounce
    val trembleX = sin(t * 41f) * sin(t * 23f) * unit * 0.004f * p.tremble
    val shakeX = a.shake * unit * 0.03f
    val cx = w / 2f + trembleX + shakeX + a.sceneDx.x * unit
    val cy = h / 2f + cfg.offsetY.coerceIn(-0.3f, 0.3f) * h + breath * unit * 0.005f + bounceY + a.sceneDy.x * unit

    val ew = unit * 0.16f * cfg.eyeSize.coerceIn(0.4f, 2f)
    val eh = ew * cfg.eyeAspect.coerceIn(0.4f, 2.5f)
    val sep = max(unit * 0.19f * cfg.eyeSpacing.coerceIn(0.4f, 2f), ew * 0.62f)
    val eyesY = cy - (if (cfg.showMouth) unit * 0.06f else 0f)
    val style = cfg.eyeStyle
    // With irises the eyes move less; the iris does most of the looking.
    // Idle glances should be unmistakable at phone distance; other activities stay more focused.
    val eyeTravel = when (activity) {
        Activity.IDLE -> if (style == "solid") 0.82f else 0.56f
        Activity.LISTENING -> if (style == "solid") 0.62f else 0.38f
        else -> if (style == "solid") 0.56f else 0.30f
    }
    val gx = a.gazeX.x * ew * eyeTravel
    val gy = a.gazeY.x * eh * eyeTravel * 0.66f
    val iris = IrisStyle(style, cfg.irisSize.coerceIn(0.3f, 0.9f), cfg.pupilSize.coerceIn(0.2f, 0.8f), a.gazeX.x, a.gazeY.x)
    val scale = (1f + a.listen.x * 0.07f) * (1f + a.pop.x * 0.09f)
    val heartbeat = 1f + 0.1f * p.pulse * max(0f, sin(t * 2f * PI.toFloat() * 1.1f)).pow(6)
    val closed = p.zzz.coerceIn(0f, 1f)
    val withGlow = cfg.glow > 0f

    val radius = unit * 0.045f * cfg.glow.coerceIn(0f, 1.5f)
    if (radius != glowRadius) {
        glowRadius = radius
        glowPaint.maskFilter = if (radius > 0.5f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
    }

    val shelter = if (a.scene == Scene.WX_RAIN) {
        val sx = cx + UmbrellaGeo.SHAFT_X * unit
        androidx.compose.ui.geometry.Rect(sx - UmbrellaGeo.RADIUS * unit, cy + UmbrellaGeo.TOP_Y * unit + UmbrellaGeo.RADIUS * unit * 0.25f, sx + UmbrellaGeo.RADIUS * unit, h)
    } else null
    drawWeatherBackground(a.ambient, t, color, shelter)
    if (cfg.showClock) {
        if (cfg.clockStyle == "analog") drawClock(cfg.clockCorner, p.color, unit) else drawDigitalClock(cfg.clockCorner, p.color, unit)
    }
    if (activity == Activity.LISTENING) {
        // Draw after the weather layer so the listening state is never hidden by the background.
        val pulse = (0.55f + 0.22f * sin(t * 4.2f) + a.listen.x * 0.45f).coerceIn(0.28f, 1f)
        val thickness = max(3f, min(w, h) * 0.008f) * (0.9f + a.listen.x * 0.35f)
        val inset = thickness * 1.8f
        val edge = color.copy(alpha = color.alpha * pulse)
        drawRoundRect(
            color = edge,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(thickness * 2.5f),
            style = Stroke(width = thickness),
        )
        if (cfg.glow > 0f) {
            drawIntoCanvas { canvas ->
                glowPaint.color = edge.toArgb()
                glowPaint.style = android.graphics.Paint.Style.STROKE
                glowPaint.strokeWidth = thickness * 2.2f
                canvas.nativeCanvas.drawRoundRect(
                    inset, inset, w - inset, h - inset, thickness * 2.5f, thickness * 2.5f, glowPaint,
                )
                glowPaint.style = android.graphics.Paint.Style.FILL
            }
        }
    }

    rotate(p.tilt + a.sway.x, Offset(cx, cy)) {
        for (side in intArrayOf(-1, 1)) {
            val shape = if (side < 0) p.left else p.right
            // Curiosity: the eye on the side being looked at grows a little, the other shrinks (perspective).
            val curiosity = 1f + 0.14f * a.gazeX.x * side
            // Squash & stretch: the eye widens slightly while it closes.
            val squash = 1f + 0.08f * (1f - a.blink)
            val eyeW = ew * shape.width * scale * squash
            val eyeH = max(eh * shape.height * a.blink * scale * curiosity, ew * 0.06f)
            val center = Offset(
                cx + side * (sep + shape.offsetX * ew * 0.5f) + gx,
                eyesY + shape.offsetY * eh + gy,
            )
            val dizzy = p.dizzy.coerceIn(0f, 1f)
            // One-eye peek while sleeping: the left eye opens even though the face is "asleep".
            val peeking = side < 0 && a.scene == Scene.SLEEP_PEEK
            val eyeClosed = if (peeking) 0f else closed
            if (eyeClosed < 0.98f && dizzy < 0.98f) {
                drawEye(center, eyeW, eyeH, side, shape, p, color.copy(alpha = color.alpha * (1f - eyeClosed) * (1f - dizzy)), withGlow, iris, heartbeat)
            }
            if (dizzy > 0.02f) drawSpiral(center, ew * scale * 0.55f, t * 7f * side, color.copy(alpha = color.alpha * dizzy))
            if (p.sunglasses > 0.02f) drawSunglassLens(center, ew * scale, eh * scale, side, color, p.sunglasses, sep)
            if (p.glasses > 0.02f) drawGlassFrame(center, ew * scale, eh * scale, side, color.copy(alpha = color.alpha * p.glasses), sep)
            if (p.blush > 0.02f) drawBlush(Offset(center.x + side * ew * 0.25f, center.y + eh * scale * 0.5f + ew * 0.28f), ew, color.copy(alpha = color.alpha * p.blush))
            if (eyeClosed > 0.02f) drawClosedEye(center, ew * scale, breath, color.copy(alpha = color.alpha * closed), withGlow)
            if (cfg.showBrows) {
                // Anchored to the resting eye top, so blinking does not move the brows.
                val raise = p.browRaise + if (side < 0) p.browAsym else -p.browAsym
                val restingTop = eyesY + shape.offsetY * eh * 0.5f + gy * 0.6f - eh * scale / 2f
                val browCenter = Offset(cx + side * sep + gx * 0.8f, restingTop - ew * 0.3f - raise * ew * 0.24f)
                drawBrow(browCenter, ew * scale, side, p, color.copy(alpha = color.alpha * (1f - closed * 0.4f)), cfg.browThickness, withGlow)
            }
        }
        val mouthY = cy + unit * 0.2f
        if (cfg.showMouth) {
            val talk = a.speech.x
            // Louder syllables open the mouth more and make it a bit rounder (narrower).
            val mw = unit * 0.24f * p.mouthWidth * cfg.mouthSize.coerceIn(0.3f, 2f) * (1f - 0.18f * talk)
            val mouthC = Offset(cx + p.mouthOffsetX * unit * 0.12f + gx * 0.3f, mouthY + gy * 0.3f)
            drawMouth(mouthC, mw, p.mouthOpen + talk * 0.95f, p, t, color, unit * 0.022f * cfg.mouthThickness.coerceIn(0.5f, 3f), withGlow)
            if (p.tongue > 0.02f) drawTongue(mouthC, mw, p, t, color)
            if (a.ambient.isCold && closed < 0.5f) drawColdBreath(mouthC, unit, t, color)
        }
        if (a.ambient.isHot && closed < 0.5f) {
            drawHeatSweat(FaceGeometry(cx, cy, unit, eyesY, mouthY, sep, ew, eh), t, color)
        }
        a.scene?.let { sc ->
            val geo = FaceGeometry(cx, cy, unit, eyesY, mouthY, sep, ew, eh)
            drawSceneProps(sc, a.sceneT, a.sceneSeed, geo, color, sc.seconds, a.ambient)
        }
        // Arms on top of props so hands hold them.
        if (cfg.showArms) {
            val sh = SHOULDER
            drawArm(Offset(cx - sh.x * unit, cy + sh.y * unit), Offset(cx + a.lhx.x * unit, cy + a.lhy.x * unit), -1, unit, color, a.lha.x.coerceIn(0f, 1f), cfg.armHands)
            drawArm(Offset(cx + sh.x * unit, cy + sh.y * unit), Offset(cx + a.rhx.x * unit, cy + a.rhy.x * unit), 1, unit, color, a.rha.x.coerceIn(0f, 1f), cfg.armHands)
        }
        if (p.dots > 0.02f) drawThoughtBubbles(cx + sep + ew * 0.55f, eyesY - eh * 0.6f, unit, t, p.dots, color)
        if (p.zzz > 0.02f) drawZzz(cx + sep + ew * 0.3f, eyesY - eh * 0.2f, unit, t, p.zzz, color)
    }
}

internal fun DrawScope.glow(path: Path, color: Color, alpha: Float, stroke: Float = 0f) {
    drawIntoCanvas {
        glowPaint.style = if (stroke > 0f) android.graphics.Paint.Style.STROKE else android.graphics.Paint.Style.FILL
        glowPaint.strokeWidth = stroke
        glowPaint.strokeCap = android.graphics.Paint.Cap.ROUND
        glowPaint.strokeJoin = android.graphics.Paint.Join.ROUND
        glowPaint.color = color.copy(alpha = (color.alpha * alpha * 0.7f).coerceIn(0f, 1f)).toArgb()
        it.nativeCanvas.drawPath(path.asAndroidPath(), glowPaint)
    }
}

/**
 * Eye silhouette: a quadrilateral with sloped top/bottom edges and independently rounded top/bottom
 * corners, optionally with a concave cut from below (happy arc).
 */
private fun eyePath(c: Offset, w: Float, h: Float, side: Int, e: EyeShape): Path {
    val half = w / 2f
    val inner = -side // +1 when the inner corner is to the right (left eye)
    val topC = c.y - h / 2f
    val botC = c.y + h / 2f
    val dyTop = e.slopeTop * half
    val dyBot = e.slopeBottom * half
    val xL = c.x - half
    val xR = c.x + half
    // y of top/bottom at the left and right edges, honoring which side is inner.
    var topL = topC - dyTop * inner
    var topR = topC + dyTop * inner
    var botL = botC - dyBot * inner
    var botR = botC + dyBot * inner
    val minH = h * 0.15f + 1f
    if (botL < topL + minH) { val m = (topL + botL) / 2f; topL = m - minH / 2f; botL = m + minH / 2f }
    if (botR < topR + minH) { val m = (topR + botR) / 2f; topR = m - minH / 2f; botR = m + minH / 2f }

    val pts = arrayOf(Offset(xL, topL), Offset(xR, topR), Offset(xR, botR), Offset(xL, botL))
    val maxR = min(w, h) / 2f
    val radii = floatArrayOf(e.radiusTop * maxR, e.radiusTop * maxR, e.radiusBottom * maxR, e.radiusBottom * maxR)

    val path = Path()
    path.moveTo((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f)
    for (i in 0..3) {
        val corner = pts[(i + 1) % 4]
        val prev = pts[i]
        val next = pts[(i + 2) % 4]
        val lp = hypot(prev.x - corner.x, prev.y - corner.y)
        val ln = hypot(next.x - corner.x, next.y - corner.y)
        val r = min(radii[(i + 1) % 4], min(lp, ln) / 2f)
        val a = Offset(corner.x + (prev.x - corner.x) / lp * r, corner.y + (prev.y - corner.y) / lp * r)
        val b = Offset(corner.x + (next.x - corner.x) / ln * r, corner.y + (next.y - corner.y) / ln * r)
        path.lineTo(a.x, a.y)
        // Cubic with the circle constant gives near-circular corners.
        val k = 0.55f
        path.cubicTo(
            a.x + (corner.x - a.x) * k, a.y + (corner.y - a.y) * k,
            b.x + (corner.x - b.x) * k, b.y + (corner.y - b.y) * k,
            b.x, b.y,
        )
    }
    path.close()

    if (e.crescent > 0.01f) {
        val bottom = max(botL, botR)
        val top = bottom - h * e.crescent.coerceIn(0f, 1f)
        val cut = Path().apply { addOval(Rect(c.x - w * 0.8f, top, c.x + w * 0.8f, top + h * 1.3f)) }
        return Path.combine(PathOperation.Difference, path, cut)
    }
    return path
}

private class IrisStyle(val style: String, val size: Float, val pupil: Float, val gazeX: Float, val gazeY: Float)

private fun DrawScope.drawEye(
    c: Offset, w: Float, h: Float, side: Int, shape: EyeShape, p: FaceParams, color: Color,
    withGlow: Boolean, iris: IrisStyle, heartbeat: Float,
) {
    val heart = p.heart.coerceIn(0f, 1f)
    if (heart < 0.99f) {
        val path = eyePath(c, w, h, side, shape)
        val alpha = 1f - heart
        when (iris.style) {
            "ring" -> {
                val stroke = w * 0.09f
                if (withGlow) glow(path, color, alpha, stroke)
                drawPath(path, color.copy(alpha = color.alpha * alpha * 0.1f))
                drawPath(path, color.copy(alpha = color.alpha * alpha), style = Stroke(stroke, join = StrokeJoin.Round))
            }
            "cartoon" -> {
                if (withGlow) glow(path, color, alpha * 0.6f)
                drawPath(path, lerpColor(Color.White, color, 0.12f).copy(alpha = color.alpha * alpha))
            }
            else -> {
                if (withGlow) glow(path, color, alpha)
                // Soft inner light: brighter towards the upper middle, like an emissive screen.
                val brush = Brush.radialGradient(
                    0f to lerpColor(color, Color.White, 0.3f).copy(alpha = color.alpha * alpha),
                    1f to color.copy(alpha = color.alpha * alpha),
                    center = Offset(c.x, c.y - h * 0.2f),
                    radius = max(w, h) * 0.75f,
                )
                drawPath(path, brush)
                if (iris.style == "solid") {
                    // Small specular highlight towards the outer-top corner.
                    clipPath(path) {
                        drawOval(
                            Color.White.copy(alpha = 0.22f * alpha * color.alpha),
                            topLeft = Offset(c.x + side * w * 0.12f - w * 0.11f, c.y - h * 0.36f),
                            size = Size(w * 0.22f, h * 0.13f),
                        )
                    }
                }
            }
        }
        if (iris.style != "solid") drawIris(path, c, w, h, iris, p, color, alpha)
    }
    if (heart > 0.01f) {
        val s = w * 1.15f * (0.6f + 0.4f * heart) * heartbeat
        val path = heartPath(c, s)
        if (withGlow) glow(path, color, heart)
        drawPath(path, color.copy(alpha = color.alpha * heart))
    }
}

/** Iris/pupil variants, clipped to the eye silhouette. Fades out as the eye closes. */
private fun DrawScope.drawIris(eye: Path, c: Offset, w: Float, h: Float, s: IrisStyle, p: FaceParams, color: Color, alpha: Float) {
    val visibility = ((h / w - 0.3f) / 0.35f).coerceIn(0f, 1f) * alpha
    if (visibility < 0.01f) return
    val r = w * s.size / 2f
    val center = Offset(
        c.x + s.gazeX * max(0f, w / 2f - r) * 0.8f,
        c.y + s.gazeY * max(0f, h / 2f - r) * 0.7f,
    )
    val pr = (r * s.pupil * p.pupil).coerceAtMost(r * 0.9f)
    fun glint(radius: Float) {
        drawCircle(Color.White.copy(alpha = visibility * 0.9f), radius * 0.38f, Offset(center.x - radius * 0.45f, center.y - radius * 0.45f))
        drawCircle(Color.White.copy(alpha = visibility * 0.6f), radius * 0.16f, Offset(center.x + radius * 0.4f, center.y + radius * 0.35f))
    }
    clipPath(eye) {
        when (s.style) {
            "cartoon" -> {
                drawCircle(color.copy(alpha = visibility), r, center)
                drawCircle(lerpColor(color, Color.Black, 0.35f).copy(alpha = visibility), r, center, style = Stroke(r * 0.14f))
                drawCircle(Color.Black.copy(alpha = visibility), pr, center)
                glint(pr)
            }
            "ring" -> {
                val gr = pr * 1.1f
                drawCircle(color.copy(alpha = visibility * 0.35f), gr * 1.35f, center)
                drawCircle(color.copy(alpha = visibility), gr, center)
            }
            "orb" -> {
                val big = (r * 1.15f * p.pupil).coerceAtMost(min(w, h) * 0.42f)
                drawCircle(Color.Black.copy(alpha = visibility), big, center)
                drawCircle(lerpColor(color, Color.Black, 0.5f).copy(alpha = visibility * 0.8f), big, center, style = Stroke(big * 0.08f))
                glint(big * 1.1f)
            }
            else -> {
                drawCircle(lerpColor(color, Color.Black, 0.62f).copy(alpha = visibility), r, center)
                drawCircle(lerpColor(color, Color.Black, 0.45f).copy(alpha = visibility * 0.9f), r, center, style = Stroke(r * 0.12f))
                drawCircle(Color.Black.copy(alpha = visibility), pr, center)
                glint(pr)
            }
        }
    }
}

private fun heartPath(c: Offset, s: Float) = Path().apply {
    val x = c.x
    val y = c.y - s * 0.1f
    moveTo(x, y + s * 0.45f)
    cubicTo(x - s * 0.95f, y - s * 0.05f, x - s * 0.38f, y - s * 0.78f, x, y - s * 0.25f)
    cubicTo(x + s * 0.38f, y - s * 0.78f, x + s * 0.95f, y - s * 0.05f, x, y + s * 0.45f)
    close()
}

/**
 * Mouth as a closed shape between an upper and a lower lip curve. When it opens with a smile the upper
 * lip flattens, giving a "D" grin instead of a banana; a frown opens as an arch.
 */
private fun DrawScope.drawMouth(
    c: Offset, mw: Float, openAmount: Float, p: FaceParams, time: Float, color: Color, stroke: Float, withGlow: Boolean,
) {
    val n = 32
    val depth = p.mouthCurve * mw * 0.28f
    val open = openAmount.coerceIn(0f, 1.4f) * mw * 0.38f
    val openness = (open / (mw * 0.12f)).coerceIn(0f, 1f)
    val amp = p.mouthWave * mw * 0.07f
    val top = FloatArray(n + 1)
    val bottom = FloatArray(n + 1)
    val xs = FloatArray(n + 1)
    for (i in 0..n) {
        val t = -1f + 2f * i / n
        val env = 1f - t * t
        val wave = amp * sin(t * PI.toFloat() * 3f + time * 2.5f)
        val se = sqrt(env)
        xs[i] = c.x + t * mw / 2f
        // Upper lip keeps only part of the curve once the mouth opens.
        val upperDepth = depth * (1f - 0.65f * openness * if (depth > 0) 1f else 0.3f)
        top[i] = c.y + upperDepth * env + wave - open * 0.18f * se
        bottom[i] = c.y + depth * env + wave + open * 0.82f * se
    }
    val path = Path().apply {
        moveTo(xs[0], top[0])
        for (i in 1..n) lineTo(xs[i], top[i])
        for (i in n downTo 0) lineTo(xs[i], bottom[i])
        close()
    }
    if (withGlow) glow(path, color, 1f, stroke)
    if (open > 0.5f) {
        drawPath(path, color)
        // Dark inside so an open mouth reads as an opening, not a blob.
        val inner = Path().apply {
            moveTo(xs[2], top[2] + stroke * 0.5f)
            for (i in 3..n - 2) lineTo(xs[i], top[i] + stroke * 0.5f)
            for (i in n - 2 downTo 2) lineTo(xs[i], bottom[i] - stroke * 0.5f)
            close()
        }
        drawPath(inner, Color.Black.copy(alpha = 0.55f * openness))
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** One eyebrow stroke, rotated so that + angle brings the inner end down. */
private fun DrawScope.drawBrow(c: Offset, eyeW: Float, side: Int, p: FaceParams, color: Color, thickness: Float, withGlow: Boolean) {
    val half = eyeW * 0.42f
    val rad = Math.toRadians((p.browAngle * -side).toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    fun pt(x: Float, y: Float) = Offset(c.x + x * cos - y * sin, c.y + x * sin + y * cos)
    val arch = -p.browCurve.coerceIn(-1f, 1f) * eyeW * 0.14f
    val start = pt(-half, 0f)
    val control = pt(0f, arch * 2f)
    val end = pt(half, 0f)
    val path = Path().apply { moveTo(start.x, start.y); quadraticTo(control.x, control.y, end.x, end.y) }
    val stroke = eyeW * 0.085f * thickness.coerceIn(0.5f, 2.5f)
    if (withGlow) glow(path, color, 1f, stroke)
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

// Segments a..g of a 7-segment digit.
private val DIGIT_SEGMENTS = arrayOf(
    "abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg",
)

/** Retro 7-segment clock (old radio-alarm style) with a blinking colon. Uses the device time zone. */
private fun DrawScope.drawDigitalClock(corner: String, color: Color, unit: Float) {
    val dh = min(size.minDimension * 0.07f, unit * 0.075f) // digit height
    val dw = dh * 0.55f
    val th = dh * 0.13f // segment thickness
    val gap = dh * 0.22f
    val colonW = dh * 0.3f
    val totalW = dw * 4 + gap * 3 + colonW
    val margin = size.minDimension * 0.045f
    val x0 = if (corner.endsWith("left")) margin else size.width - margin - totalW
    val y0 = if (corner.startsWith("bottom")) size.height - margin - dh else margin
    val now = java.time.LocalTime.now()
    val digits = "%02d%02d".format(now.hour, now.minute)
    val lit = color.copy(alpha = color.alpha * 0.9f)
    val off = Color.Transparent // only lit segments, no "ghost" digits

    fun segment(x: Float, y: Float, horizontal: Boolean, len: Float, c: Color) {
        val h = th / 2f
        val path = Path().apply {
            if (horizontal) {
                moveTo(x, y); lineTo(x + h, y - h); lineTo(x + len - h, y - h); lineTo(x + len, y); lineTo(x + len - h, y + h); lineTo(x + h, y + h)
            } else {
                moveTo(x, y); lineTo(x - h, y + h); lineTo(x - h, y + len - h); lineTo(x, y + len); lineTo(x + h, y + len - h); lineTo(x + h, y + h)
            }
            close()
        }
        if (c.alpha <= 0f) return
        glow(path, c, 0.8f)
        drawPath(path, c)
    }

    fun digit(x: Float, d: Int) {
        val on = DIGIT_SEGMENTS[d]
        val half = dh / 2f
        val inset = th * 0.15f
        // Slight italic slant like real LED clocks.
        val k = dh * 0.08f
        fun c(s: Char) = if (s in on) lit else off
        segment(x + k + inset, y0, true, dw - 2 * inset, c('a'))
        segment(x + k * 0.5f + inset, y0 + half, true, dw - 2 * inset, c('g'))
        segment(x + inset, y0 + dh, true, dw - 2 * inset, c('d'))
        segment(x + k + dw, y0 + inset, false, half - 2 * inset, c('b'))
        segment(x + k * 0.5f + dw, y0 + half + inset, false, half - 2 * inset, c('c'))
        segment(x + k, y0 + inset, false, half - 2 * inset, c('f'))
        segment(x + k * 0.5f, y0 + half + inset, false, half - 2 * inset, c('e'))
    }

    var x = x0
    for (i in 0..3) {
        digit(x, digits[i] - '0')
        x += dw + gap
        if (i == 1) {
            val blink = if (now.second % 2 == 0) lit else off
            val cx = x - gap / 2f + colonW / 2f - gap * 0.3f
            drawCircle(blink, th * 0.6f, Offset(cx, y0 + dh * 0.32f))
            drawCircle(blink, th * 0.6f, Offset(cx - dh * 0.03f, y0 + dh * 0.7f))
            x += colonW
        }
    }
}

/** Small neon analog clock in a corner, with a smoothly sweeping second hand. */
private fun DrawScope.drawClock(corner: String, color: Color, unit: Float) {
    val r = min(size.minDimension * 0.075f, unit * 0.09f)
    val margin = r * 0.6f + size.minDimension * 0.02f
    val c = Offset(
        if (corner.endsWith("left")) margin + r else size.width - margin - r,
        if (corner.startsWith("bottom")) size.height - margin - r else margin + r,
    )
    val dim = color.copy(alpha = color.alpha * 0.55f)
    drawCircle(dim, r, c, style = Stroke(r * 0.06f))
    for (i in 0 until 12) {
        val a = i * PI.toFloat() / 6f
        val inner = if (i % 3 == 0) 0.74f else 0.83f
        drawLine(dim, Offset(c.x + sin(a) * r * inner, c.y - kotlin.math.cos(a) * r * inner), Offset(c.x + sin(a) * r * 0.92f, c.y - kotlin.math.cos(a) * r * 0.92f), r * (if (i % 3 == 0) 0.06f else 0.035f), StrokeCap.Round)
    }
    val now = java.time.LocalTime.now()
    val sec = now.second + now.nano / 1e9f
    val min = now.minute + sec / 60f
    val hour = (now.hour % 12) + min / 60f
    fun hand(turns: Float, len: Float, width: Float, col: Color) {
        val a = turns * 2f * PI.toFloat()
        drawLine(col, c, Offset(c.x + sin(a) * r * len, c.y - kotlin.math.cos(a) * r * len), width, StrokeCap.Round)
    }
    hand(hour / 12f, 0.5f, r * 0.09f, color.copy(alpha = color.alpha * 0.85f))
    hand(min / 60f, 0.75f, r * 0.06f, color.copy(alpha = color.alpha * 0.85f))
    hand(sec / 60f, 0.82f, r * 0.025f, dim)
    drawCircle(color, r * 0.06f, c)
}

/** Archimedean spiral for dizzy "@@" eyes. */
private fun DrawScope.drawSpiral(c: Offset, r: Float, rotation: Float, color: Color) {
    val path = Path()
    val turns = 3f
    val n = 90
    for (i in 0..n) {
        val k = i / n.toFloat()
        val a = k * turns * 2f * PI.toFloat() + rotation
        val rr = r * k
        val x = c.x + kotlin.math.cos(a) * rr
        val y = c.y + sin(a) * rr
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    glow(path, color, 1f, r * 0.13f)
    drawPath(path, color, style = Stroke(r * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Rounded reading-glasses frame around one eye, plus the bridge from the left eye. */
private fun DrawScope.drawGlassFrame(c: Offset, w: Float, h: Float, side: Int, color: Color, sep: Float) {
    val fw = w * 1.35f
    val fh = max(h, w * 0.9f) * 1.05f
    val frame = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(c.x - fw / 2, c.y - fh / 2), Size(fw, fh)), androidx.compose.ui.geometry.CornerRadius(fw * 0.3f)))
    }
    val sw = w * 0.07f
    drawPath(frame, color.copy(alpha = color.alpha * 0.08f))
    glow(frame, color, 0.8f, sw)
    drawPath(frame, color, style = Stroke(sw))
    if (side < 0) {
        val bridge = Path().apply { moveTo(c.x + fw / 2, c.y - fh * 0.1f); quadraticTo(c.x + sep, c.y - fh * 0.3f, c.x + 2 * sep - fw / 2, c.y - fh * 0.1f) }
        drawPath(bridge, color, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Dark sunglasses lens over one eye (hides it), plus the bridge from the left lens. */
private fun DrawScope.drawSunglassLens(c: Offset, w: Float, h: Float, side: Int, color: Color, k: Float, sep: Float) {
    val lw = w * 1.4f
    val lh = max(h * 0.75f, w * 0.75f)
    val top = c.y - lh * 0.55f
    val lens = Path().apply {
        // Classic aviator-ish lens: flat top, rounded bottom.
        moveTo(c.x - lw / 2, top)
        lineTo(c.x + lw / 2, top)
        cubicTo(c.x + lw / 2, top + lh * 0.9f, c.x + lw * 0.2f, top + lh * 1.05f, c.x, top + lh)
        cubicTo(c.x - lw * 0.2f, top + lh * 1.05f, c.x - lw / 2, top + lh * 0.9f, c.x - lw / 2, top)
        close()
    }
    drawPath(lens, Color.Black.copy(alpha = 0.92f * k))
    drawPath(lens, color.copy(alpha = color.alpha * 0.18f * k))
    glow(lens, color, 0.8f * k, w * 0.06f)
    drawPath(lens, color.copy(alpha = color.alpha * k), style = Stroke(w * 0.06f))
    // Shine.
    drawLine(Color.White.copy(alpha = 0.45f * k), Offset(c.x - lw * 0.28f, top + lh * 0.2f), Offset(c.x - lw * 0.1f, top + lh * 0.55f), w * 0.05f, StrokeCap.Round)
    if (side < 0) {
        drawLine(color.copy(alpha = color.alpha * k), Offset(c.x + lw / 2, top + lh * 0.12f), Offset(c.x + 2 * sep - lw / 2, top + lh * 0.12f), w * 0.06f, StrokeCap.Round)
    }
}

/** Anime-style blush strokes under an eye. */
private fun DrawScope.drawBlush(c: Offset, ew: Float, color: Color) {
    for (i in -1..1) {
        val x = c.x + i * ew * 0.16f
        drawLine(color.copy(alpha = color.alpha * 0.7f), Offset(x + ew * 0.05f, c.y - ew * 0.07f), Offset(x - ew * 0.05f, c.y + ew * 0.07f), ew * 0.045f, StrokeCap.Round)
    }
}

/** Tongue sticking out of the mouth corner, wiggling a little. */
private fun DrawScope.drawTongue(m: Offset, mw: Float, p: FaceParams, t: Float, color: Color) {
    val k = p.tongue.coerceIn(0f, 1f)
    val x = m.x + mw * 0.18f + sin(t * 4f) * mw * 0.02f
    val y = m.y + p.mouthCurve * mw * 0.2f
    val len = mw * 0.24f * k
    val wd = mw * 0.18f
    val tongue = Path().apply {
        moveTo(x - wd / 2, y)
        lineTo(x - wd / 2, y + len - wd / 2)
        quadraticTo(x - wd / 2, y + len + wd * 0.1f, x, y + len + wd * 0.1f)
        quadraticTo(x + wd / 2, y + len + wd * 0.1f, x + wd / 2, y + len - wd / 2)
        lineTo(x + wd / 2, y)
    }
    drawPath(tongue, color.copy(alpha = color.alpha * 0.85f * k))
    drawLine(Color.Black.copy(alpha = 0.5f * k), Offset(x, y + wd * 0.2f), Offset(x, y + len * 0.7f), mw * 0.02f, StrokeCap.Round)
}

/** Peaceful closed eye "︶" for sleeping, gently rising and falling with the breath. */
private fun DrawScope.drawClosedEye(c: Offset, w: Float, breath: Float, color: Color, withGlow: Boolean) {
    val half = w * 0.48f
    val depth = w * (0.2f + 0.02f * breath)
    val y = c.y + w * 0.05f
    val path = Path().apply {
        moveTo(c.x - half, y)
        quadraticTo(c.x, y + depth * 2f, c.x + half, y)
    }
    val stroke = w * 0.11f
    if (withGlow) glow(path, color, 1f, stroke)
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}

/** Three thought bubbles rising beside the head, lighting up in sequence. */
private fun DrawScope.drawThoughtBubbles(x0: Float, y0: Float, unit: Float, time: Float, visibility: Float, color: Color) {
    for (i in 0..2) {
        val phase = max(0f, sin(time * 3.2f - i * 1.1f))
        val a = visibility * (0.3f + 0.7f * phase)
        val r = unit * (0.01f + 0.008f * i) * (0.9f + 0.15f * phase)
        val center = Offset(x0 + i * unit * 0.045f, y0 - i * unit * 0.05f - phase * unit * 0.006f)
        drawCircle(color.copy(alpha = color.alpha * a.coerceIn(0f, 1f)), r, center)
    }
}

private fun DrawScope.drawZzz(x0: Float, y0: Float, unit: Float, time: Float, visibility: Float, color: Color) {
    for (i in 0..2) {
        val phase = ((time / 3.2f) + i / 3f) % 1f
        val s = unit * (0.03f + 0.045f * phase)
        val x = x0 + phase * unit * 0.14f + sin(phase * 6f) * unit * 0.01f
        val y = y0 - phase * unit * 0.32f
        val a = visibility * sin(phase * PI.toFloat())
        val path = Path().apply {
            moveTo(x, y); lineTo(x + s, y); lineTo(x, y + s); lineTo(x + s, y + s)
        }
        drawPath(
            path, color.copy(alpha = (color.alpha * a).coerceIn(0f, 1f)),
            style = Stroke(width = unit * 0.009f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
