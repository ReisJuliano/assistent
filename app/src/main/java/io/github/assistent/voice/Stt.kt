package io.github.assistent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.assistent.config.ConfigStore
import io.github.assistent.config.SttConfig
import io.github.assistent.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** One utterance of speech → text. Empty string = nothing understood / silence. */
class Listener(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
) {
    /**
     * @param noSpeechTimeoutMs give up if the user does not start talking within this time.
     * @param onLevel mic loudness 0..1 (face pulse); @param onPartial live transcript.
     */
    suspend fun listen(noSpeechTimeoutMs: Long, onLevel: (Float) -> Unit, onPartial: (String) -> Unit): String {
        val cfg = config.current.stt
        return when (cfg.provider) {
            "openai" -> whisper(cfg, noSpeechTimeoutMs, onLevel)
            else -> kotlinx.coroutines.withTimeoutOrNull(noSpeechTimeoutMs + cfg.maxSeconds * 1000L + 5_000) {
                androidRecognizer(cfg, noSpeechTimeoutMs, onLevel, onPartial)
            } ?: ""
        }
    }

    // ------------------------------------------------------------ Android SpeechRecognizer

    private suspend fun androidRecognizer(
        cfg: SttConfig, timeoutMs: Long, onLevel: (Float) -> Unit, onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Main) {
        val language = config.current.personality.language
        suspendCancellableCoroutine { cont ->
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            fun finish(text: String) {
                if (cont.isActive) cont.resume(text)
                sr.destroy()
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) = finish("")
                override fun onResults(results: Bundle?) =
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                override fun onPartialResults(partial: Bundle?) {
                    partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, cfg.silenceMs.toLong())
            sr.startListening(intent)
            cont.invokeOnCancellation { runCatching { sr.cancel(); sr.destroy() } }
        }
    }

    // ------------------------------------------------------------ Whisper-compatible API

    @SuppressLint("MissingPermission")
    private suspend fun whisper(cfg: SttConfig, timeoutMs: Long, onLevel: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val rate = 16_000
        val pcm = recordUtterance(rate, timeoutMs, cfg.silenceMs.toLong(), cfg.maxSeconds * 1000L, onLevel)
            ?: return@withContext ""
        val wav = wav(pcm, rate)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", cfg.model)
            .addFormDataPart("language", cfg.language)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(cfg.baseUrl.trimEnd('/') + "/audio/transcriptions")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("STT HTTP ${res.code}: ${text.take(200)}")
            AppJson.parseToJsonElement(text).jsonObject["text"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty().trim()
        }
    }

    /** Energy-based VAD: waits for speech, stops after [silenceMs] of quiet. Null if nobody spoke. */
    @SuppressLint("MissingPermission")
    private suspend fun recordUtterance(rate: Int, timeoutMs: Long, silenceMs: Long, maxMs: Long, onLevel: (Float) -> Unit): ByteArray? =
        withContext(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            val out = ByteArrayOutputStream()
            val frame = ShortArray(rate / 50) // 20 ms
            val bytes = ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            var noise = 0.01
            var speaking = false
            var lastVoice = 0L
            val start = System.currentTimeMillis()
            try {
                rec.startRecording()
                while (true) {
                    ensureActive()
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += frame[i] * frame[i].toDouble()
                    val rms = sqrt(sum / n) / 32768.0
                    onLevel((rms * 8).coerceIn(0.0, 1.0).toFloat())
                    val now = System.currentTimeMillis()
                    val voiced = rms > maxOf(noise * 3.0, 0.015)
                    if (!speaking) noise = noise * 0.95 + rms * 0.05
                    if (voiced) { speaking = true; lastVoice = now }
                    // Keep a little audio before speech starts so the first syllable is not cut.
                    bytes.clear(); for (i in 0 until n) bytes.putShort(frame[i])
                    out.write(bytes.array(), 0, n * 2)
                    if (!speaking && out.size() > rate * 2 / 2) {
                        val keep = out.toByteArray().takeLast(rate * 2 / 4).toByteArray()
                        out.reset(); out.write(keep)
                    }
                    if (!speaking && now - start > timeoutMs) return@withContext null
                    if (speaking && now - lastVoice > silenceMs) break
                    if (now - start > maxMs) break
                }
            } finally {
                onLevel(0f)
                runCatching { rec.stop() }
                rec.release()
            }
            if (speaking) out.toByteArray() else null
        }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }
}
