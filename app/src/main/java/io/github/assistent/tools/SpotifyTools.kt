package io.github.assistent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AppJson
import io.github.assistent.core.EventDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class SpotifyTokens(val accessToken: String = "", val refreshToken: String = "", val expiresAt: Long = 0L, val user: String = "")

/**
 * The owner's Spotify account (Authorization Code + PKCE, no client secret). Each person creates a free app in the
 * Spotify developer dashboard, pastes its Client ID in Settings and registers [REDIRECT] as redirect URI. Tokens live in
 * a private file (never in config.json, so not exposed through the config API or the mesh) and refresh themselves.
 */
class SpotifyAuth(private val context: Context, private val config: ConfigStore, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "spotify-auth.json")
    private val lock = Mutex()
    @Volatile private var tokens: SpotifyTokens = runCatching { AppJson.decodeFromString(SpotifyTokens.serializer(), file.readText()) }.getOrDefault(SpotifyTokens())
    private val _status = MutableStateFlow(describe())
    val status: StateFlow<String> = _status
    private var listen: Job? = null

    val connected get() = tokens.refreshToken.isNotBlank()

    fun startSignIn(): String {
        val clientId = config.current.spotify.clientId.trim()
        if (clientId.isBlank()) return "Cole o Client ID do seu app do Spotify primeiro".also { _status.value = it }
        listen?.cancel()
        val verifier = rnd(64)
        val state = rnd(16)
        val server = runCatching { ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")) }.getOrElse {
            return "Porta $PORT ocupada; tente de novo".also { s -> _status.value = s }
        }
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = "https://accounts.spotify.com/authorize?response_type=code&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT)}" +
            "&code_challenge_method=S256&code_challenge=$challenge&state=$state&scope=${enc(SCOPES)}"
        _status.value = "Aguardando login no navegador…"
        listen = scope.launch(Dispatchers.IO) {
            try {
                server.use { s ->
                    s.soTimeout = 10 * 60_000
                    while (true) {
                        val sock = s.accept()
                        val target = sock.getInputStream().bufferedReader().readLine().orEmpty().split(" ").getOrNull(1).orEmpty()
                        if (!target.startsWith("/callback")) { sock.close(); continue }
                        val q = target.substringAfter('?', "").split('&').filter { '=' in it }
                            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        val msg = runCatching {
                            q["error"]?.let { error("login cancelado ($it)") }
                            require(q["state"] == state) { "state inválido" }
                            exchange(FormBody.Builder().add("grant_type", "authorization_code").add("code", q["code"] ?: error("sem código"))
                                .add("redirect_uri", REDIRECT).add("client_id", clientId).add("code_verifier", verifier).build())
                            val me = request("GET", "me")
                            val name = runCatching { AppJson.parseToJsonElement(me).jsonObject["display_name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                            save(tokens.copy(user = name.orEmpty()))
                            "Spotify conectado${name?.let { " como $it" }.orEmpty()} ✓"
                        }.getOrElse { if (it is CancellationException) throw it; "Falhou: ${it.message}" }
                        val html = "<!doctype html><meta charset=utf-8><body style='background:#000;color:#1db954;font:20px sans-serif;text-align:center;padding-top:30vh'>" +
                            msg.replace("<", "&lt;") + "<p style='color:#888;font-size:15px'>Pode voltar para o robô.</p>"
                        sock.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$html".toByteArray()); flush()
                        }
                        sock.close()
                        _status.value = msg
                        context.packageManager.getLaunchIntentForPackage(context.packageName)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)?.let { runCatching { context.startActivity(it) } }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Login não concluído (${e.message})"
            }
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return _status.value
    }

    fun signOut() { save(SpotifyTokens()); _status.value = describe() }

    suspend fun accessToken(): String = lock.withLock {
        val t = tokens
        if (t.refreshToken.isBlank()) throw SpotifyError("Spotify não conectado: Configurações → Casa → Conectar Spotify")
        if (t.accessToken.isNotBlank() && System.currentTimeMillis() < t.expiresAt - 60_000) return t.accessToken
        exchange(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", t.refreshToken)
            .add("client_id", config.current.spotify.clientId.trim()).build())
        tokens.accessToken
    }

    private suspend fun exchange(body: FormBody) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                if (text.contains("invalid_grant")) { save(SpotifyTokens()); _status.value = "Spotify desconectado: conecte de novo" }
                throw SpotifyError("login do Spotify falhou (HTTP ${r.code}): ${text.take(120)}")
            }
            val o = AppJson.parseToJsonElement(text).jsonObject
            save(tokens.copy(
                accessToken = o["access_token"]!!.jsonPrimitive.content,
                refreshToken = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: tokens.refreshToken,
                expiresAt = System.currentTimeMillis() + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) * 1000,
            ))
        }
    }

    /** Web API call with the user's token; one retry after a refresh on 401. */
    suspend fun request(method: String, path: String, body: JsonObject? = null): String = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val req = Request.Builder().url("https://api.spotify.com/v1/$path").header("Authorization", "Bearer ${accessToken()}")
                .method(method, if (method == "GET") null else (body?.toString() ?: "").toRequestBody(EventDispatcher.JSON)).build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                when {
                    res.isSuccessful -> return@withContext text
                    res.code == 401 && attempt == 0 -> lock.withLock { tokens = tokens.copy(expiresAt = 0) }
                    res.code == 403 -> throw SpotifyError("o Spotify recusou (${errorReason(text)}); controlar a reprodução exige Spotify Premium")
                    res.code == 404 -> throw SpotifyError("nenhum aparelho ativo no Spotify")
                    else -> throw SpotifyError("Spotify HTTP ${res.code}: ${errorReason(text)}")
                }
            }
        }
        throw SpotifyError("Spotify recusou o acesso: conecte de novo")
    }

    private fun errorReason(text: String) = runCatching {
        AppJson.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
    }.getOrDefault(text.take(100))

    private fun save(t: SpotifyTokens) {
        tokens = t
        runCatching { file.writeText(AppJson.encodeToString(SpotifyTokens.serializer(), t)) }
        _status.value = describe()
    }

    private fun describe() = if (tokens.refreshToken.isNotBlank()) "Conectado${tokens.user.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}" else "Não conectado"

    companion object {
        const val PORT = 4381
        /** Register exactly this in the Spotify app's "Redirect URIs" (loopback IP, as Spotify requires). */
        const val REDIRECT = "http://127.0.0.1:$PORT/callback"
        private const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
            "playlist-read-private user-library-read user-read-private"
        private val rng = SecureRandom()
        private fun rnd(n: Int) = b64(ByteArray(n).also(rng::nextBytes))
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

class SpotifyError(message: String) : Exception(message)

/** A Spotify Connect device. */
data class SpotifyDevice(val id: String, val name: String, val type: String, val active: Boolean)

object SpotifyDevices {
    fun parse(json: String): List<SpotifyDevice> = runCatching {
        AppJson.parseToJsonElement(json).jsonObject["devices"]!!.jsonArray.map { it.jsonObject }.mapNotNull { o ->
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SpotifyDevice(id, o["name"]?.jsonPrimitive?.content.orEmpty(), o["type"]?.jsonPrimitive?.content.orEmpty(),
                o["is_active"]?.jsonPrimitive?.content == "true")
        }
    }.getOrDefault(emptyList())

    /** Where to play: what was asked, else the active device, else the only one; null = ask the person. */
    fun choose(devices: List<SpotifyDevice>, requested: String, thisPhone: String): SpotifyDevice? {
        val r = requested.trim().lowercase()
        if (r.isNotBlank()) {
            if (r in setOf("aqui", "neste celular", "celular", "este celular", "robo", "robô", "você", "voce")) {
                devices.firstOrNull { it.name.equals(thisPhone, true) }?.let { return it }
            }
            return devices.firstOrNull { it.id == requested || it.name.equals(requested, true) }
                ?: devices.firstOrNull { it.name.lowercase().contains(r) || r.contains(it.name.lowercase()) }
                ?: devices.firstOrNull { it.type.lowercase().contains(r) || r.contains(it.type.lowercase()) }
                ?: TYPE_WORDS.entries.firstOrNull { (word, _) -> r.contains(word) }?.let { (_, type) -> devices.firstOrNull { it.type.equals(type, true) } }
        }
        return devices.firstOrNull { it.active } ?: devices.singleOrNull()
    }

    private val TYPE_WORDS = mapOf(
        "computador" to "Computer", "pc" to "Computer", "notebook" to "Computer", "televis" to "TV", "tv" to "TV",
        "caixa" to "Speaker", "som" to "Speaker", "alexa" to "Speaker", "echo" to "Speaker", "celular" to "Smartphone",
        "telefone" to "Smartphone", "tablet" to "Tablet", "carro" to "Automobile", "videogame" to "GameConsole",
    )

    fun describe(devices: List<SpotifyDevice>) = devices.joinToString(", ") { "${it.name} (${it.type.lowercase()})" }
}

private fun thisPhoneName() = Build.MODEL.orEmpty()

/**
 * Plays music on a Spotify Connect device and confirms what is actually playing. With several devices and none
 * active it doesn't guess: it asks the model to ask the person which one.
 */
class SpotifyPlayTool(private val context: Context, private val auth: SpotifyAuth) : Tool {
    override val name = "music_play"
    override val description = "Play music on Spotify (track, artist, album, playlist or podcast) on a Spotify Connect device " +
        "(this phone, PC, TV, speaker). If the result starts with ASK_DEVICE, ask the person which of the listed devices to use " +
        "and call music_play again with 'device'. Use device='aqui' for this robot's phone."
    override val parameters = params {
        string("query", "What to play")
        string("type", "Kind", required = false, enum = listOf("track", "artist", "album", "playlist", "show"))
        string("device", "Device name, 'aqui' for this phone, or empty", required = false)
    }

    override suspend fun execute(args: JsonObject): String { return try {
        val q = args.str("query") ?: return "missing query"
        val type = args.str("type") ?: "track"
        var devices = SpotifyDevices.parse(auth.request("GET", "me/player/devices"))
        val requested = args.str("device").orEmpty()
        // Nothing online (or "here" asked and the app is closed): open Spotify on this phone and wait for it to appear.
        val wantsHere = requested.isNotBlank() && SpotifyDevices.choose(devices, requested, thisPhoneName()) == null &&
            requested.lowercase() in setOf("aqui", "neste celular", "celular", "este celular")
        if ((devices.isEmpty() || wantsHere) && openSpotifyHere()) {
            repeat(8) { if (devices.none { it.name.equals(thisPhoneName(), true) }) { delay(1_000); devices = SpotifyDevices.parse(auth.request("GET", "me/player/devices")) } }
        }
        if (devices.isEmpty()) return "nenhum aparelho com Spotify aberto: abra o Spotify no celular, PC ou TV"
        val target = SpotifyDevices.choose(devices, if (wantsHere) thisPhoneName() else requested, thisPhoneName())
            ?: return "ASK_DEVICE: em qual aparelho tocar? Disponíveis: ${SpotifyDevices.describe(devices)}"
        val found = search(q, type) ?: return "não achei \"$q\" no Spotify"
        val body = buildJsonObject {
            if (found.first.startsWith("spotify:track:") || found.first.startsWith("spotify:episode:")) put("uris", JsonArray(listOf(JsonPrimitive(found.first))))
            else put("context_uri", found.first)
        }
        auth.request("PUT", "me/player/play?device_id=${target.id}", body)
        confirm(found.second, target.name)
    } catch (e: SpotifyError) {
        "FALHOU: ${e.message}"
    } }

    private suspend fun search(q: String, type: String): Pair<String, String>? {
        val text = auth.request("GET", "search?type=$type&limit=1&q=" + URLEncoder.encode(q, "UTF-8"))
        val items = runCatching { AppJson.parseToJsonElement(text).jsonObject["${type}s"]!!.jsonObject["items"]!!.jsonArray }.getOrNull()
        val o = items?.firstOrNull { it is JsonObject }?.jsonObject ?: return null
        val artist = o["artists"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        return o["uri"]!!.jsonPrimitive.content to (o["name"]?.jsonPrimitive?.content.orEmpty() + (artist?.let { " - $it" } ?: ""))
    }

    private suspend fun confirm(what: String, device: String): String {
        repeat(4) {
            delay(700)
            val now = runCatching { AppJson.parseToJsonElement(auth.request("GET", "me/player")).jsonObject }.getOrNull()
            if (now?.get("is_playing")?.jsonPrimitive?.content == "true") {
                val dev = now["device"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: device
                val item = now["item"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: what
                return "tocando $item em $dev (confirmado)"
            }
        }
        return "pedi para tocar $what em $device, mas o Spotify não confirmou (sem confirmação)"
    }

    private fun openSpotifyHere(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music") ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}

/** Pause, resume, next, previous, volume, shuffle, repeat, seek, now playing — confirmed by reading the player back. */
class SpotifyControlTool(private val auth: SpotifyAuth) : Tool {
    override val name = "music_control"
    override val description = "Control Spotify playback: pause, resume, next, previous, volume (value 0-100), shuffle (on/off), " +
        "repeat (track/context/off), seek (value = seconds), now_playing."
    override val parameters = params {
        string("action", "Action", enum = listOf("pause", "resume", "next", "previous", "volume", "shuffle", "repeat", "seek", "now_playing"))
        string("value", "Volume, on/off, repeat mode or seconds", required = false)
    }

    override suspend fun execute(args: JsonObject): String { return try {
        val v = args.str("value").orEmpty()
        when (args.str("action")) {
            "pause" -> { auth.request("PUT", "me/player/pause"); state("pausado") { it["is_playing"]?.jsonPrimitive?.content == "false" } }
            "resume" -> { auth.request("PUT", "me/player/play"); state("tocando") { it["is_playing"]?.jsonPrimitive?.content == "true" } }
            "next" -> { auth.request("POST", "me/player/next"); nowPlaying() }
            "previous" -> { auth.request("POST", "me/player/previous"); nowPlaying() }
            "volume" -> { auth.request("PUT", "me/player/volume?volume_percent=${(v.toIntOrNull() ?: 50).coerceIn(0, 100)}"); "volume ${v.ifBlank { "50" }}%" }
            "shuffle" -> { auth.request("PUT", "me/player/shuffle?state=${v != "off"}"); "aleatório ${if (v == "off") "desligado" else "ligado"}" }
            "repeat" -> { auth.request("PUT", "me/player/repeat?state=${v.ifBlank { "context" }}"); "repetir: ${v.ifBlank { "context" }}" }
            "seek" -> { auth.request("PUT", "me/player/seek?position_ms=${(v.toIntOrNull() ?: 0) * 1000}"); "pulei para ${v}s" }
            else -> nowPlaying()
        }
    } catch (e: SpotifyError) {
        "FALHOU: ${e.message}"
    } }

    private suspend fun state(label: String, ok: (JsonObject) -> Boolean): String {
        repeat(4) {
            delay(500)
            val now = runCatching { AppJson.parseToJsonElement(auth.request("GET", "me/player")).jsonObject }.getOrNull() ?: return@repeat
            if (ok(now)) return "$label (confirmado)"
        }
        return "$label pedido, mas sem confirmação do Spotify"
    }

    private suspend fun nowPlaying(): String {
        delay(600)
        val text = auth.request("GET", "me/player/currently-playing")
        if (text.isBlank()) return "nada tocando agora"
        val o = AppJson.parseToJsonElement(text).jsonObject
        val item = o["item"]?.jsonObject ?: return "nada tocando agora"
        val artist = item["artists"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        return "tocando ${item["name"]?.jsonPrimitive?.content}${artist?.let { " - $it" }.orEmpty()}"
    }
}

class SpotifyDevicesTool(private val auth: SpotifyAuth) : Tool {
    override val name = "music_devices"
    override val description = "List the Spotify Connect devices available now (this phone, PC, TV, speakers) and which is active."
    override val parameters = params()

    override suspend fun execute(args: JsonObject): String { return try {
        val d = SpotifyDevices.parse(auth.request("GET", "me/player/devices"))
        if (d.isEmpty()) "nenhum aparelho com Spotify aberto agora" else d.joinToString("; ") { "${it.name} (${it.type.lowercase()}${if (it.active) ", ativo" else ""})" }
    } catch (e: SpotifyError) {
        "FALHOU: ${e.message}"
    } }
}

class SpotifyTransferTool(private val auth: SpotifyAuth) : Tool {
    override val name = "music_transfer"
    override val description = "Move what is playing on Spotify to another device (TV, PC, speaker, 'aqui' = this phone)."
    override val parameters = params { string("device", "Device name") }

    override suspend fun execute(args: JsonObject): String { return try {
        val devices = SpotifyDevices.parse(auth.request("GET", "me/player/devices"))
        val target = SpotifyDevices.choose(devices, args.str("device").orEmpty(), thisPhoneName())
            ?: return "ASK_DEVICE: qual aparelho? Disponíveis: ${SpotifyDevices.describe(devices)}"
        auth.request("PUT", "me/player", buildJsonObject { put("device_ids", JsonArray(listOf(JsonPrimitive(target.id)))); put("play", true) })
        "música transferida para ${target.name}"
    } catch (e: SpotifyError) {
        "FALHOU: ${e.message}"
    } }
}
