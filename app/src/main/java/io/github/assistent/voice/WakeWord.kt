package io.github.assistent.voice

import android.content.Context
import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Offline, always-on wake phrase detection with Vosk. No fixed hotword: any phrase from
 * `wake.phrases` (with the configured assistant name and aliases) is matched with fuzzy and phonetic matching,
 * and whatever comes after the phrase in the same breath is returned as the command
 * ("ei {nome}, que horas são?" → "que horas são").
 */
class WakeWordEngine(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
) {
    private val _progress = MutableStateFlow<Float?>(null)
    /** Model download progress 0..1, null when idle. */
    val progress: StateFlow<Float?> = _progress
    private var model: Model? = null
    private var modelUrl: String? = null
    private val modelLock = Mutex()

    suspend fun ensureModel(): Model = modelLock.withLock {
        val url = config.current.wake.modelUrl
        model?.takeIf { modelUrl == url }?.let { return it }
        withContext(Dispatchers.IO) {
            val name = url.substringAfterLast('/').removeSuffix(".zip")
            val dir = File(context.filesDir, "vosk/$name")
            if (!File(dir, ".ok").exists()) download(url, dir)
            // New models keep files in am/ and conf/; older ones (like small-pt-0.3) have final.mdl at the root.
            val root = dir.walkTopDown().maxDepth(3).firstOrNull {
                it.isDirectory && (File(it, "am/final.mdl").exists() || File(it, "final.mdl").exists())
            } ?: dir
            Model(root.absolutePath).also { model?.close(); model = it; modelUrl = url }
        }
    }

    private fun download(url: String, dir: File) {
        dir.deleteRecursively(); dir.mkdirs()
        _progress.value = 0f
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) error("model download HTTP ${res.code}")
                val total = res.body!!.contentLength().takeIf { it > 0 } ?: 50_000_000L
                val counting = object : java.io.FilterInputStream(res.body!!.byteStream()) {
                    var count = 0L
                    override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also {
                        if (it > 0) { count += it; _progress.value = (count.toFloat() / total).coerceAtMost(1f) }
                    }
                }
                ZipInputStream(counting).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val out = File(dir, e.name).canonicalFile
                        require(out.path.startsWith(dir.canonicalPath)) { "bad zip entry" }
                        if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                    }
                }
            }
            File(dir, ".ok").createNewFile()
        } finally {
            _progress.value = null
        }
    }

    /**
     * Suspends until a wake phrase is heard. [onAttention] fires as soon as a partial result
     * looks like the wake phrase (to react on the face immediately).
     * @return the command spoken after the phrase, possibly empty.
     */
    @android.annotation.SuppressLint("MissingPermission")
    // Last 4 s of microphone audio (16 kHz float), so the voice that said the wake phrase can be identified.
    private val ring = FloatArray(16_000 * 4)
    private var ringPos = 0

    /** Audio of whoever just said the wake phrase (≈3 s), set when [awaitWake] returns. */
    @Volatile var lastWakeAudio: FloatArray? = null
        private set

    private class VoiceCapture(val target: Int, val done: kotlinx.coroutines.CompletableDeferred<FloatArray>) {
        val data = FloatArray(target)
        var size = 0
    }
    @Volatile private var capture: VoiceCapture? = null

    /**
     * Collects [seconds] of *voiced* audio from the always-on wake loop (for voice enrollment), so it never fights
     * over the microphone. Returns null if the loop is not listening or nobody talks before [timeoutMs].
     */
    suspend fun captureVoice(seconds: Int, timeoutMs: Long): FloatArray? {
        val c = VoiceCapture(16_000 * seconds, kotlinx.coroutines.CompletableDeferred())
        capture = c
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { c.done.await() }
        } finally {
            if (capture === c) capture = null
        }
    }

    /** The last [seconds] of microphone audio (16 kHz), from the always-on wake-word loop. */
    fun snapshot(seconds: Float): FloatArray {
        val n = (16_000 * seconds).toInt().coerceAtMost(ring.size)
        return FloatArray(n) { ring[(ringPos - n + it + ring.size) % ring.size] }
    }

    suspend fun awaitWake(
        onAttention: () -> Unit = {}, onDismiss: () -> Unit = {}, onAudio: (Float) -> Unit = {},
        /** True while the robot sleeps: then only a very clear call wakes it up. */
        strict: () -> Boolean = { false },
        /** Raw microphone frames, for sound recognition on the same stream. */
        onPcm: (ShortArray, Int) -> Unit = { _, _ -> },
        /** True while music plays in the room: song lyrics must not sound like someone calling it. */
        music: () -> Boolean = { false },
    ): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val m = ensureModel()
            val phrases = phraseVariants()
            val threshold = config.current.wake.sensitivity.coerceIn(0.4f, 0.98f)
            val rec = Recognizer(m, 16000f)
            // Do not restrict decoding to a Vosk grammar while sleeping. Grammars can silently reject a custom
            // name that is absent from the model vocabulary. Matching stays constrained to configured phrases.
            val minBuf = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val mic = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16000),
            )
            val buf = ShortArray(800) // 50 ms
            var attention = false
            var attentionAt = 0L
            var noiseFloor = 0.01f
            var voiceTail = 0
            try {
                mic.startRecording()
                while (true) {
                    ensureActive()
                    val n = mic.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // Safety handoff: a partial wake hit must never leave the wake detector
                    // holding the microphone forever if Vosk does not emit an endpoint.
                    if (attention && System.currentTimeMillis() - attentionAt > 1_800L) {
                        return@withContext ""
                    }
                    var sum = 0.0
                    for (k in 0 until n) {
                        sum += buf[k] * buf[k].toDouble()
                        ring[ringPos] = buf[k] / 32768f
                        ringPos = (ringPos + 1) % ring.size
                    }
                    val rms = (kotlin.math.sqrt(sum / n) / 32768.0).toFloat()
                    onAudio(rms)
                    onPcm(buf, n)
                    capture?.let { c ->
                        // Only voiced frames count towards enrollment audio.
                        if (rms > maxOf(0.008f, noiseFloor * 3f)) {
                            val take = minOf(n, c.target - c.size)
                            for (k in 0 until take) c.data[c.size + k] = buf[k] / 32768f
                            c.size += take
                            if (c.size >= c.target) { capture = null; c.done.complete(c.data) }
                        }
                    }
                    // Cheap VAD: don't run the recognizer on silence (big CPU/heat saver); keep a short tail after sound.
                    noiseFloor = if (rms < noiseFloor) noiseFloor * 0.95f + rms * 0.05f else noiseFloor * 0.999f + rms * 0.001f
                    if (rms > maxOf(0.006f, noiseFloor * 2.5f)) voiceTail = 20 // ~1 s
                    if (voiceTail <= 0) continue
                    voiceTail--
                    val r = rec
                    if (r.acceptWaveForm(buf, n)) {
                        val heard = field(r.result, "text")
                        // With music playing only a near-exact call counts (lyrics are full of almost-names).
                        val need = if (music()) 0.86f else if (strict()) threshold.coerceAtLeast(0.78f) else threshold.coerceAtMost(0.72f)
                        val remainder = match(heard, phrases, need)
                        if (heard.isNotBlank()) Log.d(TAG, "heard='$heard' match=${remainder != null}")
                        // React before returning the completed recognition result. Waiting for the caller to
                        // resume after this branch made a valid wake phrase look unresponsive on the face.
                        if (!attention && remainder != null) {
                            attention = true
                            attentionAt = System.currentTimeMillis()
                            onAttention()
                        }
                        if (remainder != null) { lastWakeAudio = snapshot(3f); return@withContext remainder }
                        if (attention && System.currentTimeMillis() - attentionAt > 2_500) {
                            attention = false
                            onDismiss()
                        }
                    } else if (!attention && !music()) {
                        // Hand off as soon as the wake phrase is stable in a partial result. This releases
                        // AudioRecord before Listener opens the microphone for the actual command.
                        val partial = match(
                            field(rec.partialResult, "partial"), phrases, threshold.coerceAtMost(0.68f),
                        )
                        if (partial != null) {
                            attention = true
                            attentionAt = System.currentTimeMillis()
                            onAttention()
                            lastWakeAudio = snapshot(2.5f)
                            return@withContext partial
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE") ""
            } finally {
                runCatching { mic.stop() }
                mic.release()
                rec.close()
            }
        }

    private fun field(json: String?, key: String) = runCatching {
        (AppJson.parseToJsonElement(json.orEmpty()).jsonObject[key] as? JsonPrimitive)?.content
    }.getOrNull().orEmpty()

    private fun phraseVariants(): List<List<String>> {
        val cfg = config.current
        val names = listOf(cfg.personality.name) + cfg.wake.nameAliases
        return cfg.wake.phrases.flatMap { p ->
            if (p.contains("{name}")) names.map { p.replace("{name}", it) } else listOf(p)
        }.map { words(it) }.filter { it.isNotEmpty() }.distinct()
            .sortedByDescending { it.size } // prefer the longest configured phrase
    }

    companion object {
        private const val TAG = "WakeWord"

        fun words(s: String): List<String> = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

        /** Returns the text after the matched phrase, or null when no phrase matches. */
        fun match(text: String, phrases: List<List<String>>, threshold: Float): String? {
            val heard = words(text)
            if (heard.isEmpty()) return null
            for (phrase in phrases) {
                val target = phrase.joinToString(" ")
                for (start in 0..heard.size - phrase.size) {
                    val window = heard.subList(start, start + phrase.size).joinToString(" ")
                    if (similarity(window, target) >= threshold) {
                        // Original-cased remainder: drop the same number of words from the raw text.
                        val raw = text.trim().split(Regex("\\s+"))
                        return raw.drop(start + phrase.size).joinToString(" ")
                    }
                }
            }
            return null
        }

        fun similarity(a: String, b: String): Float {
            if (a == b) return 1f
            val d = levenshtein(a, b)
            val spelling = 1f - d.toFloat() / maxOf(a.length, b.length)
            val phonetic = if (phoneticKey(a) == phoneticKey(b)) 0.92f else 0f
            return maxOf(spelling, phonetic)
        }

        /** Small Portuguese-friendly fallback for common ASR confusions. */
        private fun phoneticKey(value: String): String = words(value).joinToString("")
            .replace("ph", "f")
            .replace("qu", "k")
            .replace("ch", "x")
            .replace("ç", "s")
            .replace("c", "k")
            .replace("q", "k")
            .replace("z", "s")
            .replace("y", "i")
            .replace(Regex("[aeiou]"), "a")

        private fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
