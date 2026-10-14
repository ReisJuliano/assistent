package io.github.assistent.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import io.github.assistent.config.ConfigStore
import io.github.assistent.face.Life
import io.github.assistent.face.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Social reactions to what people do with the robot (inspired by EMO / Vector): waving back, dancing to music,
 * purring when petted, giggling when tickled, complaining when shaken, peekaboo when the camera is covered,
 * shy when stared at, "too close!", mimicking nods and head shakes, saying bye, reacting to the charger and to
 * loud sounds. Each reaction has its own cooldown so it stays special.
 */
class Interactions(
    private val context: Context,
    private val config: ConfigStore,
    private val runtime: AssistantRuntime,
    private val scope: CoroutineScope,
) {
    private val cooldowns = HashMap<String, Long>()

    private fun ready(key: String, ms: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - (cooldowns[key] ?: 0L) < ms) return false
        cooldowns[key] = now
        return true
    }

    // Automatic reactions have a soft budget; direct actions remain responsive.
    private val autoReactions = ArrayDeque<Long>()

    private fun budget(): Boolean = true

    private val state get() = runtime.state.value
    private val awake get() = state.activity != Activity.SLEEPING
    private val idle get() = state.activity == Activity.IDLE
    private val enabled get() = config.current.life.enabled

    private fun react(scene: Scene, category: String? = null, examples: List<String> = emptyList(), lineDelay: Long = 400, name: String? = null) {
        runtime.life.play(scene)
        if (category != null) scope.launch {
            delay(lineDelay)
            val line = Catchphrases.line(category, examples).replace("{n}", name ?: "")
            runtime.showBubble(line, keepEmotion = true)
        }
    }

    fun start() {
        startSensors()
        // Music playing on the phone itself (Spotify, YouTube…) → dance.
        scope.launch {
            val am = context.getSystemService(AudioManager::class.java)
            var activePolls = 0
            var lastSpoke = 0L
            while (true) {
                delay(3_000)
                // The system TTS of our own replies plays on the music stream: ignore it and a few seconds after.
                if (state.activity == Activity.SPEAKING || state.activity == Activity.LISTENING) lastSpoke = System.currentTimeMillis()
                val foreign = am.isMusicActive && System.currentTimeMillis() - lastSpoke > 10_000
                activePolls = if (foreign) activePolls + 1 else 0
                if (am.isMusicActive) android.util.Log.d("Interactions", "phone music active polls=$activePolls state=${state.activity}")
                if (enabled && activePolls >= 1) onMusic(phone = true)
            }
        }
    }

    // ------------------------------------------------------------------ music

    private fun onMusic(phone: Boolean) {
        if (!awake || !enabled) return
        val current = Scene.of(state.scene)
        if (current == Scene.DANCE) return
        if (!phone && !config.current.life.roomMusic) return
        if (!ready("dance", if (phone) 45_000 else 3 * 60_000)) return
        if (ready("dance_line", 10 * 60_000)) react(Scene.DANCE, "dance", DANCE, 1_500) else runtime.life.play(Scene.DANCE)
    }

    private var prevRms = 0f

    private val music = MusicDetector()
    @Volatile private var musicHeardAt = 0L

    /** True while music has been heard in the room recently: wake phrases need to be clearer then. */
    val musicPlaying get() = System.currentTimeMillis() - musicHeardAt < 20_000

    fun onAudioLevel(rms: Float) {
        // Sudden loud sound (clap, door) → a little start.
        if (rms > maxOf(0.35f, music.noise * 10f) && prevRms < rms / 5f) onLoudSound()
        prevRms = rms
        music.push(rms)?.let { bpm ->
            musicHeardAt = System.currentTimeMillis()
            Life.danceBpm = bpm
            android.util.Log.d("Interactions", "room music bpm=$bpm")
            onMusic(phone = false)
        }
    }

    private fun onLoudSound() {
        if (!enabled) return
        if (state.activity == Activity.SLEEPING) { runtime.onNoiseWhileSleeping(); return }
        if (idle && ready("loud", 5 * 60_000) && budget()) runtime.setEmotion(Emotion.SURPRISED, 1_200)
    }

    // ------------------------------------------------------------------ camera

    private val waveX = ArrayDeque<Pair<Long, Float>>()

    /** Centroid (0..1, screen-mirrored) of the moving area of the frame and how much moved (0..1). */
    fun onMotionCentroid(x: Float, amount: Float) {
        if (!enabled || !awake || state.activity == Activity.SPEAKING || state.activity == Activity.LISTENING || state.activity == Activity.THINKING) {
            waveX.clear()
            return
        }
        val now = System.currentTimeMillis()
        // Keep a generous range: camera exposure and distance change the moved fraction a lot.
        if (amount < 0.012f || amount > 0.45f) { waveX.clear(); return }
        val g = runtime.gaze.value
        if (!g.present) return
        val faceX = (g.x + 1f) / 2f
        // Exposure flicker and head movement happen near the face, not in a separate
        // moving region. This rejects false waves from the entire image shifting.
        if (abs(x - faceX) < 0.16f && amount < 0.12f) { waveX.clear(); return }
        waveX.addLast(now to x)
        while (waveX.isNotEmpty() && now - waveX.first().first > 2_500) waveX.removeFirst()
        // A wave = the moving area going left-right-left a few times.
        var reversals = 0
        var dir = 0
        var anchor = waveX.firstOrNull()?.second ?: return
        var low = anchor
        var high = anchor
        for ((_, v) in waveX) {
            low = minOf(low, v)
            high = maxOf(high, v)
            val d = v - anchor
            if (abs(d) > 0.10f) {
                val s = if (d > 0) 1 else -1
                if (dir != 0 && s != dir) reversals++
                dir = s
                anchor = v
            }
        }
        if (reversals >= 3) android.util.Log.d("Interactions", "wave? reversals=$reversals amount=$amount x=$x face=${(g.x + 1f) / 2f}")
        // Waving is something the person does on purpose: short cooldown, not part of the automatic budget.
        val span = high - low
        if (reversals >= 3) android.util.Log.d("Interactions", "wave? reversals=$reversals span=$span amount=$amount")
        if (reversals >= 4 && waveX.size >= 12 && span >= 0.32f && awake && ready("wave", 20_000)) {
            waveX.clear()
            react(Scene.WAVE_BACK, "wave_back", WAVE, 500)
        }
    }

    private var darkSince = 0L
    private var hiding = false

    /** Mean brightness of the camera frame (0..255): covering the camera plays peekaboo. */
    fun onBrightness(mean: Float) {
        if (!enabled || !awake) return
        val now = System.currentTimeMillis()
        if (mean < 14f) {
            if (darkSince == 0L) darkSince = now
            if (!hiding && now - darkSince > 1_500 && idle && ready("peekaboo", 5 * 60_000) && budget()) {
                hiding = true
                react(Scene.WHERE_ARE_YOU, "peek_where", WHERE, 600)
            }
        } else if (mean > 35f) {
            darkSince = 0L
            if (hiding) {
                hiding = false
                runtime.life.clear()
                react(Scene.FOUND_YOU, "peek_found", FOUND, 200)
            }
        }
    }

    private var stareSince = 0L
    private var closeSince = 0L
    private val yaws = ArrayDeque<Pair<Long, Float>>()
    private val pitches = ArrayDeque<Pair<Long, Float>>()

    private var sleepFaceSince = 0L
    private var noFaceSince = 0L

    fun onGaze(g: Gaze) {
        val now = System.currentTimeMillis()
        // Someone looking at the sleeping robot for a few seconds wakes it up (sleepy, then happy to see them).
        if (state.activity == Activity.SLEEPING) {
            if (!g.present) { sleepFaceSince = 0L; return }
            if (sleepFaceSince == 0L) sleepFaceSince = now
            if (now - sleepFaceSince > 1_500 && ready("wake_by_face", 15_000)) {
                android.util.Log.d("Interactions", "face in front of sleeping robot -> wake")
                sleepFaceSince = 0L
                runtime.wake(greet = true)
            }
            return
        }
        sleepFaceSince = 0L
        if (!enabled) return
        if (!g.present) {
            stareSince = 0L
            closeSince = 0L
            if (noFaceSince == 0L) noFaceSince = now
            // Nobody around for a long while after someone left: a silent thought bubble, never speech to an empty room.
            if (now - noFaceSince > 20 * 60_000L && lastFaceAt > 0 && now - lastFaceAt < 2 * 3600_000L && idle &&
                state.scene == null && ready("alone_camera", 2 * 3600_000L)
            ) {
                noFaceSince = now
                runtime.showBubble(Catchphrases.line("alone_camera", ALONE_CAMERA), speak = false, keepEmotion = true)
            }
            onFaceGone()
            return
        }
        noFaceSince = 0L
        lastFaceAt = now
        // Too close to the camera.
        if (g.size > 0.4f) android.util.Log.d("Interactions", "face size=${g.size}")
        if (g.size > 0.47f) {
            if (closeSince == 0L) closeSince = now
            if (now - closeSince > 800 && idle && ready("too_close", 10 * 60_000) && budget()) react(Scene.TOO_CLOSE, "too_close", TOO_CLOSE, 300)
        } else closeSince = 0L
        // Long eye contact → shy.
        if (abs(g.x) < 0.3f && abs(g.y) < 0.35f && abs(g.yaw) < 12f) {
            if (stareSince == 0L) stareSince = now
            if (now - stareSince > 12_000 && idle && ready("stare", 20 * 60_000) && budget()) {
                stareSince = 0L
                react(Scene.SHY, "shy", SHY, 700)
            }
        } else stareSince = 0L
        // Nodding / shaking the head → mimic.
        if (config.current.life.mimic && idle) {
            if (oscillates(yaws, now, g.yaw, 14f) && ready("head_shake", 5 * 60_000) && budget()) runtime.life.play(Scene.HEAD_SHAKE)
            if (oscillates(pitches, now, g.pitch, 12f) && ready("head_nod", 5 * 60_000) && budget()) runtime.life.play(Scene.HEAD_NOD)
        }
    }

    private fun oscillates(q: ArrayDeque<Pair<Long, Float>>, now: Long, v: Float, amp: Float): Boolean {
        q.addLast(now to v)
        while (q.isNotEmpty() && now - q.first().first > 1_600) q.removeFirst()
        var reversals = 0
        var dir = 0
        var anchor = q.first().second
        for ((_, a) in q) {
            val d = a - anchor
            if (abs(d) > amp) {
                val s = if (d > 0) 1 else -1
                if (dir != 0 && s != dir) reversals++
                dir = s
                anchor = a
            }
        }
        if (reversals >= 3) { q.clear(); return true }
        return false
    }

    // Goodbye when a known person who stayed a while leaves.
    private var lastFaceAt = 0L
    private var knownName: String? = null
    private var knownSince = 0L

    fun onKnownSeen(name: String) {
        val now = System.currentTimeMillis()
        if (knownName != name) { knownName = name; knownSince = now }
    }

    private fun onFaceGone() {
        val name = knownName ?: return
        val now = System.currentTimeMillis()
        if (now - lastFaceAt < 20_000 || now - knownSince < 60_000) return
        knownName = null
        if (idle && kotlin.random.Random.nextFloat() < 0.6f && ready("bye_$name", 60 * 60_000) && budget()) react(Scene.WAVE_BACK, "goodbye", BYE, 300, name)
    }

    // ------------------------------------------------------------------ touch

    fun onPet() {
        if (!enabled) return
        android.util.Log.d("Interactions", "pet gesture accepted activity=${state.activity}")
        if (state.activity == Activity.SLEEPING) runtime.wake(greet = false)
        runtime.life.interruptForInteraction()
        if (ready("pet", 8_000)) react(Scene.PURR, if (ready("pet_line", 60_000)) "pet" else null, PET, 800)
    }

    fun onTickle() {
        if (!enabled) return
        android.util.Log.d("Interactions", "tickle gesture accepted activity=${state.activity}")
        if (state.activity == Activity.SLEEPING) runtime.wake(greet = false)
        runtime.life.interruptForInteraction()
        if (ready("tickle", 6_000)) react(Scene.GIGGLE, if (ready("tickle_line", 45_000)) "tickle" else null, TICKLE, 500)
    }

    // ------------------------------------------------------------------ device sensors

    private fun startSensors() {
        val sm = context.getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { acc ->
            sm.registerListener(object : SensorEventListener {
                private val peaks = ArrayDeque<Long>()
                override fun onSensorChanged(e: SensorEvent) {
                    val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    if (abs(g - SensorManager.GRAVITY_EARTH) > 12f) {
                        val now = System.currentTimeMillis()
                        peaks.addLast(now)
                        while (peaks.isNotEmpty() && now - peaks.first() > 1_200) peaks.removeFirst()
                        if (peaks.size >= 7) { peaks.clear(); onShaken() }
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }, acc, SensorManager.SENSOR_DELAY_UI)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> onCharging()
                    Intent.ACTION_BATTERY_LOW -> onLowBattery()
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    private fun onShaken() {
        if (!enabled || !ready("shaken", 15_000)) return
        android.util.Log.d("Interactions", "physical shake accepted")
        if (state.activity == Activity.SLEEPING) runtime.wake(greet = false)
        react(Scene.WAKE_DIZZY, "shaken", SHAKEN, 1_200)
    }

    private fun onCharging() {
        if (!enabled || !ready("charge", 60_000)) return
        if (state.activity == Activity.SLEEPING) return
        react(Scene.CHARGE, "charge", CHARGE, 400)
    }

    private fun onLowBattery() {
        if (!enabled || !ready("low_battery", 30 * 60_000) || state.activity == Activity.SLEEPING) return
        runtime.setEmotion(Emotion.SLEEPY, 8_000)
        runtime.showBubble(Catchphrases.line("low_battery", LOW_BATTERY), emotion = Emotion.SLEEPY, keepEmotion = true)
    }

    companion object {
        private val WAVE = listOf("Oi! 👋", "Eaaae! 👋", "Olá, olá!", "Oi oi! Tudo bem?", "Tchauzinho… quer dizer, oi! 😄")
        private val DANCE = listOf("Essa música é boa demais! 🎶", "Solta o som! 🎧", "Não consigo parar de dançar!", "Que batida! 🕺")
        private val PET = listOf("Hmm… que gostoso 🥰", "Mais um pouquinho…", "Rrrr… 💙", "Carinho é bom demais.")
        private val TICKLE = listOf("Hihihi! Cócegas não! 🤭", "Para, para! Hahaha!", "Hihi, aí não!", "Ai, que cócegas!")
        private val SHAKEN = listOf("Ei! Para de me sacudir! 😵", "Uooou… tô tonto…", "Tudo girando… 💫", "Calma! Sou frágil!")
        private val CHARGE = listOf("Energia! 🔋⚡", "Hmm, eletricidade fresquinha!", "Ahhh, recarregando… que delícia.")
        private val LOW_BATTERY = listOf("Tô ficando sem bateria… 🪫", "Me carrega? Tô fraquinho…", "Bateria baixa… bocejo…")
        private val WHERE = listOf("Ué… cadê você?", "Apagaram a luz?! 😮", "Ei, cadê todo mundo?", "Tá escuro aqui…")
        private val FOUND = listOf("Achou! 😄", "Ahá! Te achei!", "Bu! 👻", "Aí está você!")
        private val TOO_CLOSE = listOf("Opa, muito perto! 😳", "Uou, que zoom!", "Ei, espaço pessoal! 😅", "Tô vendo seu nariz por dentro!")
        private val SHY = listOf("Que foi? Tô com vergonha… 😳", "Para de me encarar, hihi.", "Tem alguma coisa no meu rosto?", "Assim eu fico sem graça…")
        private val BYE = listOf("Tchau, {n}! 👋", "Até mais, {n}!", "Volta logo, {n}!", "Vai com Deus, {n}!")
        private val ALONE_CAMERA = listOf(
            "Não tô vendo ninguém… só se for fantasma.",
            "Acho que a casa está vazia. Ou vocês estão muito bem escondidos.",
            "Se tem alguém aí, dá um oi! Desculpa se eu não te vi.",
            "Será que alguém pode acender a luz? Meus olhos estão trabalhando no escuro.",
            "Nenhum rosto detectado. Vou fingir que foi uma pausa dramática.",
            "Cadê todo mundo? Até meus pixels estão sentindo falta de companhia.",
        )
    }
}
