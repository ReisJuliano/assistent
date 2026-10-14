package io.github.assistent.face

import androidx.compose.ui.graphics.Color
import io.github.assistent.core.Activity
import io.github.assistent.core.Emotion

/**
 * Shape of one eye, Cozmo-style (inspired by playfultechnology/esp32-eyes presets): the expression comes
 * from the silhouette itself (height, sloped top/bottom edges, separate top/bottom corner radii, a concave
 * cut from below) instead of lids clipping a rectangle. Values are relative to the configured eye size.
 * Slopes refer to the inner corner, so one shape mirrors naturally onto the other eye.
 */
data class EyeShape(
    val height: Float = 1f,
    val width: Float = 1f,
    /** + = inner top corner lower (angry/focused), − = inner top corner higher (sad/worried). */
    val slopeTop: Float = 0f,
    /** Same convention for the bottom edge (both negative = droopy, sleepy eyes). */
    val slopeBottom: Float = 0f,
    /** Corner roundness 0..1 (1 = fully round), top and bottom independently. */
    val radiusTop: Float = 0.45f,
    val radiusBottom: Float = 0.45f,
    /** Concave cut from below, 0..1: turns the eye into a happy "^" arc. */
    val crescent: Float = 0f,
    /** + = outward. */
    val offsetX: Float = 0f,
    /** + = down. */
    val offsetY: Float = 0f,
)

/**
 * Every visual knob of the face. Expressions are presets of these numbers; the animator springs every
 * number independently, so any expression morphs organically into any other.
 */
data class FaceParams(
    val left: EyeShape = EyeShape(),
    val right: EyeShape = EyeShape(),
    /** 0..1 cross-fade from eye shape to heart. */
    val heart: Float = 0f,
    /** Pupil dilation: <1 tiny (surprised, angry), >1 dilated (listening, love). */
    val pupil: Float = 1f,
    /** Eyebrow height: −1 lowered … +1 raised high. */
    val browRaise: Float = 0f,
    /** Degrees. + = inner ends down (angry), − = inner ends up (sad/worried). */
    val browAngle: Float = 0f,
    /** Arch 0 flat … 1 arched. */
    val browCurve: Float = 0.15f,
    /** Added to the left brow height, subtracted from the right (skeptical). */
    val browAsym: Float = 0f,
    /** −1 frown … +1 big smile. */
    val mouthCurve: Float = 0.15f,
    val mouthOpen: Float = 0f,
    val mouthWidth: Float = 0.8f,
    val mouthWave: Float = 0f,
    val mouthOffsetX: Float = 0f,
    /** Head tilt in degrees. */
    val tilt: Float = 0f,
    /** Laughing bounce amplitude. */
    val bounce: Float = 0f,
    /** Fast tiny jitter (anger). */
    val tremble: Float = 0f,
    /** Heartbeat pulse (love). */
    val pulse: Float = 0f,
    val dots: Float = 0f,
    val zzz: Float = 0f,
    val brightness: Float = 1f,
    /** Tongue sticking out of the mouth corner (gaming, concentration, yum). */
    val tongue: Float = 0f,
    /** "///" blush marks under the eyes. */
    val blush: Float = 0f,
    /** Spiral dizzy eyes. */
    val dizzy: Float = 0f,
    /** Reading glasses. */
    val glasses: Float = 0f,
    /** Dark sunglasses (hot days). */
    val sunglasses: Float = 0f,
    val color: Color = Color.Unspecified,
    /** One-shot head shake when entering this expression (read on change, not animated). */
    val enterShake: Float = 0f,
    /** One-shot "pop" (quick scale overshoot) when entering this expression. */
    val enterPop: Float = 0f,
)

/** Same shape on both eyes (mirrored by the renderer). */
internal fun FaceParams.eyes(e: EyeShape) = copy(left = e, right = e)

object FacePresets {
    /** The face always keeps the user's color; expressions only change shapes and motion. */

