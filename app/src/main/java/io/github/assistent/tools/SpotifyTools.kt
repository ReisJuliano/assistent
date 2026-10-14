package io.github.assistent.tools

import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AppJson
import io.github.assistent.core.EventDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class SpotifyPlayTool(private val http: OkHttpClient, private val config: ConfigStore) : Tool {
    override val name = "music_play"
    override val description = "Play music on Spotify Connect. Requires spotify.accessToken in config."
    override val parameters = params {
        string("query", "Track, artist, album or playlist search")
    }

    override suspend fun execute(args: JsonObject): String {
        val q = args.str("query") ?: return "missing query"
        val token = config.current.spotify.accessToken.takeIf { it.isNotBlank() } ?: return "spotify not connected"
        val uri = search(token, q) ?: return "not found"
        val body = buildJsonObject { put("uris", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(uri)))) }
        val res = spotify("PUT", "me/player/play", token, body)
        return if (res.isBlank()) "playing" else res
    }

    private suspend fun search(token: String, q: String): String? {
        val url = "https://api.spotify.com/v1/search?type=track&limit=1&q=" + URLEncoder.encode(q, "UTF-8")
        val text = spotify("GET", url.removePrefix("https://api.spotify.com/v1/"), token)
        return runCatching {
            AppJson.parseToJsonElement(text).jsonObject["tracks"]!!.jsonObject["items"]!!.jsonArray.firstOrNull()
                ?.jsonObject?.get("uri")?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun spotify(method: String, path: String, token: String, body: JsonObject? = null): String = spotifyRequest(http, config, method, path, token, body)
}

class SpotifySimpleTool(
    override val name: String,
    override val description: String,
    private val method: String,
    private val path: String,
    private val http: OkHttpClient,
    private val config: ConfigStore,
    private val hasVolume: Boolean = false,
) : Tool {
    override val parameters = if (hasVolume) params { number("volume", "Volume 0-100") } else params()

    override suspend fun execute(args: JsonObject): String {
        val token = config.current.spotify.accessToken.takeIf { it.isNotBlank() } ?: return "spotify not connected"
        val p = if (hasVolume) "$path?volume_percent=${(args.num("volume") ?: 50.0).toInt().coerceIn(0, 100)}" else path
        val res = spotifyRequest(http, config, method, p, token)
        return res.ifBlank { "ok" }
    }
}

class SpotifyDevicesTool(private val http: OkHttpClient, private val config: ConfigStore) : Tool {
    override val name = "music_devices"
    override val description = "List the user's available Spotify Connect devices, such as this phone, a computer, TV, or smart speaker."
    override val parameters = params()

    override suspend fun execute(args: JsonObject): String {
        val token = config.current.spotify.accessToken.takeIf { it.isNotBlank() } ?: return "spotify not connected"
        val text = spotifyRequest(http, config, "GET", "me/player/devices", token)
        return runCatching {
            AppJson.parseToJsonElement(text).jsonObject["devices"]!!.jsonArray.joinToString("; ") { item ->
                val obj = item.jsonObject
                "${obj["name"]?.jsonPrimitive?.content} (${obj["type"]?.jsonPrimitive?.content}, id=${obj["id"]?.jsonPrimitive?.content})"
            }.ifBlank { "no Spotify devices available" }
        }.getOrElse { text }
    }
}

class SpotifyTransferTool(private val http: OkHttpClient, private val config: ConfigStore) : Tool {
    override val name = "music_transfer"
    override val description = "Transfer Spotify playback to a named Connect device, such as Alexa, a TV, a PC, or this phone. Requires Spotify Premium."
    override val parameters = params { string("device", "Device name or exact Spotify device id") }

    override suspend fun execute(args: JsonObject): String {
        val requested = args.str("device")?.trim().orEmpty()
        if (requested.isBlank()) return "missing device"
        val token = config.current.spotify.accessToken.takeIf { it.isNotBlank() } ?: return "spotify not connected"
        val devices = spotifyRequest(http, config, "GET", "me/player/devices", token)
        val target = runCatching {
            AppJson.parseToJsonElement(devices).jsonObject["devices"]!!.jsonArray.map { it.jsonObject }
                .firstOrNull { it["id"]?.jsonPrimitive?.content == requested || it["name"]?.jsonPrimitive?.content.equals(requested, true) }
        }.getOrNull() ?: return "Spotify device '$requested' not found"
        val id = target["id"]?.jsonPrimitive?.content ?: return "Spotify device has no id"
        val body = buildJsonObject { put("device_ids", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(id)))); put("play", true) }
        val result = spotifyRequest(http, config, "PUT", "me/player", token, body)
        return result.ifBlank { "playing on ${target["name"]?.jsonPrimitive?.content}" }
    }
}

suspend fun spotifyRequest(
    http: OkHttpClient,
    config: ConfigStore,
    method: String,
    path: String,
    token: String,
    body: JsonObject? = null,
): String = withContext(Dispatchers.IO) {
    val device = config.current.spotify.deviceId.takeIf { it.isNotBlank() }?.let { if ('?' in path) "&device_id=$it" else "?device_id=$it" }.orEmpty()
    val req = Request.Builder()
        .url("https://api.spotify.com/v1/$path$device")
        .header("Authorization", "Bearer $token")
        .method(method, when (method) {
            "GET" -> null
            else -> (body?.toString() ?: "{}").toRequestBody(EventDispatcher.JSON)
        })
        .build()
    http.newCall(req).execute().use { res ->
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) "spotify HTTP ${res.code}: ${text.take(180)}" else text
    }
}
