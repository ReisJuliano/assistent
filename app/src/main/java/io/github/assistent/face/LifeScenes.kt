package io.github.assistent.face

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import io.github.assistent.core.Emotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Life": little things the robot does on its own so it feels alive — idle hobbies while nobody talks to
 * it, dreams while sleeping and reactions when it wakes up. Inspired by Anki Vector/Cozmo idle animations,
 * Living.ai EMO and Tamagotchi-style routines. Each scene is a short script over time that tweaks the face
 * and draws a few neon line-art props, always in the face color.
 */
enum class Scene(val key: String, val seconds: Float, val kind: Kind, val label: String) {
    GAMING("gaming", 14f, Kind.AWAKE, "Videogame"),
    // Light gags (see LightGag): they last until the runtime switches the light off, then get cleared.
    LIGHT_LAMP("light_lamp", 3600f, Kind.REACT, "Luz: abajur"),
    LIGHT_PHONE("light_phone", 3600f, Kind.REACT, "Luz: lanterna do celular"),
    LIGHT_BULB("light_bulb", 3600f, Kind.REACT, "Luz: ideia brilhante"),
    READING("reading", 16f, Kind.AWAKE, "Lendo"),
    STUDYING("studying", 16f, Kind.AWAKE, "Estudando"),
    WALKING_DOG("walking_dog", 16f, Kind.AWAKE, "Passeando com o cachorro"),
    MUSIC("music", 14f, Kind.AWAKE, "Ouvindo música"),
    COFFEE("coffee", 12f, Kind.AWAKE, "Café / chá / suco"),
    BUBBLEGUM("bubblegum", 9f, Kind.AWAKE, "Chiclete"),
    FLY("fly", 11f, Kind.AWAKE, "Mosquinha"),
    YAWN("yawn", 5f, Kind.AWAKE, "Bocejo"),
    SNEEZE("sneeze", 4.5f, Kind.AWAKE, "Espirro"),
    WHISTLE("whistle", 10f, Kind.AWAKE, "Assobiando"),
    HICCUP("hiccup", 6.5f, Kind.AWAKE, "Soluço"),
    BORED("bored", 8f, Kind.AWAKE, "Entediado"),
    BALL("ball", 10f, Kind.AWAKE, "Bolinha"),
    SNACK("snack", 12f, Kind.AWAKE, "Lanchinho"),
    WX_COLD("wx_cold", 16f, Kind.AWAKE, "Frio: cachecol e chá"),
    WX_RAIN("wx_rain", 16f, Kind.AWAKE, "Chuva: guarda-chuva"),
    WX_HOT("wx_hot", 16f, Kind.AWAKE, "Calor: óculos escuros"),
    PEEKABOO("peekaboo", 7f, Kind.AWAKE, "Esconde-esconde"),
    WORKOUT("workout", 12f, Kind.AWAKE, "Academia"),
    MEDITATE("meditate", 12f, Kind.AWAKE, "Meditando"),
    SELFIE("selfie", 8f, Kind.AWAKE, "Selfie"),
    MOVIE("movie", 14f, Kind.AWAKE, "Pipoca e filme"),
    RAIN_CLOUD("rain_cloud", 10f, Kind.AWAKE, "Nuvenzinha de chuva"),
    STARGAZE("stargaze", 12f, Kind.AWAKE, "Olhando estrelas"),
    STRETCH("stretch", 6f, Kind.AWAKE, "Espreguiçando"),
    DREAM_LOVE("dream_love", 18f, Kind.DREAM, "Sonhando com a namoradinha"),
    DREAM_FOOD("dream_food", 16f, Kind.DREAM, "Sonhando com pizza"),
    DREAM_SHEEP("dream_sheep", 18f, Kind.DREAM, "Contando carneirinhos"),
    DREAM_GAME("dream_game", 16f, Kind.DREAM, "Sonhando que joga"),
    SLEEP_PEEK("sleep_peek", 4f, Kind.DREAM, "Espia e volta a dormir"),
    WAKE_STARTLED("wake_startled", 2.8f, Kind.WAKE, "Acorda assustado"),
    DANCE("dance", 22f, Kind.REACT, "Dançando"),
    HEARD("heard", 1.4f, Kind.REACT, "Ouvi você"),
    WAVE_BACK("wave_back", 2.8f, Kind.REACT, "Acena de volta"),
    GIGGLE("giggle", 3f, Kind.REACT, "Cócegas"),
    PURR("purr", 3.5f, Kind.REACT, "Carinho"),
    SHY("shy", 3.8f, Kind.REACT, "Vergonha"),
    TOO_CLOSE("too_close", 2.6f, Kind.REACT, "Muito perto!"),
    WHERE_ARE_YOU("where_are_you", 15f, Kind.REACT, "Cadê você?"),
    FOUND_YOU("found_you", 2.4f, Kind.REACT, "Achou!"),
    CHARGE("charge", 3f, Kind.REACT, "Carregando"),
    HEAD_SHAKE("head_shake", 1.4f, Kind.REACT, "Balança a cabeça"),
    HEAD_NOD("head_nod", 1.4f, Kind.REACT, "Concorda"),
    WAKE_DIZZY("wake_dizzy", 3.4f, Kind.WAKE, "Acorda zonzo"),
    WAKE_GROGGY("wake_groggy", 3.4f, Kind.WAKE, "Acorda sonolento"),
    TIMER_DONE("timer_done", 3.2f, Kind.REACT, "Timer pronto"),
    MESSAGE_DELIVERY("message_delivery", 4.2f, Kind.REACT, "Entrega recado"),
    INTERCOM("intercom", 8f, Kind.REACT, "Interfone"),
    DRINK_WATER("drink_water", 5.5f, Kind.REACT, "Beber agua"),
    TAKE_PILL("take_pill", 6f, Kind.REACT, "Tomar remedio"),
    EAT_FOOD("eat_food", 6f, Kind.REACT, "Comer"),
    STRETCH_BREAK("stretch_break", 6f, Kind.REACT, "Alongar"),
    CAT_CUDDLE("cat_cuddle", 6.5f, Kind.REACT, "Gatinho no colo"),
    DOG_PAT("dog_pat", 6f, Kind.REACT, "Carinho no cachorro");

    /** AWAKE: hobbies while idle. DREAM: while sleeping. WAKE: right after waking. REACT: answer to something a person did. */
    enum class Kind { AWAKE, DREAM, WAKE, REACT }

    companion object {
        fun of(key: String?): Scene? = entries.firstOrNull { it.key == key }
    }
}

/** What a scene wants this frame: target face, optional gaze override and a whole-face offset (in face units). */
class SceneFrame(
    val params: FaceParams,
    val gaze: Offset? = null,
    val dx: Float = 0f,
    val dy: Float = 0f,
    /** Override of spring stiffness for snappy moments (a scare must be instant, not floaty). */
    val stiffness: Float? = null,
)

/** Positions the props are drawn relative to. */
class FaceGeometry(
    val cx: Float, val cy: Float, val unit: Float, val eyesY: Float, val mouthY: Float,
    val sep: Float, val ew: Float, val eh: Float,
)

