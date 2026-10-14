package io.github.assistent.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Real-time "robot voice" for any TTS output (16-bit mono PCM), applied in [PcmPlayer]:
 * - ring modulation (multiply by a low sine) — the classic sci-fi robot timbre;
 * - feedback comb filter — short metallic resonance, like talking inside a tin can;
 * - optional bit/sample-rate crush for an 8-bit feel.
 * Stateful across chunks so there are no clicks between buffers.
 */
class RobotVoice(private val sampleRate: Int, private val preset: Preset) {

    data class Preset(
        val ringHz: Float,
        val ringMix: Float,
        val combMs: Float,
        val combFeedback: Float,
        val bits: Int = 16,
        val hold: Int = 1,
        val gain: Float = 1f,
    )

    companion object {
        val PRESETS = mapOf(
            "light" to Preset(ringHz = 38f, ringMix = 0.25f, combMs = 3.2f, combFeedback = 0.35f, gain = 1.05f),
            "classic" to Preset(ringHz = 55f, ringMix = 0.6f, combMs = 4.5f, combFeedback = 0.5f, gain = 1.1f),
            "heavy" to Preset(ringHz = 72f, ringMix = 0.85f, combMs = 6.5f, combFeedback = 0.6f, bits = 9, hold = 2, gain = 1.15f),
        )

        fun of(name: String, sampleRate: Int): RobotVoice? = PRESETS[name]?.let { RobotVoice(sampleRate, it) }
    }

    private val delay = FloatArray((sampleRate * preset.combMs / 1000f).toInt().coerceAtLeast(1))
    private var di = 0
    private var phase = 0.0
    private val step = 2.0 * PI * preset.ringHz / sampleRate
    private var held = 0f
    private var holdCount = 0
    private val levels = (1 shl (preset.bits - 1)).toFloat()

    /** Processes [n] bytes of little-endian 16-bit PCM in place. */
    fun process(buf: ByteArray, n: Int) {
        var i = 0
        while (i + 1 < n) {
            var x = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort() / 32768f

            // Ring modulation, mixed with the dry voice so words stay intelligible.
            val carrier = sin(phase).toFloat()
            phase += step
            if (phase > 2 * PI) phase -= 2 * PI
            x = x * (1f - preset.ringMix) + x * carrier * preset.ringMix * 1.6f

            // Feedback comb filter (metallic resonance).
            val y = x + delay[di] * preset.combFeedback
            delay[di] = y
            di = (di + 1) % delay.size
            var out = y * (1f - preset.combFeedback * 0.5f) * preset.gain

            // Bit / sample-rate crush.
            if (preset.hold > 1) {
                if (holdCount == 0) held = out
                holdCount = (holdCount + 1) % preset.hold
                out = held
            }
            if (preset.bits < 16) out = kotlin.math.round(out * levels) / levels

            // Soft limiter: no harsh clipping.
            out = out / (1f + abs(out) * 0.35f)
            val s = (out.coerceIn(-1f, 1f) * 32767f).toInt()
            buf[i] = (s and 0xff).toByte()
            buf[i + 1] = ((s shr 8) and 0xff).toByte()
            i += 2
        }
    }
}
