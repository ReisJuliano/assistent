package io.github.assistent.voice

import android.content.Context
import android.util.Log
import io.github.assistent.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * What the robot *hears* besides words: Google's YAMNet (AudioSet, 521 sound classes) running on the phone over
 * the same microphone stream as the wake word. About once a second it classifies the last ~1 s of audio and
 * reports the sounds we care about (music, speech, cat, dog, doorbell, sneeze...).
 *
 * The model (~4 MB, Apache-2.0) is downloaded at runtime to `filesDir/models/yamnet.tflite`.
 */
class SoundEars(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    /** A sound we recognised: [kind] (see [GROUPS]) and its score (0..1). */
    var onSound: (kind: String, score: Float) -> Unit = { _, _ -> }

    private val ring = FloatArray(WINDOW)
    @Volatile private var pos = 0
    @Volatile private var fed = 0L
    private var interp: Interpreter? = null
    private val lock = Mutex()
    @Volatile private var failed = false
    @Volatile private var lastHeard = mapOf<String, Long>()

    /** Last time each kind was heard (ms), e.g. to know if music is playing right now. */
    fun heardWithin(kind: String, ms: Long) = System.currentTimeMillis() - (lastHeard[kind] ?: 0L) < ms

    /** Raw 16 kHz mono PCM from the wake-word loop. Cheap: only copies into a ring buffer. */
    fun feed(buf: ShortArray, n: Int) {
        var p = pos
        for (i in 0 until n) { ring[p] = buf[i] / 32768f; p = (p + 1) % WINDOW }
        pos = p
        fed += n
    }

    fun start() {
        scope.launch(Dispatchers.Default) {
            var lastFed = 0L
            while (true) {
                delay(1_000)
                if (!config.current.life.soundEvents || failed) continue
                if (fed - lastFed < WINDOW / 2) continue // microphone not running (speaking, listening, off)
                lastFed = fed
                val model = model() ?: continue
                val input = FloatArray(WINDOW) { ring[(pos + it) % WINDOW] }
                val scores = runCatching { classify(model, input) }.onFailure { Log.w(TAG, "classify", it) }.getOrNull() ?: continue
                val now = System.currentTimeMillis()
                val heard = lastHeard.toMutableMap()
                for ((kind, idx) in GROUPS) {
                    val score = idx.maxOf { scores.getOrElse(it) { 0f } }
                    if (score >= (THRESHOLDS[kind] ?: 0.4f)) {
                        heard[kind] = now
                        onSound(kind, score)
                    }
                }
                lastHeard = heard
            }
        }
    }

    /** Classifies a clip (16 kHz mono): the best score of each group over its ~1 s windows. For tests and the API. */
    suspend fun classifyClip(wave: FloatArray): Map<String, Float> {
        val model = model() ?: error("sound model unavailable")
        val best = HashMap<String, Float>()
        var start = 0
        while (start == 0 || start + WINDOW <= wave.size) {
            val w = FloatArray(WINDOW) { wave.getOrElse(start + it) { 0f } }
            val scores = lock.withLock { classify(model, w) }
            for ((kind, idx) in GROUPS) best[kind] = maxOf(best[kind] ?: 0f, idx.maxOf { scores[it] })
            start += WINDOW / 2
        }
        return best.filterValues { it >= 0.05f }.toList().sortedByDescending { it.second }.toMap()
    }

    private fun classify(model: Interpreter, wave: FloatArray): FloatArray {
        val inShape = model.getInputTensor(0).shape()
        val input: Any = if (inShape.size == 2) arrayOf(wave) else wave
        // The scores output is the one with 521 classes (some exports also return embeddings / spectrogram).
        val outIndex = (0 until model.outputTensorCount).first { model.getOutputTensor(it).shape().last() == 521 }
        val outShape = model.getOutputTensor(outIndex).shape()
        val frames = if (outShape.size == 2) outShape[0] else 1
        val out = Array(frames) { FloatArray(521) }
        val outputs = HashMap<Int, Any>()
        for (i in 0 until model.outputTensorCount) {
            val s = model.getOutputTensor(i).shape()
            outputs[i] = if (i == outIndex) out else Array(s.getOrElse(0) { 1 }) { FloatArray(s.last()) }
        }
        model.runForMultipleInputsOutputs(arrayOf(input), outputs)
        // Average over the model's internal frames.
        return FloatArray(521) { c -> out.sumOf { it[c].toDouble() }.toFloat() / frames }
    }

    private suspend fun model(): Interpreter? = lock.withLock {
        interp?.let { return it }
        runCatching {
            val file = File(context.filesDir, "models/yamnet.tflite")
            if (!file.exists() || file.length() < 1_000_000) {
                file.parentFile?.mkdirs()
                http.newCall(Request.Builder().url(config.current.life.soundModelUrl).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val tmp = File(file.parentFile, "yamnet.tmp")
                    tmp.outputStream().use { r.body!!.byteStream().copyTo(it) }
                    tmp.renameTo(file)
                }
            }
            Interpreter(file, Interpreter.Options().setNumThreads(1)).also { m ->
                if (m.getInputTensor(0).shape().size == 1 && m.getInputTensor(0).shape()[0] != WINDOW) m.resizeInput(0, intArrayOf(WINDOW))
                m.allocateTensors()
            }
        }.onFailure {
            Log.w(TAG, "sound model unavailable", it)
            failed = true
        }.getOrNull().also { interp = it }
    }

    companion object {
        private const val TAG = "SoundEars"
        /** YAMNet's native window: 0.975 s at 16 kHz. */
        const val WINDOW = 15_600

        /** AudioSet class indices (yamnet_class_map.csv) grouped into the things the robot reacts to. */
        val GROUPS = mapOf(
            "speech" to listOf(0),
            "music" to listOf(132, 133, 24),
            "humming" to listOf(32, 35),
            "cat" to listOf(76, 77, 78, 80),
            "dog" to listOf(69, 70, 72),
            "doorbell" to listOf(349, 350),
            "knock" to listOf(353),
            "sneeze" to listOf(44),
            "cough" to listOf(42),
            "laughter" to listOf(13),
            "baby_cry" to listOf(20, 19),
            "thunder" to listOf(281, 280),
            "applause" to listOf(58, 62),
            "phone_ring" to listOf(384),
            "alarm" to listOf(389, 393),
        )
        private val THRESHOLDS = mapOf(
            "speech" to 0.5f, "music" to 0.35f, "cat" to 0.3f, "dog" to 0.3f, "sneeze" to 0.22f, "laughter" to 0.25f, "baby_cry" to 0.25f,
        )
    }
}