private fun prog(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k
private fun ease(k: Float) = k * k * (3f - 2f * k)
private fun hop(t: Float, start: Float, dur: Float) = if (t in start..start + dur) sin((t - start) / dur * PI.toFloat()) else 0f
private fun shakeAt(t: Float, start: Float) = if (t < start || t > start + 1f) 0f else sin((t - start) * 2f * PI.toFloat() * 6f) * exp(-(t - start) * 5f)

object Life {
    /** Beat of the music being danced to (estimated from the microphone), beats per minute. */
    @Volatile var danceBpm = 112f

    /** 0..1 position inside the current beat. */
    fun beat(t: Float) = (t * danceBpm / 60f) % 1f

    fun frame(scene: Scene, t: Float, seed: Long, color: Color, sleeping: FaceParams): SceneFrame {
        val neutral = FacePresets.emotion(Emotion.NEUTRAL, color)
        val happy = FacePresets.emotion(Emotion.HAPPY, color)
        val joy = FacePresets.emotion(Emotion.JOY, color)
        val surprised = FacePresets.emotion(Emotion.SURPRISED, color)
        // Mild "annoyed/pouty" face for small frustrations (losing a game, a fly) — real anger is too much for hobbies.
        val angry = FacePresets.emotion(Emotion.CONFUSED, color).copy(enterShake = 0f, mouthWave = 0f, mouthCurve = -0.25f, browAngle = 10f, browRaise = -0.2f)
        val sad = FacePresets.emotion(Emotion.SAD, color)
        val sleepy = FacePresets.emotion(Emotion.SLEEPY, color)
        val closedHappy = EyeShape(height = 0.45f, radiusTop = 1f, radiusBottom = 0.1f, crescent = 0.55f, offsetY = -0.04f)
        val rnd = Random(seed)

        return when (scene) {
            Scene.GAMING -> {
                val end = scene.seconds - 2.8f
                val win = rnd.nextBoolean()
                when {
                    t < end -> SceneFrame(
                        neutral.eyes(EyeShape(height = 0.72f, slopeTop = 0.08f, radiusTop = 0.4f, radiusBottom = 0.6f)).copy(
                            pupil = 0.8f, tongue = 1f, browRaise = -0.15f, browAngle = 8f, mouthCurve = 0.1f,
                            mouthWidth = 0.5f, mouthOffsetX = 0.25f, tilt = sin(t * 2.3f) * 5f,
                        ),
                        gaze = Offset(sin(t * 4.7f) * 0.45f + sin(t * 11f) * 0.1f, 0.6f), dy = -0.05f,
                    )
                    win -> SceneFrame(joy, gaze = Offset(0f, -0.2f), dy = -0.05f - hop(t, end, 0.4f) * 0.04f - hop(t, end + 0.5f, 0.4f) * 0.04f)
                    t < end + 1.2f -> SceneFrame(angry.copy(enterShake = 0f), gaze = Offset(0f, 0.5f), dx = shakeAt(t, end) * 0.03f, dy = -0.05f)
                    else -> SceneFrame(sad, gaze = Offset(0f, 0.6f), dy = -0.05f)
                }
            }

            Scene.READING -> {
                val line = 2.2f
                val phase = (t % line) / line
                val gx = if (phase < 0.88f) mix(-0.55f, 0.55f, phase / 0.88f) else 0.55f - (phase - 0.88f) / 0.12f * 1.1f
                when {
                    t in 9f..10.2f -> SceneFrame(surprised, gaze = Offset(0f, 0.55f), dy = -0.05f) // plot twist!
                    t in 10.2f..12f -> SceneFrame(happy, gaze = Offset(0f, 0.55f), dy = -0.05f)
                    else -> SceneFrame(
                        neutral.eyes(EyeShape(height = 0.8f, radiusTop = 0.5f, radiusBottom = 0.6f)).copy(
                            mouthCurve = 0.2f, mouthWidth = 0.45f, browRaise = 0.1f,
                        ),
                        gaze = Offset(gx, 0.62f), dy = -0.05f,
                    )
                }
            }

            Scene.STUDYING -> {
                val focused = neutral.eyes(EyeShape(height = 0.78f, slopeTop = 0.06f, radiusTop = 0.45f, radiusBottom = 0.6f)).copy(
                    glasses = 1f, tongue = 0.6f, browRaise = -0.1f, browAngle = 8f, mouthCurve = 0f, mouthWidth = 0.35f, mouthOffsetX = -0.3f,
                )
                when {
                    t in 9.5f..11f -> SceneFrame(surprised.copy(glasses = 1f), gaze = Offset(0.3f, -0.6f), dy = -0.05f - hop(t, 9.5f, 0.4f) * 0.03f)
                    t in 11f..13f -> SceneFrame(joy.copy(glasses = 1f, bounce = 0.4f), gaze = Offset(0f, -0.2f), dy = -0.05f)
                    else -> SceneFrame(focused, gaze = Offset(sin(t * 3f) * 0.25f, 0.65f), dy = -0.05f)
                }
            }

            Scene.WALKING_DOG -> {
                val jump = t in 7.5f..8.6f
                when {
                    t in 8f..10.5f -> SceneFrame(joy, gaze = Offset(0.55f, 0.5f), dy = -0.06f)
                    else -> SceneFrame(
                        happy.copy(tilt = sin(t * 2f * PI.toFloat() * 1.2f) * 3f, bounce = 0.35f),
                        gaze = Offset(0.6f, if (jump) 0.2f else 0.55f), dy = -0.06f,
                    )
                }
            }

            Scene.MUSIC -> SceneFrame(
                neutral.eyes(closedHappy).copy(
                    mouthCurve = 0.5f, mouthWidth = 0.6f, mouthOpen = 0.15f + 0.2f * abs(sin(t * 4f)),
                    tilt = sin(t * 2f * PI.toFloat() * 0.9f) * 8f, bounce = 0.4f, browRaise = 0.3f, browCurve = 0.4f,
                ),
                gaze = Offset(0f, 0f),
            )

            Scene.COFFEE -> {
                val sip = (t in 2f..3.6f) || (t in 6.5f..8.1f)
                val after = (t in 3.6f..5f) || (t in 8.1f..9.5f)
                when {
                    sip -> SceneFrame(neutral.eyes(closedHappy).copy(mouthOpen = 0.3f, mouthWidth = 0.25f, mouthCurve = 0f), gaze = Offset(0f, 0.5f), dy = -0.06f)
                    after -> SceneFrame(happy.copy(blush = 0.7f), gaze = Offset(0f, 0.2f), dy = -0.06f)
                    else -> SceneFrame(neutral.copy(mouthCurve = 0.25f), gaze = Offset(0f, 0.6f), dy = -0.06f)
                }
            }

            Scene.BUBBLEGUM -> when {
                t < 3f -> SceneFrame(neutral.copy(mouthOpen = 0.25f * abs(sin(t * 6f)), mouthWidth = 0.45f, mouthCurve = 0.1f), gaze = Offset(sin(t) * 0.3f, 0f))
                t < 6.5f -> SceneFrame(
                    neutral.eyes(EyeShape(height = 1.05f, radiusTop = 0.6f, radiusBottom = 0.6f)).copy(mouthOpen = 0.25f, mouthWidth = 0.22f, mouthCurve = 0f, browRaise = 0.3f),
                    gaze = Offset(0f, 0.85f),
                )
                t < 7.5f -> SceneFrame(surprised, gaze = Offset(0f, 0.3f), dx = shakeAt(t, 6.5f) * 0.03f)
                else -> SceneFrame(joy, gaze = Offset(0f, 0f))
            }

            Scene.FLY -> {
                val away = t > 9f
                val fly = flyPos(t)
                when {
                    t in 8f..9.2f -> SceneFrame(angry.copy(enterShake = 0f), gaze = Offset(0f, 0.1f), dx = shakeAt(t, 8.1f) * 0.035f)
                    away -> SceneFrame(happy.copy(mouthCurve = 0.4f), gaze = Offset(0f, 0f))
                    else -> SceneFrame(
                        neutral.eyes(EyeShape(height = 1.06f, radiusTop = 0.6f, radiusBottom = 0.55f)).copy(browRaise = 0.3f, mouthWidth = 0.35f, mouthCurve = 0f),
                        gaze = Offset(fly.x.coerceIn(-1f, 1f), fly.y.coerceIn(-1f, 1f)),
                    )
                }
            }

            Scene.YAWN -> {
                val open = when {
                    t < 1.2f -> ease(prog(t, 0f, 1.2f)) * 1.3f
                    t < 3.2f -> 1.3f
                    else -> (1f - ease(prog(t, 3.2f, 4f))) * 1.3f
                }
                val eyesH = when {
                    t < 1.2f -> mix(1f, 0.15f, ease(prog(t, 0f, 1.2f)))
                    t < 3.2f -> 0.15f
                    else -> mix(0.15f, 1f, ease(prog(t, 3.2f, 4.2f)))
                }
                SceneFrame(
                    neutral.eyes(EyeShape(height = eyesH, radiusTop = 0.6f, radiusBottom = 0.6f)).copy(
                        mouthOpen = open, mouthWidth = mix(0.8f, 0.48f, min(1f, open)), mouthCurve = 0f,
                        browRaise = 0.35f * min(1f, open), tilt = -6f * min(1f, open),
                    ),
                    gaze = Offset(0f, -0.2f), dy = -0.02f * min(1f, open),
                )
            }

            Scene.SNEEZE -> when {
                t < 0.8f -> SceneFrame(neutral.eyes(EyeShape(height = 0.8f)).copy(browRaise = 0.4f, mouthOpen = 0.3f, mouthWidth = 0.35f, tilt = -4f), dy = -0.01f)
                t < 1.6f -> SceneFrame(neutral.eyes(EyeShape(height = 0.45f)).copy(browRaise = 0.5f, mouthOpen = 0.5f, mouthWidth = 0.35f, tilt = -8f), dy = -0.02f)
                t < 2.2f -> SceneFrame(neutral.eyes(EyeShape(height = 0.25f)).copy(browRaise = 0.6f, mouthOpen = 0.55f, mouthWidth = 0.35f, tilt = -10f), dy = -0.03f)
                t < 2.7f -> SceneFrame(neutral.eyes(EyeShape(height = 0.08f, radiusTop = 1f, radiusBottom = 1f)).copy(mouthOpen = 0.9f, mouthWidth = 0.42f, tilt = 6f, browRaise = -0.3f), dx = shakeAt(t, 2.2f) * 0.03f, dy = 0.03f)
                else -> SceneFrame(FacePresets.emotion(Emotion.CONFUSED, color).copy(enterShake = 0f, mouthWave = 0.4f))
            }

            Scene.WHISTLE -> SceneFrame(
                neutral.eyes(EyeShape(height = 0.8f, radiusTop = 0.7f, radiusBottom = 0.5f)).copy(
                    mouthOpen = 0.35f, mouthWidth = 0.22f, mouthCurve = 0f, mouthOffsetX = 0.2f, browRaise = 0.35f, browCurve = 0.4f,
                    tilt = sin(t * 1.5f) * 4f,
                ),
                gaze = Offset(-0.5f, -0.4f),
            )

            Scene.HICCUP -> {
                val beat = (t % 1.6f)
                val hic = beat < 0.22f && t > 0.5f
                SceneFrame(
                    if (hic) surprised.copy(enterPop = 0f, mouthOpen = 0.5f) else neutral.copy(mouthCurve = 0.05f, browRaise = 0.1f),
                    gaze = Offset(0f, 0.1f), dy = -hop(beat, 0f, 0.22f) * 0.03f,
                )
            }

            Scene.BORED -> {
                val (gx, gy) = when {
                    t < 2f -> -0.9f to 0f
                    t < 4f -> 0.9f to 0f
                    else -> 0f to 0.3f
                }
                SceneFrame(
                    neutral.eyes(EyeShape(height = if (t < 4f) 0.9f else 0.5f, radiusTop = 0.3f, radiusBottom = 0.6f)).copy(
                        mouthCurve = -0.1f, mouthWidth = 0.55f, mouthWave = if (t > 5f) 0.3f else 0f, browRaise = -0.2f,
                    ),
                    gaze = Offset(gx, gy), dy = if (t > 5f) 0.02f * ease(prog(t, 5f, 6f)) else 0f,
                )
            }

            Scene.DANCE -> {
                val b = beat(t)
                val hit = sin(b * PI.toFloat())
                val bar = ((t * danceBpm / 60f).toInt() % 4)
                SceneFrame(
                    neutral.eyes(closedHappy).copy(
                        mouthCurve = 0.55f, mouthWidth = 0.6f, mouthOpen = 0.1f + 0.25f * hit,
                        tilt = (if (bar % 2 == 0) 1f else -1f) * 9f * hit, browRaise = 0.3f, browCurve = 0.4f,
                    ),
                    gaze = Offset(0f, 0f), dx = (if (bar % 2 == 0) 0.02f else -0.02f) * hit, dy = -0.035f * hit, stiffness = 380f,
                )
            }

            Scene.HEARD -> when {
                t < 0.25f -> SceneFrame(
                    surprised.copy(enterPop = 0.25f, mouthOpen = 0.12f, mouthWidth = 0.28f, browRaise = 0.55f),
                    gaze = Offset(0f, -0.15f), stiffness = 520f,
                )
                t < 0.85f -> SceneFrame(
                    happy.copy(mouthCurve = 0.3f, mouthWidth = 0.5f, browRaise = 0.2f),
                    gaze = Offset(sin(t * 8f) * 0.3f, -0.1f),
                )
                else -> SceneFrame(neutral.copy(mouthCurve = 0.12f), gaze = Offset(0f, 0f))
            }

            Scene.LIGHT_LAMP, Scene.LIGHT_PHONE, Scene.LIGHT_BULB -> LightGag.frame(scene, t, sleeping, neutral, happy, joy, surprised)

            Scene.WAVE_BACK -> SceneFrame(happy.copy(tilt = 6f, mouthCurve = 0.7f, mouthWidth = 0.9f), gaze = Offset(0.1f, 0f), dy = -hop(t, 0f, 0.4f) * 0.03f)

            Scene.GIGGLE -> SceneFrame(
                joy.eyes(closedHappy).copy(blush = 1f, mouthOpen = 0.35f, bounce = 0f, tilt = sin(t * 9f) * 5f, enterPop = 0f),
                dy = -abs(sin(t * 12f)) * 0.02f, stiffness = 500f,
            )

            Scene.PURR -> SceneFrame(
                neutral.eyes(closedHappy).copy(blush = 0.9f, mouthCurve = 0.7f, mouthWidth = 0.5f, tilt = 10f + sin(t * 2f) * 3f, browRaise = 0.2f, browCurve = 0.5f),
                dx = 0.02f * sin(t * 2f),
            )

            Scene.SHY -> SceneFrame(
                neutral.eyes(EyeShape(height = 0.8f, radiusTop = 0.6f, radiusBottom = 0.6f)).copy(blush = 1f, mouthWave = 0.4f, mouthWidth = 0.4f, mouthCurve = 0.2f, tilt = -6f, browRaise = 0.3f, browAngle = -8f),
                gaze = Offset(-0.7f, 0.5f),
            )

            Scene.TOO_CLOSE -> SceneFrame(
                surprised.eyes(EyeShape(height = 1.3f, width = 1.1f, radiusTop = 0.9f, radiusBottom = 0.9f)).copy(enterPop = 0f, pupil = 0.35f, mouthOpen = 0.6f, mouthWidth = 0.3f),
                gaze = Offset(0f, 0f), dy = 0.04f, stiffness = 600f,
            )

            Scene.WHERE_ARE_YOU -> SceneFrame(
                sad.copy(tilt = sin(t * 0.9f) * 6f, mouthCurve = -0.3f),
                gaze = Offset(sin(t * 1.3f) * 0.8f, -0.1f),
            )

            Scene.FOUND_YOU -> SceneFrame(joy.copy(enterPop = 0f), gaze = Offset(0f, 0f), dy = -hop(t, 0f, 0.35f) * 0.06f, stiffness = 500f)

            Scene.CHARGE -> SceneFrame(happy.copy(blush = 0.5f, mouthCurve = 0.75f, mouthWidth = 0.8f, browRaise = 0.4f), gaze = Offset(0f, -0.3f), dy = -hop(t, 0.2f, 0.4f) * 0.03f)

            Scene.TIMER_DONE -> SceneFrame(
                surprised.copy(bounce = 0.6f, mouthOpen = 0.7f, browRaise = 0.6f, enterPop = 0f),
                gaze = Offset(0.65f, -0.65f), dy = -hop(t % 1.1f, 0f, 0.35f) * 0.05f, stiffness = 520f,
            )

            Scene.MESSAGE_DELIVERY -> SceneFrame(
                happy.copy(mouthCurve = 0.45f, mouthWidth = 0.7f, browRaise = 0.25f),
                gaze = Offset(0f, 0.55f), dy = -0.03f + sin(t * 3f) * 0.006f,
            )

            Scene.INTERCOM -> SceneFrame(
                neutral.copy(mouthOpen = 0.18f + 0.16f * abs(sin(t * 5f)), mouthWidth = 0.45f, browRaise = 0.25f),
                gaze = Offset(sin(t * 1.2f) * 0.25f, -0.1f),
            )

            Scene.DRINK_WATER -> {
                val sip = t in 1.2f..3.4f
                if (sip) SceneFrame(neutral.eyes(closedHappy).copy(mouthOpen = 0.28f, mouthWidth = 0.22f), gaze = Offset(0.1f, 0.52f), dy = -0.04f)
                else SceneFrame(happy.copy(mouthCurve = 0.45f, browRaise = 0.2f), gaze = Offset(0f, 0.35f), dy = -0.04f)
            }

            Scene.TAKE_PILL -> when {
                t < 1.7f -> SceneFrame(neutral.copy(mouthCurve = 0.15f, browRaise = 0.2f), gaze = Offset(-0.35f, 0.55f), dy = -0.04f)
                t < 3.4f -> SceneFrame(neutral.copy(mouthOpen = 0.5f, mouthWidth = 0.28f, browRaise = 0.35f), gaze = Offset(0f, 0.45f), dy = -0.04f)
                else -> SceneFrame(happy.eyes(closedHappy).copy(blush = 0.4f, mouthCurve = 0.55f), gaze = Offset(0f, 0.1f), dy = -0.04f)
            }

            Scene.EAT_FOOD -> {
                val chew = t in 1.8f..4.3f
                if (chew) SceneFrame(neutral.eyes(closedHappy).copy(mouthOpen = 0.12f * abs(sin(t * 10f)), mouthWidth = 0.42f, mouthCurve = 0.18f), dy = -0.04f)
                else SceneFrame(happy.copy(mouthCurve = 0.35f), gaze = Offset(0f, 0.55f), dy = -0.04f)
            }

            // Heard a meow: looks around, then hugs a purring kitten.
            Scene.CAT_CUDDLE -> when {
                t < 1f -> SceneFrame(surprised.copy(mouthOpen = 0.12f, browRaise = 0.5f), gaze = Offset(sin(t * 7f) * 0.6f, -0.1f), stiffness = 420f)
                else -> SceneFrame(
                    joy.eyes(closedHappy).copy(blush = 1f, mouthCurve = 0.6f, mouthWidth = 0.5f, tilt = 8f + sin(t * 1.6f) * 3f, bounce = 0f),
                    gaze = Offset(0f, 0.45f), dy = -0.05f,
                )
            }

            // Heard a bark: looks to the side where the puppy sits and pats its head.
            Scene.DOG_PAT -> when {
                t < 0.9f -> SceneFrame(surprised.copy(mouthOpen = 0.1f, browRaise = 0.45f), gaze = Offset(0.7f, 0.2f), stiffness = 420f)
                else -> SceneFrame(happy.copy(mouthCurve = 0.7f, mouthWidth = 0.75f, tilt = -5f), gaze = Offset(0.75f, 0.35f), dx = -0.03f)
            }

            Scene.STRETCH_BREAK -> SceneFrame(
                happy.copy(mouthCurve = 0.45f, mouthWidth = 0.65f, browRaise = 0.25f, tilt = sin(t * 2f) * 4f),
                gaze = Offset(0f, -0.25f), dy = -hop(t, 0.2f, 1.2f) * 0.03f,
            )

            Scene.HEAD_SHAKE -> SceneFrame(neutral.copy(mouthCurve = -0.1f, mouthWidth = 0.6f), dx = sin(t * 18f) * 0.03f * (1f - t / 1.4f), stiffness = 700f)

            Scene.HEAD_NOD -> SceneFrame(happy.copy(mouthCurve = 0.4f), dy = sin(t * 15f) * 0.025f * (1f - t / 1.4f), stiffness = 700f)

            Scene.SNACK -> {
                val bite = listOf(2f, 5f, 8f).any { t in it..it + 0.5f }
                val chew = listOf(2.5f, 5.5f, 8.5f).any { t in it..it + 1.8f }
                when {
                    t > 10.3f -> SceneFrame(happy.eyes(closedHappy).copy(blush = 0.5f, mouthCurve = 0.6f), gaze = Offset(0f, 0f), dy = -0.05f)
                    bite -> SceneFrame(neutral.copy(mouthOpen = 0.8f, mouthWidth = 0.5f, mouthCurve = 0f, browRaise = 0.3f), gaze = Offset(0f, 0.6f), dy = -0.03f)
                    chew -> SceneFrame(
                        neutral.eyes(closedHappy).copy(mouthOpen = 0.15f * abs(sin(t * 9f)), mouthWidth = 0.42f, mouthCurve = 0.25f, mouthWave = 0.2f),
                        dy = -0.05f,
                    )
                    else -> SceneFrame(neutral.eyes(EyeShape(height = 1.1f, radiusTop = 0.6f, radiusBottom = 0.6f)).copy(mouthCurve = 0.35f, pupil = 1.3f, tongue = 0.4f), gaze = Offset(0f, 0.6f), dy = -0.05f)
                }
            }

            Scene.WX_COLD -> {
                val sip = (t in 4f..5.6f) || (t in 10.5f..12.1f)
                val base = neutral.copy(blush = 0.8f, tremble = 0.7f, browRaise = 0.15f, browAngle = -6f)
                if (sip) SceneFrame(base.eyes(closedHappy).copy(mouthOpen = 0.3f, mouthWidth = 0.25f, mouthCurve = 0f, tremble = 0.2f), gaze = Offset(0f, 0.5f), dy = -0.05f)
                else SceneFrame(
                    base.eyes(EyeShape(height = 0.8f, radiusTop = 0.5f, radiusBottom = 0.6f)).copy(mouthCurve = 0.15f, mouthWidth = 0.45f),
                    gaze = Offset(sin(t * 0.6f) * 0.3f, 0.3f), dy = -0.05f,
                )
            }

            Scene.WX_RAIN -> SceneFrame(
                happy.eyes(EyeShape(height = 0.95f, radiusTop = 0.6f, radiusBottom = 0.55f)).copy(mouthCurve = 0.35f, mouthWidth = 0.6f, browRaise = 0.25f),
                gaze = Offset(-0.25f + sin(t * 0.7f) * 0.35f, -0.55f), dy = 0.03f,
            )

            Scene.WX_HOT -> {
                val pant = 0.15f + 0.12f * abs(sin(t * 6f))
                SceneFrame(
                    neutral.copy(sunglasses = 1f, mouthCurve = 0.3f, mouthWidth = 0.55f, mouthOffsetX = 0.15f, mouthOpen = if (t in 6f..10f) pant else 0f, tongue = if (t in 6f..10f) 0.5f else 0f),
                    gaze = Offset(0f, 0f),
                )
            }

            Scene.BALL -> {
                val b = ballPos(t)
                when {
                    t in 8f..9f -> SceneFrame(surprised.copy(enterPop = 0f), gaze = Offset(0f, -0.3f), dx = shakeAt(t, 8f) * 0.03f, dy = -hop(t, 8f, 0.3f) * 0.03f)
                    t > 9f -> SceneFrame(joy, gaze = Offset(0f, 0f))
                    else -> SceneFrame(
                        happy.eyes(EyeShape(height = 1f, radiusTop = 0.6f, radiusBottom = 0.5f)).copy(mouthCurve = 0.45f, mouthWidth = 0.7f),
                        gaze = Offset(b.x.coerceIn(-1f, 1f), b.y.coerceIn(-1f, 1f)),
                    )
                }
            }

            Scene.PEEKABOO -> when {
                t < 1.2f -> SceneFrame(happy, dx = -1.4f * ease(prog(t, 0.2f, 1.2f)))
                t < 3f -> SceneFrame(neutral, dx = -1.4f)
                t < 4.6f -> SceneFrame(
                    neutral.copy(left = EyeShape(height = 1.1f, radiusTop = 0.6f, radiusBottom = 0.6f), right = EyeShape(height = 0.9f), browRaise = 0.5f, mouthCurve = 0.3f),
                    gaze = Offset(-0.8f, 0f), dx = 0.85f,
                )
                else -> SceneFrame(joy, dx = mix(0.85f, 0f, ease(prog(t, 4.6f, 5.1f))), dy = -hop(t, 5f, 0.4f) * 0.05f)
            }

            Scene.WORKOUT -> {
                val rep = (t % 1.6f) / 1.6f
                val effort = sin(rep * PI.toFloat())
                when {
                    t > 9.5f -> SceneFrame(joy.copy(blush = 0.6f), gaze = Offset(0f, -0.2f))
                    else -> SceneFrame(
                        neutral.eyes(EyeShape(height = mix(0.75f, 0.35f, effort), slopeTop = 0.18f, radiusTop = 0.3f, radiusBottom = 0.6f)).copy(
                            mouthOpen = 0.2f, mouthWidth = 0.7f, mouthCurve = -0.2f * effort, browAngle = 12f, browRaise = -0.1f, tremble = effort * 0.6f,
                        ),
                        gaze = Offset(0f, -0.2f), dy = 0.015f * effort,
                    )
                }
            }

            Scene.MEDITATE -> {
                val peek = t in 8.5f..9.6f
                SceneFrame(
                    neutral.copy(
                        left = if (peek) EyeShape(height = 0.55f, radiusTop = 0.6f, radiusBottom = 0.6f) else EyeShape(height = 0.08f, radiusTop = 1f, radiusBottom = 1f),
                        right = EyeShape(height = 0.08f, radiusTop = 1f, radiusBottom = 1f),
                        mouthCurve = 0.3f, mouthWidth = 0.4f, browRaise = 0.1f, browCurve = 0.3f,
                    ),
                    gaze = if (peek) Offset(-0.6f, 0f) else Offset(0f, 0f), dy = -0.02f + sin(t * 1.4f) * 0.012f,
                )
            }

            Scene.SELFIE -> when {
                t < 2f -> SceneFrame(neutral.copy(mouthCurve = 0.2f, browRaise = 0.2f), gaze = Offset(0.6f, 0.1f), dx = -0.06f)
                t < 3.2f -> SceneFrame(
                    happy.copy(right = EyeShape(height = 0.12f, radiusTop = 1f, radiusBottom = 1f), tongue = 0.8f, tilt = 8f),
                    gaze = Offset(0.6f, 0f), dx = -0.06f,
                )
                t < 4.8f -> SceneFrame(surprised.copy(enterPop = 0f, mouthWidth = 0.25f, mouthOpen = 0.5f, tilt = -6f), gaze = Offset(0.6f, 0f), dx = -0.06f)
                else -> SceneFrame(happy.copy(blush = 0.5f), gaze = Offset(0.6f, 0.3f), dx = -0.06f)
            }

            Scene.MOVIE -> {
                val munch = 0.18f * abs(sin(t * 5f))
                when {
                    t in 6f..7.6f -> SceneFrame(surprised.copy(enterPop = 0f, pupil = 0.4f), gaze = Offset(0f, -0.1f), dy = -0.05f - hop(t, 6f, 0.35f) * 0.05f)
                    t in 10f..12f -> SceneFrame(joy, gaze = Offset(0f, -0.1f), dy = -0.05f)
                    else -> SceneFrame(
                        neutral.eyes(EyeShape(height = 0.95f, radiusTop = 0.5f, radiusBottom = 0.5f)).copy(mouthOpen = munch, mouthWidth = 0.45f, mouthCurve = 0.1f, pupil = 1.1f),
                        gaze = Offset(sin(t * 0.7f) * 0.15f, -0.1f), dy = -0.05f,
                    )
                }
            }

            Scene.RAIN_CLOUD -> if (t < 6f) SceneFrame(sad, gaze = Offset(0.1f, -0.6f))
            else SceneFrame(joy.copy(blush = 0.3f), gaze = Offset(0.4f, -0.6f), dy = -hop(t, 6.2f, 0.4f) * 0.04f)

            Scene.STARGAZE -> when {
                t in 6f..7f -> SceneFrame(surprised.copy(enterPop = 0f), gaze = Offset(0.6f, -0.9f))
                t in 7f..9.5f -> SceneFrame(neutral.eyes(EyeShape(height = 0.08f, radiusTop = 1f, radiusBottom = 1f)).copy(mouthCurve = 0.5f, mouthWidth = 0.4f, blush = 0.8f), gaze = Offset(0f, -0.5f))
                else -> SceneFrame(
                    neutral.eyes(EyeShape(height = 1.08f, radiusTop = 0.7f, radiusBottom = 0.6f)).copy(mouthCurve = 0.3f, mouthWidth = 0.4f, pupil = 1.25f, browRaise = 0.3f),
                    gaze = Offset(sin(t * 0.5f) * 0.4f, -0.9f), dy = 0.02f,
                )
            }

            Scene.STRETCH -> {
                val k = ease(prog(t, 0f, 1.5f)) * (1f - ease(prog(t, 4f, 5f)))
                SceneFrame(
                    neutral.eyes(EyeShape(height = mix(1f, 0.08f, k), radiusTop = 1f, radiusBottom = 1f)).copy(
                        mouthOpen = 0.6f * k, mouthWidth = mix(0.8f, 0.45f, k), mouthCurve = 0f, browRaise = 0.4f * k,
                        tilt = sin(t * 2f) * 10f * k,
                    ),
                    dy = -0.05f * k,
                )
            }

            Scene.DREAM_LOVE -> SceneFrame(
                sleeping.copy(mouthCurve = 0.7f, mouthWidth = 0.45f, mouthOpen = 0.05f, blush = 1f, browRaise = 0f, browCurve = 0.4f),
                dy = -hop(t % 4f, 2f, 0.3f) * 0.01f, // giggle
            )
            Scene.DREAM_FOOD -> SceneFrame(
                sleeping.copy(mouthOpen = 0.12f + 0.2f * abs(sin(t * 3f)), mouthWidth = 0.4f, mouthCurve = 0.3f, tongue = if ((t % 5f) > 3.5f) 0.7f else 0f),
            )
            Scene.DREAM_SHEEP -> SceneFrame(sleeping.copy(mouthCurve = 0.35f, mouthWidth = 0.35f))
            Scene.DREAM_GAME -> SceneFrame(sleeping.copy(mouthCurve = 0.5f, mouthWidth = 0.4f, browAngle = 6f, tongue = 0.6f))

            Scene.SLEEP_PEEK -> {
                val open = when {
                    t < 0.6f -> ease(prog(t, 0.1f, 0.6f))
                    t < 2.8f -> 1f
                    else -> 1f - ease(prog(t, 2.8f, 3.5f))
                }
                SceneFrame(
                    sleeping.copy(
                        zzz = 1f - open,
                        left = EyeShape(height = mix(0.06f, 0.55f, open), slopeTop = 0.1f, radiusTop = 0.5f, radiusBottom = 0.7f, offsetY = 0.1f),
                        browRaise = mix(-0.35f, 0.1f, open), browAsym = 0.3f * open,
                    ),
                    gaze = Offset(if (t < 1.7f) -0.6f else 0.6f, 0.1f),
                )
            }

            // Classic startle: anticipation (squeeze) → snap wide open + jump + shiver → darting look → relief sigh → sheepish.
            Scene.WAKE_STARTLED -> {
                val round = EyeShape(height = 1.18f, width = 1.02f, radiusTop = 1f, radiusBottom = 1f, offsetY = -0.05f)
                when {
                    t < 0.14f -> SceneFrame(
                        sleeping.eyes(EyeShape(height = 0.05f, radiusTop = 1f, radiusBottom = 1f, offsetY = 0.1f)).copy(zzz = 0f, browRaise = -0.5f, browAngle = 10f, mouthWidth = 0.3f),
                        dy = 0.02f, stiffness = 900f,
                    )
                    t < 0.7f -> SceneFrame(
                        surprised.eyes(round).copy(enterPop = 0f, pupil = 0.35f, mouthOpen = 0.7f, mouthWidth = 0.3f, browRaise = 1.2f, browCurve = 0.6f, tremble = 1.4f),
                        gaze = Offset(0f, -0.15f), dy = -hop(t, 0.14f, 0.35f) * 0.07f, stiffness = 700f,
                    )
                    t < 1.5f -> SceneFrame(
                        surprised.eyes(round.copy(height = 1.1f)).copy(enterPop = 0f, pupil = 0.5f, mouthOpen = 0.45f, mouthWidth = 0.28f, browRaise = 0.9f),
                        gaze = Offset(if (((t - 0.7f) / 0.27f).toInt() % 2 == 0) -0.75f else 0.75f, 0f), stiffness = 500f,
                    )
                    t < 2.1f -> SceneFrame(
                        neutral.eyes(EyeShape(height = 0.5f, slopeTop = -0.1f, radiusTop = 0.5f, radiusBottom = 0.8f)).copy(
                            mouthOpen = 0.3f, mouthWidth = 0.22f, mouthCurve = 0f, browRaise = 0.1f, browAngle = -10f,
                        ),
                        dy = 0.015f,
                    )
                    else -> SceneFrame(happy.copy(blush = 0.7f, mouthCurve = 0.35f, mouthWidth = 0.6f), gaze = Offset(-0.3f, 0.2f))
                }
            }

            Scene.WAKE_DIZZY -> when {
                t < 2.4f -> SceneFrame(
                    neutral.copy(dizzy = 1f, mouthWave = 0.6f, mouthWidth = 0.55f, mouthCurve = -0.05f, tilt = sin(t * 6f) * 8f, browRaise = 0.2f),
                    gaze = Offset(0f, 0f), dx = cos(t * 6f) * 0.012f, dy = sin(t * 6f) * 0.006f,
                )
                else -> SceneFrame(neutral, gaze = Offset(0f, 0f), dx = shakeAt(t, 2.4f) * 0.035f)
            }

            Scene.WAKE_GROGGY -> when {
                t < 1.4f -> SceneFrame(
                    sleepy.copy(
                        left = EyeShape(height = mix(0.06f, 0.5f, ease(prog(t, 0.2f, 1.2f))), radiusTop = 0.5f, radiusBottom = 0.8f),
                        right = EyeShape(height = 0.06f, radiusTop = 1f, radiusBottom = 1f),
                    ),
                    gaze = Offset(-0.2f, 0.1f),
                )
                t < 2.4f -> SceneFrame(neutral.eyes(EyeShape(height = 0.3f, radiusTop = 0.6f, radiusBottom = 0.6f)).copy(mouthOpen = 1.1f, mouthWidth = 0.45f, mouthCurve = 0f, browRaise = 0.3f, tilt = -5f))
                else -> SceneFrame(neutral.eyes(EyeShape(height = 0.85f)).copy(mouthCurve = 0.1f))
            }
        }
    }

    /** Bouncing ball crossing the screen, normalized to −1..1 around the face. */
    fun ballPos(t: Float): Offset {
        if (t > 8f) return Offset(0f, -0.5f)
        val ph = (t / 4f) % 2f
        val x = if (ph < 1f) mix(-0.9f, 0.9f, ph) else mix(0.9f, -0.9f, ph - 1f)
        val y = 0.85f - abs(sin(t * 3.2f)) * 1.1f
        return Offset(x, y)
    }

    /** Lissajous buzz of the fly, normalized to −1..1 around the face. */
    fun flyPos(t: Float): Offset = if (t > 9f) Offset(0.6f + (t - 9f) * 1.5f, -0.6f - (t - 9f)) else
        Offset(0.55f * sin(t * 1.7f) + 0.15f * sin(t * 5.3f), -0.25f + 0.3f * sin(t * 2.3f))
}

// ---------------------------------------------------------------------------------------------
// Props: neon line art in the face color
// ---------------------------------------------------------------------------------------------

internal fun DrawScope.drawSceneProps(
    scene: Scene, t: Float, seed: Long, g: FaceGeometry, color: Color, sceneSeconds: Float,
    ambient: io.github.assistent.core.Ambient = io.github.assistent.core.Ambient(),
) {
    val u = g.unit
    val sw = u * 0.011f
    // Fade props in/out at the edges of the scene.
    val alpha = min(prog(t, 0f, 0.5f), 1f - prog(t, sceneSeconds - 0.5f, sceneSeconds))
    if (alpha <= 0.01f) return
    val c = color.copy(alpha = color.alpha * alpha)
    fun stroke(p: Path, w: Float = sw, a: Float = 1f) {
        glow(p, c, a * 0.8f, w)
        drawPath(p, c.copy(alpha = c.alpha * a), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    fun fill(p: Path, a: Float = 1f) = drawPath(p, c.copy(alpha = c.alpha * a))

    when (scene) {
        Scene.LIGHT_LAMP, Scene.LIGHT_PHONE, Scene.LIGHT_BULB -> Unit // drawn by RobotFace (needs the hand position)
        Scene.GAMING, Scene.DREAM_GAME -> {
            val dream = scene == Scene.DREAM_GAME
            val center = if (dream) dreamCloud(g, t, sceneSeconds, ::stroke) else Offset(g.cx + sin(t * 9f) * u * 0.003f, g.mouthY + u * 0.2f)
            val s = if (dream) 0.45f else 1f
            gamepad(center, u * s, t, ::stroke, ::fill)
        }

        Scene.READING -> openBook(Offset(g.cx, g.mouthY + u * 0.2f), u, t, ::stroke)

        Scene.STUDYING -> {
            notebook(Offset(g.cx, g.mouthY + u * 0.2f), u, t, ::stroke)
            if (t in 9.5f..12.5f) lightbulb(Offset(g.cx + g.sep * 1.4f, g.eyesY - g.eh * 1.3f), u, t - 9.5f, ::stroke, ::fill)
        }

        Scene.WALKING_DOG -> dog(g, u, t, ::stroke, ::fill)

        Scene.MUSIC -> {
            headphones(g, u, ::stroke, ::fill)
            notes(Offset(g.cx + g.sep + g.ew, g.eyesY), u, t, 3, ::stroke, ::fill)
        }

        Scene.COFFEE -> {
            val sip = (t in 2f..3.6f) || (t in 6.5f..8.1f)
            val lift = if (sip) u * 0.06f * sin(prog(t % 4.5f, 2f, 3.6f) * PI.toFloat()).coerceAtLeast(0f) else 0f
            val c0 = Offset(g.cx + u * 0.05f, g.mouthY + u * 0.22f - lift)
            if ((ambient.temperature ?: 20f) >= 26f) juice(c0, u, t, sip, ::stroke, ::fill) else coffee(c0, u, t, ::stroke)
        }

        Scene.BUBBLEGUM -> if (t in 3f..6.5f) {
            val r = u * 0.14f * ease(prog(t, 3f, 6.5f))
            val center = Offset(g.cx, g.mouthY + r * 0.85f)
            val p = Path().apply { addOval(Rect(center, r)) }
            fill(p, 0.15f); stroke(p)
            stroke(Path().apply { arcTo(Rect(center, r * 0.7f), 200f, 50f, true) }, sw * 0.8f, 0.8f)
        } else if (t in 6.5f..7.1f) {
            burst(Offset(g.cx, g.mouthY + u * 0.1f), u * 0.15f * (1f + prog(t, 6.5f, 7.1f)), ::stroke, 1f - prog(t, 6.5f, 7.1f))
        }

        Scene.FLY -> {
            val f = Life.flyPos(t)
            val pos = Offset(g.cx + f.x * u * 0.42f, g.eyesY + f.y * u * 0.3f)
            fill(Path().apply { addOval(Rect(pos, u * 0.009f)) })
            val flap = if ((t * 30f).toInt() % 2 == 0) 1f else 0.4f
            stroke(Path().apply {
                addOval(Rect(Offset(pos.x - u * 0.012f, pos.y - u * 0.012f), Size(u * 0.012f, u * 0.008f)))
                addOval(Rect(Offset(pos.x, pos.y - u * 0.012f), Size(u * 0.012f, u * 0.008f)))
            }, sw * 0.5f, flap)
        }

        Scene.SNEEZE -> if (t in 2.2f..2.9f) burst(Offset(g.cx, g.mouthY), u * 0.18f * (0.5f + prog(t, 2.2f, 2.9f)), ::stroke, 1f - prog(t, 2.2f, 2.9f))

        Scene.WHISTLE -> notes(Offset(g.cx + u * 0.08f, g.mouthY - u * 0.02f), u * 0.75f, t, 2, ::stroke, ::fill)

        Scene.HICCUP -> {
            val beat = t % 1.6f
            if (beat < 0.6f && t > 0.5f) {
                val r = u * 0.018f * (0.6f + beat)
                stroke(Path().apply { addOval(Rect(Offset(g.cx + u * 0.12f, g.mouthY - beat * u * 0.15f), r)) }, sw * 0.7f, 1f - beat / 0.6f)
            }
        }

        Scene.DREAM_LOVE -> {
            val center = dreamCloud(g, t, sceneSeconds, ::stroke)
            val beat = 1f + 0.12f * max(0f, sin(t * 2f * PI.toFloat() * 1.1f)).let { it * it * it }
            fill(heart(center, u * 0.09f * beat))
            fill(heart(Offset(center.x + u * 0.075f, center.y - u * 0.04f + sin(t * 2f) * u * 0.01f), u * 0.035f), 0.7f)
            fill(heart(Offset(center.x - u * 0.08f, center.y + u * 0.01f + cos(t * 2f) * u * 0.01f), u * 0.028f), 0.6f)
        }

        Scene.DREAM_FOOD -> pizza(dreamCloud(g, t, sceneSeconds, ::stroke), u * 0.09f, t, ::stroke, ::fill)

        Scene.DREAM_SHEEP -> sheep(dreamCloud(g, t, sceneSeconds, ::stroke), u, t, ::stroke, ::fill)

        Scene.WAKE_STARTLED -> {
            if (t in 0.14f..1.5f) exclamation(Offset(g.cx + g.sep * 1.6f, g.eyesY - g.eh * 1.2f), u, t - 0.14f, ::stroke, ::fill)
            if (t in 0.14f..0.5f) burst(Offset(g.cx, g.eyesY - g.eh * 0.2f), u * 0.45f * (0.7f + prog(t, 0.14f, 0.5f)), ::stroke, 0.5f * (1f - prog(t, 0.14f, 0.5f)))
            if (t in 1.5f..2.6f) sweat(Offset(g.cx + g.sep + g.ew * 0.75f, g.eyesY - g.eh * 0.35f), u, (t - 1.5f) * 0.9f, ::fill)
        }

        Scene.WAKE_DIZZY -> if (t < 2.6f) {
            for (i in 0..2) {
                val a = t * 4f + i * 2f * PI.toFloat() / 3f
                val p = Offset(g.cx + cos(a) * g.sep * 1.3f, g.eyesY - g.eh * 1.05f + sin(a) * u * 0.035f)
                fill(star(p, u * 0.022f), 0.5f + 0.5f * (sin(a) * 0.5f + 0.5f))
            }
        }

        Scene.DANCE -> {
            headphones(g, u, ::stroke, ::fill)
            notes(Offset(g.cx + g.sep + g.ew, g.eyesY), u, t, 3, ::stroke, ::fill)
            notes(Offset(g.cx - g.sep - g.ew * 1.6f, g.eyesY + g.eh * 0.3f), u * 0.8f, t + 1.1f, 2, ::stroke, ::fill)
        }

        Scene.PURR -> for (i in 0..2) {
            val ph = ((t / 1.6f) + i / 3f) % 1f
            fill(heart(Offset(g.cx + g.sep * (0.6f + i * 0.4f), g.eyesY - g.eh * (0.8f + ph * 1.2f)), u * 0.03f * (0.6f + ph)), (1f - ph) * 0.9f)
        }

        Scene.GIGGLE -> for (i in 0..1) {
            val ph = ((t * 0.9f) + i * 0.5f) % 1f
            val x = g.cx + (if (i == 0) -1 else 1) * (g.sep + g.ew * (0.8f + ph * 0.3f))
            stroke(Path().apply { moveTo(x, g.eyesY - ph * u * 0.1f); lineTo(x + u * 0.02f, g.eyesY - ph * u * 0.1f - u * 0.02f) }, sw * 0.7f, 1f - ph)
        }

        Scene.CHARGE -> {
            val c = Offset(g.cx + g.sep * 1.5f, g.eyesY - g.eh * 1.2f)
            val s = u * 0.05f * (0.8f + 0.2f * sin(t * 12f))
            val bolt = Path().apply {
                moveTo(c.x + s * 0.2f, c.y - s); lineTo(c.x - s * 0.45f, c.y + s * 0.1f); lineTo(c.x, c.y + s * 0.1f)
                lineTo(c.x - s * 0.2f, c.y + s); lineTo(c.x + s * 0.45f, c.y - s * 0.1f); lineTo(c.x, c.y - s * 0.1f); close()
            }
            fill(bolt, 0.9f); stroke(bolt, sw * 0.6f)
        }

        Scene.TIMER_DONE -> {
            val c = Offset(g.cx + g.sep * 1.55f, g.eyesY - g.eh * 1.0f)
            stroke(Path().apply { addOval(Rect(c, u * 0.045f)) }, sw)
            stroke(Path().apply { moveTo(c.x, c.y); lineTo(c.x, c.y - u * 0.03f); moveTo(c.x, c.y); lineTo(c.x + u * 0.025f, c.y) }, sw * 0.55f)
            burst(c, u * 0.12f, ::stroke, 0.8f)
        }

        Scene.MESSAGE_DELIVERY -> {
            val c = Offset(g.cx, g.mouthY + u * 0.04f)
            val w = u * 0.26f
            val h = u * 0.14f
            val env = Path().apply { addRect(Rect(Offset(c.x - w / 2, c.y - h / 2), Size(w, h))) }
            fill(env, 0.22f); stroke(env, sw)
            stroke(Path().apply {
                moveTo(c.x - w / 2, c.y - h / 2); lineTo(c.x, c.y + h * 0.08f); lineTo(c.x + w / 2, c.y - h / 2)
                moveTo(c.x - w / 2, c.y + h / 2); lineTo(c.x, c.y + h * 0.08f); lineTo(c.x + w / 2, c.y + h / 2)
            }, sw * 0.55f, 0.85f)
        }

        Scene.INTERCOM -> {
            val c = Offset(g.cx + g.sep * 1.55f, g.eyesY - g.eh * 0.3f)
            stroke(Path().apply { arcTo(Rect(c, u * 0.08f), 130f, 260f, false) }, sw)
            for (i in 1..3) {
                stroke(Path().apply { arcTo(Rect(c, u * (0.11f + i * 0.045f)), -45f, 90f, false) }, sw * 0.45f, 0.9f - i * 0.18f)
            }
        }

        Scene.DRINK_WATER -> juice(Offset(g.cx, g.mouthY + u * 0.03f), u, t, t in 1.2f..3.4f, ::stroke, ::fill)

        Scene.TAKE_PILL -> {
            juice(Offset(g.cx + u * 0.08f, g.mouthY + u * 0.04f), u * 0.85f, t, t in 2f..3.4f, ::stroke, ::fill)
            val p = Offset(g.cx - u * 0.13f + prog(t, 0.4f, 2.2f) * u * 0.1f, g.mouthY - u * 0.05f)
            val pill = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(p.x - u * 0.035f, p.y - u * 0.015f), Size(u * 0.07f, u * 0.03f)), androidx.compose.ui.geometry.CornerRadius(u * 0.02f))) }
            fill(pill, 0.3f); stroke(pill, sw * 0.7f)
        }

        Scene.EAT_FOOD -> sandwich(Offset(g.cx, g.mouthY + u * 0.04f), u, if (t > 4.2f) 2 else if (t > 2.5f) 1 else 0, ::stroke, ::fill)

        Scene.CAT_CUDDLE -> if (t > 0.9f) {
            val rise = ease(prog(t, 0.9f, 1.5f))
            kitten(Offset(g.cx, g.mouthY + u * (0.42f - 0.2f * rise)), u, t, ::stroke, ::fill)
            // Little purr lines.
            if (t > 1.8f) for (i in 0..2) {
                val k = ((t * 0.7f + i / 3f) % 1f)
                val x = g.cx + u * (0.2f + k * 0.08f)
                val y = g.mouthY + u * (0.12f - k * 0.1f)
                stroke(Path().apply { moveTo(x, y); quadraticTo(x + u * 0.015f, y - u * 0.012f, x + u * 0.03f, y) }, sw * 0.6f, 1f - k)
            }
        }

        Scene.DOG_PAT -> puppy(Offset(g.cx + u * 0.74f, g.mouthY + u * 0.16f), u, t, ::stroke, ::fill)

        Scene.STRETCH_BREAK -> if (t < 4.5f) {
            for (side in intArrayOf(-1, 1)) {
                stroke(Path().apply {
                    moveTo(g.cx + side * u * 0.55f, g.cy - u * 0.38f)
                    quadraticTo(g.cx + side * u * 0.62f, g.cy - u * 0.48f, g.cx + side * u * 0.72f, g.cy - u * 0.43f)
                }, sw, 0.8f)
            }
        }

        Scene.WHERE_ARE_YOU -> {
            val q = Offset(g.cx + g.sep * 1.5f, g.eyesY - g.eh * 1.1f)
            stroke(Path().apply {
                arcTo(Rect(Offset(q.x, q.y - u * 0.015f), u * 0.022f), 180f, 270f, true)
                lineTo(q.x, q.y + u * 0.025f)
            }, sw, 0.6f + 0.4f * sin(t * 3f))
            fill(Path().apply { addOval(Rect(Offset(q.x, q.y + u * 0.05f), u * 0.007f)) }, 1f)
        }

        Scene.SNACK -> if (t < 10.3f) {
            val bites = listOf(2f, 5f, 8f).count { t > it + 0.3f }
            val near = listOf(2f, 5f, 8f).any { t in it - 0.2f..it + 0.6f }
            sandwich(Offset(g.cx, g.mouthY + u * (if (near) 0.1f else 0.24f)), u, bites, ::stroke, ::fill)
        }

        Scene.WX_COLD -> {
            scarf(g, u, t, ::stroke, ::fill)
            val sip = (t in 4f..5.6f) || (t in 10.5f..12.1f)
            coffee(Offset(g.cx, g.mouthY + u * (if (sip) 0.12f else 0.27f)), u, t, ::stroke)
        }

        Scene.WX_RAIN -> umbrella(g, u, t, ::stroke, ::fill)

        Scene.WX_HOT -> sunInCorner(u, t, ::stroke, ::fill)

        Scene.BALL -> {
            val b = if (t < 8f) Life.ballPos(t) else Offset(0f, mix(-1.4f, -0.55f, prog(t, 7.6f, 8f)))
            if (t < 8.6f) {
                val pos = Offset(g.cx + b.x * u * 0.42f, g.eyesY + b.y * u * 0.3f)
                val ball = Path().apply { addOval(Rect(pos, u * 0.03f)) }
                fill(ball, 0.25f); stroke(ball)
                stroke(Path().apply { moveTo(pos.x - u * 0.03f, pos.y); quadraticTo(pos.x, pos.y + u * 0.02f, pos.x + u * 0.03f, pos.y) }, sw * 0.6f, 0.7f)
            }
            if (t in 8f..8.6f) burst(Offset(g.cx, g.eyesY - g.eh * 0.9f), u * 0.08f, ::stroke, 1f - prog(t, 8f, 8.6f))
        }

        Scene.WORKOUT -> if (t < 9.5f) {
            val rep = (t % 1.6f) / 1.6f
            val y = g.cy - u * 0.36f - sin(rep * PI.toFloat()) * u * 0.06f
            val half = u * 0.52f
            stroke(Path().apply { moveTo(g.cx - half, y); lineTo(g.cx + half, y) }, sw * 1.2f)
            for (side in intArrayOf(-1, 1)) {
                val plate = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(g.cx + side * half - u * 0.015f, y - u * 0.045f), Size(u * 0.03f, u * 0.09f)), androidx.compose.ui.geometry.CornerRadius(u * 0.008f)))
                }
                fill(plate, 0.4f); stroke(plate)
            }
            sweat(Offset(g.cx + g.sep + g.ew * 0.7f, g.eyesY - g.eh * 0.3f), u, t, ::fill)
        }

        Scene.MEDITATE -> for (i in 0..2) {
            val ph = ((t / 3f) + i / 3f) % 1f
            stroke(Path().apply { addOval(Rect(Offset(g.cx, g.cy), u * (0.25f + ph * 0.3f))) }, sw * 0.7f, (1f - ph) * 0.35f)
        }

        Scene.SELFIE -> {
            val phone = Offset(g.cx + u * 0.42f, g.cy + u * 0.02f)
            val body = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(phone.x - u * 0.06f, phone.y - u * 0.11f), Size(u * 0.12f, u * 0.22f)), androidx.compose.ui.geometry.CornerRadius(u * 0.018f)))
            }
            stroke(body)
            fill(Path().apply { addOval(Rect(Offset(phone.x, phone.y - u * 0.085f), u * 0.007f)) })
            for (flashAt in floatArrayOf(3.0f, 4.6f)) {
                val f = 1f - prog(t, flashAt, flashAt + 0.35f)
                if (t >= flashAt && f > 0f) drawRect(Color.White.copy(alpha = 0.55f * f * f), topLeft = Offset(-size.width, -size.height), size = Size(size.width * 3, size.height * 3))
            }
        }

        Scene.MOVIE -> {
            val bucket = Offset(g.cx - u * 0.02f, g.mouthY + u * 0.22f)
            val w = u * 0.12f
            val h = u * 0.1f
            stroke(Path().apply {
                moveTo(bucket.x - w / 2, bucket.y - h / 2); lineTo(bucket.x + w / 2, bucket.y - h / 2)
                lineTo(bucket.x + w * 0.38f, bucket.y + h / 2); lineTo(bucket.x - w * 0.38f, bucket.y + h / 2); close()
            })
            for (i in -1..1) stroke(Path().apply { moveTo(bucket.x + i * w * 0.2f, bucket.y - h * 0.4f); lineTo(bucket.x + i * w * 0.16f, bucket.y + h * 0.4f) }, sw * 0.5f, 0.5f)
            val pops = if (t in 6f..7.5f) 7 else 4
            for (i in 0 until pops) {
                val flyK = if (t in 6f..7.5f) prog(t, 6f, 7.5f) else 0f
                val ang = i * 1.3f
                val p = Offset(bucket.x + (i - pops / 2f) * w * 0.15f + cos(ang) * flyK * u * 0.15f, bucket.y - h * 0.6f - sin(ang).let { abs(it) } * flyK * u * 0.15f)
                fill(Path().apply { addOval(Rect(p, u * 0.012f)) }, 0.9f)
            }
            // Flickering movie light on the face.
            drawRect(c.copy(alpha = 0.04f * (0.5f + 0.5f * sin(t * 13f) * sin(t * 7f))), topLeft = Offset(-size.width, -size.height), size = Size(size.width * 3, size.height * 3))
        }

        Scene.RAIN_CLOUD -> {
            val slide = ease(prog(t, 6f, 7.5f))
            val cc = Offset(g.cx + slide * u * 0.6f, g.eyesY - g.eh * 1.6f)
            var cloud = Path()
            for (i in -1..1) cloud = Path.combine(PathOperation.Union, cloud, Path().apply { addOval(Rect(Offset(cc.x + i * u * 0.05f, cc.y - (1 - abs(i)) * u * 0.02f), u * 0.045f)) })
            fill(cloud, 0.2f * (1f - slide)); stroke(cloud, sw, 1f - slide)
            if (t < 6.5f) for (i in 0 until 6) {
                val ph = ((t * 1.8f) + i * 0.37f) % 1f
                val x = cc.x - u * 0.08f + i * u * 0.032f
                val y = cc.y + u * 0.04f + ph * g.eh * 1.4f
                stroke(Path().apply { moveTo(x, y); lineTo(x - u * 0.006f, y + u * 0.025f) }, sw * 0.6f, 0.7f)
            }
            if (t > 6f) {
                val sun = Offset(g.cx - u * 0.35f, g.eyesY - g.eh * 1.5f)
                val r = u * 0.04f * ease(prog(t, 6f, 6.8f))
                fill(Path().apply { addOval(Rect(sun, r)) }, 0.5f)
                for (i in 0 until 8) {
                    val a = i * PI.toFloat() / 4f + t
                    stroke(Path().apply { moveTo(sun.x + cos(a) * r * 1.4f, sun.y + sin(a) * r * 1.4f); lineTo(sun.x + cos(a) * r * 2f, sun.y + sin(a) * r * 2f) }, sw * 0.6f)
                }
            }
        }

        Scene.STARGAZE -> {
            val rnd = Random(seed)
            for (i in 0 until 9) {
                val p = Offset(g.cx + (rnd.nextFloat() - 0.5f) * u * 1.1f, g.eyesY - g.eh * (1.2f + rnd.nextFloat() * 1.6f))
                val tw = 0.35f + 0.65f * abs(sin(t * (1.5f + rnd.nextFloat() * 2f) + i))
                fill(star(p, u * (0.008f + rnd.nextFloat() * 0.01f)), tw)
            }
            if (t in 5.6f..6.6f) {
                val k = prog(t, 5.6f, 6.6f)
                val head = Offset(g.cx - u * 0.4f + k * u * 0.8f, g.eyesY - g.eh * 2.4f + k * u * 0.15f)
                stroke(Path().apply { moveTo(head.x - u * 0.12f, head.y - u * 0.025f); lineTo(head.x, head.y) }, sw, 1f - k * 0.5f)
                fill(star(head, u * 0.018f))
            }
        }


        Scene.HEARD, Scene.WAVE_BACK, Scene.SHY, Scene.TOO_CLOSE, Scene.FOUND_YOU, Scene.HEAD_SHAKE, Scene.HEAD_NOD,
        Scene.SLEEP_PEEK, Scene.YAWN, Scene.BORED, Scene.WAKE_GROGGY, Scene.PEEKABOO, Scene.STRETCH -> Unit
    }
}

