package io.github.assistent.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.assistent.config.ConfigStore
import io.github.assistent.config.TtsConfig
import io.github.assistent.core.EventDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.coroutines.resume

/** Turns text into audio played through [PcmPlayer]. Provider is chosen live from config. */
class Speaker(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    val player: PcmPlayer,
) {
    private val lock = Mutex()
    private var android: TextToSpeech? = null
    private val androidReady = CompletableDeferred<Boolean>()

    init {
        player.effect = { config.current.tts.robotVoice }
    }

    suspend fun speak(text: String) = lock.withLock {
        if (text.isBlank()) return@withLock
        val spokenText = text
            .replace(Regex("(?i)\\bh\\s*m\\s*m+\\b"), "humm")
            .replace(Regex("(?i)\\bhm+\\b"), "humm")
        val cfg = config.current.tts
        val result = runCatching {
            when (cfg.provider) {
                "openai" -> openAi(spokenText, cfg)
                "elevenlabs" -> elevenLabs(spokenText, cfg)
                else -> androidTts(spokenText, cfg)
            }
        }
        val error = result.exceptionOrNull()
        if (error is kotlinx.coroutines.CancellationException) throw error
        // Remote voice failed (offline, quota…): never stay silent, fall back to the system voice.
        if (error != null && cfg.provider != "android") androidTts(spokenText, cfg)
        else result.getOrThrow()
    }

    fun stop() = player.stop()

    /** OpenAI `/audio/speech`; also Kokoro-FastAPI, openedai-speech, LocalAI… (`response_format=pcm`, 24 kHz). */
    private suspend fun openAi(text: String, cfg: TtsConfig) {
        val body = buildJsonObject {
            put("model", cfg.model); put("input", text); put("voice", cfg.voice)
            put("response_format", "pcm"); put("speed", cfg.speed)
            if (cfg.instructions.isNotBlank() && cfg.model.contains("gpt-4o")) put("instructions", cfg.instructions)
        }
        val req = Request.Builder().url(cfg.baseUrl.trimEnd('/') + "/audio/speech")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .post(body.toString().toRequestBody(EventDispatcher.JSON)).build()
        stream(req, 24_000)
    }

    private suspend fun elevenLabs(text: String, cfg: TtsConfig) {
        val body = buildJsonObject {
            put("text", text); put("model_id", cfg.elevenLabsModel)
        }
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/${cfg.elevenLabsVoiceId}/stream?output_format=pcm_24000")
            .header("xi-api-key", cfg.apiKey)
            .post(body.toString().toRequestBody(EventDispatcher.JSON)).build()
        stream(req, 24_000)
    }

    private suspend fun stream(req: Request, rate: Int) {
        val res = withContext(Dispatchers.IO) { http.newCall(req).execute() }
        res.use {
            if (!it.isSuccessful) throw IOException("TTS HTTP ${it.code}: ${it.body?.string()?.take(200)}")
            player.play(it.body!!.byteStream(), rate)
        }
    }

    // ------------------------------------------------------------ Android system TTS

    private suspend fun androidTts(text: String, cfg: TtsConfig) {
        val tts = ensureAndroid() ?: return
        val locale = Locale.forLanguageTag(config.current.personality.language)
        tts.language = locale
        val robotPitch = if (cfg.androidRobot) cfg.pitch * 0.78f else cfg.pitch
        val robotSpeed = if (cfg.androidRobot) cfg.speed * 0.9f else cfg.speed
        tts.setPitch(robotPitch.coerceIn(0.5f, 2f))
        tts.setSpeechRate(robotSpeed.coerceIn(0.5f, 2f))
        val voices = tts.voices.orEmpty().filter { it.locale.language == locale.language }
        val voice = voices.firstOrNull { it.name == cfg.androidVoice }
            ?: voices.filter { cfg.androidPreferMale && it.name.lowercase().contains("male") }
                .filter { it.locale.country == locale.country }.maxByOrNull { it.quality }
            ?: voices.filter { cfg.androidPreferMale && it.name.lowercase().contains("male") }.maxByOrNull { it.quality }
            ?: voices.filter { it.locale.country == locale.country }.maxByOrNull { it.quality }
            ?: voices.maxByOrNull { it.quality }
        voice?.let { tts.voice = it }

        val file = File(context.cacheDir, "tts.wav")
        val ok = suspendCancellableCoroutine { cont ->
            val id = "u" + System.nanoTime()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            })
            tts.synthesizeToFile(text, Bundle(), file, id)
        }
        if (!ok || !file.exists()) return
        val (rate, offset) = wavInfo(file)
        file.inputStream().use { input ->
            input.skip(offset.toLong())
            player.play(input, rate)
        }
    }

    private suspend fun ensureAndroid(): TextToSpeech? {
        if (android == null) {
            withContext(Dispatchers.Main) {
                android = TextToSpeech(context) { status -> androidReady.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        return if (androidReady.await()) android else null
    }

    /** Voices available for the settings screen. */
    suspend fun androidVoices(): List<String> =
        ensureAndroid()?.voices.orEmpty().map { it.name }.sorted()

    /** Returns sample rate and byte offset of PCM data in a WAV file. */
    private fun wavInfo(f: File): Pair<Int, Int> = RandomAccessFile(f, "r").use { raf ->
        val header = ByteArray(minOf(4096, raf.length().toInt()))
        raf.readFully(header)
        fun le32(i: Int) = (header[i].toInt() and 0xff) or ((header[i + 1].toInt() and 0xff) shl 8) or
            ((header[i + 2].toInt() and 0xff) shl 16) or ((header[i + 3].toInt() and 0xff) shl 24)
        val rate = le32(24)
        var pos = 12
        while (pos + 8 <= header.size) {
            val id = String(header, pos, 4, Charsets.US_ASCII)
            val size = le32(pos + 4)
            if (id == "data") return@use rate to (pos + 8)
            pos += 8 + size
        }
        rate to 44
    }
}
