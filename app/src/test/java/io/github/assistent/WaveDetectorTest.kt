package io.github.assistent

import io.github.assistent.vision.HandSample
import io.github.assistent.vision.WaveDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class WaveDetectorTest {
    private fun feed(d: WaveDetector, open: Boolean, amp: Float, y: Float = 0.4f): Boolean {
        var waved = false
        for (i in 0 until 16) { // 2 s at 8 fps
            val t = i * 125L
            val x = 0.5f + amp * sin(i * 2.4f)
            if (d.push(HandSample(t, x, y, open), t)) waved = true
        }
        return waved
    }

    @Test fun openPalmSideToSideIsAWave() = assertTrue(feed(WaveDetector(), open = true, amp = 0.08f))
    @Test fun aStillHandIsNot() = assertFalse(feed(WaveDetector(), open = true, amp = 0.005f))
    @Test fun aMovingFistIsNot() = assertFalse(feed(WaveDetector(), open = false, amp = 0.08f))
    @Test fun aLoweredHandIsNot() = assertFalse(feed(WaveDetector(), open = true, amp = 0.08f, y = 0.9f))
}