// ----------------------------------------------------------------- individual props

private typealias Pen = (Path, Float, Float) -> Unit
private typealias Fill = (Path, Float) -> Unit

private fun DrawScope.gamepad(c: Offset, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val w = u * 0.34f
    val h = u * 0.1f
    val body = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(c.x - w / 2, c.y - h / 2), Size(w, h)), androidx.compose.ui.geometry.CornerRadius(h / 2)))
        addOval(Rect(Offset(c.x - w / 2 + h * 0.35f, c.y + h * 0.25f), h * 0.5f))
        addOval(Rect(Offset(c.x + w / 2 - h * 0.35f, c.y + h * 0.25f), h * 0.5f))
    }
    stroke(body, u * 0.011f, 1f)
    val d = Offset(c.x - w * 0.28f, c.y)
    val a = h * 0.22f
    stroke(Path().apply { moveTo(d.x - a, d.y); lineTo(d.x + a, d.y); moveTo(d.x, d.y - a); lineTo(d.x, d.y + a) }, u * 0.012f, 1f)
    val rnd = Random((t * 6f).toInt())
    for (i in 0..1) {
        val b = Offset(c.x + w * (0.22f + 0.1f * i), c.y + h * (if (i == 0) 0.1f else -0.15f))
        fill(Path().apply { addOval(Rect(b, h * 0.13f)) }, if (rnd.nextFloat() < 0.4f) 1f else 0.35f)
    }
}

