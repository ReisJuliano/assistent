package io.github.assistent.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.assistent.AssistentApp
import io.github.assistent.MainActivity
import io.github.assistent.R
import io.github.assistent.core.Activity
import io.github.assistent.reminders.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Keeps the assistant alive and listening: waits for a wake phrase (offline, Vosk) or a manual
 * trigger (tap on the face, API), then runs a full voice conversation.
 */
class AssistantService : LifecycleService() {
    private val graph get() = (application as AssistentApp).graph
    private var loop: Job? = null
    private var wakeFailedAt = 0L

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.createChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ReminderScheduler.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(graph.config.current.personality.name)
            .setContentText("Ouvindo a palavra de ativação")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Parar", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        lifecycleScope.launch {
            graph.wakeWord.progress.collect { p -> graph.runtime.state.update { it.copy(wakeModelProgress = p) } }
        }
        loop = lifecycleScope.launch { voiceLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        graph.runtime.state.update { it.copy(serviceRunning = false, wakeReady = false) }
        super.onDestroy()
    }

    private suspend fun voiceLoop() {
        val runtime = graph.runtime
        runtime.state.update { it.copy(serviceRunning = true) }
        while (lifecycleScope.isActive) {
            try {
                val command = coroutineScope {
                    val wake = async { awaitWakePhrase() }
                    // Wake settings edited live (phrases, model, on/off): restart listening with them.
                    val wakeConfigChanged = async {
                        graph.config.config.map { it.wake to it.personality.name }.distinctUntilChanged().drop(1).first()
                    }
                    val result: String? = select {
                        wake.onAwait { it }
                        runtime.manualTrigger.onReceive { "" }
                        wakeConfigChanged.onAwait { wakeFailedAt = 0L; null }
                    }
                    wake.cancel(); wakeConfigChanged.cancel()
                    result
                }
                if (command != null) {
                    // Who called? Identify the voice of the wake phrase in the background (face may not be visible).
                    graph.wakeWord.lastWakeAudio?.let { audio ->
                        lifecycleScope.launch {
                            runCatching { graph.voiceIdentity.identify(audio) }.getOrNull()?.person?.let { graph.presence.onSeen(it) }
                        }
                    }
                    runtime.converse(command)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "voice loop error", e)
                runtime.state.update { it.copy(error = e.message) }
                if (runtime.state.value.activity != Activity.SLEEPING) runtime.setActivity(Activity.IDLE)
                delay(1_000)
            }
        }
    }

    /** Never returns if wake phrases are disabled or the model is unavailable (manual trigger still works). */
    private suspend fun awaitWakePhrase(): String {
        val runtime = graph.runtime
        if (!graph.config.current.wake.enabled || System.currentTimeMillis() - wakeFailedAt < RETRY_MS) {
            runtime.state.update { it.copy(wakeReady = false) }
            awaitCancellation()
        }
        try {
            graph.wakeWord.ensureModel()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "wake model unavailable", e)
            wakeFailedAt = System.currentTimeMillis()
            runtime.state.update { it.copy(wakeReady = false, error = "Wake model: ${e.message}") }
            awaitCancellation()
        }
        runtime.state.update { it.copy(wakeReady = true) }
        // Works while sleeping too: calling the name wakes it up (happy face) and it starts listening.
        var wasSleeping = false
        return graph.wakeWord.awaitWake(
            onAttention = {
                bringFaceToFront()
                wasSleeping = runtime.state.value.activity == Activity.SLEEPING
                runtime.onWakePhraseHeard()
                runtime.setActivity(Activity.LISTENING)
            },
            // Sounded like the name but was not: go back to what it was doing.
            onDismiss = { runtime.setActivity(if (wasSleeping) Activity.SLEEPING else Activity.IDLE) },
            onAudio = graph.interactions::onAudioLevel,
            onPcm = graph.ears::feed,
            strict = { runtime.state.value.activity == Activity.SLEEPING },
            music = { graph.interactions.musicPlaying },
        )
    }

    /** If another app is on screen (e.g. it opened YouTube here), calling it brings the face back. */
    private fun bringFaceToFront() {
        if (MainActivity.visible) return
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }.onFailure { Log.w(TAG, "can't bring the face back (allow 'display over other apps')", it) }
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val RETRY_MS = 5 * 60_000L

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AssistantService::class.java))
    }
}
