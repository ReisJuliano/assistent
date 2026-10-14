package io.github.assistent.tools

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import io.github.assistent.config.ConfigStore
import io.github.assistent.config.LanDeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URLEncoder

/**
 * Real ADB over the network (the device protocol on port 5555: CNXN/AUTH with an RSA key), via the dadb library.
 * The robot has its own key in `filesDir/adb`; the first connection makes the TV ask "Allow network debugging?".
 */
class AdbRemote(private val context: Context) {
    private val dir = File(context.filesDir, "adb").apply { mkdirs() }

    private fun keys(): AdbKeyPair {
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Opens a connection, runs [block], closes. Errors become [AdbFailure] with a message fit for the user. */
    suspend fun <T> session(host: String, port: Int, block: (Dadb) -> T): T = withContext(Dispatchers.IO) {
        try {
            Dadb.create(host, port, keys(), 4_000, 8_000).use(block)
        } catch (e: dadb.AdbAuthException) {
            throw AdbFailure("a TV pediu autorização: aceite \"Permitir depuração\" na tela da TV (marque sempre permitir) e tente de novo")
        } catch (e: java.net.ConnectException) {
            throw AdbFailure("sem conexão ADB em $host:$port (TV desligada, fora da rede ou depuração de rede desligada)")
        } catch (e: java.net.SocketTimeoutException) {
            throw AdbFailure("a TV em $host não respondeu a tempo")
        } catch (e: java.net.NoRouteToHostException) {
            throw AdbFailure("sem rota até $host (TV desligada ou em outra rede)")
        }
    }

    fun shell(d: Dadb, cmd: String): String = d.shell(cmd).allOutput.trim()
}

class AdbFailure(message: String) : Exception(message)

/** What can be read back from an Android TV, to confirm an action really happened. */
data class TvState(val awake: Boolean?, val foreground: String, val playback: String)

internal object TvProbe {
    fun state(remote: AdbRemote, d: Dadb): TvState {
        val power = remote.shell(d, "dumpsys power | grep -m1 mWakefulness=")
        val fg = remote.shell(d, "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'")
        val media = remote.shell(d, "dumpsys media_session | grep -m3 'state=PlaybackState'")
        return TvState(
            awake = when { power.contains("Awake") -> true; power.contains("Asleep") || power.contains("Dozing") -> false; else -> null },
            foreground = Regex("([a-zA-Z0-9_.]+)/").find(fg)?.groupValues?.get(1).orEmpty(),
            playback = when {
                media.contains("state=3") -> "playing"
                media.contains("state=2") -> "paused"
                media.contains("state=1") -> "stopped"
                else -> "unknown"
            },
        )
    }
}

/**
 * Android TV control through real ADB. Every action is confirmed by reading the TV state back (power, foreground app,
 * media session); when the TV can't confirm, the result says so instead of claiming success.
 */
class TvControlTool(private val context: Context, private val config: ConfigStore, private val remote: AdbRemote = AdbRemote(context)) : Tool {
    override val name = "tv_control"
    override val description = "Control an Android TV on the network through ADB and report the REAL result (verified). " +
        "Actions: status, power_on, power_off, home, back, up, down, left, right, enter, play, pause, play_pause, next, previous, " +
        "rewind, fast_forward, volume_up, volume_down, mute, open_app (value = app name like youtube, netflix, plex, jellyfin, " +
        "globoplay, prime video, disney, spotify), search (value = what to look for, app = where), type_text."
    override val activity = io.github.assistent.core.Activity.SEARCHING
    override val parameters = params {
        string("action", "Action", enum = ACTIONS)
        string("device", "TV name or id; empty = the only/nearest TV", required = false)
        string("value", "App name, search text or text to type", required = false)
        string("app", "For search: youtube, netflix, prime video, plex, jellyfin, globoplay, spotify", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val tv = pick(args.str("device").orEmpty()) ?: return noTv(args.str("device").orEmpty())
        if (tv.host.isBlank()) return "${tv.name}: sem IP configurado"
        val action = args.str("action")?.lowercase().orEmpty()
        val port = tv.adbPort.coerceIn(1, 65535)
        return try {
            when (action) {
                "power_on" -> powerOn(tv, port)
                else -> remote.session(tv.host, port) { d -> run(d, tv, action, args) }
            }
        } catch (e: AdbFailure) {
            "${tv.name}: FALHOU - ${e.message}"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "${tv.name}: FALHOU - ${e.message}"
        }
    }

    private fun run(d: Dadb, tv: LanDeviceConfig, action: String, args: JsonObject): String {
        fun key(code: Int) = remote.shell(d, "input keyevent $code")
        fun state() = TvProbe.state(remote, d)
        return when (action) {
            "status" -> {
                val s = state()
                val model = remote.shell(d, "getprop ro.product.model")
                "${tv.name} ($model): tela ${if (s.awake == true) "ligada" else if (s.awake == false) "desligada" else "?"}, " +
                    "app ${s.foreground.ifBlank { "?" }}, mídia ${s.playback}"
            }
            "power_off" -> {
                key(223) // KEYCODE_SLEEP
                if (waitFor { state().awake == false }) "${tv.name}: desligada (confirmado)" else "${tv.name}: comando enviado, mas a TV ainda aparece ligada"
            }
            "open_app" -> {
                val pkg = resolveApp(d, args.str("value").orEmpty()) ?: return "${tv.name}: app '${args.str("value")}' não está instalado"
                remote.shell(d, "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p $pkg 1")
                if (waitFor { state().foreground == pkg }) "${tv.name}: $pkg aberto (confirmado)" else "${tv.name}: pedi para abrir $pkg, mas não confirmei que está na tela"
            }
            "search" -> {
                val q = args.str("value").orEmpty().ifBlank { return "search precisa do que procurar" }
                val app = args.str("app").orEmpty().ifBlank { "youtube" }
                val pkg = resolveApp(d, app) ?: return "${tv.name}: app '$app' não está instalado"
                val enc = URLEncoder.encode(q, "UTF-8")
                val uri = SEARCH_URIS.entries.firstOrNull { app.contains(it.key, true) }?.value?.replace("{q}", enc)
                if (uri != null) remote.shell(d, "am start -a android.intent.action.VIEW -d '$uri' $pkg")
                else remote.shell(d, "am start -a android.intent.action.SEARCH --es query '${q.replace("'", "")}' $pkg")
                if (waitFor { state().foreground == pkg }) "${tv.name}: buscando \"$q\" no $app (app na tela, confirmado)"
                else "${tv.name}: pedi a busca de \"$q\" no $app, mas não confirmei que o app abriu"
            }
            "type_text" -> {
                val text = args.str("value").orEmpty().replace(" ", "%s").replace(Regex("[\"'`\\\\$]"), "")
                remote.shell(d, "input text \"$text\"")
                "${tv.name}: texto digitado"
            }
            "play", "pause", "play_pause" -> {
                val before = state().playback
                key(MEDIA[action]!!)
                val want = when (action) { "play" -> "playing"; "pause" -> "paused"; else -> if (before == "playing") "paused" else "playing" }
                val after = if (waitFor { state().playback == want }) want else state().playback
                if (after == want) "${tv.name}: ${if (want == "playing") "tocando" else "pausado"} (confirmado)"
                else "${tv.name}: comando enviado; estado da mídia: $after (sem confirmação)"
            }
            else -> {
                val code = KEYS[action] ?: MEDIA[action] ?: return "ação desconhecida '$action'"
                key(code)
                "${tv.name}: tecla $action enviada (a TV não informa o resultado dessa tecla)"
            }
        }
    }

    /** Wake the screen; if ADB is unreachable (deep sleep / off) send Wake-on-LAN first and wait for it. */
    private suspend fun powerOn(tv: LanDeviceConfig, port: Int): String {
        val reachable = runCatching { remote.session(tv.host, port) { true } }.getOrDefault(false)
        if (!reachable) {
            val mac = WakeOnLan.parseMac(tv.mac) ?: return "${tv.name}: FALHOU - TV não responde e não tem MAC para Wake-on-LAN"
            withContext(Dispatchers.IO) { WakeOnLan.send(mac, tv.broadcast, tv.port) }
            var up = false
            repeat(12) { if (!up) { delay(2_000); up = runCatching { remote.session(tv.host, port) { true } }.getOrDefault(false) } }
            if (!up) return "${tv.name}: Wake-on-LAN enviado, mas a TV não ficou online em 24 s"
        }
        return remote.session(tv.host, port) { d ->
            if (TvProbe.state(remote, d).awake == true) return@session "${tv.name}: já estava ligada"
            remote.shell(d, "input keyevent 224") // KEYCODE_WAKEUP
            if (waitFor { TvProbe.state(remote, d).awake == true }) "${tv.name}: ligada (confirmado)" else "${tv.name}: comando enviado, mas a tela não acendeu"
        }
    }

    /** Friendly app name → installed package (TV and phone variants). */
    private fun resolveApp(d: Dadb, name: String): String? {
        val wanted = name.lowercase().trim()
        if (PACKAGE.matches(wanted)) return wanted
        val installed = remote.shell(d, "pm list packages").lines().map { it.removePrefix("package:").trim() }.toSet()
        APPS.entries.firstOrNull { (k, _) -> wanted.contains(k) || k.contains(wanted) }?.value
            ?.firstOrNull { it in installed }?.let { return it }
        return installed.firstOrNull { it.contains(wanted.replace(" ", "")) }
    }

    private fun waitFor(attempts: Int = 10, check: () -> Boolean): Boolean {
        repeat(attempts) {
            if (runCatching(check).getOrDefault(false)) return true
            Thread.sleep(600)
        }
        return false
    }

    /** By name/id; empty → the only TV, or the TV in this device's room. */
    private fun pick(requested: String): LanDeviceConfig? {
        val tvs = config.current.lanDevices.filter { it.kind == "tv" || it.adbPort > 0 && it.kind != "pc" }
        if (requested.isNotBlank()) return config.current.lanDevices.firstOrNull { it.name.equals(requested, true) || it.id.equals(requested, true) }
            ?: tvs.firstOrNull { it.name.contains(requested, true) }
        if (tvs.size == 1) return tvs.first()
        val room = config.current.device.room
        return tvs.firstOrNull { room.isNotBlank() && it.name.contains(room, true) }
    }

    private fun noTv(requested: String): String {
        val tvs = config.current.lanDevices.filter { it.kind == "tv" }
        return when {
            requested.isNotBlank() -> "TV '$requested' não cadastrada"
            tvs.isEmpty() -> "nenhuma TV cadastrada (use device discover_devices ou cadastre em Configurações → Casa)"
            else -> "há ${tvs.size} TVs (${tvs.joinToString { it.name }}): pergunte qual"
        }
    }

    companion object {
        private val PACKAGE = Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+")
        private val ACTIONS = listOf(
            "status", "power_on", "power_off", "home", "back", "up", "down", "left", "right", "enter",
            "play", "pause", "play_pause", "next", "previous", "rewind", "fast_forward",
            "volume_up", "volume_down", "mute", "open_app", "search", "type_text",
        )
        private val KEYS = mapOf(
            "home" to 3, "back" to 4, "up" to 19, "down" to 20, "left" to 21, "right" to 22, "enter" to 66,
            "volume_up" to 24, "volume_down" to 25, "mute" to 164,
        )
        private val MEDIA = mapOf(
            "play" to 126, "pause" to 127, "play_pause" to 85, "next" to 87, "previous" to 88, "rewind" to 89, "fast_forward" to 90,
        )
        private val APPS = linkedMapOf(
            "youtube" to listOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv", "com.google.android.youtube"),
            "netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
            "prime" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
            "disney" to listOf("com.disney.disneyplus"),
            "plex" to listOf("com.plexapp.android"),
            "jellyfin" to listOf("org.jellyfin.androidtv", "org.jellyfin.mobile"),
            "globoplay" to listOf("com.globo.globotv"),
            "spotify" to listOf("com.spotify.tv.android", "com.spotify.music"),
            "max" to listOf("com.wbd.stream", "com.hbo.hbonow"),
            "kodi" to listOf("org.xbmc.kodi"),
        )
        private val SEARCH_URIS = mapOf(
            "youtube" to "https://www.youtube.com/results?search_query={q}",
            "netflix" to "nflx://www.netflix.com/search?q={q}",
            "spotify" to "spotify:search:{q}",
        )
    }
}