    fun emotion(e: Emotion, base: Color): FaceParams = when (e) {
        Emotion.NEUTRAL -> FaceParams(color = base)

        Emotion.HAPPY -> FaceParams(mouthCurve = 0.65f, mouthWidth = 1f, browRaise = 0.3f, browCurve = 0.4f, color = base)
            .eyes(EyeShape(height = 0.62f, radiusTop = 1f, radiusBottom = 0.25f, crescent = 0.42f, offsetY = -0.04f))

        Emotion.JOY -> FaceParams(
            mouthCurve = 0.85f, mouthOpen = 0.6f, mouthWidth = 1.1f, bounce = 1f, browRaise = 0.6f, browCurve = 0.5f,
            color = base, enterPop = 1f,
        ).eyes(EyeShape(height = 0.5f, width = 1.06f, radiusTop = 1f, radiusBottom = 0.1f, crescent = 0.6f, offsetY = -0.07f))

        Emotion.SAD -> FaceParams(
            mouthCurve = -0.5f, mouthWidth = 0.62f, browRaise = 0.15f, browAngle = -24f, browCurve = 0f,
            pupil = 1.15f, tilt = -3f, color = base,
        ).eyes(EyeShape(height = 0.66f, slopeTop = -0.24f, radiusTop = 0.3f, radiusBottom = 0.9f, offsetY = 0.1f))

        Emotion.SURPRISED -> FaceParams(
            mouthCurve = 0f, mouthOpen = 1.1f, mouthWidth = 0.38f, pupil = 0.55f, browRaise = 1f, browCurve = 0.45f,
            color = base, enterPop = 1f,
        ).eyes(EyeShape(height = 1.22f, width = 1.12f, radiusTop = 0.85f, radiusBottom = 0.85f, offsetY = -0.04f))

        // Curious "hm?": asymmetric and playful, without the lowered angry brow.
        Emotion.CONFUSED -> FaceParams(
            left = EyeShape(height = 1.04f, radiusTop = 0.7f, radiusBottom = 0.6f, offsetY = -0.04f),
            right = EyeShape(height = 0.78f, slopeTop = 0.04f, radiusTop = 0.45f, radiusBottom = 0.65f, offsetY = 0.02f),
            mouthWave = 0.35f, mouthCurve = 0.08f, mouthWidth = 0.52f, mouthOffsetX = 0.18f, tilt = -3f,
            browRaise = 0.34f, browAsym = 0.48f, browAngle = -3f, color = base, enterShake = 0.15f,
        )

        Emotion.ANGRY -> FaceParams(
            mouthCurve = -0.4f, mouthWidth = 0.6f, pupil = 0.75f, browRaise = -0.5f, browAngle = 28f, browCurve = 0f,
            tremble = 1f, color = base, enterShake = 0.5f,
        ).eyes(EyeShape(height = 0.64f, slopeTop = 0.32f, radiusTop = 0.15f, radiusBottom = 0.7f))

        Emotion.LOVE -> FaceParams(
            heart = 1f, pulse = 1f, pupil = 1.3f, mouthCurve = 0.65f, mouthWidth = 0.9f, browRaise = 0.45f, browCurve = 0.45f,
            blush = 1f, color = base, enterPop = 0.8f,
        )

        Emotion.THINKING -> thinking(base)

        Emotion.SLEEPY -> FaceParams(
            mouthCurve = 0f, mouthWidth = 0.45f, mouthOpen = 0.1f, browRaise = -0.25f, browAngle = -6f,
            brightness = 0.8f, pupil = 0.9f, tilt = -3f, color = base,
        ).eyes(EyeShape(height = 0.4f, slopeTop = -0.14f, radiusTop = 0.5f, radiusBottom = 0.8f, offsetY = 0.1f))
    }

    /** "Hmm…": one eye narrowed, the other open, looking up to the side, mouth pulled to a corner, thought bubbles. */
    private fun thinking(base: Color) = FaceParams(
        left = EyeShape(height = 0.56f, slopeTop = 0.12f, radiusTop = 0.35f, radiusBottom = 0.7f, offsetY = 0.03f),
        right = EyeShape(height = 1.05f, radiusTop = 0.6f, radiusBottom = 0.55f, offsetY = -0.03f),
        mouthCurve = -0.12f, mouthWidth = 0.32f, mouthOffsetX = 0.45f, tilt = 3f, dots = 1f,
        browRaise = 0.25f, browAsym = -0.55f, browAngle = 4f, color = base,
    )

    /** Activity overrides the mood while it lasts. */
    fun resolve(activity: Activity, emotion: Emotion, base: Color): FaceParams = when (activity) {
        Activity.SLEEPING -> FaceParams(
            mouthCurve = 0.05f, mouthOpen = 0.12f, mouthWidth = 0.3f, zzz = 1f, brightness = 0.6f, tilt = -4f,
            browRaise = -0.35f, browCurve = 0.1f, color = base,
        ).eyes(EyeShape(height = 0.06f, radiusTop = 1f, radiusBottom = 1f, offsetY = 0.12f))

        Activity.LISTENING -> emotion(emotion, base).copy(
            heart = 0f, pupil = 1.2f, mouthCurve = 0.2f, mouthOpen = 0.16f, mouthWidth = 0.62f, mouthWave = 0.08f,
            bounce = 0f, tremble = 0f, pulse = 0f, tilt = 3f, browRaise = 0.35f, browAsym = 0f, browAngle = -4f,
            enterShake = 0f, enterPop = 0f,
        ).eyes(EyeShape(height = 1.12f, width = 1.05f, radiusTop = 0.55f, radiusBottom = 0.55f, offsetY = -0.02f))

        Activity.THINKING -> thinking(base)

        Activity.SEARCHING -> FaceParams(
            mouthCurve = 0f, mouthOpen = 0.15f, mouthWidth = 0.42f, dots = 1f, browRaise = -0.1f, browAngle = 6f, color = base,
        ).eyes(EyeShape(height = 0.72f, slopeTop = 0.06f, radiusTop = 0.4f, radiusBottom = 0.6f))

        Activity.SPEAKING, Activity.IDLE -> emotion(emotion, base)
    }
}
