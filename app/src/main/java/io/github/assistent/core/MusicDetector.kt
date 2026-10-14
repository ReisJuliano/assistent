package io.github.assistent.core

/**
 * Hears music in the room from the microphone loudness (50 ms RMS frames from the wake-word loop).
 *
 * Music ≈ sound that is *sustained* (speech has gaps between words) and has a *regular beat* (autocorrelation of
 * the onset envelope peaks at one lag). The background-noise floor only learns from quiet frames, so a song that
 * keeps playing doesn't slowly become "noise" and stop being detected.
 */
class MusicDetector(private val frameSeconds: Float = 0.05f) {
    private val env = FloatArray((6f / frameSeconds).toInt()) // 6 s window
    private var pos = 0
    private var filled = 0
    private var frames = 0
    /** Ambient floor: falls quickly, rises only on quiet-ish frames. */
    var noise = 0.004f
        private set
    private var hits = 0

    /** Feeds one frame; every second returns the BPM once music has been heard for ~2 s in a row, else null. */
    fun push(rms: Float): Float? {
        noise = when {
            rms < noise -> noise * 0.8f + rms * 0.2f
            rms < noise * 2.5f -> noise * 0.995f + rms * 0.005f
            else -> noise
        }.coerceAtLeast(0.0005f)
        env[pos] = rms
        pos = (pos + 1) % env.size
        filled = minOf(env.size, filled + 1)
        if (++frames % (1f / frameSeconds).toInt() != 0 || filled < env.size) return null
        val bpm = bpm()
        hits = if (bpm != null) hits + 1 else 0
        return if (hits >= 2) bpm else null
    }

    fun reset() { hits = 0 }

    internal fun bpm(): Float? {
        val n = env.size
        val x = FloatArray(n) { env[(pos + it) % n] }
        val mean = x.average().toFloat()
        if (mean < maxOf(0.006f, noise * 2.5f)) return null
        // Sustained: most frames well above the floor (speech drops to the floor between words).
        val floor = maxOf(noise * 1.8f, mean * 0.35f)
        if (x.count { it > floor } / n.toFloat() < 0.75f) return null
        val onset = FloatArray(n) { if (it == 0) 0f else maxOf(0f, x[it] - x[it - 1]) }
        val om = onset.average().toFloat()
        val norm = onset.sumOf { ((it - om) * (it - om)).toDouble() }.toFloat()
        if (norm < 1e-7f) return null
        val minLag = (0.3f / frameSeconds).toInt() // 200 BPM
        val maxLag = (1.2f / frameSeconds).toInt() // 50 BPM
        var bestLag = 0
        var best = 0f
        for (lag in minLag..maxLag) {
            var s = 0f
            for (i in lag until n) s += (onset[i] - om) * (onset[i - lag] - om)
            val c = s / norm
            if (c > best) { best = c; bestLag = lag }
        }
        if (best < 0.18f || bestLag == 0) return null
        var bpm = 60f / (bestLag * frameSeconds)
        // Fold into a danceable range.
        while (bpm < 80f) bpm *= 2f
        while (bpm > 170f) bpm /= 2f
        return bpm
    }
}