private fun DrawScope.openBook(c: Offset, u: Float, t: Float, stroke: Pen) {
    val w = u * 0.17f
    val h = u * 0.12f
    val spine = Offset(c.x, c.y + h * 0.08f)
    for (side in intArrayOf(-1, 1)) {
        val page = Path().apply {
            moveTo(spine.x, spine.y - h / 2)
            quadraticTo(spine.x + side * w * 0.5f, spine.y - h / 2 - h * 0.12f, spine.x + side * w, spine.y - h / 2)
            lineTo(spine.x + side * w, spine.y + h / 2)
            quadraticTo(spine.x + side * w * 0.5f, spine.y + h / 2 - h * 0.12f, spine.x, spine.y + h / 2)
            close()
        }
        stroke(page, u * 0.01f, 1f)
        for (l in 1..4) {
            val y = spine.y - h / 2 + h * l / 5.5f
            stroke(Path().apply { moveTo(spine.x + side * w * 0.18f, y); lineTo(spine.x + side * w * 0.85f, y) }, u * 0.004f, 0.5f)
        }
    }
    // Page flip every 5 s.
    val f = prog(t % 5f, 4.2f, 4.9f)
    if (f in 0.01f..0.99f) {
        val x = spine.x + w * cos(f * PI.toFloat())
        val lift = sin(f * PI.toFloat()) * h * 0.4f
        stroke(Path().apply {
            moveTo(spine.x, spine.y - h / 2); quadraticTo((spine.x + x) / 2, spine.y - h / 2 - lift, x, spine.y - h / 2 - lift * 0.3f)
            lineTo(x, spine.y + h / 2 - lift * 0.3f); quadraticTo((spine.x + x) / 2, spine.y + h / 2 - lift, spine.x, spine.y + h / 2)
        }, u * 0.008f, 0.8f)
    }
}

