package io.github.assistent.brain

import android.util.Log
import io.github.assistent.config.ConfigStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenAI-compatible embeddings endpoint. Defaults to Gemini free tier when google.apiKey is set,
 * otherwise null (feature degrades gracefully).
 */
class EmbeddingClient(private val config: ConfigStore, private val http: OkHttpClient) {

    fun isAvailable(): Boolean {
        val g = config.current.google
        val m = config.current.memory
        return m.semantic != "off" && g.apiKey.isNotBlank()
    }

    /** Returns one embedding vector per input text, or null on failure/no key. */
    suspend fun embed(texts: List<String>): List<FloatArray>? {
        if (!isAvailable()) return null
        val g = config.current.google
        val url = "https://generativelanguage.googleapis.com/v1beta/openai/embeddings"
        return try {
            val body = JSONObject().apply {
                put("model", g.embeddingModel)
                put("input", JSONArray(texts))
            }.toString()
            val req = Request.Builder().url("$url?key=${g.apiKey}")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            val resp = http.newCall(req).execute().use { it.body?.string() ?: return null }
            val data = JSONObject(resp).getJSONArray("data")
            (0 until data.length()).map { i ->
                val arr = data.getJSONObject(i).getJSONArray("embedding")
                FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
            }
        } catch (e: Exception) {
            Log.w("EmbeddingClient", "embed failed: ${e.message}")
            null
        }
    }

    fun model() = config.current.google.embeddingModel

    companion object {
        fun toBytes(v: FloatArray): ByteArray =
            ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array()

        fun fromBytes(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            val denom = Math.sqrt((na * nb).toDouble()).toFloat()
            return if (denom == 0f) 0f else dot / denom
        }
    }
}
