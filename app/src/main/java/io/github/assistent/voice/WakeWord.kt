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
            // Phrases whose every word exists in the model vocabulary are detected with a grammar recognizer:
            // it can only output those phrases or [unk], with per-word confidence. Far more reliable than fuzzy
            // matching free text. Phrases with unknown words (a made-up name) fall back to fuzzy matching.
            val spoken = rawPhraseVariants()
            val known = spoken.filter { p -> p.all { knows(m, it) } }
            val grammarRec = known.takeIf { it.isNotEmpty() }?.let { list ->
                val json = grammarJson(m, list)
                runCatching { Recognizer(m, 16000f, json).apply { setWords(true) } }
                    .onFailure { Log.w(TAG, "grammar recognizer failed", it) }.getOrNull()
            }
            val knownNormalized = known.map { p -> p.flatMap { words(it) } }
            Log.i(TAG, "wake phrases: grammar=${known.map { it.joinToString(" ") }} fuzzyOnly=${(spoken - known.toSet()).map { it.joinToString(" ") }}")
            val rec = Recognizer(m, 16000f).apply { setWords(true); setPartialWords(true) }
            val recentFree = ArrayDeque<TimedWord>()
            fun freeWordsNow(): List<TimedWord> = recentFree.toList() + timedWords(rec.partialResult)
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

            /** Text said after the wake phrase in the same breath, from the free recognizer (by word timing). */
            fun remainderFromFree(nameEnd: Float? = null): String {
                val result = rec.finalResult
                if (nameEnd != null && nameEnd > 0f) return wordsAfter(result, nameEnd).trim()
                return match(field(result, "text"), phrases, 0.5f) ?: ""
            }

            try {
                mic.startRecording()
                while (true) {
                    ensureActive()
                    val n = mic.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // Safety handoff: a partial wake hit must never leave the wake detector
                    // holding the microphone forever if Vosk does not emit an endpoint.
                    if (attention && System.currentTimeMillis() - attentionAt > 1_800L) {
                        lastWakeAudio = snapshot(2.5f)
                        return@withContext remainderFromFree()
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

                    val freeDone = rec.acceptWaveForm(buf, n)
                    if (grammarRec != null) {
                        if (grammarRec.acceptWaveForm(buf, n)) {
                            val hit = grammarHit(grammarRec.result, knownNormalized)
                            val call = hit != null && isCall(hit, freeWordsNow())
                            // Music: lyrics can resemble the name, so demand a very confident decode.
                            val need = when { music() -> 0.9f; strict() -> 0.8f; else -> (1.6f - threshold).coerceIn(0.55f, 0.85f) }
                            if (hit != null) Log.d(TAG, "grammar heard '${hit.phrase}' conf=${"%.2f".format(hit.confidence)} call=$call need=$need")
                            if (hit != null && call && hit.confidence >= need) {
                                if (!attention) onAttention()
                                lastWakeAudio = snapshot(3f)
                                return@withContext remainderFromFree(hit.endSec)
                            }
                            if (attention) { attention = false; onDismiss() }
                        } else if (!attention && !music()) {
                            // React on the face as soon as the partial result contains the phrase.
                            val partial = words(field(grammarRec.partialResult, "partial").replace("[unk]", " "))
                            if (knownNormalized.any { containsPhrase(partial, it) }) {
                                attention = true
                                attentionAt = System.currentTimeMillis()
                                onAttention()
                            }
                        }
                        // Word times of both decoders count from their creation (same frames, never reset), so the
                        // grammar's "name ends at" lines up with the free text; just consume finished free results.
                        if (freeDone) {
                            recentFree.addAll(timedWords(rec.result))
                            val last = recentFree.lastOrNull()?.end ?: 0f
                            while (recentFree.isNotEmpty() && recentFree.first().end < last - 10f) recentFree.removeFirst()
                        }
                        continue
                    }

                    // Fuzzy fallback for names the model cannot spell.
                    if (freeDone) {
                        val heard = field(rec.result, "text")
                        // With music playing only a near-exact call counts (lyrics are full of almost-names).
                        val need = if (music()) 0.86f else if (strict()) threshold.coerceAtLeast(0.78f) else threshold.coerceAtMost(0.72f)
                        val remainder = match(heard, phrases, need)
                        if (heard.isNotBlank()) Log.d(TAG, "heard='$heard' match=${remainder != null}")
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
                        val partial = match(field(rec.partialResult, "partial"), phrases, threshold.coerceAtMost(0.68f))
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
                grammarRec?.close()
            }
        }

    /**
     * Barge-in by name: while the robot talks, listens (echo-cancelled) only for the wake phrases and stop words.
     * Returns "stop" or "name" when one is heard clearly, null if cancelled. Words the robot itself is saying
     * right now ([speakingNow]) never count, so its own voice can't interrupt it.
     */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun awaitBargeIn(stopWords: List<String>, speakingNow: () -> String): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val m = ensureModel()
            val names = rawPhraseVariants().filter { p -> p.all { knows(m, it) } }
            val stops = stopWords.map { spokenWords(it) }.filter { p -> p.isNotEmpty() && p.all { knows(m, it) } }
            if (names.isEmpty() && stops.isEmpty()) return@withContext kotlinx.coroutines.awaitCancellation()
            val rec = Recognizer(m, 16000f, grammarJson(m, names + stops)).apply { setWords(true) }
            val minBuf = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val mic = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16000),
            )
            val aec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(mic.audioSessionId) else null
            val ns = if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(mic.audioSessionId) else null
            aec?.enabled = true
            ns?.enabled = true
            val nameNorm = names.map { p -> p.flatMap { words(it) } }
            val stopNorm = stops.map { p -> p.flatMap { words(it) } }
            val buf = ShortArray(800)
            try {
                mic.startRecording()
                while (true) {
                    ensureActive()
                    val n = mic.read(buf, 0, buf.size)
                    if (n <= 0 || !rec.acceptWaveForm(buf, n)) continue
                    val own = words(speakingNow())
                    val result = rec.result
                    val stop = grammarHit(result, stopNorm)
                    val name = grammarHit(result, nameNorm)
                    val hit = listOfNotNull(stop?.let { "stop" to it }, name?.let { "name" to it })
                        .firstOrNull { (_, h) -> h.atStart && h.confidence >= 0.75f && !containsPhrase(own, words(h.phrase)) }
                    if (hit != null) {
                        Log.d(TAG, "barge-in ${hit.first}: '${hit.second.first}' conf=${hit.second.second}")
                        return@withContext hit.first
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            } finally {
                runCatching { mic.stop() }
                mic.release()
                aec?.release()
                ns?.release()
                rec.close()
            }
        }

    /** Result of running the wake detector on a recorded/synthesised clip (diagnostics, `/wake/selftest`). */
    @kotlinx.serialization.Serializable
    data class WakeTest(
        val grammarPhrases: List<String>,
        val unknownNameWords: List<String>,
        val grammarHeard: String,
        val confidence: Float,
        val detected: Boolean,
        val freeText: String,
        val command: String,
        val freeWords: String = "",
    )

    /** Runs the same detection as [awaitWake] (grammar + free text) over [audio] (16 kHz) without the microphone. */
    suspend fun detectOffline(audio: FloatArray): WakeTest = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val m = ensureModel()
        val spoken = rawPhraseVariants()
        val known = spoken.filter { p -> p.all { knows(m, it) } }
        val knownNormalized = known.map { p -> p.flatMap { words(it) } }
        val grammarRec = known.takeIf { it.isNotEmpty() }?.let { Recognizer(m, 16000f, grammarJson(m, it)).apply { setWords(true) } }
        val rec = Recognizer(m, 16000f).apply { setWords(true) }
        try {
            val padded = audio + FloatArray(8_000)
            val buf = ShortArray(800)
            val grammarResults = mutableListOf<String>()
            val freeTexts = mutableListOf<String>()
            val freeJson = mutableListOf<String>()
            var i = 0
            while (i < padded.size) {
                val n = minOf(buf.size, padded.size - i)
                for (k in 0 until n) buf[k] = (padded[i + k].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                if (grammarRec?.acceptWaveForm(buf, n) == true) grammarResults += grammarRec.result
                if (rec.acceptWaveForm(buf, n)) rec.result.let { freeJson += it; freeTexts += field(it, "text") }
                i += n
            }
            grammarRec?.let { grammarResults += it.finalResult }
            rec.finalResult.let { freeJson += it; freeTexts += field(it, "text") }
            val freeWords = freeJson.flatMap { timedWords(it) }
            val hits = grammarResults.mapNotNull { grammarHit(it, knownNormalized) }
            val hit = hits.firstOrNull { isCall(it, freeWords) } ?: hits.maxByOrNull { it.confidence }
            val free = freeTexts.filter { it.isNotBlank() }.joinToString(" ")
            val need = (1.6f - config.current.wake.sensitivity.coerceIn(0.4f, 0.98f)).coerceIn(0.55f, 0.85f)
            WakeTest(
                grammarPhrases = known.map { it.joinToString(" ") },
                unknownNameWords = spokenWords(config.current.personality.name).filterNot { knows(m, it) },
                grammarHeard = hit?.let { it.phrase + if (isCall(it, freeWords)) "" else " (não é chamado)" }.orEmpty(),
                confidence = hit?.confidence ?: 0f,
                detected = if (known.isNotEmpty()) hit != null && isCall(hit, freeWords) && hit.confidence >= need else match(free, phraseVariants(), 0.72f) != null,
                freeText = free,
                freeWords = freeWords.joinToString(" ") { "${it.word}@%.2f-%.2f".format(it.start, it.end) } +
                    hits.joinToString(" | ", prefix = "  grammar: ") { "${it.phrase}@%.2f-%.2f start=${it.atStart}".format(it.startSec, it.endSec) },
                command = if (hit != null && isCall(hit, freeWords)) freeJson.joinToString(" ") { wordsAfter(it, hit.endSec) }.trim()
                    else match(free, phraseVariants(), 0.5f).orEmpty(),
            )
        } finally {
            grammarRec?.close()
            rec.close()
        }
    }

    /** Vosk grammar: the phrases, greetings alone (so "ei" isn't [unk]) and [unk] for everything else. */
    private fun grammarJson(m: Model, phrases: List<List<String>>): String {
        val greetings = GREETING_WORDS.filter { knows(m, it) }.map { listOf(it) }
        return ((phrases + greetings).distinct().map { "\"" + it.joinToString(" ") + "\"" } + "\"[unk]\"").joinToString(",", "[", "]")
    }

    /** Words of every configured phrase, lowercase but with accents (as the model spells them). */
    private fun rawPhraseVariants(): List<List<String>> {
        val cfg = config.current
        val names = listOf(cfg.personality.name) + cfg.wake.nameAliases
        return cfg.wake.phrases.flatMap { p ->
            if (p.contains("{name}")) names.map { p.replace("{name}", it) } else listOf(p)
        }.map { spokenWords(it) }.filter { it.isNotEmpty() }.distinct()
    }

    /** Name words the recognizer can't spell, or null if the model isn't loaded yet. */
    fun unknownNameWords(name: String = config.current.personality.name): List<String>? {
        val m = model ?: return null
        return spokenWords(name).filterNot { knows(m, it) }
    }

    /** Funny names that this model recognizes well (all in its vocabulary). */
    fun goodNames(): List<String> {
        val m = model ?: return emptyList()
        return FUNNY_NAMES.filter { n -> spokenWords(n).all { knows(m, it) } }
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

        /** Fun names that tend to be in Portuguese Vosk vocabularies; filtered by [goodNames] for the loaded model. */
        val FUNNY_NAMES = listOf(
            "Parafuso", "Gambiarra", "Torresmo", "Rabanete", "Coxinha", "Abacaxi", "Brigadeiro", "Biscoito",
            "Salsicha", "Farofa", "Caramelo", "Sabugo", "Bigode", "Tampinha", "Pastel", "Pudim", "Zezinho", "Cocada",
        )

        /** Lowercase words keeping accents (Vosk vocabularies are spelled with them). */
        fun spokenWords(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}0-9]+")).filter { it.isNotBlank() }

        /** True if the model can output [word]: a grammar silently drops unknown words. Uses the native lookup. */
        fun knows(m: Model, word: String): Boolean = runCatching {
            com.sun.jna.Function.getFunction("vosk", "vosk_model_find_word").invokeInt(arrayOf(m.pointer, word)) >= 0
        }.getOrDefault(true)

        /**
         * A wake phrase found in a grammar result: the phrase, the confidence of its weakest word, when it ends in the
         * audio (seconds) and whether it opened the utterance. A name in the middle of a sentence ("comprei um
         * parafuso") is someone talking *about* it, not calling it.
         */
        fun grammarHit(json: String, phrases: List<List<String>>): GrammarHit? = runCatching {
            val obj = AppJson.parseToJsonElement(json).jsonObject
            val items = (obj["result"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonObject }.orEmpty()
            val itemWords = items.map { (it["word"] as JsonPrimitive).content }
            val norm = itemWords.map { if (it == "[unk]") "[unk]" else words(it).joinToString(" ") }
            for (phrase in phrases) {
                val start = norm.windowed(phrase.size).indexOfFirst { it == phrase }
                if (start < 0) continue
                val part = items.subList(start, start + phrase.size)
                val conf = part.minOf { (it["conf"] as JsonPrimitive).content.toFloat() }
                val begin = (part.first()["start"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
                val end = (part.last()["end"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
                // Only greetings may come before the phrase; any other (unknown) word means mid-sentence.
                val atStart = norm.subList(0, start).all { it in GREETINGS }
                return GrammarHit(phrase.joinToString(" "), conf, begin, end, atStart)
            }
            null
        }.getOrNull()

        fun timedWords(json: String): List<TimedWord> = runCatching {
            val obj = AppJson.parseToJsonElement(json).jsonObject
            val arr = (obj["result"] ?: obj["partial_result"]) as? kotlinx.serialization.json.JsonArray
            arr?.map { it.jsonObject }.orEmpty().map {
                TimedWord(
                    (it["word"] as JsonPrimitive).content,
                    (it["start"] as JsonPrimitive).content.toFloat(),
                    (it["end"] as JsonPrimitive).content.toFloat(),
                )
            }
        }.getOrDefault(emptyList())

        /**
         * Is this grammar hit really someone *calling* the robot? Works for any name: the grammar decoder can only
         * say the configured phrases, so it is cross-checked against what the free decoder heard at the same time.
         * - another (non-greeting) word right before the phrase → it's mid-sentence ("comprei um parafuso");
         * - the free decoder heard a different word over the same stretch → a longer word that contains the name
         *   ("anexo" for "nexo"). If the free decoder heard nothing there, the grammar decides.
         */
        fun isCall(hit: GrammarHit, free: List<TimedWord>): Boolean {
            // Words before the phrase are judged by the free decoder (real words); the grammar's [unk] also fires on
            // breaths and noise, so it is only used when the free decoder has nothing at all.
            if (!hit.atStart && free.isEmpty()) return false
            val name = words(hit.phrase).filterNot { it in GREETINGS }
            val before = free.filter { it.end <= hit.startSec + 0.05f && it.end > hit.startSec - 2.5f }
            if (before.any { w -> words(w.word).any { it !in GREETINGS } }) return false
            val over = free.filter { it.start < hit.endSec - 0.05f && it.end > hit.startSec + 0.05f }
                .flatMap { words(it.word) }.filterNot { it in GREETINGS }
            if (over.isEmpty() || name.isEmpty()) return true
            // Only a longer word that *contains* the name contradicts it ("anexo" ⊃ "nexo"). A plain mishearing of an
            // unusual name ("mesmo" for "torresmo") is normal for the free decoder and must not block a real call.
            return over.none { w -> name.any { n -> w != n && w.length > n.length && w.contains(n) } }
        }

        private val GREETING_WORDS = listOf("ei", "oi", "olá", "ok", "fala", "hey", "e", "aí")
        private val GREETINGS = setOf("ei", "oi", "ola", "ok", "fala", "hey", "e", "ai", "bom", "dia", "boa", "tarde", "noite")

        /** Words of a free-recognizer result that start after [afterSec] (the command after the wake phrase). */
        fun wordsAfter(json: String, afterSec: Float): String = runCatching {
            val obj = AppJson.parseToJsonElement(json).jsonObject
            (obj["result"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonObject }.orEmpty()
                .filter { ((it["start"] as JsonPrimitive).content.toFloat()) >= afterSec - 0.05f }
                .joinToString(" ") { (it["word"] as JsonPrimitive).content }
        }.getOrDefault("")

        fun containsPhrase(heard: List<String>, phrase: List<String>): Boolean =
            phrase.isNotEmpty() && heard.windowed(phrase.size).any { it == phrase }

        /** Extracts a likely spelling of the configured name from one calibration utterance. */
        fun nameCandidate(text: String): String? {
            val ignored = setOf("ei", "hey", "oi", "ola", "olá", "ok", "okay", "fala", "acorda", "bom", "dia")
            val candidate = text.trim().split(Regex("\\s+"))
                .filter { words(it).isNotEmpty() && words(it).first() !in ignored }
                .joinToString(" ") { it.trim { c -> !c.isLetterOrDigit() } }
                .trim()
            return candidate.takeIf { words(it).size in 1..3 && it.length >= 2 }
        }

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

data class GrammarHit(val phrase: String, val confidence: Float, val startSec: Float, val endSec: Float, val atStart: Boolean) {
    val first get() = phrase
    val second get() = confidence
}

/** A word from the free recognizer with its time in the audio (seconds since the decoder started). */
data class TimedWord(val word: String, val start: Float, val end: Float)