private fun DrawScope.notebook(c: Offset, u: Float, t: Float, stroke: Pen) {
    val w = u * 0.26f
    val h = u * 0.12f
    stroke(Path().apply { addRect(Rect(Offset(c.x - w / 2, c.y - h / 2), Size(w, h))) }, u * 0.01f, 1f)
    for (l in 1..3) {
        val y = c.y - h / 2 + h * l / 4f
        stroke(Path().apply { moveTo(c.x - w * 0.42f, y); lineTo(c.x + w * 0.42f, y) }, u * 0.004f, 0.4f)
    }
    // Pencil scribbling along the line.
    val px = c.x - w * 0.35f + ((t * 0.12f) % 1f) * w * 0.7f + sin(t * 25f) * u * 0.006f
    val py = c.y - h * 0.05f + cos(t * 19f) * u * 0.004f
    stroke(Path().apply { moveTo(px, py); lineTo(px + u * 0.05f, py - u * 0.07f) }, u * 0.012f, 1f)
}

/** Round kitten held against the chest: ears, closed happy eyes, whiskers, paws over the arms, swishing tail. */
private fun DrawScope.kitten(c: Offset, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val r = u * 0.085f
    val head = Path().apply { addOval(Rect(c, r)) }
    fill(head, 0.15f); stroke(head, u * 0.01f, 1f)
    for (side in intArrayOf(-1, 1)) {
        stroke(Path().apply {
            moveTo(c.x + side * r * 0.45f, c.y - r * 0.85f)
            lineTo(c.x + side * r * 0.85f, c.y - r * 1.45f)
            lineTo(c.x + side * r * 0.95f, c.y - r * 0.45f)
        }, u * 0.01f, 1f)
        // Closed, content eyes.
        stroke(Path().apply {
            moveTo(c.x + side * r * 0.5f, c.y - r * 0.05f)
            quadraticTo(c.x + side * r * 0.35f, c.y - r * 0.25f, c.x + side * r * 0.2f, c.y - r * 0.05f)
        }, u * 0.007f, 1f)
        for (k in 0..1) stroke(Path().apply {
            moveTo(c.x + side * r * 0.35f, c.y + r * (0.3f + k * 0.15f)); lineTo(c.x + side * r * 1.15f, c.y + r * (0.2f + k * 0.3f))
        }, u * 0.004f, 0.7f)
    }
    stroke(Path().apply { moveTo(c.x - r * 0.1f, c.y + r * 0.25f); quadraticTo(c.x, c.y + r * 0.4f, c.x + r * 0.1f, c.y + r * 0.25f) }, u * 0.006f, 1f)
    val tail = sin(t * 3f) * r * 0.5f
    stroke(Path().apply {
        moveTo(c.x + r * 0.9f, c.y + r * 1.3f)
        quadraticTo(c.x + r * 1.9f, c.y + r * 1.1f + tail, c.x + r * 1.7f, c.y + r * 0.2f + tail)
    }, u * 0.01f, 1f)
}

