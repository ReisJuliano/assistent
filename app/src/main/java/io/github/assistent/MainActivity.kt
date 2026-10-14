package io.github.assistent

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.assistent.core.Activity
import io.github.assistent.core.Ambient
import io.github.assistent.face.RobotFace
import io.github.assistent.service.AssistantService
import io.github.assistent.ui.AppTheme
import io.github.assistent.ui.SettingsScreen
import io.github.assistent.ui.SpeechBubble
import io.github.assistent.ui.Subtitles
import io.github.assistent.ui.TimerOverlay
import io.github.assistent.vision.FaceTracker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as AssistentApp).graph
    private var tracker: FaceTracker? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // Face config is applied live: orientation, keep-screen-on, camera tracking.
        lifecycleScope.launch {
            graph.config.config.map { it.face }.distinctUntilChanged().collect { face ->
                requestedOrientation = when (face.orientation) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
                if (face.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updateTracker(face.cameraTracking)
            }
        }

        setContent { AppTheme { Root() } }
        requestMissingPermissions()
    }

    /** Self-update needs two special permissions that Android only grants from its settings screens. */
    private var updatePermsMissing by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        val u = graph.updater
        updatePermsMissing = graph.config.current.update.let { it.enabled && it.url.isNotBlank() } && (!u.canInstall() || !u.canRelaunch())
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    companion object {
        /** The face is in the foreground (used by the updater to decide whether it may reopen it). */
        @Volatile var visible = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Composable
    private fun Root() {
        val state by graph.runtime.state.collectAsState()
        val gaze by graph.runtime.gaze.collectAsState()
        val config by graph.config.config.collectAsState()
        val timers by graph.timers.active.collectAsState()
        var showSettings by remember { mutableStateOf(false) }

        BackHandler(enabled = showSettings) { showSettings = false }
        var updatePromptDismissed by remember { mutableStateOf(false) }
        if (updatePermsMissing && !updatePromptDismissed && !showSettings) {
            val u = graph.updater
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { updatePromptDismissed = true },
                title = { Text("Atualização automática") },
                text = {
                    Text(
                        "Para eu me atualizar sozinho (sem reinstalar o APK), libere nas próximas telas:\n\n" +
                            (if (!u.canInstall()) "• Instalar apps desconhecidos\n" else "") +
                            (if (!u.canRelaunch()) "• Sobrepor a outros apps (para eu reabrir depois de atualizar)\n" else ""),
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        startActivity(if (!u.canInstall()) u.installPermissionIntent() else u.overlayPermissionIntent())
                    }) { Text("Permitir") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { updatePromptDismissed = true }) { Text("Agora não") }
                },
            )
        }
        LaunchedEffect(showSettings) { if (!showSettings) hideSystemBars() }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            RobotFace(
                activity = state.activity,
                emotion = state.emotion,
                gaze = gaze,
                face = config.face,
                audioLevel = graph.runtime::audioLevel,
                scene = state.scene,
                sceneStartedAt = state.sceneStartedAt,
                lightOffAt = state.lightOffAt,
                ambient = if (config.life.weatherEffects && (state.weatherActive || config.life.weatherAlways)) state.ambient else Ambient(),
                mimic = config.life.enabled && config.life.mimic,
                // Speech bubbles are not waves. The actual WAVE_BACK scene owns the arm gesture.
                waving = false,
                modifier = Modifier.pointerInput(Unit) {
                    // Petting (slow strokes) and tickling (fast scribbles) on the face.
                    var start = 0L
                    var dist = 0f
                    var reversals = 0
                    var lastDx = 0f
                    detectDragGestures(
                        onDragStart = { start = System.currentTimeMillis(); dist = 0f; reversals = 0; lastDx = 0f },
                        onDrag = { _, d ->
                            dist += kotlin.math.abs(d.x) + kotlin.math.abs(d.y)
                            if (kotlin.math.abs(d.x) > 4f) {
                                if (lastDx != 0f && kotlin.math.sign(d.x) != kotlin.math.sign(lastDx)) reversals++
                                lastDx = d.x
                            }
                        },
                        onDragEnd = {
                            val ms = System.currentTimeMillis() - start
                            if (reversals >= 4 && ms < 2_000) graph.interactions.onTickle()
                            else if (dist > 250f) graph.interactions.onPet()
                        },
                    )
                }.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onFaceTap() },
                        onDoubleTap = {
                            if (graph.runtime.state.value.activity == Activity.SLEEPING) graph.runtime.wake() else graph.runtime.sleep()
                        },
                        onLongPress = { showSettings = true },
                    )
                },
            )
            val ops by graph.operations.list.collectAsState()
            io.github.assistent.ui.OperationsHud(
                ops, io.github.assistent.face.parseHexColor(config.face.color),
                Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 16.dp),
            )
            // Light gags: the white screen only lights things up at full backlight.
            val lighting = io.github.assistent.face.LightGag.isLight(io.github.assistent.face.Scene.of(state.scene))
            LaunchedEffect(lighting) {
                window.attributes = window.attributes.apply {
                    screenBrightness = if (lighting) 1f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            val cameraLabel = when (state.faceStatus) {
                "recognized" -> "Vendo ${state.faceIdentity} (${(state.faceSimilarity * 100).toInt()}%)"
                "unknown" -> "Rosto não reconhecido"
                "recognizing" -> "Reconhecendo rosto..."
                else -> ""
            }
            val visibleLabel = ""
            if (visibleLabel.isNotBlank() && config.face.cameraTracking) {
                Text(
                    visibleLabel,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
                )
            }
            if (config.face.showSubtitles) Subtitles(state)
            TimerOverlay(timers, io.github.assistent.face.parseHexColor(config.face.color), Modifier.align(androidx.compose.ui.Alignment.TopEnd))
            SpeechBubble(state.bubble, io.github.assistent.face.parseHexColor(config.face.color))
            AnimatedVisibility(
                visible = showSettings,
                enter = fadeIn() + slideInVertically { it / 8 },
                exit = fadeOut() + slideOutVertically { it / 8 },
            ) {
                SettingsScreen(graph, onClose = { showSettings = false })
            }
        }
    }

    private fun onFaceTap() {
        val runtime = graph.runtime
        when {
            runtime.state.value.activity == Activity.SPEAKING -> runtime.interruptSpeaking()
            runtime.state.value.serviceRunning -> runtime.manualTrigger.trySend(Unit)
            hasPermission(Manifest.permission.RECORD_AUDIO) -> graph.scope.launch { runtime.converse() }
            else -> requestMissingPermissions()
        }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot(::hasPermission)
        if (wanted.isEmpty()) onPermissionsChanged() else permissions.launch(wanted.toTypedArray())
    }

    private fun onPermissionsChanged() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) AssistantService.start(this)
        updateTracker(graph.config.current.face.cameraTracking)
    }

    private fun updateTracker(enabled: Boolean) {
        if (enabled && hasPermission(Manifest.permission.CAMERA)) {
            if (tracker == null) {
                tracker = FaceTracker(
                    this, { g ->
                        graph.runtime.onFace(g)
                        if (!g.present) {
                            graph.presence.clear()
                            graph.greeter.onFaceGone()
                            graph.body.onFaceGone()
                        }
                        graph.interactions.onGaze(g)
                    }, graph.runtime.life::onObjects,
                    labelObjects = { graph.config.current.life.let { it.enabled && it.mimic } },
                    wantsCrop = graph.people::wantsCrop,
                    onFaceCrop = { bmp -> graph.people.onFaceCrop(bmp, graph.greeter::onSighting) },
                    onMotion = { graph.runtime.onMotion() },
                    onSubjectChanged = {
                        graph.presence.clear()
                        graph.greeter.onFaceTargetChanged()
                    },
                    scanQr = { graph.mesh.scanning.value },
                    onQr = graph.mesh::onQrScanned,
                    onMotionCentroid = graph.interactions::onMotionCentroid,
                    onBrightness = { brightness ->
                        graph.runtime.cameraBrightness = brightness
                        graph.interactions.onBrightness(brightness)
                    },
                    powerSave = { graph.config.current.life.powerSave },
                    wantsFrame = { graph.config.current.let { it.people.outfits || (it.life.enabled && it.life.mimic) } },
                    onFrame = graph.body::onFrame,
                    wantsHands = {
                        graph.config.current.life.let { it.enabled && it.gestures } && graph.runtime.gaze.value.present &&
                            graph.runtime.state.value.activity != io.github.assistent.core.Activity.SLEEPING
                    },
                    onHandFrame = graph.hands::onFrame,
                    sleeping = {
                        graph.runtime.state.value.activity == io.github.assistent.core.Activity.SLEEPING &&
                            System.currentTimeMillis() > graph.runtime.faceCheckUntil
                    },
                ).also { it.start(this) }
            }
        } else {
            tracker?.stop(); tracker = null
        }
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
