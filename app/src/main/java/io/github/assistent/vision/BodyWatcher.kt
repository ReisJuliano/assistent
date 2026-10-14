package io.github.assistent.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.data.PersonEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.min

/** One object seen by the camera: COCO [label], [score] and its box (0..1, upright frame). */
data class SeenObject(val label: String, val score: Float, val box: RectF)

/**
 * Sees whole bodies and objects, not only faces: EfficientDet-Lite0 (COCO, 80 objects: person, cat, dog, cup,
 * book, cell phone…) on a downscaled camera frame about once a second, all on the phone.
 *
 * "Outfit of the day": when a face is recognised, the colours of that person's torso (just below the face) are
 * remembered for the day. Later, a body whose face is turned away or too far gets compared with today's outfits —
 * a weak but useful "that's probably Ana walking by". These guesses mark presence in the house; they never become
 * the trusted identity used in conversation (that stays face-only).
 *
 * The model (~4.6 MB, Apache-2.0) is downloaded at runtime to `filesDir/models/objects.tflite`.
 */
class BodyWatcher(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    /** Objects in the latest analysed frame. */
    var onObjects: (List<SeenObject>) -> Unit = {}
    /** A body matched someone's outfit of the day. */
    var onOutfitMatch: (personId: Long, score: Float) -> Unit = { _, _ -> }

    private class Outfit(val signature: FloatArray, val at: Long)

    private val outfits = HashMap<Long, Outfit>()
    private val lock = Mutex()
    private var interp: Interpreter? = null
    private var labels: List<String> = emptyList()
    @Volatile private var failed = false
    @Volatile private var busy = false
    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastFace: RectF? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var faceRecognized = false

    /** Called by the camera ~1/s with an upright downscaled frame and the tracked face box (0..1), if any. */
    fun onFrame(frame: Bitmap, face: RectF?) {
        lastFrame = frame
        lastFace = face
        lastFrameAt = System.currentTimeMillis()
        if (busy || failed) return
        busy = true
        scope.launch(Dispatchers.Default) {
            try {
                val objects = detect(frame) ?: return@launch
                onObjects(objects)
                if (config.current.people.outfits && !faceRecognized) matchOutfits(frame, objects)
            } catch (e: Exception) {
                Log.w(TAG, "detect failed: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    /** The face in front is a known person right now: remember their outfit and don't guess by clothes. */
    fun onFaceRecognized(person: PersonEntity) {
        faceRecognized = true
        val frame = lastFrame ?: return
        val face = lastFace ?: return
        if (!config.current.people.outfits || System.currentTimeMillis() - lastFrameAt > 3_000) return
        // Torso: from a bit below the chin, as wide as the shoulders.
        val fw = face.width()
        val fh = face.height()
        val torso = RectF(face.centerX() - fw * 1.0f, face.bottom + fh * 0.35f, face.centerX() + fw * 1.0f, face.bottom + fh * 2.0f)
        val visible = RectF(torso).apply { if (!intersect(0f, 0f, 1f, 1f)) setEmpty() }
        if (visible.height() < torso.height() * 0.45f || visible.width() < 0.05f) return
        val sig = signature(frame, visible) ?: return
        synchronized(outfits) {
            val old = outfits[person.id]
            // Blend with the morning's sample so lighting changes during the day don't break it.
            val merged = if (old != null && sameDay(old.at)) FloatArray(sig.size) { old.signature[it] * 0.7f + sig[it] * 0.3f } else sig
            outfits[person.id] = Outfit(merged, System.currentTimeMillis())
        }
    }

    fun onFaceGone() { faceRecognized = false }

    private fun matchOutfits(frame: Bitmap, objects: List<SeenObject>) {
        val today = synchronized(outfits) { outfits.filterValues { sameDay(it.at) && System.currentTimeMillis() - it.at < 12 * 3600_000L } }
        if (today.isEmpty()) return
        for (body in objects.filter { it.label == "person" && it.score >= 0.5f && it.box.height() >= 0.3f }) {
            val b = body.box
            val torso = RectF(b.left + b.width() * 0.25f, b.top + b.height() * 0.22f, b.right - b.width() * 0.25f, b.top + b.height() * 0.55f)
            val sig = signature(frame, torso) ?: continue
            val scores = today.mapValues { similarity(sig, it.value.signature) }.entries.sortedByDescending { it.value }
            val best = scores.firstOrNull() ?: continue
            val second = scores.getOrNull(1)?.value ?: 0f
            if (best.value >= MATCH && best.value - second >= 0.08f) onOutfitMatch(best.key, best.value)
        }
    }

    private suspend fun detect(frame: Bitmap): List<SeenObject>? {
        val m = model() ?: return null
        val shape = m.getInputTensor(0).shape() // [1, h, w, 3]
        val ih = shape[1]
        val iw = shape[2]
        val scaled = Bitmap.createScaledBitmap(frame, iw, ih, true)
        val px = IntArray(iw * ih)
        scaled.getPixels(px, 0, iw, 0, 0, iw, ih)
        val quant = m.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.UINT8
        val input = ByteBuffer.allocateDirect(iw * ih * 3 * if (quant) 1 else 4).order(ByteOrder.nativeOrder())
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (quant) { input.put(r.toByte()); input.put(g.toByte()); input.put(b.toByte()) }
            else { input.putFloat(r / 255f); input.putFloat(g / 255f); input.putFloat(b / 255f) }
        }
        input.rewind()
        val outs = HashMap<Int, Any>()
        val n = m.outputTensorCount
        for (i in 0 until n) {
            val s = m.getOutputTensor(i).shape()
            outs[i] = when (s.size) {
                3 -> Array(s[0]) { Array(s[1]) { FloatArray(s[2]) } }
                2 -> Array(s[0]) { FloatArray(s[1]) }
                else -> FloatArray(s.getOrElse(0) { 1 })
            }
        }
        lock.withLock { m.runForMultipleInputsOutputs(arrayOf(input), outs) }
        // Standard detection post-process outputs: boxes [1,N,4], classes [1,N], scores [1,N], count [1].
        @Suppress("UNCHECKED_CAST")
        val boxes = outs.values.first { it is Array<*> && (it as Array<*>).firstOrNull() is Array<*> } as Array<Array<FloatArray>>
        @Suppress("UNCHECKED_CAST")
        val rows = outs.values.filter { it is Array<*> && (it as Array<*>).firstOrNull() is FloatArray }.map { (it as Array<FloatArray>)[0] }
        if (rows.size < 2) return emptyList()
        // Scores are all within 0..1; class ids go up to ~90.
        val scores = rows.firstOrNull { r -> r.all { it in 0f..1f } } ?: rows[1]
        val classes = rows.first { it !== scores }
        val count = (outs.values.firstOrNull { it is FloatArray } as FloatArray?)?.firstOrNull()?.toInt() ?: scores.size
        return (0 until min(count, scores.size)).mapNotNull { i ->
            if (scores[i] < 0.4f) return@mapNotNull null
            val label = labels.getOrNull(classes[i].toInt()) ?: return@mapNotNull null
            if (label == "???") return@mapNotNull null
            val bb = boxes[0][i] // ymin, xmin, ymax, xmax
            SeenObject(label, scores[i], RectF(bb[1].coerceIn(0f, 1f), bb[0].coerceIn(0f, 1f), bb[3].coerceIn(0f, 1f), bb[2].coerceIn(0f, 1f)))
        }
    }

    /** Runs the detector on any image (API tests). */
    suspend fun detectOnce(bitmap: Bitmap): List<SeenObject> = detect(bitmap) ?: error("object model unavailable")

    private suspend fun model(): Interpreter? {
        interp?.let { return it }
        return lock.withLock {
            interp ?: runCatching {
                val file = File(context.filesDir, "models/objects.tflite")
                if (!file.exists() || file.length() < 1_000_000) {
                    file.parentFile?.mkdirs()
                    http.newCall(Request.Builder().url(config.current.people.objectModelUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val tmp = File(file.parentFile, "objects.tmp")
                        tmp.outputStream().use { r.body!!.byteStream().copyTo(it) }
                        tmp.renameTo(file)
                    }
                }
                labels = embeddedLabels(file.readBytes())
                Interpreter(file, Interpreter.Options().setNumThreads(2))
            }.onFailure {
                Log.w(TAG, "object model unavailable", it)
                failed = true
            }.getOrNull().also { interp = it }
        }
    }

    companion object {
        private const val TAG = "BodyWatcher"
        /** Histogram intersection needed to call an outfit match. */
        const val MATCH = 0.72f
        private const val HB = 12
        private const val SB = 3
        private const val VB = 3

        /** Labels file packed inside the model's metadata (a zip appended to the .tflite). */
        fun embeddedLabels(model: ByteArray): List<String> {
            val sig = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
            var start = -1
            for (i in 0..model.size - 4) if (model[i] == sig[0] && model[i + 1] == sig[1] && model[i + 2] == sig[2] && model[i + 3] == sig[3]) { start = i; break }
            if (start < 0) return emptyList()
            ZipInputStream(model.inputStream(start, model.size - start)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name.endsWith(".txt")) return z.readBytes().decodeToString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            return emptyList()
        }

        /** HSV colour histogram of a region (normalised to sum 1); grey/dark pixels go to value-only bins. */
        fun signature(frame: Bitmap, region: RectF): FloatArray? {
            val l = (region.left * frame.width).toInt().coerceIn(0, frame.width - 1)
            val t = (region.top * frame.height).toInt().coerceIn(0, frame.height - 1)
            val r = (region.right * frame.width).toInt().coerceIn(l + 1, frame.width)
            val b = (region.bottom * frame.height).toInt().coerceIn(t + 1, frame.height)
            if (r - l < 8 || b - t < 8) return null
            val hist = FloatArray(HB * SB * VB + VB)
            val hsv = FloatArray(3)
            val step = maxOf(1, minOf(r - l, b - t) / 24)
            var total = 0f
            var y = t
            while (y < b) {
                var x = l
                while (x < r) {
                    Color.colorToHSV(frame.getPixel(x, y), hsv)
                    val v = (hsv[2] * VB).toInt().coerceAtMost(VB - 1)
                    val bin = if (hsv[1] < 0.18f || hsv[2] < 0.15f) HB * SB * VB + v
                    else ((hsv[0] / 360f * HB).toInt().coerceAtMost(HB - 1) * SB + (hsv[1] * SB).toInt().coerceAtMost(SB - 1)) * VB + v
                    hist[bin] += 1f
                    total += 1f
                    x += step
                }
                y += step
            }
            if (total == 0f) return null
            for (i in hist.indices) hist[i] /= total
            return hist
        }

        /** Histogram intersection: 1 = same colour distribution. */
        fun similarity(a: FloatArray, b: FloatArray) = a.indices.sumOf { min(a[it], b[it]).toDouble() }.toFloat()

        private fun sameDay(at: Long): Boolean {
            val z = java.time.ZoneId.systemDefault()
            return java.time.Instant.ofEpochMilli(at).atZone(z).toLocalDate() == java.time.LocalDate.now(z)
        }
    }
}