/** Puppy sitting beside the robot, tongue out, tail wagging. */
private fun DrawScope.puppy(c: Offset, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val s = u * 0.1f
    val body = Path().apply { addOval(Rect(Offset(c.x - s * 0.8f, c.y - s * 0.4f), Size(s * 1.6f, s * 1.5f))) }
    fill(body, 0.12f); stroke(body, u * 0.01f, 1f)
    val head = Offset(c.x - s * 0.15f, c.y - s * 0.95f)
    val hp = Path().apply { addOval(Rect(head, s * 0.62f)) }
    fill(hp, 0.15f); stroke(hp, u * 0.01f, 1f)
    // Floppy ears.
    for (side in intArrayOf(-1, 1)) stroke(Path().apply {
        moveTo(head.x + side * s * 0.45f, head.y - s * 0.4f)
        quadraticTo(head.x + side * s * 0.95f, head.y - s * 0.1f, head.x + side * s * 0.65f, head.y + s * 0.45f)
    }, u * 0.011f, 1f)
    for (side in intArrayOf(-1, 1)) fill(Path().apply { addOval(Rect(Offset(head.x + side * s * 0.22f, head.y - s * 0.08f), s * 0.07f)) }, 1f)
    fill(Path().apply { addOval(Rect(Offset(head.x, head.y + s * 0.18f), s * 0.1f)) }, 1f)
    val pant = 0.5f + 0.5f * sin(t * 12f)
    stroke(Path().apply { moveTo(head.x + s * 0.05f, head.y + s * 0.32f); lineTo(head.x + s * 0.08f, head.y + s * (0.45f + 0.08f * pant)) }, u * 0.012f, 1f)
    val wag = sin(t * 14f) * s * 0.35f
    stroke(Path().apply {
        moveTo(c.x + s * 0.75f, c.y + s * 0.6f)
        quadraticTo(c.x + s * 1.3f, c.y + s * 0.4f, c.x + s * 1.4f + wag, c.y - s * 0.1f)
    }, u * 0.01f, 1f)
}

