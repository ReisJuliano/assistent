package io.github.assistent.config

import io.github.assistent.core.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.security.SecureRandom

/** JSON file in app-private storage. Single source of truth for [AppConfig]. */
class ConfigStore(dir: File) {
    private val file = File(dir, "config.json")
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config
    val current: AppConfig get() = _config.value

    @Synchronized
    fun update(transform: (AppConfig) -> AppConfig): AppConfig {
        val next = transform(_config.value)
        save(next)
        _config.value = next
        return next
    }

    fun replace(config: AppConfig) = update { config }

    /** RFC 7396-style merge of [patch] into the current config. */
    fun patch(patch: JsonObject): AppConfig = update { cfg ->
        val merged = merge(AppJson.encodeToJsonElement(cfg), patch)
        AppJson.decodeFromJsonElement<AppConfig>(merged)
    }

    fun regenerateToken(): String = update { it.copy(api = it.api.copy(token = newToken())) }.api.token

    private fun load(): AppConfig {
        val loaded = runCatching { AppJson.decodeFromString(AppConfig.serializer(), file.readText()) }.getOrNull() ?: AppConfig()
        var withToken = if (loaded.api.token.isBlank()) loaded.copy(api = loaded.api.copy(token = newToken())) else loaded
        if (withToken.device.id.isBlank()) withToken = withToken.copy(device = withToken.device.copy(id = java.util.UUID.randomUUID().toString()))
        withToken = migrate(withToken)
        if (withToken != loaded || !file.exists()) save(withToken)
        return withToken
    }

    private fun save(config: AppConfig) {
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(AppJson.encodeToString(AppConfig.serializer(), config))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val CURRENT_VERSION = 7

        /** Upgrades settings saved by older versions (defaults that changed on purpose). */
        fun migrate(cfg: AppConfig): AppConfig {
            var c = cfg
            if (c.version < 2) {
                // v2: life is rare (hours between events), fewer greetings, no greeting strangers by default.
                c = c.copy(
                    life = c.life.copy(idleSeconds = 600, minGapSeconds = 3600, maxGapSeconds = 3 * 3600),
                    people = c.people.copy(greetAfterMinutes = maxOf(c.people.greetAfterMinutes, 180), greetUnknown = false),
                )
            }
            if (c.version < 3) {
                // v3: harder to wake up by accident — no aliases that are common words, stricter matching, no room-music dancing.
                c = c.copy(
                    wake = c.wake.copy(
                        nameAliases = c.wake.nameAliases.filterNot { it.lowercase() in setOf("anexo", "nexus", "nexa") },
                        sensitivity = maxOf(c.wake.sensitivity, 0.82f),
                    ),
                    life = c.life.copy(roomMusic = false),
                )
            }
            if (c.version < 4) {
                // v4: room music is opt-out and the life cadence can be tuned in minutes.
                c = c.copy(life = c.life.copy(idleSeconds = 120, roomMusic = true, minGapSeconds = 300, maxGapSeconds = 20 * 60))
            }
            if (c.version < 5) {
                // v5: make existing installations use the livelier idle cadence too.
                c = c.copy(life = c.life.copy(idleSeconds = 120, minGapSeconds = 300, maxGapSeconds = 20 * 60))
            }
            if (c.version < 6 && (c.update.url.isBlank() || ":8318/" in c.update.url)) {
                // v6: updates come from GitHub releases instead of the dev PC's LAN server.
                c = c.copy(update = c.update.copy(url = UpdateConfig.DEFAULT_URL))
            }
            if (c.version < 7 && c.tts.robotVoice == "light") {
                // v7: "light" was barely audible as a robot; the classic preset is the default now.
                c = c.copy(tts = c.tts.copy(robotVoice = "classic"))
            }
            return c.copy(version = CURRENT_VERSION)
        }

        fun newToken(): String {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun merge(base: JsonElement, patch: JsonElement): JsonElement {
            if (base !is JsonObject || patch !is JsonObject) return patch
            val out = base.toMutableMap()
            for ((k, v) in patch) out[k] = out[k]?.let { merge(it, v) } ?: v
            return JsonObject(out)
        }
    }
}
