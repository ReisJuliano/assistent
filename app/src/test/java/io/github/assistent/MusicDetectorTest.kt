package io.github.assistent

import io.github.assistent.core.MusicDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class MusicDetectorTest {
    private val rnd = Random(7)

    /** Song loudness envelope: a bed plus a kick on every beat, with a little jitter. */
    private fun song(t: Float, bpm: Float, level: Float = 0.04f): Float {
        val beat = 60f / bpm
        val since = t % beat
        return level + level * 2.5f * exp(-since * 14f) + rnd.nextFloat() * level * 0.2f
    }

    /** Speech: syllable bursts with pauses between words and sentences. */
    private fun speech(t: Float): Float {
        val word = (t * 2.7f).toInt()
        val inWord = (t * 2.7f) % 1f < 0.55f && word % 7 != 6
        return if (inWord) 0.03f + rnd.nextFloat() * 0.05f else 0.002f + rnd.nextFloat() * 0.002f
    }

    private fun run(d: MusicDetector, seconds: Float, f: (Float) -> Float, start: Float = 0f): List<Float> {
        val out = mutableListOf<Float>()
        var t = start
        while (t < start + seconds) { d.push(f(t))?.let(out::add); t += 0.05f }
        return out
    }

    @Test fun detectsMusicAndItsTempo() {
        val d = MusicDetector()
        run(d, 5f, { 0.003f + rnd.nextFloat() * 0.001f })
        val hits = run(d, 12f, { song(it, 120f) }, start = 5f)
        assertNotNull(hits.lastOrNull())
        assertEquals(120f, hits.last(), 8f)
    }

    @Test fun keepsDetectingALongSong() {
        val d = MusicDetector()
        run(d, 5f, { 0.003f })
        val hits = run(d, 180f, { song(it, 100f) }, start = 5f)
        // Still hearing music after three minutes (the noise floor must not swallow it).
        val late = run(d, 10f, { song(it, 100f) }, start = 185f)
        assertNotNull(hits.firstOrNull())
        assertNotNull(late.lastOrNull())
    }

    @Test fun ignoresSpeechAndSilence() {
        val d = MusicDetector()
        assertNull(run(d, 30f, { 0.003f + rnd.nextFloat() * 0.001f }).lastOrNull())
        assertNull(run(d, 40f, ::speech).lastOrNull())
    }
}