private fun DrawScope.lightbulb(c: Offset, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val r = u * 0.035f * (0.7f + 0.3f * ease(min(1f, t * 3f)))
    val bulb = Path().apply { addOval(Rect(c, r)) }
    fill(bulb, 0.25f); stroke(bulb, u * 0.009f, 1f)
    stroke(Path().apply {
        moveTo(c.x - r * 0.45f, c.y + r * 1.1f); lineTo(c.x + r * 0.45f, c.y + r * 1.1f)
        moveTo(c.x - r * 0.35f, c.y + r * 1.35f); lineTo(c.x + r * 0.35f, c.y + r * 1.35f)
    }, u * 0.008f, 1f)
    val rays = 0.5f + 0.5f * sin(t * 10f)
    for (i in 0..4) {
        val a = (-PI / 2 + (i - 2) * 0.5).toFloat()
        stroke(Path().apply {
            moveTo(c.x + cos(a) * r * 1.4f, c.y + sin(a) * r * 1.4f); lineTo(c.x + cos(a) * r * 1.9f, c.y + sin(a) * r * 1.9f)
        }, u * 0.007f, rays)
    }
}

private fun DrawScope.dog(g: FaceGeometry, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val jump = hop(t, 7.5f, 1.1f)
    // Walks on the robot's left (drawn mirrored so it faces outward); leash in the left hand.
    val base = Offset(g.cx - u * 0.66f, g.mouthY + u * 0.2f - jump * u * 0.06f)
    val s = u * 0.06f
    // Ground scrolling under the paws.
    for (i in 0..4) {
        val x = g.cx - u * 0.9f + ((i * 0.1f + t * 0.25f) % 0.5f + 0.5f) % 0.5f * u
        stroke(Path().apply { moveTo(x, g.mouthY + u * 0.2f + s * 1.25f); lineTo(x + u * 0.03f, g.mouthY + u * 0.2f + s * 1.25f) }, u * 0.005f, 0.5f)
    }
    scale(-1f, 1f, base) {
    val body = Path().apply { addOval(Rect(Offset(base.x - s, base.y - s * 0.45f), Size(s * 2f, s * 0.9f))) }
    stroke(body, u * 0.01f, 1f)
    val head = Offset(base.x + s * 1.15f, base.y - s * 0.55f)
    stroke(Path().apply { addOval(Rect(head, s * 0.42f)) }, u * 0.01f, 1f)
    fill(Path().apply { addOval(Rect(Offset(head.x + s * 0.15f, head.y - s * 0.08f), s * 0.07f)) }, 1f)
    // Floppy ear.
    stroke(Path().apply { moveTo(head.x - s * 0.15f, head.y - s * 0.35f); quadraticTo(head.x - s * 0.5f, head.y - s * 0.1f, head.x - s * 0.3f, head.y + s * 0.25f) }, u * 0.009f, 1f)
    // Legs trotting.
    val step = sin(t * 11f)
    for ((i, x) in listOf(-0.6f, -0.3f, 0.35f, 0.65f).withIndex()) {
        val k = if (i % 2 == 0) step else -step
        stroke(Path().apply { moveTo(base.x + x * s, base.y + s * 0.3f); lineTo(base.x + x * s + k * s * 0.25f, base.y + s * 1.2f) }, u * 0.009f, 1f)
    }
    // Wagging tail.
    val wag = sin(t * 16f) * s * 0.3f
    stroke(Path().apply { moveTo(base.x - s, base.y - s * 0.2f); quadraticTo(base.x - s * 1.4f, base.y - s * 0.6f + wag, base.x - s * 1.5f, base.y - s * 1f + wag) }, u * 0.009f, 1f)
    if (jump > 0.1f) exclamation(Offset(head.x + s * 0.6f, head.y - s * 1.1f), u * 0.6f, t - 7.5f, stroke, fill)
    }
    // Leash from the robot's left hand to the collar (collar is mirrored to the dog's right side on screen).
    val collar = Offset(base.x - s * 1.15f + s * 0.3f, base.y - s * 0.55f + s * 0.3f)
    val from = Offset(g.cx - u * 0.5f, g.cy + u * 0.36f)
    stroke(Path().apply { moveTo(from.x, from.y); quadraticTo((from.x + collar.x) / 2, max(from.y, collar.y) + u * 0.06f, collar.x, collar.y) }, u * 0.005f, 0.8f)
}

private fun DrawScope.headphones(g: FaceGeometry, u: Float, stroke: Pen, fill: Fill) {
    val cupX = g.sep + g.ew * 0.95f
    val y = g.eyesY + g.eh * 0.1f
    stroke(Path().apply {
        moveTo(g.cx - cupX, y)
        cubicTo(g.cx - cupX, g.eyesY - g.eh * 1.8f, g.cx + cupX, g.eyesY - g.eh * 1.8f, g.cx + cupX, y)
    }, u * 0.014f, 1f)
    for (side in intArrayOf(-1, 1)) {
        val cup = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(g.cx + side * cupX - u * 0.025f, y - u * 0.05f), Size(u * 0.05f, u * 0.1f)), androidx.compose.ui.geometry.CornerRadius(u * 0.02f)))
        }
        fill(cup, 0.35f); stroke(cup, u * 0.01f, 1f)
    }
}

private fun DrawScope.notes(origin: Offset, u: Float, t: Float, count: Int, stroke: Pen, fill: Fill) {
    for (i in 0 until count) {
        val ph = ((t / 2.4f) + i.toFloat() / count) % 1f
        val p = Offset(origin.x + ph * u * 0.12f + sin(ph * 8f) * u * 0.015f, origin.y - ph * u * 0.25f)
        val a = sin(ph * PI.toFloat())
        val s = u * 0.022f
        fill(Path().apply { addOval(Rect(Offset(p.x - s, p.y - s * 0.7f), Size(s * 1.6f, s * 1.2f))) }, a)
        stroke(Path().apply { moveTo(p.x + s * 0.55f, p.y); lineTo(p.x + s * 0.55f, p.y - s * 2.6f); quadraticTo(p.x + s * 1.4f, p.y - s * 2.2f, p.x + s * 1.3f, p.y - s * 1.5f) }, u * 0.006f, a)
    }
}

/** Tall glass of juice with a bendy straw and ice cubes; the level drops while sipping. */
private fun DrawScope.juice(c: Offset, u: Float, t: Float, sipping: Boolean, stroke: Pen, fill: Fill) {
    val w = u * 0.08f
    val h = u * 0.13f
    val glass = Path().apply {
        moveTo(c.x - w / 2, c.y - h / 2); lineTo(c.x + w / 2, c.y - h / 2)
        lineTo(c.x + w * 0.38f, c.y + h / 2); lineTo(c.x - w * 0.38f, c.y + h / 2); close()
    }
    val level = 0.25f + 0.1f * (1f - ((t % 12f) / 12f)) + if (sipping) -0.03f * sin(t * 8f) else 0f
    val liquid = Path().apply {
        val y = c.y - h / 2 + h * level
        moveTo(c.x - w * 0.46f, y); lineTo(c.x + w * 0.46f, y)
        lineTo(c.x + w * 0.37f, c.y + h / 2 - u * 0.004f); lineTo(c.x - w * 0.37f, c.y + h / 2 - u * 0.004f); close()
    }
    fill(liquid, 0.3f)
    stroke(glass, u * 0.009f, 1f)
    for (i in 0..1) {
        val ice = Offset(c.x - w * 0.15f + i * w * 0.28f, c.y - h * 0.05f + i * h * 0.12f + sin(t * 2f + i) * u * 0.003f)
        stroke(Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset(ice.x - u * 0.012f, ice.y - u * 0.012f), Size(u * 0.024f, u * 0.024f))) }, u * 0.005f, 0.8f)
    }
    // Bendy straw towards the mouth.
    stroke(Path().apply {
        moveTo(c.x + w * 0.1f, c.y + h * 0.3f)
        lineTo(c.x + w * 0.05f, c.y - h * 0.62f)
        quadraticTo(c.x + w * 0.02f, c.y - h * 0.78f, c.x - w * 0.25f, c.y - h * 0.82f)
    }, u * 0.01f, 1f)
    // Lemon slice on the rim.
    stroke(Path().apply { arcTo(androidx.compose.ui.geometry.Rect(Offset(c.x + w * 0.5f, c.y - h / 2), u * 0.022f), 180f, 180f, false) }, u * 0.007f, 0.9f)
}

private fun DrawScope.coffee(c: Offset, u: Float, t: Float, stroke: Pen) {
    val w = u * 0.09f
    val h = u * 0.09f
    stroke(Path().apply {
        moveTo(c.x - w / 2, c.y - h / 2); lineTo(c.x + w / 2, c.y - h / 2)
        lineTo(c.x + w * 0.4f, c.y + h / 2); lineTo(c.x - w * 0.4f, c.y + h / 2); close()
    }, u * 0.01f, 1f)
    stroke(Path().apply { arcTo(Rect(Offset(c.x + w * 0.48f, c.y - h * 0.05f), h * 0.22f), -90f, 180f, true) }, u * 0.009f, 1f)
    for (i in -1..1) {
        val x = c.x + i * w * 0.25f
        val ph = (t * 0.8f + i * 0.3f) % 1f
        stroke(Path().apply {
            moveTo(x, c.y - h * 0.65f)
            cubicTo(x + u * 0.015f, c.y - h * 0.85f, x - u * 0.015f, c.y - h * 1.05f, x + sin(t * 3f + i) * u * 0.008f, c.y - h * 1.3f)
        }, u * 0.006f, 0.25f + 0.5f * sin(ph * PI.toFloat()))
    }
}

/** Thought cloud above-right of the head with the little bubbles leading to it. Returns its center. */
private fun DrawScope.dreamCloud(g: FaceGeometry, t: Float, seconds: Float, stroke: Pen): Offset {
    val u = g.unit
    val a = min(prog(t, 0f, 1f), 1f - prog(t, seconds - 1f, seconds))
    val center = Offset(g.cx + u * 0.28f, g.eyesY - u * 0.27f + sin(t * 1.2f) * u * 0.006f)
    var cloud = Path()
    val r = u * 0.075f
    for (i in 0 until 7) {
        val ang = i * 2f * PI.toFloat() / 7f
        val p = Offset(center.x + cos(ang) * r * 1.35f, center.y + sin(ang) * r * 0.75f)
        cloud = Path.combine(PathOperation.Union, cloud, Path().apply { addOval(Rect(p, r * 0.65f)) })
    }
    cloud = Path.combine(PathOperation.Union, cloud, Path().apply { addOval(Rect(Offset(center.x - r * 1.4f, center.y - r * 0.8f), Size(r * 2.8f, r * 1.6f))) })
    stroke(cloud, u * 0.009f, a)
    for (i in 0..2) {
        val k = (i + 1) / 4f
        val p = Offset(mix(g.cx + g.sep + g.ew * 0.2f, center.x - r, k), mix(g.eyesY - g.eh * 0.4f, center.y + r * 0.8f, k))
        stroke(Path().apply { addOval(Rect(p, u * (0.006f + 0.004f * i))) }, u * 0.006f, a * prog(t, i * 0.3f, i * 0.3f + 0.3f))
    }
    return center
}

private fun heart(c: Offset, s: Float) = Path().apply {
    val y = c.y - s * 0.1f
    moveTo(c.x, y + s * 0.45f)
    cubicTo(c.x - s * 0.95f, y - s * 0.05f, c.x - s * 0.38f, y - s * 0.78f, c.x, y - s * 0.25f)
    cubicTo(c.x + s * 0.38f, y - s * 0.78f, c.x + s * 0.95f, y - s * 0.05f, c.x, y + s * 0.45f)
    close()
}

