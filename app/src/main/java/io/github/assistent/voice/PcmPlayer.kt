package io.github.assistent.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.sqrt

/**
 * Plays 16-bit mono PCM and exposes its loudness, used to animate the mouth.
 * Every TTS provider ends up here, so lip sync works the same for all voices.
 */
class PcmPlayer {
    /** Robot voice preset name ("off", "light", "classic", "heavy"), read at the start of each utterance. */
    @Volatile var effect: () -> String = { "off" }
    @Volatile private var track: AudioTrack? = null
    /** Loudness per chunk, indexed by playback position so the mouth matches what is heard. */
    private val levels = java.util.concurrent.CopyOnWriteArrayList<Float>()
    @Volatile private var chunkFrames = 1

    val level: Float
        get() {
            val t = track ?: return 0f
            val idx = runCatching { t.playbackHeadPosition / chunkFrames }.getOrDefault(-1)
            return if (idx in levels.indices) levels[idx] else 0f
        }

    fun stop() {
        track?.runCatching { pause(); flush() }
    }

    suspend fun play(stream: InputStream, sampleRate: Int) = withContext(Dispatchers.IO) {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        levels.clear()
        chunkFrames = (sampleRate * 0.03).toInt()
        val fx = RobotVoice.of(effect(), sampleRate)
        track = t
        try {
            t.play()
            // ~30 ms chunks: small enough for responsive lip sync.
            val chunk = chunkFrames * 2
            val buf = ByteArray(chunk)
            var carry: Byte? = null
            var written = 0L
            while (true) {
                ensureActive()
                var n = 0
                carry?.let { buf[0] = it; n = 1; carry = null }
                while (n < chunk) {
                    val r = stream.read(buf, n, chunk - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) break
                if (n % 2 == 1) { carry = buf[n - 1]; n-- }
                fx?.process(buf, n)
                levels += rms(buf, n)
                t.write(buf, 0, n)
                written += n
                if (n < chunk) break
            }
            // Wait for the tail of the buffer to actually play.
            val totalFrames = (written / 2).toInt()
            while (t.playbackHeadPosition < totalFrames && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                ensureActive()
                delay(20)
            }
        } finally {
            runCatching { t.stop() }
            t.release()
            track = null
        }
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val r = sqrt(sum / maxOf(1, n / 2)) / 32768.0
        // Speech RMS rarely exceeds ~0.3: expand to 0..1 with a soft knee.
        return (r * 4.0).coerceIn(0.0, 1.0).toFloat()
    }
}
