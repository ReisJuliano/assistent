package io.github.assistent.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV reader for voice uploads: PCM 16-bit, any channel count/rate, returned as 16 kHz mono floats. */
object Wav {
    fun decode16kMono(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "not a WAV file" }
        var pos = 12
        var channels = 1
        var rate = 16_000
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
                bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                require(bits == 16) { "only 16-bit PCM WAV is supported" }
                val frames = minOf(size, bytes.size - pos - 8) / (2 * channels)
                val mono = FloatArray(frames) { f ->
                    var acc = 0f
                    for (c in 0 until channels) acc += b.getShort(pos + 8 + (f * channels + c) * 2) / 32768f
                    acc / channels
                }
                return if (rate == 16_000) mono else resample(mono, rate, 16_000)
            }
            pos += 8 + size + (size and 1)
        }
        error("WAV has no data chunk")
    }

    /** Raw 16-bit little-endian mono PCM at [rate] → 16 kHz floats. */
    fun pcmTo16k(pcm: ByteArray, rate: Int): FloatArray {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val x = FloatArray(pcm.size / 2) { b.getShort(it * 2) / 32768f }
        return if (rate == 16_000) x else resample(x, rate, 16_000)
    }

    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        val n = (x.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() * from / to
            val i0 = t.toInt().coerceAtMost(x.size - 1)
            val i1 = (i0 + 1).coerceAtMost(x.size - 1)
            val f = (t - i0).toFloat()
            x[i0] * (1 - f) + x[i1] * f
        }
    }
}
