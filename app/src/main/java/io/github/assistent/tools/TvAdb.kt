package io.github.assistent.tools

import io.github.assistent.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

internal object TvAdbWire {
    fun frame(service: String): ByteArray {
        val bytes = service.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "ADB service is too long" }
        return "%04x".format(bytes.size).toByteArray(Charsets.US_ASCII) + bytes
    }
}

internal class TvAdbClient(private val host: String, private val port: Int) {
    fun probe(): String = shell("getprop ro.product.model").ifBlank { error("TV returned no model") }

    fun shell(command: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 1_500)
            socket.soTimeout = 2_500
            socket.getOutputStream().apply {
                write(TvAdbWire.frame("shell:$command"))
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream())
            val status = input.readNBytes(4).toString(Charsets.US_ASCII)
            if (status != "OKAY") {
                val sizeText = input.readNBytes(4).toString(Charsets.US_ASCII)
                val size = sizeText.toIntOrNull(16) ?: 0
                val error = input.readNBytes(size).toString(Charsets.UTF_8)
                error("ADB rejected command: ${error.ifBlank { status }}")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Some Android TV shells keep the transport open after writing the result.
            }
            return output.toString(Charsets.UTF_8.name()).trim()
        }
    }
}

class TvControlTool(private val config: ConfigStore) : Tool {
    override val name = "tv_control"
    override val description = "Control a configured Android TV through ADB TCP and report the real result. Actions: status, home, back, up, down, left, right, enter, play, pause, next, previous, volume_up, volume_down, mute, power, open_app."
    override val parameters = params {
        string("device", "Configured TV name or id")
        string("action", "Action", enum = ACTIONS)
        string("value", "Package name for open_app", required = false)
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val requested = args.str("device")?.trim().orEmpty()
        val device = config.current.lanDevices.firstOrNull {
            it.name.equals(requested, true) || it.id.equals(requested, true)
        } ?: return@withContext "TV '$requested' is not configured"
        if (device.host.isBlank()) return@withContext "TV ${device.name} has no IP/host configured"
        val action = args.str("action")?.lowercase().orEmpty()
        val command = when (action) {
            "status" -> "getprop ro.product.model"
            "open_app" -> {
                val pkg = args.str("value")?.trim().orEmpty()
                if (pkg.isBlank() || !PACKAGE.matches(pkg)) return@withContext "open_app requires an Android package name"
                "monkey -p $pkg 1"
            }
            else -> KEY_COMMANDS[action] ?: return@withContext "unknown TV action '$action'"
        }
        runCatching {
            val output = TvAdbClient(device.host, device.adbPort.coerceIn(1, 65535)).shell(command)
            when (action) {
                "status" -> if (output.isNotBlank()) "${device.name} online ($output)" else "${device.name} online"
                "open_app" -> {
                    val visible = TvAdbClient(device.host, device.adbPort.coerceIn(1, 65535))
                        .shell("dumpsys activity activities")
                        .contains(args.str("value")!!.trim())
                    if (visible) "${device.name}: ${args.str("value")} open and verified"
                    else "${device.name}: ADB accepted open_app, but the foreground app was not verified"
                }
                else -> "${device.name}: ADB accepted $action; final TV state was not verifiable"
            }
        }.getOrElse { "${device.name}: ADB failed: ${it.message}" }
    }

    companion object {
        private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val ACTIONS = listOf("status", "home", "back", "up", "down", "left", "right", "enter", "play", "pause", "next", "previous", "volume_up", "volume_down", "mute", "power", "open_app")
        private val KEY_COMMANDS = mapOf(
            "home" to "input keyevent 3", "back" to "input keyevent 4",
            "up" to "input keyevent 19", "down" to "input keyevent 20",
            "left" to "input keyevent 21", "right" to "input keyevent 22",
            "enter" to "input keyevent 66", "play" to "input keyevent 126",
            "pause" to "input keyevent 127", "next" to "input keyevent 87",
            "previous" to "input keyevent 88", "volume_up" to "input keyevent 24",
            "volume_down" to "input keyevent 25", "mute" to "input keyevent 164",
            "power" to "input keyevent 26",
        )
    }
}
