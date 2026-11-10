package io.github.assistent.core

import io.github.assistent.core.EventDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.exp

/**
 * Tracks the ambient "mood" of the house from sounds, interactions and conversations.
 * Operates on exponentially-decaying per-channel rates; computes a stable label with hysteresis.
 * No Android deps — fully testable on JVM.
 */
class HouseMood(private val events: EventDispatcher) {

    data class Mood(
        val label: String,  // alegre | animada | calma | quieta | sonolenta | tensa | triste
        val valence: Float, // -1..1 (negative = tense/sad, positive = happy/lively)
        val arousal: Float, // 0..1 (0 = calm/quiet, 1 = excited/intense)
        val sinceMs: Long = System.currentTimeMillis(),
    )

    private val _mood = MutableStateFlow(Mood("calma", 0f, 0.1f))
    val mood: StateFlow<Mood> = _mood

    // Decaying rate channels (events per minute, exponential decay τ = 5 min)
    private var speechRate = 0f      // spoken speech detected
    private var laughRate = 0f       // laughter
    private var tensionRate = 0f     // baby cry, storm, rapid-fire speech
    private var livelyRate = 0f      // music, applause
    private var sadRate = 0f         // sad emotion tags from brain

    private var lastTickMs = System.currentTimeMillis()
    private var labelChangedAt = 0L
    private var pendingLabel = ""
    private val HYSTERESIS_MS = 5 * 60_000L // 5 min stable before label commits

    // --- input hooks ---

    fun onSound(kind: String, score: Float) {
        when (kind) {
            "speech" -> speechRate += score * 0.5f
            "laughter" -> laughRate += score
            "music" -> livelyRate += score * 0.6f
            "applause" -> livelyRate += score * 0.4f
            "baby_cry" -> tensionRate += score * 0.8f
            "thunder", "storm" -> tensionRate += score * 0.3f
            "sneeze" -> { /* no mood impact */ }
        }
        recompute()
    }

    fun onInteraction(kind: String) {
        when (kind) {
            "pet", "giggle", "purr" -> laughRate += 0.4f
            "wave", "love" -> livelyRate += 0.3f
            "conversation" -> speechRate += 0.3f
        }
        recompute()
    }

    fun onUserEmotionTag(tag: String) {
        when (tag) {
            "happy", "joy", "love" -> laughRate += 0.5f
            "sad" -> sadRate += 0.6f
            "angry" -> tensionRate += 0.5f
            "surprised" -> livelyRate += 0.2f
        }
        recompute()
    }

    // --- tick: decay all channels (call periodically, e.g. every 30 s) ---

    fun tick(nowMs: Long = System.currentTimeMillis()) {
        val dt = (nowMs - lastTickMs) / 60_000f // minutes
        lastTickMs = nowMs
        val decay = exp(-dt / 5f) // τ = 5 min
        speechRate *= decay
        laughRate *= decay
        tensionRate *= decay
        livelyRate *= decay
        sadRate *= decay
        recompute()
    }

    private fun recompute() {
        val valence = (laughRate * 0.6f + livelyRate * 0.3f - tensionRate * 0.5f - sadRate * 0.7f)
            .coerceIn(-1f, 1f)
        val arousal = (speechRate * 0.3f + laughRate * 0.4f + livelyRate * 0.4f + tensionRate * 0.3f)
            .coerceIn(0f, 1f)

        val label = when {
            tensionRate > 1f       -> "tensa"
            sadRate > 0.8f         -> "triste"
            laughRate > 1f && arousal > 0.5f -> "alegre"
            livelyRate > 0.8f      -> "animada"
            arousal < 0.1f && speechRate < 0.1f -> "quieta"
            arousal < 0.2f         -> "calma"
            else                   -> "calma"
        }

        // Hysteresis: commit label only after 5 min stable
        val nowMs = System.currentTimeMillis()
        if (label != _mood.value.label) {
            if (label != pendingLabel) { pendingLabel = label; labelChangedAt = nowMs }
            else if (nowMs - labelChangedAt >= HYSTERESIS_MS) {
                val newMood = Mood(label, valence, arousal)
                _mood.value = newMood
                events.emit("house_mood", buildJsonObject {
                    put("label", label); put("valence", valence); put("arousal", arousal)
                })
            }
        } else {
            pendingLabel = ""
            _mood.value = _mood.value.copy(valence = valence, arousal = arousal)
        }
    }
}
