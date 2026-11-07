package io.github.assistent.brain

import io.github.assistent.core.EmotionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Queries the PC neural brain server (neuro-server/brain_server.py).
 * Falls back silently to the local EmotionEngine when the server is unreachable.
 */
class NeuralBrainClient(
    private val emotionEngine: EmotionEngine,
    private val scope: CoroutineScope,
) {
    @Volatile var serverUrl: String = ""
    @Volatile var connected: Boolean = false

    @Serializable
    private data class NeuralState(
        val valence: Float,
        val arousal: Float,
        val curiosity: Float,
        val boredom: Float,
        val energy: Float,
        val mood_label: String,
    )

    @Serializable
    data class ThoughtResponse(val category: String?, val intensity: Float)

    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(30_000)
                if (serverUrl.isNotBlank()) syncState()
            }
        }
    }

    fun configure(url: String) {
        serverUrl = url.trimEnd('/')
        scope.launch(Dispatchers.IO) { syncState() }
    }

    private suspend fun syncState() {
        try {
            val raw = get("$serverUrl/state") ?: return
            applyToEmotionEngine(json.decodeFromString<NeuralState>(raw))
            connected = true
        } catch (_: Exception) {
            connected = false
        }
    }

    private fun applyToEmotionEngine(s: NeuralState) {
        emotionEngine.robotValence   = s.valence
        emotionEngine.robotArousal   = s.arousal
        emotionEngine.robotCuriosity = s.curiosity
        emotionEngine.robotBoredom   = s.boredom
        if (s.energy > 0.5f)
            emotionEngine.robotArousal = (emotionEngine.robotArousal + (s.energy - 0.5f) * 0.4f).coerceAtMost(1f)
    }

    fun sendEvent(event: String, intensity: Float = 1.0f) {
        if (serverUrl.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                post("$serverUrl/event", """{"event":"$event","intensity":$intensity}""")
                syncState()
            } catch (_: Exception) { connected = false }
        }
    }

    suspend fun fetchThought(): ThoughtResponse? {
        if (!connected || serverUrl.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val raw = get("$serverUrl/thought") ?: return@withContext null
                json.decodeFromString<ThoughtResponse>(raw)
            } catch (_: Exception) { null }
        }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000; conn.readTimeout = 3_000
        return try { if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null }
        finally { conn.disconnect() }
    }

    private fun post(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 2_000; conn.readTimeout = 3_000
        try { conn.outputStream.write(body.toByteArray()); conn.responseCode }
        finally { conn.disconnect() }
    }
}
