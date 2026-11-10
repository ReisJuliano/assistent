package io.github.assistent.brain

import android.graphics.BitmapFactory
import android.util.Log
import io.github.assistent.config.ConfigStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Fetches a dream image from Pollinations (free, no key) or a local SD server.
 * Images are cached in filesDir/dreams/ (max 12 files).
 */
class DreamImageClient(
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    init { cacheDir.mkdirs() }

    suspend fun fetch(prompt: String): ByteArray? {
        val cfg = config.current.life
        return when (cfg.dreamImages) {
            "pollinations" -> fetchPollinations(prompt)
            "sd" -> fetchSd(prompt, cfg.sdUrl)
            else -> null
        }
    }

    private fun fetchPollinations(prompt: String): ByteArray? {
        return try {
            val enc = URLEncoder.encode(prompt, "UTF-8")
            val seed = Random.nextInt(100_000)
            val url = "https://image.pollinations.ai/prompt/$enc?width=640&height=640&nologo=true&seed=$seed"
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()?.also { cacheFile(it) }
            }
        } catch (e: Exception) {
            Log.w("DreamImageClient", "pollinations: ${e.message}")
            null
        }
    }

    private fun fetchSd(prompt: String, sdUrl: String): ByteArray? {
        if (sdUrl.isBlank()) return null
        return try {
            val body = JSONObject().apply {
                put("prompt", prompt); put("width", 640); put("height", 640); put("steps", 20)
            }.toString()
            val req = Request.Builder().url("$sdUrl/sdapi/v1/txt2img")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val images = JSONObject(resp.body?.string() ?: return null).getJSONArray("images")
                if (images.length() == 0) return null
                val b64 = images.getString(0)
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)?.also { cacheFile(it) }
            }
        } catch (e: Exception) {
            Log.w("DreamImageClient", "sd: ${e.message}")
            null
        }
    }

    private fun cacheFile(bytes: ByteArray) {
        try {
            val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            if (files.size >= 12) files.take(files.size - 11).forEach { it.delete() }
            File(cacheDir, "${System.currentTimeMillis()}.jpg").writeBytes(bytes)
        } catch (e: Exception) {
            Log.w("DreamImageClient", "cache: ${e.message}")
        }
    }

    fun listCached(): List<File> = cacheDir.listFiles()
        ?.filter { it.extension == "jpg" }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    companion object {
        /** Remove registered person names from a prompt to avoid privacy leaks. */
        fun sanitize(prompt: String, blocklist: List<String>): String {
            var result = prompt
            for (name in blocklist) {
                result = result.replace(Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE), "someone")
            }
            return result
        }
    }
}
