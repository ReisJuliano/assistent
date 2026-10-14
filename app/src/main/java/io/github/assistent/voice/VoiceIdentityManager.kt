package io.github.assistent.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AppJson
import io.github.assistent.data.PersonDao
import io.github.assistent.data.PersonEntity
import io.github.assistent.data.SpeakerProfileDao
import io.github.assistent.data.SpeakerProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.sqrt

/** Who spoke, with the cosine score of the best match. */
data class VoiceMatch(val person: PersonEntity?, val score: Float)

/**
 * On-device speaker recognition with sherpa-onnx (CAM++ speaker embeddings, model downloaded on first use).
 * Enrollment captures ~10 s of voiced audio from the always-on wake-word microphone loop (no mic conflicts);
 * identification runs on the audio of the wake phrase ("ei Nexo"), so the robot knows who called it.
 */
class VoiceIdentityManager(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val dao: SpeakerProfileDao,
    private val people: PersonDao,
) {
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val lock = Mutex()
    private var extractor: SpeakerEmbeddingExtractor? = null
    private val listSer = ListSerializer(ListSerializer(Float.serializer()))

    private suspend fun ensureModel(): SpeakerEmbeddingExtractor? = lock.withLock {
        extractor?.let { return it }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, "models/speaker.onnx")
                if (!file.exists() || file.length() < 1_000_000) {
                    _status.value = "Baixando modelo de voz…"
                    file.parentFile?.mkdirs()
                    http.newCall(Request.Builder().url(config.current.people.voiceModelUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val tmp = File(file.parentFile, "speaker.tmp")
                        tmp.outputStream().use { r.body!!.byteStream().copyTo(it) }
                        tmp.renameTo(file)
                    }
                }
                SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = file.absolutePath, numThreads = 2)).also {
                    _status.value = ""
                }
            }.onFailure {
                Log.w(TAG, "speaker model unavailable", it)
                _status.value = "Modelo de voz indisponível: ${it.message}"
            }.getOrNull()
        }.also { extractor = it }
    }

    /** Embedding of 16 kHz mono float audio (−1..1), or null if too short / model missing. */
    suspend fun embed(samples: FloatArray): FloatArray? = withContext(Dispatchers.Default) {
        if (samples.size < 16_000) return@withContext null // < 1 s is not enough to tell voices apart
        val ex = ensureModel() ?: return@withContext null
        val stream = ex.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            stream.inputFinished()
            if (!ex.isReady(stream)) return@withContext null
            normalize(ex.compute(stream))
        } finally {
            stream.release()
        }
    }

    /**
     * Saves a voice profile from [audio] (≈10 s of the person talking). Several windows of the recording become
     * separate samples, so the profile covers some variation; enrolling again adds to it (max 24 samples).
     */
    suspend fun enroll(personId: Long, audio: FloatArray): String = withContext(Dispatchers.Default) {
        val win = 16_000 * 3
        val windows = if (audio.size <= win) listOf(audio)
        else (0..(audio.size - win) step (win / 2)).map { audio.copyOfRange(it, it + win) }
        val embs = windows.mapNotNull { embed(it) }
        if (embs.isEmpty()) return@withContext "Não consegui ouvir sua voz direito. Tente de novo, mais perto e falando normalmente."
        val existing = dao.byPerson(personId)
        val all = (existing?.let { decode(it.embeddings) }.orEmpty() + embs).takeLast(24)
        val next = (existing ?: SpeakerProfileEntity(personId = personId)).copy(
            embeddings = AppJson.encodeToString(listSer, all.map { it.toList() }),
            model = "sherpa-campplus",
            updatedAt = System.currentTimeMillis(),
        )
        if (existing == null) dao.insert(next) else dao.update(next)
        cache = null
        "Voz cadastrada (${all.size} amostras)"
    }

    @Volatile private var cache: List<Pair<PersonEntity, List<FloatArray>>>? = null

    /** Identifies the speaker of [audio]. Uses centroid + mean of the top-3 samples, with a margin over the runner-up. */
    suspend fun identify(audio: FloatArray): VoiceMatch {
        val emb = embed(audio) ?: return VoiceMatch(null, -1f)
        val profiles = cache ?: dao.all().filter { it.model.startsWith("sherpa") }.mapNotNull { sp ->
            val p = people.get(sp.personId) ?: return@mapNotNull null
            p to decode(sp.embeddings)
        }.also { cache = it }
        var best: PersonEntity? = null
        var bestScore = -1f
        var second = -1f
        for ((p, samples) in profiles) {
            if (samples.isEmpty()) continue
            val sims = samples.map { cosine(emb, it) }.sortedDescending()
            val topMean = sims.take(3).average().toFloat()
            val centroid = normalize(FloatArray(emb.size) { i -> samples.sumOf { it[i].toDouble() }.toFloat() })
            val score = 0.5f * topMean + 0.5f * cosine(emb, centroid)
            if (score > bestScore) { second = bestScore; bestScore = score; best = p } else if (score > second) second = score
        }
        val threshold = config.current.people.voiceThreshold
        val ok = best != null && bestScore >= threshold && (second < 0f || bestScore - second >= 0.06f)
        Log.d(TAG, "voice best=${best?.name} score=$bestScore second=$second threshold=$threshold ok=$ok")
        return VoiceMatch(if (ok) best else null, bestScore)
    }

    suspend fun hasProfile(personId: Long) = dao.byPerson(personId)?.let { decode(it.embeddings).isNotEmpty() } == true

    private fun decode(json: String): List<FloatArray> =
        runCatching { AppJson.decodeFromString(listSer, json).map { it.toFloatArray() } }.getOrDefault(emptyList())

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        private const val TAG = "VoiceId"
    }
}
