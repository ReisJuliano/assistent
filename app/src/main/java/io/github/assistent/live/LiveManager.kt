package io.github.assistent.live

import android.util.Log
import io.github.assistent.brain.Brain
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AssistantRuntime
import io.github.assistent.core.Emotion
import io.github.assistent.core.EventDispatcher
import io.github.assistent.face.Scene
import io.github.assistent.core.Catchphrases
import io.github.assistent.tools.ToolRegistry
import io.github.assistent.voice.PcmPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream

enum class LiveState { IDLE, CONNECTING, LIVE, CLOSING }

/**
 * State machine: Idle → Connecting → Live → Closing → Idle.
 * Shares the wake-word PCM feed via [feedPcm]; call it from the wake loop's onPcm.
 */
class LiveManager(
    private val config: ConfigStore,
    private val brain: Brain,
    private val tools: ToolRegistry,
    private val runtime: AssistantRuntime,
    private val events: EventDispatcher,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LiveState.IDLE)
    val state: StateFlow<LiveState> get() = _state

    private var session: LiveSession? = null
    private var sessionJob: Job? = null
    private var audioOut: PipedOutputStream? = null
    private var silenceMs = 0L
    private var lastAudioMs = 0L

    /** Route PCM from the shared wake-word AudioRecord to the Live session. */
    fun feedPcm(samples: ShortArray, count: Int) {
        if (_state.value != LiveState.LIVE) return
        session?.sendAudioChunk(samples.copyOf(count))
        lastAudioMs = System.currentTimeMillis()
    }

    fun start() {
        if (_state.value != LiveState.IDLE) return
        val apiKey = config.current.google.apiKey
        if (apiKey.isBlank()) {
            Log.w("LiveManager", "no google.apiKey — Live mode unavailable")
            return
        }
        sessionJob = scope.launch(Dispatchers.IO) {
            _state.value = LiveState.CONNECTING
            val systemPrompt = brain.buildLiveSystemPrompt()
            val toolDecls = buildFunctionDeclarations()
            val cfg = config.current
            val pip = PipedOutputStream()
            val pis = PipedInputStream(pip, 65536)
            audioOut = pip
            val player = PcmPlayer()
            player.effect = { cfg.tts.robotVoice }
            val playJob = scope.launch {
                runCatching { player.play(pis, 24000) }
            }
            lateinit var sess: LiveSession
            sess = LiveSession(
                http = http,
                apiKey = apiKey,
                model = cfg.google.liveModel,
                systemPrompt = systemPrompt,
                toolDeclarations = toolDecls,
                voice = cfg.live.voice,
                listener = object : LiveSessionListener {
                    override fun onAudioChunk(pcm24k: ShortArray) {
                        // Write little-endian bytes to pipe for PcmPlayer
                        val bytes = ByteArray(pcm24k.size * 2)
                        for (i in pcm24k.indices) {
                            bytes[i * 2] = (pcm24k[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = (pcm24k[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        runCatching { pip.write(bytes) }
                    }
                    override fun onEmotionTag(emotion: String) {
                        val e = mapEmotion(emotion)
                        scope.launch { runtime.setEmotion(e) }
                    }
                    override fun onToolCall(id: String, name: String, args: JSONObject) {
                        scope.launch(Dispatchers.IO) {
                            val tool = tools.find(name) ?: run {
                                sess.sendToolResponse(id, "Tool not found: $name")
                                return@launch
                            }
                            val kArgs = buildJsonObject {
                                for (key in args.keys()) {
                                    when (val v = args[key]) {
                                        is String -> put(key, v)
                                        is Boolean -> put(key, v)
                                        is Int -> put(key, v.toLong())
                                        is Long -> put(key, v)
                                        is Double -> put(key, v)
                                        else -> put(key, v.toString())
                                    }
                                }
                            }
                            val result = runCatching { tools.run(tool, kArgs) }.getOrElse { it.message ?: "error" }
                            sess.sendToolResponse(id, result)
                        }
                    }
                    override fun onTurnComplete() { lastAudioMs = System.currentTimeMillis() }
                    override fun onError(msg: String) {
                        Log.w("LiveManager", "session error: $msg")
                        scope.launch { stop(msg) }
                    }
                    override fun onClosed() {
                        scope.launch { stop(null) }
                    }
                },
            )
            session = sess
            sess.connect()
            _state.value = LiveState.LIVE
            runtime.life.play(Scene.LIVE)
            events.emit("live_started", buildJsonObject { put("model", cfg.google.liveModel) })
            Catchphrases.line("live_intro", listOf("tô no modo papo furado, pode interromper!", "modo ao vivo ativado — fala aí!")).let { runtime.showBubble(it, speak = false) }
            // Silence timeout monitor
            lastAudioMs = System.currentTimeMillis()
            val silenceTimeoutMs = cfg.live.silenceTimeoutSeconds * 1_000L
            val maxMs = cfg.live.maxDurationMinutes * 60_000L
            val startMs = System.currentTimeMillis()
            while (_state.value == LiveState.LIVE) {
                kotlinx.coroutines.delay(5_000)
                val now = System.currentTimeMillis()
                if (now - startMs > maxMs) { stop("max duration reached"); break }
                if (now - lastAudioMs > silenceTimeoutMs) { stop("silence timeout"); break }
            }
            playJob.cancelAndJoin()
        }
    }

    fun stop(reason: String?) {
        if (_state.value == LiveState.IDLE) return
        _state.value = LiveState.CLOSING
        session?.close()
        session = null
        audioOut?.runCatching { close() }
        audioOut = null
        scope.launch {
            val bye = Catchphrases.line("live_bye", listOf("voltando ao normal!", "saindo do modo ao vivo."))
            runtime.showBubble(bye, speak = true)
            events.emit("live_ended", buildJsonObject { put("reason", reason ?: "user") })
            runtime.life.clear()
            _state.value = LiveState.IDLE
        }
        sessionJob?.cancel()
    }

    fun toggle() {
        if (_state.value == LiveState.IDLE) start() else stop("user")
    }

    private fun buildFunctionDeclarations(): JSONArray {
        val arr = JSONArray()
        for (tool in tools.all()) {
            val paramsStr = tool.parameters.toString()
            runCatching {
                arr.put(JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", JSONObject(paramsStr))
                })
            }
        }
        return arr
    }

    private fun mapEmotion(tag: String): Emotion = when (tag.lowercase()) {
        "feliz", "happy", "alegre" -> Emotion.JOY
        "triste", "sad" -> Emotion.SAD
        "surpreso", "surprised" -> Emotion.SURPRISED
        "com raiva", "angry" -> Emotion.ANGRY
        "pensativo", "thinking" -> Emotion.THINKING
        "entusiasmado", "excited" -> Emotion.JOY
        else -> Emotion.NEUTRAL
    }
}