private fun DrawScope.pizza(c: Offset, s: Float, t: Float, stroke: Pen, fill: Fill) {
    val wob = sin(t * 2f) * 6f
    val tip = Offset(c.x + sin(wob * PI.toFloat() / 180f) * s * 0.1f, c.y + s * 0.7f)
    val slice = Path().apply {
        moveTo(tip.x, tip.y); lineTo(c.x - s * 0.7f, c.y - s * 0.45f)
        quadraticTo(c.x, c.y - s * 0.75f, c.x + s * 0.7f, c.y - s * 0.45f); close()
    }
    stroke(slice, s * 0.12f, 1f)
    for (p in listOf(Offset(-0.2f, -0.2f), Offset(0.22f, -0.15f), Offset(0f, 0.2f))) {
        fill(Path().apply { addOval(Rect(Offset(c.x + p.x * s, c.y + p.y * s), s * 0.11f)) }, 0.9f)
    }
}

private fun DrawScope.sheep(c: Offset, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val s = u * 0.025f
    // Fence.
    stroke(Path().apply {
        moveTo(c.x - s * 0.8f, c.y + s * 1.6f); lineTo(c.x - s * 0.8f, c.y + s * 0.2f)
        moveTo(c.x + s * 0.8f, c.y + s * 1.6f); lineTo(c.x + s * 0.8f, c.y + s * 0.2f)
        moveTo(c.x - s * 1.2f, c.y + s * 0.6f); lineTo(c.x + s * 1.2f, c.y + s * 0.6f)
    }, u * 0.006f, 0.8f)
    val ph = (t % 2.5f) / 2.5f
    val x = mix(c.x - u * 0.09f, c.x + u * 0.09f, ph)
    val y = c.y + s * 0.6f - sin(ph * PI.toFloat()) * s * 2.4f
    val body = Path().apply {
        for (i in 0..4) addOval(Rect(Offset(x - s * 0.6f + i * s * 0.3f, y - s * 0.35f + (i % 2) * s * 0.15f), s * 0.42f))
    }
    fill(body, 0.3f); stroke(body, u * 0.005f, 1f)
    fill(Path().apply { addOval(Rect(Offset(x + s * 0.85f, y - s * 0.1f), s * 0.25f)) }, 1f)
}

/** A sandwich (bread, lettuce, cheese) with bite marks taken out of the right corner. */
private fun DrawScope.sandwich(c: Offset, u: Float, bites: Int, stroke: Pen, fill: Fill) {
    val w = u * 0.2f
    val h = u * 0.09f
    var shape = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(c.x - w / 2, c.y - h / 2), Size(w, h)), androidx.compose.ui.geometry.CornerRadius(h * 0.45f)))
    }
    repeat(bites) { b ->
        val bc = Offset(c.x + w / 2 - b * w * 0.16f, c.y - h * 0.5f + b * h * 0.12f)
        val bite = Path().apply {
            addOval(Rect(bc, h * 0.38f))
            addOval(Rect(Offset(bc.x - h * 0.3f, bc.y + h * 0.05f), h * 0.3f))
        }
        shape = Path.combine(PathOperation.Difference, shape, bite)
    }
    fill(shape, 0.3f)
    stroke(shape, u * 0.009f, 1f)
    // Filling layers (wavy lettuce + cheese line), clipped to what is left.
    val lettuce = Path().apply {
        moveTo(c.x - w * 0.48f, c.y - h * 0.05f)
        for (k in 1..12) lineTo(c.x - w * 0.48f + k * w * 0.08f, c.y - h * 0.05f + (if (k % 2 == 0) -1 else 1) * h * 0.08f)
    }
    clipPath(shape) {
        stroke(lettuce, u * 0.007f, 0.9f)
        stroke(Path().apply { moveTo(c.x - w * 0.48f, c.y + h * 0.18f); lineTo(c.x + w * 0.48f, c.y + h * 0.18f) }, u * 0.006f, 0.7f)
    }
}

/** Striped knitted scarf around the "neck" with a hanging end. */
private fun DrawScope.scarf(g: FaceGeometry, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val y = g.mouthY + u * 0.13f
    val half = u * 0.3f
    val th = u * 0.055f
    val band = Path().apply {
        moveTo(g.cx - half, y - th / 2)
        quadraticTo(g.cx, y + u * 0.04f - th / 2, g.cx + half, y - th / 2)
        lineTo(g.cx + half, y + th / 2)
        quadraticTo(g.cx, y + u * 0.04f + th / 2, g.cx - half, y + th / 2)
        close()
    }
    fill(band, 0.35f); stroke(band, u * 0.009f, 1f)
    for (i in -3..3) {
        val x = g.cx + i * half / 3.5f
        val yy = y + u * 0.04f * (1f - (i / 3.5f) * (i / 3.5f))
        stroke(Path().apply { moveTo(x, yy - th * 0.4f); lineTo(x, yy + th * 0.4f) }, u * 0.007f, 0.7f)
    }
    // Hanging end, swaying a little in the cold wind.
    val sway = sin(t * 2.2f) * u * 0.01f
    val tail = Path().apply {
        moveTo(g.cx + half * 0.55f, y)
        lineTo(g.cx + half * 0.55f + th, y)
        lineTo(g.cx + half * 0.6f + th + sway, y + u * 0.15f)
        lineTo(g.cx + half * 0.6f + sway, y + u * 0.15f)
        close()
    }
    fill(tail, 0.35f); stroke(tail, u * 0.009f, 1f)
    for (k in 0..3) {
        val fx = g.cx + half * 0.6f + sway + k * th / 3f
        stroke(Path().apply { moveTo(fx, y + u * 0.15f); lineTo(fx, y + u * 0.175f) }, u * 0.005f, 0.8f)
    }
}

/** Umbrella geometry shared with the rain renderer (so drops stop at the canopy). Relative to the face center. */
internal object UmbrellaGeo {
    const val SHAFT_X = 0.38f
    const val RADIUS = 0.58f
    const val TOP_Y = -0.52f
    const val HANDLE_Y = 0.24f
    val rimY get() = TOP_Y + RADIUS * 0.42f
}

/** Umbrella held upright by a straight shaft just outside the right eye; drops bounce off the canopy. */
private fun DrawScope.umbrella(g: FaceGeometry, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val top = Offset(g.cx + u * UmbrellaGeo.SHAFT_X, g.cy + u * UmbrellaGeo.TOP_Y)
    val r = u * UmbrellaGeo.RADIUS
    val rimY = g.cy + u * UmbrellaGeo.rimY
    val canopy = Path().apply {
        moveTo(top.x - r, rimY)
        cubicTo(top.x - r, top.y - r * 0.05f, top.x + r, top.y - r * 0.05f, top.x + r, rimY)
        val n = 6
        for (k in n - 1 downTo 0) {
            val x0 = top.x - r + (k + 1) * 2 * r / n
            val x1 = top.x - r + k * 2 * r / n
            quadraticTo((x0 + x1) / 2, rimY - r * 0.1f, x1, rimY)
        }
        close()
    }
    fill(canopy, 0.25f); stroke(canopy, u * 0.011f, 1f)
    for (k in 1..5) {
        val x = top.x - r + k * 2 * r / 6
        stroke(Path().apply { moveTo(top.x, top.y + r * 0.02f); quadraticTo((top.x + x) / 2, top.y + r * 0.12f, x, rimY - r * 0.05f) }, u * 0.005f, 0.6f)
    }
    stroke(Path().apply { moveTo(top.x, top.y); lineTo(top.x, top.y - u * 0.03f) }, u * 0.01f, 1f)
    // Straight vertical shaft and a J handle.
    val handle = Offset(top.x, g.cy + u * UmbrellaGeo.HANDLE_Y)
    stroke(Path().apply {
        moveTo(top.x, rimY)
        lineTo(handle.x, handle.y)
        quadraticTo(handle.x, handle.y + u * 0.06f, handle.x - u * 0.05f, handle.y + u * 0.045f)
    }, u * 0.011f, 1f)
    // Splashes on the canopy.
    for (k in 0..4) {
        val ph = ((t * 1.3f) + k * 0.23f) % 1f
        if (ph < 0.25f) {
            val x = top.x - r * 0.75f + k * r * 0.37f
            val y = top.y + r * 0.08f + kotlin.math.abs(x - top.x) / r * r * 0.3f
            val q = ph / 0.25f
            stroke(Path().apply { moveTo(x - u * 0.01f * q, y - u * 0.012f * q); lineTo(x - u * 0.018f * q, y - u * 0.022f * q) }, u * 0.004f, 1f - q)
            stroke(Path().apply { moveTo(x + u * 0.01f * q, y - u * 0.012f * q); lineTo(x + u * 0.018f * q, y - u * 0.022f * q) }, u * 0.004f, 1f - q)
        }
    }
}

/** A little smiling sun with rotating rays in the top-left corner of the screen. */
private fun DrawScope.sunInCorner(u: Float, t: Float, stroke: Pen, fill: Fill) {
    val r = minOf(size.width, size.height) * 0.05f
    val c = Offset(r * 2.6f, r * 2.6f)
    val disc = Path().apply { addOval(Rect(c, r)) }
    fill(disc, 0.35f); stroke(disc, u * 0.009f, 1f)
    for (i in 0 until 10) {
        val a = i * 2f * PI.toFloat() / 10f + t * 0.6f
        val l = if (i % 2 == 0) 1.75f else 1.5f
        stroke(Path().apply { moveTo(c.x + cos(a) * r * 1.3f, c.y + sin(a) * r * 1.3f); lineTo(c.x + cos(a) * r * l, c.y + sin(a) * r * l) }, u * 0.008f, 1f)
    }
    // Sunglasses on the sun too.
    stroke(Path().apply { moveTo(c.x - r * 0.55f, c.y - r * 0.15f); lineTo(c.x + r * 0.55f, c.y - r * 0.15f) }, u * 0.006f, 1f)
    fill(Path().apply { addOval(Rect(Offset(c.x - r * 0.28f, c.y - r * 0.05f), r * 0.22f)); addOval(Rect(Offset(c.x + r * 0.28f, c.y - r * 0.05f), r * 0.22f)) }, 1f)
    stroke(Path().apply { moveTo(c.x - r * 0.35f, c.y + r * 0.4f); quadraticTo(c.x, c.y + r * 0.65f, c.x + r * 0.35f, c.y + r * 0.4f) }, u * 0.006f, 1f)
}

private fun DrawScope.sweat(c: Offset, u: Float, t: Float, fill: Fill) {
    val ph = (t % 1.2f) / 1.2f
    val p = Offset(c.x + ph * u * 0.01f, c.y + ph * u * 0.06f)
    val r = u * 0.014f
    val drop = Path().apply {
        moveTo(p.x, p.y - r * 1.8f)
        quadraticTo(p.x + r * 1.1f, p.y, p.x, p.y + r)
        quadraticTo(p.x - r * 1.1f, p.y, p.x, p.y - r * 1.8f)
        close()
    }
    fill(drop, 1f - ph)
}

private fun DrawScope.exclamation(c: Offset, u: Float, t: Float, stroke: Pen, fill: Fill) {
    val pop = 1f + 0.4f * exp(-t * 6f) * sin(t * 25f)
    val h = u * 0.06f * pop
    stroke(Path().apply { moveTo(c.x, c.y - h / 2); lineTo(c.x, c.y + h * 0.15f) }, u * 0.016f, 1f)
    fill(Path().apply { addOval(Rect(Offset(c.x, c.y + h * 0.45f), u * 0.009f * pop)) }, 1f)
}

private fun DrawScope.burst(c: Offset, r: Float, stroke: Pen, a: Float) {
    for (i in 0 until 8) {
        val ang = i * PI.toFloat() / 4f + 0.3f
        stroke(Path().apply {
            moveTo(c.x + cos(ang) * r * 0.55f, c.y + sin(ang) * r * 0.55f); lineTo(c.x + cos(ang) * r, c.y + sin(ang) * r)
        }, r * 0.06f, a)
    }
}

private fun star(c: Offset, r: Float) = Path().apply {
    for (i in 0 until 10) {
        val rr = if (i % 2 == 0) r else r * 0.45f
        val a = (-PI / 2 + i * PI / 5).toFloat()
        val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

