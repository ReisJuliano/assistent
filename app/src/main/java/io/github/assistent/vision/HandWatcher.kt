package io.github.assistent.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import io.github.assistent.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** One hand seen in a frame: palm centre (0..1, as the person sees it on the screen) and whether it is an open palm. */
data class HandSample(val t: Long, val x: Float, val y: Float, val openPalm: Boolean)

/**
 * Detects a wave: an open palm, raised, going side to side. Pure logic (unit tested) fed by [HandWatcher].
 * Needs at least two direction changes of a real amplitude within [windowMs], mostly with an open palm.
 */
class WaveDetector(private val windowMs: Long = 2_200, private val minSwing: Float = 0.035f) {
    private val samples = ArrayDeque<HandSample>()

    fun push(s: HandSample?, now: Long = s?.t ?: System.currentTimeMillis()): Boolean {
        if (s != null) samples.addLast(s)
        while (samples.isNotEmpty() && now - samples.first().t > windowMs) samples.removeFirst()
        if (samples.size < 5) return false
        val open = samples.count { it.openPalm } / samples.size.toFloat()
        val raised = samples.count { it.y < 0.75f } / samples.size.toFloat()
        if (open < 0.6f || raised < 0.6f) return false
        // Count direction changes of the palm's x, ignoring jitter smaller than minSwing.
        var reversals = 0
        var dir = 0
        var anchor = samples.first().x
        for (p in samples) {
            val d = p.x - anchor
            if (kotlin.math.abs(d) < minSwing) continue
            val nd = if (d > 0) 1 else -1
            if (dir != 0 && nd != dir) reversals++
            dir = nd
            anchor = p.x
        }
        if (reversals < 2) return false
        // A new wave needs new swings (the window starts over), so waving keeps getting answered, no cooldown.
        samples.clear()
        return true
    }
}

/**
 * Hands on camera with Google MediaPipe's gesture recognizer (hand landmarks + "Open_Palm" etc.), on the phone.
 * Fed small frames ~8/s only while someone is in front of the camera; reports waves (and gestures by name).
 * The model (~8 MB, Apache-2.0) is downloaded at runtime to `filesDir/models/gesture_recognizer.task`.
 */
class HandWatcher(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    var onWave: () -> Unit = {}
    /** Other recognised gestures: Thumb_Up, Thumb_Down, Victory, Pointing_Up, Closed_Fist, ILoveYou. */
    var onGesture: (String) -> Unit = {}

    private val wave = WaveDetector()
    @Volatile private var recognizer: GestureRecognizer? = null
    @Volatile private var loading = false
    @Volatile private var failed = false
    @Volatile private var busy = false
    private var lastGesture = ""
    /** Latest gesture of any kind (also Open_Palm / Closed_Fist) and when: games read it (rock-paper-scissors). */
    @Volatile var lastSeen: Pair<String, Long> = "" to 0L
    private var lastGestureAt = 0L

    /** Upright frame (any size; it is not mirrored). Drops frames while the previous one is still running. */
    fun onFrame(frame: Bitmap, now: Long = System.currentTimeMillis()) {
        if (busy || failed || !config.current.life.gestures) return
        val r = recognizer ?: return load()
        busy = true
        try {
            val result = r.recognize(BitmapImageBuilder(frame).build())
            val hand = result.landmarks().firstOrNull()
            val gesture = result.gestures().firstOrNull()?.firstOrNull()
            val name = gesture?.takeIf { it.score() >= 0.55f }?.categoryName().orEmpty()
            if (name.isNotBlank() && name != "None") lastSeen = name to now
            val sample = hand?.let { lm ->
                // Palm centre ≈ mean of wrist and the finger bases; mirror x so it matches what the person sees.
                val pts = listOf(0, 5, 9, 13, 17).map { lm[it] }
                HandSample(now, 1f - pts.map { it.x() }.average().toFloat(), pts.map { it.y() }.average().toFloat(), name == "Open_Palm")
            }
            if (wave.push(sample, now)) {
                Log.d(TAG, "wave")
                onWave()
            } else if (name.isNotBlank() && name != "None" && name != "Open_Palm" && (name != lastGesture || now - lastGestureAt > 5_000)) {
                lastGesture = name
                lastGestureAt = now
                onGesture(name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "recognize failed: ${e.message}")
        } finally {
            busy = false
        }
    }

    private fun load() {
        if (loading || failed) return
        loading = true
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "models/gesture_recognizer.task")
                if (!file.exists() || file.length() < 1_000_000) {
                    file.parentFile?.mkdirs()
                    http.newCall(Request.Builder().url(config.current.life.gestureModelUrl).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val tmp = File(file.parentFile, "gesture.tmp")
                        tmp.outputStream().use { r.body!!.byteStream().copyTo(it) }
                        tmp.renameTo(file)
                    }
                }
                val buffer = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
                recognizer = GestureRecognizer.createFromOptions(
                    context,
                    GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.5f)
                        .build(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "gesture model unavailable", e)
                failed = true
            } finally {
                loading = false
            }
        }
    }

    /** Runs the recognizer once on an image (API tests): gesture name and palm position, or null. */
    fun recognizeOnce(frame: Bitmap): String? {
        val r = recognizer ?: run { load(); return "carregando modelo, tente de novo em alguns segundos" }
        val result = r.recognize(BitmapImageBuilder(frame).build())
        val g = result.gestures().firstOrNull()?.firstOrNull() ?: return null
        val lm = result.landmarks().firstOrNull()
        return "${g.categoryName()} ${"%.2f".format(g.score())}" + (lm?.let { " palm=(%.2f,%.2f)".format(1f - it[9].x(), it[9].y()) } ?: "")
    }

    private companion object { const val TAG = "HandWatcher" }
}
