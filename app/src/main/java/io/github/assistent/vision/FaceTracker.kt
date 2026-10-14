package io.github.assistent.vision

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size
import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import io.github.assistent.core.Gaze
import java.util.concurrent.Executors

/**
 * Front camera + on-device ML Kit. Reports where the user is (eyes follow them), their smile, head tilt and
 * winks (the robot mirrors them), and — every few seconds — what objects are around (headphones, a cup, a
 * book…) so the robot can imitate. No image is stored or sent anywhere.
 */
class FaceTracker(
    private val context: Context,
    private val onGaze: (Gaze) -> Unit,
    private val onObjects: (Set<String>) -> Unit = {},
    private val labelObjects: () -> Boolean = { false },
    private val wantsCrop: () -> Boolean = { false },
    private val onFaceCrop: (android.graphics.Bitmap) -> Unit = {},
    private val onMotion: () -> Unit = {},
    private val onSubjectChanged: () -> Unit = {},
    private val scanQr: () -> Boolean = { false },
    private val onQr: (String) -> Unit = {},
    /** Centroid x (0..1, mirrored like the robot's view) of the moving area and the moving fraction. */
    private val onMotionCentroid: (Float, Float) -> Unit = { _, _ -> },
    /** Mean brightness 0..255 (covering the camera ≈ 0). */
    private val onBrightness: (Float) -> Unit = {},
    private val powerSave: () -> Boolean = { true },
    private val sleeping: () -> Boolean = { false },
    /** Whole-frame consumers (bodies/objects): want a frame now? */
    private val wantsFrame: () -> Boolean = { false },
    /** Upright, downscaled frame (~1/s) and the tracked face box in 0..1, if any. */
    private val onFrame: (android.graphics.Bitmap, android.graphics.RectF?) -> Unit = { _, _ -> },
) {
    private var lastFrameOut = 0L
    private val barcodes by lazy { com.google.mlkit.vision.barcode.BarcodeScanning.getClient() }
    private var prevLuma: IntArray? = null
    private var motionFrames = 0
    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            // Eye landmarks let us align faces before recognition (big accuracy win for MobileFaceNet).
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .enableTracking()
            // Detect smaller faces too; the embedding model upsamples the crop afterwards.
            .setMinFaceSize(0.05f)
            .build(),
    )
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.65f).build())
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var analysis: ImageAnalysis? = null
    private var previewView: PreviewView? = null
    private var camera: Camera? = null
    private var lastSeen = 0L
    private var lastFrame = 0L
    private var lastLabels = 0L
    private var lastFaceLog = 0L
    private var lastBrightness = 0f
    private var lastExposureChange = 0L
    private var lockedBox: Rect? = null
    private var lockedMissingAt = 0L
    private var lockedTrackingId: Int? = null
    private var previousFaceCount = 0

    fun start(owner: LifecycleOwner) {
        this.owner = owner
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val p = future.get().also { provider = it }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            // More pixels are important when the person is several meters away.
                            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                this.analysis = analysis
                bind(p, owner)
            }.onFailure { Log.w(TAG, "camera unavailable", it) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun attachPreview(view: PreviewView) {
        previewView = view
        val p = provider
        val o = owner
        if (p != null && o != null) bind(p, o)
    }

    /** Removes the UI preview while keeping analysis alive for recognition and wake behavior. */
    fun detachPreview() {
        previewView = null
        val p = provider
        val o = owner
        if (p != null && o != null) bind(p, o)
    }

    private fun bind(p: ProcessCameraProvider, owner: LifecycleOwner) {
        val a = analysis ?: return
        p.unbindAll()
        val preview = previewView?.let { view ->
            Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        }
        camera = if (preview == null) p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
        else p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, a)
    }

    fun stop() {
        provider?.unbindAll()
        camera = null
        onGaze(Gaze())
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image
        // Power save: ~4 fps awake, ~1 fps asleep (only motion matters then).
        val interval = when { !powerSave() -> 80L; sleeping() -> 350L; else -> 250L }
        if (media == null || now - lastFrame < interval) { proxy.close(); return }
        lastFrame = now
        val rotation = proxy.imageInfo.rotationDegrees
        val upright = rotation == 90 || rotation == 270
        val w = (if (upright) proxy.height else proxy.width).toFloat()
        val h = (if (upright) proxy.width else proxy.height).toFloat()
        detectMotion(proxy)
        adjustExposure(now)
        val image = InputImage.fromMediaImage(media, rotation)
        try {
            val faces = Tasks.await(detector.process(image))
            val face = selectTrackedFace(faces, now, w, h)
            if (face != null) {
                lastSeen = now
                if (now - lastFaceLog > 2_000) {
                    lastFaceLog = now
                    Log.d(TAG, "face box=${face.boundingBox.width()}x${face.boundingBox.height()} image=${w.toInt()}x${h.toInt()} brightness=${lastBrightness.toInt()}")
                }
                // Keep distant faces: Bitmap scaling in People can recover detail better than
                // dropping the sighting at this stage.
                if (face.boundingBox.width() > 35 && wantsCrop() && goodForRecognition(face)) {
                    (alignedFace(proxy, face, rotation) ?: cropFace(proxy, face.boundingBox, rotation))?.let(onFaceCrop)
                }
                val nx = face.boundingBox.exactCenterX() / w * 2f - 1f
                val ny = face.boundingBox.exactCenterY() / h * 2f - 1f
                // Front camera image is not mirrored: user on screen-left appears on image-right. The robot behaves like a
                // mirror, so the subject's left eye maps to the robot's left eye and the roll keeps its on-screen direction.
                onGaze(
                    Gaze(
                        x = (-nx * 1.3f).coerceIn(-1f, 1f),
                        y = (ny * 1.3f).coerceIn(-1f, 1f),
                        smiling = face.smilingProbability ?: 0f,
                        present = true,
                        tilt = face.headEulerAngleZ.coerceIn(-30f, 30f),
                        leftEyeOpen = face.leftEyeOpenProbability ?: 1f,
                        rightEyeOpen = face.rightEyeOpenProbability ?: 1f,
                        size = face.boundingBox.width() / w,
                        yaw = face.headEulerAngleY,
                        pitch = face.headEulerAngleX,
                    ),
                )
            } else if (now - lastSeen > 900) {
                onGaze(Gaze())
            }
            if (!sleeping() && wantsFrame() && now - lastFrameOut > 1_000) {
                lastFrameOut = now
                uprightFrame(proxy, rotation)?.let { frame ->
                    val box = face?.boundingBox?.let { b -> android.graphics.RectF(b.left / w, b.top / h, b.right / w, b.bottom / h) }
                    onFrame(frame, box)
                }
            }
            if (scanQr()) {
                Tasks.await(barcodes.process(image)).firstNotNullOfOrNull { it.rawValue }?.let(onQr)
            }
            if (labelObjects() && face != null && now - lastLabels > 15_000) {
                lastLabels = now
                val labels = Tasks.await(labeler.process(image))
                onObjects(labels.map { it.text.lowercase() }.toSet())
            }
        } catch (e: Exception) {
            Log.w(TAG, "analysis failed: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    /**
     * Keeps one visual subject selected while several faces are visible. The selected face
     * may become smaller or larger, but it must remain near its previous position. If it
     * disappears for a moment, the largest remaining face becomes the new subject.
     */
    private fun selectTrackedFace(
        faces: List<com.google.mlkit.vision.face.Face>,
        now: Long,
        width: Float,
        height: Float,
    ): com.google.mlkit.vision.face.Face? {
        if (faces.isEmpty()) return null
        val previous = lockedBox
        if (previous == null) {
            return faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }.also {
                lockedBox = Rect(it.boundingBox)
                lockedTrackingId = it.trackingId
                previousFaceCount = faces.size
                lockedMissingAt = 0L
            }
        }
        // A new face entering the frame gets one recognition turn of its own. This prevents
        // the first person from remaining pinned forever when a second person arrives.
        if (faces.size > 1 && previousFaceCount <= 1 && lockedTrackingId != null) {
            faces.firstOrNull { it.trackingId != null && it.trackingId != lockedTrackingId }?.let {
                onSubjectChanged()
                lockedBox = Rect(it.boundingBox)
                lockedTrackingId = it.trackingId
                previousFaceCount = faces.size
                lockedMissingAt = 0L
                return it
            }
        }
        previousFaceCount = faces.size
        val px = previous.exactCenterX() / width
        val py = previous.exactCenterY() / height
        val previousArea = previous.width().toFloat() * previous.height().toFloat()
        val candidate = faces
            .map { face ->
                val box = face.boundingBox
                val dx = box.exactCenterX() / width - px
                val dy = box.exactCenterY() / height - py
                val area = box.width().toFloat() * box.height().toFloat()
                val ratio = area / previousArea.coerceAtLeast(1f)
                Triple(face, kotlin.math.sqrt(dx * dx + dy * dy), ratio)
            }
            .filter { (_, distance, ratio) -> distance < 0.45f && ratio in 0.18f..5.5f }
            .minByOrNull { it.second + kotlin.math.abs(kotlin.math.ln(it.third)) * 0.08f }
            ?.first
        if (candidate != null) {
            lockedBox = Rect(candidate.boundingBox)
            lockedTrackingId = candidate.trackingId
            lockedMissingAt = 0L
            return candidate
        }
        if (lockedMissingAt == 0L) lockedMissingAt = now
        if (now - lockedMissingAt < 900L) return null
        return faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }.also {
            lockedBox = Rect(it.boundingBox)
            lockedTrackingId = it.trackingId
            lockedMissingAt = 0L
        }
    }

    private fun adjustExposure(now: Long) {
        if (now - lastExposureChange < 3_000L) return
        val c = camera ?: return
        val range = c.cameraInfo.exposureState.exposureCompensationRange
        val target = when {
            lastBrightness < 45f -> range.upper.coerceAtMost(3)
            lastBrightness > 105f -> range.lower.coerceAtLeast(-1)
            else -> return
        }
        lastExposureChange = now
        c.cameraControl.setExposureCompensationIndex(target)
        Log.d(TAG, "exposure brightness=${lastBrightness.toInt()} index=$target range=$range")
    }

    /** Cheap motion detector: average luminance on a 24x18 grid, compared with the previous frame. */
    private fun detectMotion(proxy: ImageProxy) = runCatching {
        val plane = proxy.planes[0]
        val buf = plane.buffer
        val gw = 24
        val gh = 18
        val cells = IntArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x = (gx + 0.5f) * proxy.width / gw
            val y = (gy + 0.5f) * proxy.height / gh
            cells[gy * gw + gx] = buf.get(y.toInt() * plane.rowStride + x.toInt() * plane.pixelStride).toInt() and 0xFF
        }
        val prev = prevLuma
        prevLuma = cells
        lastBrightness = cells.average().toFloat()
        onBrightness(lastBrightness)
        if (prev != null) {
            // Where is the movement? (for wave detection) — image is not mirrored, the robot sees it mirrored.
            val upright = proxy.imageInfo.rotationDegrees.let { it == 90 || it == 270 }
            var sx = 0f; var cnt = 0
            for (gy in 0 until gh) for (gx in 0 until gw) {
                if (kotlin.math.abs(cells[gy * gw + gx] - prev[gy * gw + gx]) > 18) {
                    // For a 90/270 rotated sensor, image-y is the horizontal axis of the upright picture.
                    sx += if (upright) gy / (gh - 1f) else gx / (gw - 1f); cnt++
                }
            }
            if (cnt > 0) onMotionCentroid(1f - sx / cnt, cnt / cells.size.toFloat())
            // Ignore global brightness shifts (auto-exposure, lights): compare after removing the mean change.
            val meanShift = cells.indices.sumOf { cells[it] - prev[it] } / cells.size
            val changed = cells.indices.count { kotlin.math.abs(cells[it] - prev[it] - meanShift) > 18 }
            motionFrames = if (changed > cells.size * 0.06f) motionFrames + 1 else 0
            if (motionFrames >= 1) { motionFrames = 0; onMotion() }
        }
    }

    /** Upright, slightly padded square crop of the face for recognition. */
    /** The camera frame rotated upright and shrunk to at most 480 px (enough for object detection and colours). */
    private fun uprightFrame(proxy: ImageProxy, rotation: Int): android.graphics.Bitmap? = runCatching {
        val raw = proxy.toBitmap()
        val k = 480f / maxOf(raw.width, raw.height)
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()); if (k < 1f) postScale(k, k) }
        android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }.getOrNull()

    /** Only reasonably frontal, open-eyed, not tiny faces are worth embedding (bad frames poison the profile). */
    private fun goodForRecognition(face: com.google.mlkit.vision.face.Face): Boolean {
        if (kotlin.math.abs(face.headEulerAngleY) > 28f || kotlin.math.abs(face.headEulerAngleX) > 22f) return false
        val l = face.leftEyeOpenProbability
        val r = face.rightEyeOpenProbability
        if (l != null && r != null && l < 0.3f && r < 0.3f) return false
        return true
    }

    /**
     * ArcFace-style alignment: rotates/scales the upright frame so the eyes land on the standard 112×112 template
     * positions the embedding model was trained with. Returns null if eye landmarks are missing.
     */
    private fun alignedFace(proxy: ImageProxy, face: com.google.mlkit.vision.face.Face, rotation: Int): android.graphics.Bitmap? = runCatching {
        val a = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position ?: return null
        val b = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position ?: return null
        // Template eyes are image-left / image-right, whatever the subject's left/right is.
        val p1 = if (a.x <= b.x) a else b
        val p2 = if (a.x <= b.x) b else a
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist < 14f) return null // too far away to be useful
        val q1x = 38.2946f; val q1y = 51.6963f
        val q2x = 73.5318f; val q2y = 51.5014f
        val scale = kotlin.math.sqrt((q2x - q1x) * (q2x - q1x) + (q2y - q1y) * (q2y - q1y)) / dist
        val angle = Math.toDegrees(kotlin.math.atan2((q2y - q1y).toDouble(), (q2x - q1x).toDouble()) - kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val raw = proxy.toBitmap()
        val m = android.graphics.Matrix().apply {
            postRotate(rotation.toFloat()) // raw sensor -> upright (same frame as the landmarks, up to a translation)
        }
        val upright = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val align = android.graphics.Matrix().apply {
            postTranslate(-p1.x, -p1.y)
            postRotate(angle)
            postScale(scale, scale)
            postTranslate(q1x, q1y)
        }
        val out = android.graphics.Bitmap.createBitmap(112, 112, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(upright, align, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        out
    }.getOrNull()

    private fun cropFace(proxy: ImageProxy, box: android.graphics.Rect, rotation: Int): android.graphics.Bitmap? = runCatching {
        val raw = proxy.toBitmap()
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        val upright = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        // Keep the same face-to-canvas ratio at every distance. If the padded square crosses
        // the camera edge, preserve it with a margin instead of shrinking/cropping the face.
        val size = (maxOf(box.width(), box.height()) * 1.30f)
            .toInt()
            .coerceAtLeast(112)
        val left = box.centerX() - size / 2
        val top = box.centerY() - size / 2
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(upright, -left.toFloat(), -top.toFloat(), android.graphics.Paint())
        out
    }.getOrNull()

    companion object {
        private const val TAG = "FaceTracker"
    }
}
