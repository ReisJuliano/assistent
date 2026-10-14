package io.github.assistent.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Tiny energy VAD used only while TTS is speaking.
 *
 * It uses the voice communication source so Android can apply echo cancellation, then requires sustained
 * microphone energy above the robot's own output level before interrupting.
 */
class BargeInDetector {
    suspend fun awaitSpeech(ttsLevel: () -> Float): Boolean = withContext(Dispatchers.IO) {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        )
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
        aec?.enabled = true
        val buf = ShortArray(rate / 20) // 50 ms
        var sustainedMs = 0
        var noise = 0.015f
        var detected = false
        try {
            rec.startRecording()
            while (!detected) {
                ensureActive()
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val rms = rms(buf, n)
                noise = if (rms < noise) noise * 0.85f + rms * 0.15f else noise * 0.995f + rms * 0.005f
                val threshold = maxOf(0.055f, noise * 3.5f, ttsLevel() * 0.55f)
                sustainedMs = if (rms > threshold) sustainedMs + 50 else 0
                detected = sustainedMs >= 450
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            aec?.release()
        }
        detected
    }

    private fun rms(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
        return (sqrt(sum / maxOf(1, n)) / 32768.0).toFloat()
    }
}
