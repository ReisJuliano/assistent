package io.github.assistent.core

import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.face.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalTime
import kotlin.random.Random

/**
 * Gives the robot a life of its own: when nobody talks to it, it picks little activities (weighted by time
 * of day and weather), dreams while sleeping, reacts when it wakes up and keeps the real weather as ambience.
 */
class LifeDirector(
    private val config: ConfigStore,
    private val state: MutableStateFlow<AssistantState>,
    private val http: OkHttpClient,
    private val events: EventDispatcher,
    private val scope: CoroutineScope,
) {
    @Volatile private var lastConversation = System.currentTimeMillis()
    private var nextSceneAt = System.currentTimeMillis() + gap(5 * 60, 20 * 60)
    private var nextSelfWakeAt = 0L
    private val recentScenes = ArrayDeque<Scene>()
    /** Last moment anything "alive" happened (scene, quip, wake-up): keeps events spread out. */
    private var lastLifeEvent = 0L

    private var nextWeatherAt = System.currentTimeMillis() + gap(30 * 60, 2 * 3600)
    private var weatherUntil = 0L
    private var lastWeatherCondition = ""

    /** Asks the runtime to wake up on its own (no one around). */
    var onSelfWake: () -> Unit = {}
    private var nextDreamAt = 0L
    private var geo: Triple<String, Double, Double>? = null
    private var nextQuipAt = System.currentTimeMillis() + gap(8 * 60, 20 * 60)
    private var sleepTalked = 0L

    /** Shows a short line in the speech bubble (text, said while sleeping). */
    var onQuip: (String, Boolean) -> Unit = { _, _ -> }

    fun start() {
        scope.launch { while (true) { tick(); delay(500) } }
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (config.current.life.weatherEffects) runCatching { refreshWeather() }.onFailure { Log.w(TAG, "weather: ${it.message}") }
                delay(20 * 60_000L)
            }
        }
    }

    /** Talking with someone. Hobbies keep going (it can read and chat at the same time). */
    fun onConversation() {
        lastConversation = System.currentTimeMillis()
    }

    /** A direct touch replaces the current reaction immediately; speech and camera tracking do not. */
    fun interruptForInteraction() {
        val current = Scene.of(state.value.scene)
        if (current != null && current.kind != Scene.Kind.DREAM) clear()
    }

    /** Woken up by voice or touch: groggy or dizzy, never a scare (scares are only for sudden movement while asleep). */
    fun onWokeUp() {
        val cfg = config.current.life
        if (!cfg.enabled || !cfg.wakeReactions) return
        // Dizziness is reserved for a real physical shake, not ordinary wake-up.
        play(Scene.WAKE_GROGGY)
    }

    private val mimicCooldown = mutableMapOf<Scene, Long>()
    private var mimicCandidate: Scene? = null
    private var mimicHits = 0
    private var lastMimicAt = 0L

    /**
     * Objects the camera sees (ML Kit labels). If the user wears headphones the robot puts its own on and
     * bops along; a cup → coffee time; a book → reads too; and so on.
     */
    fun onObjects(labels: Set<String>) {
        val cfg = config.current.life
        val s = state.value
        if (!cfg.enabled || !cfg.mimic || s.activity == Activity.SLEEPING) return
        val scene = MIMIC.entries.firstOrNull { (words, _) -> labels.any { l -> words.any { l.contains(it) } } }?.value
        // Must keep seeing the same thing for a few checks in a row (≈30 s), not a single noisy label.
        if (scene == null || scene != mimicCandidate) { mimicCandidate = scene; mimicHits = if (scene == null) 0 else 1; return }
        if (++mimicHits < 3) return
        mimicHits = 0
        val now = System.currentTimeMillis()
        if (now - lastMimicAt < 15 * 60_000 || now - (mimicCooldown[scene] ?: 0L) < 60 * 60_000) return
        mimicCooldown[scene] = now
        lastMimicAt = now
        play(scene)
        events.emit("mimic", JsonPrimitive(scene.key))
    }

    fun play(scene: Scene) {
        // The light gags hold the screen white; other scenes must not cut them off abruptly.
        val current = Scene.of(state.value.scene)
        if (io.github.assistent.face.LightGag.isLight(current) && !io.github.assistent.face.LightGag.isLight(scene)) return
        state.update { it.copy(scene = scene.key, sceneStartedAt = System.currentTimeMillis()) }
        lastLifeEvent = System.currentTimeMillis()
        recentScenes.addLast(scene)
        while (recentScenes.size > 10) recentScenes.removeFirst()
        events.emit("scene", JsonPrimitive(scene.key))
        // Some scenes say something so they read clearly.
        SCENE_LINES[scene]?.let { (delayMs, category, examples) ->
            scope.launch {
                delay(delayMs)
                if (state.value.scene == scene.key) onQuip(Catchphrases.line(category, examples), false)
            }
        }
    }

    fun clear() = state.update { it.copy(scene = null) }

    private fun tick() {
        val cfg = config.current.life
        val s = state.value
        val now = System.currentTimeMillis()
        val current = Scene.of(s.scene)
        // A weather moment ends by itself after a while.
        if (s.weatherActive && now > weatherUntil) state.update { it.copy(weatherActive = false) }

        // Disabled = nothing happens on its own; scenes requested explicitly (API/settings) still play to the end.
        if (current != null) {
            // Talking in their sleep, once per dream sometimes.
            if (current.kind == Scene.Kind.DREAM && current != Scene.SLEEP_PEEK && sleepTalked != s.sceneStartedAt &&
                now - s.sceneStartedAt > 3_000 && s.bubble == null
            ) {
                sleepTalked = s.sceneStartedAt
                if (Random.nextFloat() < 0.6f) onQuip(Catchphrases.line("sleep_talk", SLEEP_TALK), true)
            }
            val over = now - s.sceneStartedAt > current.seconds * 1000
            val interrupted = when (current.kind) {
                Scene.Kind.AWAKE -> s.activity == Activity.SLEEPING
                Scene.Kind.DREAM -> s.activity != Activity.SLEEPING
                Scene.Kind.WAKE, Scene.Kind.REACT -> s.activity == Activity.SLEEPING
            }
            if (over || interrupted) clear()
            return
        }
        if (!cfg.enabled) return
        // No queues, timers or budgets: every half second each kind of thing just has a small random chance.
        // On average a hobby every (min+max)/2 of the configured gap, a comment every ~20 min, weather now and then.
        when (s.activity) {
            Activity.SLEEPING -> {
                if (cfg.selfWake && chance(3 * 3600)) onSelfWake()
                else if (cfg.dreams && current == null && chance(25 * 60)) dreamScene(cfg.scenes)?.let(::play)
            }
            Activity.LISTENING -> Unit // someone is talking to it
            else -> {
                val avg = ((cfg.minGapSeconds + cfg.maxGapSeconds) / 2).coerceAtLeast(30)
                if (current == null && s.activity == Activity.IDLE && chance(avg)) pickAwake(cfg.scenes)?.let(::play)
                if (s.bubble == null && chance(20 * 60)) {
                    val (cat, examples) = weatherQuip(s.ambient)?.takeIf { Random.nextBoolean() } ?: timeQuips(LocalTime.now())
                    onQuip(Catchphrases.line(cat, examples), false)
                }
                if (cfg.weatherEffects && current == null && chance(3 * 3600) && weatherScene(s.ambient) != null) startWeatherMoment()
            }
        }
    }

    /** True with probability matching "about once every [everySeconds]" for a 0.5 s tick. */
    private fun chance(everySeconds: Int) = Random.nextFloat() < 0.5f / everySeconds.coerceAtLeast(1)

    private fun dreamScene(allowed: List<String>) = Scene.entries.filter {
        it.kind == Scene.Kind.DREAM && it != Scene.SLEEP_PEEK && it !in recentScenes && (allowed.isEmpty() || it.key in allowed)
    }.randomOrNull()

    /**
     * Comments about the weather outside right now ("chuvinha boa pra dormir, né?"). AI-written in batches of 10
     * per kind of weather; each line is said once, then a new batch is written.
     */
    private fun weatherQuip(a: Ambient): Pair<String, List<String>>? {
        val t = a.temperature ?: return null
        return when {
            a.condition in setOf("rain", "drizzle", "storm") -> "weather_rain" to listOf(
                "Chuvinha boa pra dormir, né?", "Esse barulhinho de chuva dá um sono…", "Dia perfeito pra um chá e um cobertor.",
                "Lá fora tá chovendo, aqui dentro tá quentinho.",
            )
            a.condition == "snow" || t <= 13f -> "weather_cold" to listOf(
                "Brrr, que friozinho!", "Tempo de chocolate quente, hein?", "Meus circuitos estão tremendo de frio.",
            )
            t >= 30f -> "weather_hot" to listOf(
                "Que calor! Tô derretendo aqui.", "Alguém liga um ventilador pra mim?", "Hoje é dia de sorvete, certeza.",
            )
            a.condition == "fog" -> "weather_fog" to listOf("Que neblina lá fora, parece filme de mistério.", "Hoje o mundo acordou embaçado.")
            a.condition == "clear" && a.isDay && t in 20f..28f -> "weather_nice" to listOf(
                "Que dia bonito lá fora!", "Solzinho gostoso hoje, hein?", "Dia perfeito pra dar uma volta.",
            )
            else -> null
        }
    }

    /** Idle comments that fit the time of day: sleepy mornings, hungry around lunch, slow afternoons, cosy nights. */
    private fun timeQuips(now: LocalTime): Pair<String, List<String>> = when (now.hour) {
        in 5..9 -> "quip_morning" to listOf("Bom dia… acho. Cadê meu café? ☕", "Só mais cinco minutinhos de soneca…", "Acordar cedo devia ser opcional.", "Meus circuitos ainda estão carregando…")
        in 11..13 -> "quip_lunch" to listOf("Que horas sai o almoço? 🍽️", "Tô com uma fome de leão… de lata.", "Alguém falou em lasanha?", "Hora do rango! Ou quase…")
        in 14..16 -> "quip_afternoon" to listOf("Aquela moleza pós-almoço…", "Um cafezinho agora cairia bem.", "Tarde preguiçosa, hein?")
        in 18..21 -> "quip_evening" to listOf("Fim do dia… bora relaxar?", "Que tal uma pizza hoje? 🍕", "Hora de colocar os pés pra cima.")
        in 22..23, in 0..4 -> "quip_night" to listOf("Tá ficando tarde… *bocejo*", "Já vi estrelas mais acordadas que eu.", "Boa noite pro mundo… quase.", "Contando carneirinhos: 1, 2… zzz")
        else -> "idle_quip" to IDLE_QUIPS
    }

    /** Sleepier at night and early morning: dozes off sooner. */
    fun sleepFactor(): Float = when (LocalTime.now().hour) {
        in 22..23, in 0..5 -> 0.35f
        6, 7, 8 -> 0.5f
        13, 14 -> 0.75f
        else -> 1f
    }

    private fun gap(min: Int, max: Int) = (Random.nextInt(min.coerceAtLeast(1), max.coerceAtLeast(min + 1)) * 1000L)

    /**
     * Weighted by context, with real-life rules: no dog walk in the rain or late at night, stars only on a clear
     * night, hot drinks when cold, juice when hot, no workout in the heat, movies and books on rainy days…
     */
    private fun pickAwake(allowed: List<String>): Scene? {
        val hour = LocalTime.now().hour
        val amb = state.value.ambient
        val temp = amb.temperature ?: 22f
        val wet = amb.condition in setOf("rain", "drizzle", "storm", "snow")
        val night = hour >= 19 || hour < 5
        val weights = Scene.entries.filter { it.kind == Scene.Kind.AWAKE && !it.key.startsWith("wx_") && it !in recentScenes && (allowed.isEmpty() || it.key in allowed) }
            .associateWith { sc ->
                var w = 1f
                when (sc) {
                    Scene.WALKING_DOG -> w = when {
                        wet || amb.condition == "fog" || hour >= 22 || hour < 6 -> 0f
                        amb.condition == "clear" && amb.isDay && temp in 18f..30f -> 3f
                        else -> 1f
                    }
                    Scene.STARGAZE -> w = if (night && amb.condition == "clear") 3f else 0f
                    Scene.COFFEE -> w = when {
                        temp <= 14f -> 4f // tea / hot coffee
                        temp >= 26f -> 3f // cold juice with a straw
                        hour in 6..10 -> 3f
                        else -> 1f
                    }
                    Scene.MOVIE -> w = if (wet) 3f else if (hour >= 19) 2.5f else 0.8f
                    Scene.READING -> w = if (wet || temp <= 14f) 2.5f else 1f
                    Scene.STRETCH -> if (hour in 6..10) w = 4f
                    Scene.YAWN -> w = when { hour >= 22 || hour < 8 -> 4f; hour in 13..15 -> 3f; else -> 0.6f }
                    Scene.SNACK -> w = when (hour) { 11, 12, 13 -> 4f; 16 -> 2f; 19, 20 -> 3f; else -> 0.2f }
                    Scene.GAMING, Scene.WORKOUT, Scene.BALL, Scene.SELFIE -> if (hour >= 22 || hour < 7) w = 0.2f
                    Scene.MEDITATE -> if (hour in 6..8 || hour >= 21) w = 2f
                    Scene.RAIN_CLOUD -> w = if (amb.condition in setOf("rain", "drizzle", "storm")) 3f else 0.4f
                    Scene.WORKOUT -> w = if (temp >= 30f) 0f else if (hour in 6..9 || hour in 17..19) 2f else 1f
                    Scene.SNEEZE -> if ((amb.temperature ?: 20f) < 14f) w = 2.5f
                    else -> Unit
                }
                w
            }.filterValues { it > 0f }
        val total = weights.values.sum()
        if (total <= 0f) return null
        var r = Random.nextFloat() * total
        for ((sc, w) in weights) {
            r -= w
            if (r <= 0f) return sc
        }
        return weights.keys.last()
    }

    private fun refreshWeather() {
        val place = config.current.weather.defaultLocation
        val g = geo?.takeIf { it.first == place } ?: run {
            val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", place).addQueryParameter("count", "1").build()
            val res = get(url.toString()).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
            Triple(place, res["latitude"]!!.jsonPrimitive.content.toDouble(), res["longitude"]!!.jsonPrimitive.content.toDouble()).also { geo = it }
        }
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", g.second.toString()).addQueryParameter("longitude", g.third.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day").build()
        val cur = get(url.toString()).jsonObject["current"]?.jsonObject ?: return
        val code = cur["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val condition = when (code) {
            0, 1 -> "clear"
            2, 3 -> "cloudy"
            45, 48 -> "fog"
            51, 53, 55, 56, 57 -> "drizzle"
            61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
            71, 73, 75, 77, 85, 86 -> "snow"
            95, 96, 99 -> "storm"
            else -> "clear"
        }
        val amb = Ambient(condition, cur["temperature_2m"]?.jsonPrimitive?.content?.toFloatOrNull(), cur["is_day"]?.jsonPrimitive?.content != "0")
        state.update { it.copy(ambient = amb) }
        if (config.current.life.weatherEffects && weatherScene(amb) != null && amb.condition != lastWeatherCondition) {
            lastWeatherCondition = amb.condition
            if (state.value.activity != Activity.SLEEPING) startWeatherMoment()
        }
    }

    /** Lets the API or settings force a weather look; [moment] also plays the weather moment right away. */
    fun setAmbient(ambient: Ambient, moment: Boolean = true) {
        state.update { it.copy(ambient = ambient) }
        if (moment) startWeatherMoment()
    }

    /** Shows the weather on screen for 1–2 minutes and plays the matching animation. */
    fun startWeatherMoment() {
        val amb = state.value.ambient
        weatherUntil = System.currentTimeMillis() + gap(60, 120)
        state.update { it.copy(weatherActive = true) }
        weatherScene(amb)?.let { if (state.value.activity == Activity.IDLE) play(it) }
    }

    /** Which animation fits the weather, null when the weather is unremarkable. */
    private fun weatherScene(a: Ambient): Scene? {
        val temp = a.temperature ?: 20f
        return when {
            a.condition in setOf("rain", "drizzle", "storm") -> Scene.WX_RAIN
            a.condition == "snow" || temp <= 14f -> Scene.WX_COLD
            temp >= 28f && a.condition in setOf("clear", "cloudy") -> Scene.WX_HOT
            else -> null
        }
    }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use {
        AppJson.parseToJsonElement(it.body?.string().orEmpty())
    }

    companion object {
        private const val TAG = "Life"
        private val IDLE_QUIPS = listOf(
            "Alguém aí quer ouvir uma piada?", "Tô entediado… bora fazer alguma coisa?", "Será que robô sonha com ovelhas elétricas?",
            "Hmm… acho que esqueci de atualizar alguma coisa.", "Que silêncio… vou cantar! Lá-lá-lá 🎵", "Se precisar, é só chamar, viu?",
            "Contando pixels… 1, 2, 3…", "Hoje tô inspirado!",
        )
        /** Scenes that say something: (delay, phrase category, examples). */
        private val SCENE_LINES = mapOf(
            Scene.SNEEZE to Triple(2_200L, "sneeze", listOf("Atchim! 🤧", "ATCHIM! Desculpa…", "Atchim! Acho que é alergia a poeira digital.")),
            Scene.HICCUP to Triple(600L, "hiccup", listOf("Hic! …hic!", "Ai, soluço… hic!", "Alguém me dá um susto? Hic!")),
            Scene.STRETCH to Triple(1_500L, "stretch", listOf("Aaaah… que espreguiçada boa!", "Hmmm, estalou tudo!")),
            Scene.BORED to Triple(5_000L, "bored", listOf("Aff… que tédio.", "Nada pra fazer… 😮‍💨")),
        )
        private val ANYONE_THERE = listOf(
            "Tem alguém aí? 👀", "Oi? …acho que tô sozinho.", "Hmm, que silêncio… cadê todo mundo?", "Acordei! Alguém quer conversar?",
            "Será que dormi muito? Que horas são?", "Olá? Alguém em casa?",
        )
        fun anyoneThereLines() = ANYONE_THERE
        private val SLEEP_TALK = listOf(
            "hmm… pizza de bateria… 🍕", "não… cinco minutinhos…", "hehe… para… 💕", "zzz… quem pegou meu carregador…",
            "…eu ganho essa partida…", "mamãe, não quero ir pra escola…", "zzz… ovelha número 342…",
        )
        private val MIMIC = linkedMapOf(
            listOf("headphone", "headset", "earphone", "earbud") to Scene.MUSIC,
            listOf("joystick", "gamepad", "video game", "game controller") to Scene.GAMING,
            listOf("coffee", "mug", "tea") to Scene.COFFEE,
            listOf("book", "newspaper", "magazine") to Scene.READING,
            listOf("glasses", "spectacles") to Scene.STUDYING,
            listOf("dog") to Scene.WALKING_DOG,
            listOf("popcorn") to Scene.MOVIE,
            listOf("ball", "football", "soccer") to Scene.BALL,
            listOf("umbrella") to Scene.RAIN_CLOUD,
        )
    }
}
