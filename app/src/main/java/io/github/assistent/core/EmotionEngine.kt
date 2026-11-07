package io.github.assistent.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Continuous emotion state with exponential decay for both the robot and the perceived user. */
class EmotionEngine(private val scope: CoroutineScope) {

    // Robot internal state
    @Volatile var robotValence: Float = 0f      // -1..1
    @Volatile var robotArousal: Float = 0f      // -1..1
    @Volatile var robotCuriosity: Float = 0f    // 0..1
    @Volatile var robotBoredom: Float = 0f      // 0..1, grows with idle time
    @Volatile var robotAnxiety: Float = 0f      // 0..1, grows during long tool calls

    // Perceived user emotional state (face + speech content)
    @Volatile var userValence: Float = 0f       // -1..1
    @Volatile var userArousal: Float = 0f       // -1..1

    @Volatile var lastVocalizationMs: Long = 0L

    init {
        scope.launch {
            while (true) {
                delay(500)
                tick()
            }
        }
    }

    private fun tick() {
        robotValence   = decay(robotValence,   0.97f)
        robotArousal   = decay(robotArousal,   0.97f)
        robotCuriosity = decay(robotCuriosity, 0.95f)
        robotAnxiety   = decay(robotAnxiety,   0.94f)

        if (abs(robotArousal) < 0.2f && robotCuriosity < 0.1f) {
            robotBoredom = (robotBoredom + 0.005f).coerceAtMost(1f)
        } else {
            robotBoredom = decay(robotBoredom, 0.96f)
        }
        if (robotAnxiety > 0.3f) robotValence = (robotValence - robotAnxiety * 0.05f).coerceAtLeast(-1f)
        if (robotCuriosity > 0.3f) robotBoredom = (robotBoredom - robotCuriosity * 0.1f).coerceAtLeast(0f)

        userValence = decay(userValence, 0.993f)
        userArousal = decay(userArousal, 0.993f)
    }

    // --- Robot mutators ---

    fun onToolStarted(toolName: String) {
        robotAnxiety = (robotAnxiety + 0.3f).coerceAtMost(1f)
        if (toolName.contains("search") || toolName.contains("web")) {
            robotCuriosity = (robotCuriosity + 0.15f).coerceAtMost(1f)
        }
    }

    fun onToolSucceeded() {
        robotAnxiety   = (robotAnxiety   - 0.4f).coerceAtLeast(0f)
        robotCuriosity = (robotCuriosity + 0.2f).coerceAtMost(1f)
        robotValence   = (robotValence   + 0.1f).coerceAtMost(1f)
    }

    fun onToolFailed() {
        robotAnxiety = (robotAnxiety + 0.2f).coerceAtMost(1f)
        robotValence = (robotValence - 0.15f).coerceAtLeast(-1f)
    }

    fun onUserSpeech() {
        robotBoredom = (robotBoredom - 0.3f).coerceAtLeast(0f)
        robotArousal = (robotArousal + 0.2f).coerceAtMost(1f)
    }

    fun onPositiveInteraction() {
        robotValence = (robotValence + 0.4f).coerceAtMost(1f)
        robotArousal = (robotArousal + 0.2f).coerceAtMost(1f)
    }

    // --- User mutators ---

    fun onUserSmile(smileProb: Float) {
        val delta = (smileProb - 0.5f) * 0.4f
        userValence = (userValence + delta).coerceIn(-1f, 1f)
    }

    fun onUserEmotionTag(tag: String) {
        when (tag.lowercase().trim()) {
            "happy", "alegre", "animado", "empolgado" -> { userValence = 0.7f; userArousal = 0.5f }
            "sad", "triste", "chateado"               -> { userValence = -0.6f; userArousal = -0.3f }
            "stressed", "estressado", "ansioso"       -> { userValence = -0.4f; userArousal = 0.7f }
            "angry", "bravo", "irritado"              -> { userValence = -0.7f; userArousal = 0.9f }
            "calm", "calmo", "tranquilo"              -> { userValence = 0.2f; userArousal = -0.4f }
            "neutral", "neutro"                       -> { userValence = 0f; userArousal = 0f }
            "humor", "humorado"                       -> { userValence = 0.8f; userArousal = 0.6f }
        }
        if (userValence > 0.3f)  robotValence = (robotValence + userValence * 0.3f).coerceAtMost(1f)
        if (userValence < -0.3f) robotValence = (robotValence + userValence * 0.2f).coerceAtLeast(-1f)
    }

    // --- Query helpers ---

    fun shouldShowHmmBubble(): Boolean {
        val sinceMs = System.currentTimeMillis() - lastVocalizationMs
        return robotAnxiety in 0.2f..0.49f && robotArousal < 0.1f && sinceMs > 12_000L
    }

    fun toDisplayEmotion(): Emotion = when {
        robotValence > 0.4f && robotArousal > 0.3f  -> Emotion.JOY
        robotValence > 0.4f                          -> Emotion.HAPPY
        robotValence < -0.4f && robotArousal > 0.5f -> Emotion.ANGRY
        robotValence < -0.3f                         -> Emotion.SAD
        robotArousal > 0.6f                          -> Emotion.SURPRISED
        robotCuriosity > 0.5f                        -> Emotion.THINKING
        else                                          -> Emotion.NEUTRAL
    }

    /** Jokes are appropriate only when the user seems positive and the robot is not anxious/sad. */
    fun jokesAppropriate(): Boolean =
        userValence > -0.1f && robotValence > -0.1f && userArousal > -0.5f

    private fun decay(v: Float, factor: Float): Float =
        if (v > 0f) (v * factor).coerceAtLeast(0f) else (v * factor).coerceAtMost(0f)
}
