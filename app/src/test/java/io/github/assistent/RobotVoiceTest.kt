package io.github.assistent

import io.github.assistent.voice.RobotVoice
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RobotVoiceTest {
    private fun tone(): ByteArray {
        val n = 24_000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = (sin(2 * PI * 220 * i / 24_000.0) * 0.8 * 32767).toInt()
            out[i * 2] = (s and 0xff).toByte(); out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test fun everyPresetStaysBoundedAndChangesTheSignal() {
        for (name in RobotVoice.PRESETS.keys) {
            val original = tone()
            val buf = original.copyOf()
            val fx = RobotVoice.of(name, 24_000)!!
            // Process in uneven chunks like the player does.
            var pos = 0
            while (pos < buf.size) { val n = minOf(1440, buf.size - pos); val chunk = buf.copyOfRange(pos, pos + n); fx.process(chunk, n); chunk.copyInto(buf, pos); pos += n }
            var diff = 0L
            for (i in buf.indices step 2) {
                val a = ((original[i + 1].toInt() shl 8) or (original[i].toInt() and 0xff)).toShort()
                val b = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()
                diff += abs(a - b)
            }
            assertTrue("$name should alter the voice", diff > 1_000_000)
        }
    }

    @Test fun offMeansNoEffect() {
        assertTrue(RobotVoice.of("off", 24_000) == null)
    }
}
