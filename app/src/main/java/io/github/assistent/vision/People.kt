package io.github.assistent.vision

import android.content.Context
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AppJson
import io.github.assistent.data.PersonDao
import io.github.assistent.data.PersonEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Result of looking at a face: a known person, or null for a stranger. */
data class Sighting(val person: PersonEntity?, val similarity: Float)

/**
 * On-device face recognition: MobileFaceNet turns a face crop into an embedding; people are matched by
 * cosine similarity against the samples saved when they were enrolled. Nothing leaves the phone.
 */
class People(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val dao: PersonDao,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow("")
    /** Human-readable status for the settings screen (download, enrolling…). */
    val status: StateFlow<String> = _status

    private var interpreter: Interpreter? = null
    private var inputSize = 112
    private var batch = 1
    private var outputSize = 192
    private val modelLock = Mutex()
    @Volatile private var busy = false
    @Volatile private var lastRun = 0L
    @Volatile private var cache: List<Pair<PersonEntity, List<FloatArray>>>? = null
    private var candidateName: String? = null
    private var candidateHits = 0
    private var candidateAt = 0L

    private class Enrollment(val name: String, val samples: MutableList<FloatArray> = mutableListOf(), val done: CompletableDeferred<PersonEntity?> = CompletableDeferred())
    @Volatile private var enrollment: Enrollment? = null

    /** Called after someone is enrolled (to share the face with other devices). */
    var onEnrolled: (PersonEntity) -> Unit = {}

    /** Called by [FaceTracker]: do we want a face crop right now? */
    fun wantsCrop(): Boolean {
        if (!config.current.people.recognition || busy) return false
        // Recognition must feel live. The model is local and throttled by busy, so battery
        // saving may reduce it slightly, but never enough to leave a stale identity on screen.
        val interval = if (enrollment != null) 500L else if (config.current.life.powerSave) 1_200L else 700L
        return System.currentTimeMillis() - lastRun > interval
    }

    /** Called by [FaceTracker] with an upright face crop. */
    fun onFaceCrop(face: Bitmap, onSighting: (Sighting) -> Unit) {
        busy = true
        lastRun = System.currentTimeMillis()
        scope.launch(Dispatchers.Default) {
            try {
                val emb = embed(face) ?: return@launch
                val e = enrollment
                if (e != null) {
                    // Six identical frames are not six useful enrollment examples. Keep only
                    // genuinely different embeddings so the profile covers small pose changes.
                    val duplicate = e.samples.lastOrNull()?.let { cosine(it, emb) >= 0.995f } == true
                    if (!duplicate) e.samples += emb
                    val instruction = when (e.samples.size) {
                        0 -> "centralize o rosto"
                        1, 2 -> "olhe reto"
                        3, 4 -> "vire levemente para a esquerda"
                        5, 6 -> "vire levemente para a direita"
                        7, 8 -> "incline um pouco para cima"
                        else -> "mantenha o rosto dentro da câmera"
                    }
                    _status.value = "Cadastrando ${e.name}… ${e.samples.size}/$SAMPLES — $instruction"
                    Log.d(TAG, "enroll name=${e.name} sample=${e.samples.size}/$SAMPLES duplicate=$duplicate crop=${face.width}x${face.height}")
                    if (e.samples.size >= SAMPLES) finishEnrollment(e)
                } else {
                    val sighting = stabilize(identify(emb))
                    sighting.person?.let { p -> if (sighting.similarity >= config.current.people.threshold + 0.12f) learn(p, emb) }
                    Log.d(TAG, "identify person=${sighting.person?.name ?: "unknown"} similarity=${sighting.similarity}")
                    onSighting(sighting)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "recognition failed", t)
            } finally {
                busy = false
            }
        }
    }

    /** Starts capturing samples of whoever is in front of the camera. Returns the saved person, or null on timeout. */
    suspend fun enroll(name: String): PersonEntity? {
        if (ensureModel() == null) return null
        val e = Enrollment(name.trim())
        enrollment = e
        _status.value = "Olhe para a câmera, ${e.name}…"
        val result = withTimeoutOrNull(20_000) { e.done.await() }
        if (result == null) _status.value = "Não consegui ver um rosto. Tente de novo com mais luz."
        enrollment = null
        return result
    }

    private suspend fun finishEnrollment(e: Enrollment) {
        enrollment = null
        val existing = dao.byName(e.name)
        val samples = (existing?.let { decode(it.embeddings) }.orEmpty() + e.samples).takeLast(20)
        val saved = if (existing != null) existing.copy(embeddings = encode(samples)).also { dao.update(it) }
        else PersonEntity(name = e.name, embeddings = encode(samples)).let { it.copy(id = dao.insert(it)) }
        cache = null
        _status.value = "${e.name} cadastrado(a) ✓"
        scope.launch {
            delay(3_000)
            if (_status.value.contains("cadastrado")) _status.value = ""
        }
        onEnrolled(saved)
        e.done.complete(saved)
    }

    private var lastLearnAt = 0L

    /** Adds a confidently recognized but *new-looking* view of the person to their profile (max 30 samples). */
    private suspend fun learn(p: PersonEntity, emb: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - lastLearnAt < 60_000) return
        val fresh = dao.get(p.id) ?: return
        val samples = decode(fresh.embeddings)
        if (samples.any { cosine(it, emb) > 0.9f }) return // already have a view like this
        lastLearnAt = now
        dao.update(fresh.copy(embeddings = encode((samples + emb).takeLast(30))))
        cache = null
        Log.d(TAG, "learned a new view of ${p.name} (${samples.size + 1} samples)")
    }

    /** Old profiles (pre-alignment) don't match the new embeddings: clear their samples once and ask to re-enroll. */
    suspend fun migratePipeline() {
        val prefs = context.getSharedPreferences("people", Context.MODE_PRIVATE)
        if (prefs.getInt("pipeline", 1) >= PIPELINE_VERSION) return
        val stale = dao.all().filter { decode(it.embeddings).isNotEmpty() }
        stale.forEach { dao.update(it.copy(embeddings = "[]")) }
        cache = null
        prefs.edit().putInt("pipeline", PIPELINE_VERSION).apply()
        if (stale.isNotEmpty()) _status.value = "Reconhecimento facial melhorado: recadastre ${stale.joinToString { it.name }}"
    }

    suspend fun list() = dao.all()
    suspend fun delete(id: Long) = (dao.delete(id) > 0).also { cache = null }
    suspend fun update(p: PersonEntity) { dao.update(p); cache = null }

    suspend fun updateNotes(name: String, notes: String): PersonEntity? {
        val person = dao.byName(name) ?: return null
        val updated = person.copy(notes = notes.trim())
        dao.update(updated)
        cache = null
        return updated
    }

    suspend fun markSeen(p: PersonEntity) = dao.update(p.copy(lastSeen = System.currentTimeMillis())).also { cache = null }

    /** Do not change identity from one noisy/blurred frame. */
    private fun stabilize(sighting: Sighting): Sighting {
        val person = sighting.person ?: run {
            if (System.currentTimeMillis() - candidateAt > 2_500L) {
                candidateName = null
                candidateHits = 0
            }
            return sighting
        }
        val now = System.currentTimeMillis()
        if (person.name == candidateName && now - candidateAt <= 2_500L) candidateHits++
        else {
            candidateName = person.name
            candidateHits = 1
        }
        candidateAt = now
        return if (candidateHits >= 2) sighting else Sighting(null, sighting.similarity)
    }

    private suspend fun identify(emb: FloatArray): Sighting {
        val people = cache ?: dao.all().map { it to decode(it.embeddings) }.also { cache = it }
        var best: PersonEntity? = null
        var bestSim = -1f
        var secondSim = -1f
        for ((p, samples) in people) {
            if (samples.isEmpty()) continue
            // Compare against the profile centroid instead of one lucky/noisy frame.
            val centroid = FloatArray(samples.first().size)
            samples.forEach { sample -> sample.forEachIndexed { i, v -> centroid[i] += v } }
            var norm = 0f
            centroid.forEach { norm += it * it }
            norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-6f)
            val centroidSim = cosine(emb, FloatArray(centroid.size) { centroid[it] / norm })
            // A centroid is stable, but it can blur profiles captured at different
            // distances or lighting. Keep the strongest valid enrollment sample too.
            // Mean of the 3 closest samples: robust to one lucky/odd sample, still covers pose variety.
            val topMean = samples.map { cosine(emb, it) }.sortedDescending().take(3).average().toFloat()
            val sim = 0.5f * centroidSim + 0.5f * topMean
            if (sim > bestSim) {
                secondSim = bestSim
                bestSim = sim
                best = p
            } else if (sim > secondSim) {
                secondSim = sim
            }
        }
        val margin = bestSim - secondSim
        val configuredThreshold = config.current.people.threshold
        // With one enrolled person, low-light/distant frames can fluctuate by ~0.04
        // between adjacent captures. Keep a conservative floor, while multiple profiles
        // still use the configured rigor and the margin check below.
        val threshold = if (people.size == 1) {
            minOf(configuredThreshold, 0.52f)
        } else {
            configuredThreshold.coerceIn(0.4f, 0.78f)
        }
        Log.d(TAG, "scores best=${best?.name ?: "none"} bestSim=$bestSim secondSim=$secondSim threshold=$threshold configured=$configuredThreshold margin=$margin")
        return if (best != null && bestSim >= threshold && (secondSim < 0f || margin >= 0.035f)) {
            Sighting(best, bestSim)
        } else {
            Sighting(null, bestSim)
        }
    }

    // ------------------------------------------------------------ model

    private suspend fun ensureModel(): Interpreter? = modelLock.withLock {
        interpreter?.let { return it }
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, "models/face.tflite")
                if (!file.exists() || file.length() < 100_000) {
                    _status.value = "Baixando modelo de rosto…"
                    file.parentFile?.mkdirs()
                    http.newCall(Request.Builder().url(config.current.people.modelUrl).build()).execute().use { res ->
                        if (!res.isSuccessful) error("HTTP ${res.code}")
                        val tmp = File(file.parentFile, "face.tmp")
                        tmp.outputStream().use { res.body!!.byteStream().copyTo(it) }
                        tmp.renameTo(file)
                    }
                }
                val interp = Interpreter(file, Interpreter.Options().setNumThreads(2))
                val inShape = interp.getInputTensor(0).shape() // [batch, h, w, 3]
                batch = inShape[0].coerceAtLeast(1)
                inputSize = inShape[1]
                outputSize = interp.getOutputTensor(0).shape().last()
                _status.value = ""
                interp
            }.onFailure {
                Log.w(TAG, "face model unavailable", it)
                _status.value = "Modelo de rosto indisponível: ${it.message}"
            }.getOrNull()
        }.also { interpreter = it }
    }

    private suspend fun embed(face: Bitmap): FloatArray? {
        val interp = ensureModel() ?: return null
        val scaled = Bitmap.createScaledBitmap(face, inputSize, inputSize, true)
        val prepared = compensateLowLight(scaled)
        val input = ByteBuffer.allocateDirect(batch * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        val px = IntArray(inputSize * inputSize)
        prepared.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
        repeat(batch) { // some MobileFaceNet exports expect a pair; feed the same face
            for (p in px) {
                input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        val output = Array(batch) { FloatArray(outputSize) }
        synchronized(interp) { interp.run(input, output) }
        val direct = normalize(output[0])
        // Test-time augmentation: average with the mirrored face (standard trick, steadier embeddings).
        val flipped = runEmbedding(interp, android.graphics.Bitmap.createBitmap(prepared, 0, 0, prepared.width, prepared.height,
            android.graphics.Matrix().apply { postScale(-1f, 1f, prepared.width / 2f, prepared.height / 2f) }, false)) ?: return direct
        return normalize(FloatArray(direct.size) { direct[it] + flipped[it] })
    }

    private fun runEmbedding(interp: Interpreter, bmp: Bitmap): FloatArray? = runCatching {
        val input = ByteBuffer.allocateDirect(batch * inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
        val px = IntArray(inputSize * inputSize)
        bmp.getPixels(px, 0, inputSize, 0, 0, inputSize, inputSize)
        repeat(batch) {
            for (p in px) {
                input.putFloat(((p shr 16 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p shr 8 and 0xFF) - 127.5f) / 128f)
                input.putFloat(((p and 0xFF) - 127.5f) / 128f)
            }
        }
        val output = Array(batch) { FloatArray(outputSize) }
        synchronized(interp) { interp.run(input, output) }
        normalize(output[0])
    }.getOrNull()

    /** Front cameras can underexpose a face at night; lift only genuinely dark crops. */
    private fun compensateLowLight(bitmap: Bitmap): Bitmap {
        val sample = IntArray(256)
        bitmap.getPixels(sample, 0, 16, 0, 0, 16, 16)
        val average = sample.map { ((it shr 16 and 0xFF) * 0.299f + (it shr 8 and 0xFF) * 0.587f + (it and 0xFF) * 0.114f) }.average().toFloat()
        if (average >= 82f) return bitmap
        val factor = (108f / average.coerceAtLeast(18f)).coerceIn(1.0f, 1.55f)
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Config.ARGB_8888)
        val matrix = ColorMatrix(floatArrayOf(
            factor, 0f, 0f, 0f, 0f,
            0f, factor, 0f, 0f, 0f,
            0f, 0f, factor, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        Canvas(out).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

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

    private val listSerializer = ListSerializer(ListSerializer(Float.serializer()))
    private fun encode(samples: List<FloatArray>) = AppJson.encodeToString(listSerializer, samples.map { it.toList() })
    private fun decode(json: String): List<FloatArray> =
        runCatching { AppJson.decodeFromString(listSerializer, json).map { it.toFloatArray() } }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "People"
        private const val SAMPLES = 12
        /** Bump when the embedding pipeline changes: old profiles are incompatible and must be re-enrolled. */
        const val PIPELINE_VERSION = 2
    }
}
