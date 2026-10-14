package io.github.assistent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** One thing the robot is doing for someone (a tool call), with its real state. */
@Serializable
data class Operation(
    val id: String,
    val tool: String,
    /** pt-BR label for the screen ("Pesquisando: previsão do tempo"). */
    val label: String,
    /** Visual family: search, weather, time, tv, music, home, face, memory, list, light, game, webhook, other. */
    val kind: String,
    /** running, succeeded, failed, unknown (done but not confirmed), cancelled. */
    val state: String,
    val detail: String = "",
    val startedAt: Long,
    val endedAt: Long = 0,
)

/**
 * Every tool call, from voice, API or mesh, becomes an observable operation: the screen and the event stream show
 * it start, and finish as succeeded / failed / unknown / cancelled according to the tool's real result — never a
 * success the tool didn't report. Operations run independently and "parar" cancels all of them.
 */
class Operations(private val events: EventDispatcher, private val scope: CoroutineScope) {
    private val _list = MutableStateFlow<List<Operation>>(emptyList())
    /** Running operations plus the ones that ended in the last few seconds (for the HUD). */
    val list: StateFlow<List<Operation>> = _list
    private val jobs = ConcurrentHashMap<String, Job>()
    private var seq = 0L

    suspend fun track(tool: String, args: JsonObject, block: suspend () -> String): String {
        val id = "op" + synchronized(this) { ++seq }
        val op = Operation(id, tool, label(tool, args), kind(tool), "running", startedAt = System.currentTimeMillis())
        coroutineContext[Job]?.let { jobs[id] = it }
        _list.update { it + op }
        events.emit("tool_started", AppJson.encodeToJsonElement(Operation.serializer(), op))
        return try {
            val result = block()
            finish(id, outcome(result), result.take(160))
            result
        } catch (e: CancellationException) {
            finish(id, "cancelled", "")
            throw e
        } catch (e: Exception) {
            finish(id, "failed", e.message.orEmpty())
            "error: ${e.message}"
        } finally {
            jobs.remove(id)
        }
    }

    /** "parar": cancels every running operation. Returns how many were running. */
    fun cancelAll(): Int {
        val running = jobs.values.toList()
        running.forEach { it.cancel() }
        return running.size
    }

    private fun finish(id: String, state: String, detail: String) {
        val now = System.currentTimeMillis()
        var ended: Operation? = null
        _list.update { ops -> ops.map { if (it.id == id) it.copy(state = state, detail = detail, endedAt = now).also { o -> ended = o } else it } }
        ended?.let { events.emit("tool_$state", AppJson.encodeToJsonElement(Operation.serializer(), it)) }
        // Keep the final state on screen for a moment, then drop it.
        scope.launch {
            delay(if (state == "succeeded") 2_500 else 5_000)
            _list.update { ops -> ops.filterNot { it.id == id } }
        }
    }

    companion object {
        /** A result that starts like this failed. */
        private val FAILED_START = listOf("error", "erro", "failed", "falha", "unknown", "missing", "need ", "that time is", "no ", "nenhum", "nenhuma", "invalid")
        /** These mean failure wherever they appear. */
        private val FAILED_ANY = listOf(
            "falhou", "failed", "not configured", "não cadastrad", "não está instalado", "sem conexão", "não responde",
            "is not configured", "não consegui", "not found", "não encontrad", "unavailable", "indisponível",
        )
        private val UNCONFIRMED = listOf("sem confirmação", "não confirmei", "not verified", "não informa", "status unknown", "unconfirmed", "accepted")

        /** Reads a tool's own words: failures and "sent but not confirmed" are never shown as success. */
        fun outcome(result: String): String {
            val r = result.lowercase()
            if (FAILED_START.any { r.startsWith(it) } || FAILED_ANY.any { r.contains(it) }) return "failed"
            if (UNCONFIRMED.any { r.contains(it) }) return "unknown"
            return "succeeded"
        }

        fun kind(tool: String): String = when {
            tool.startsWith("web_search") || tool.startsWith("fetch_url") -> "search"
            tool.contains("weather") -> "weather"
            tool.contains("reminder") || tool.contains("timer") || tool.contains("event") || tool == "whats_next" -> "time"
            tool.startsWith("tv_") -> "tv"
            tool.startsWith("music_") || tool.startsWith("spotify") -> "music"
            tool == "device" || tool.startsWith("wake") || tool.startsWith("lan") -> "home"
            tool.contains("face") || tool.contains("person") -> "face"
            tool in setOf("remember", "recall", "forget") -> "memory"
            tool.startsWith("list_") -> "list"
            tool == "screen_light" -> "light"
            tool.contains("quiz") -> "game"
            tool.startsWith("hook_") -> "webhook"
            tool in setOf("say_in_room", "broadcast", "call_room", "where_is", "list_rooms", "leave_message") -> "home"
            else -> "other"
        }

        private val LABELS = mapOf(
            "web_search" to "Pesquisando", "fetch_url" to "Lendo a página", "get_weather" to "Vendo o clima",
            "create_reminder" to "Criando lembrete", "list_reminders" to "Vendo lembretes", "cancel_reminder" to "Cancelando lembrete",
            "start_timer" to "Iniciando timer", "list_timers" to "Vendo timers", "cancel_timer" to "Cancelando timer",
            "add_event" to "Marcando na agenda", "list_events" to "Vendo a agenda", "whats_next" to "Vendo o próximo compromisso",
            "tv_control" to "TV", "remember" to "Guardando na memória", "recall" to "Lembrando", "forget" to "Esquecendo",
            "register_face" to "Cadastrando rosto", "screen_light" to "Luz", "device" to "Aparelho",
            "music_play" to "Música", "music_pause" to "Pausando música", "music_next" to "Próxima música",
            "say_in_room" to "Falando em outro cômodo", "broadcast" to "Avisando a casa", "where_is" to "Procurando pessoa",
            "leave_message" to "Deixando recado", "list_add" to "Anotando na lista", "start_quiz" to "Preparando quiz",
        )

        fun label(tool: String, args: JsonObject): String {
            val base = LABELS[tool] ?: tool.replace('_', ' ').replaceFirstChar { it.uppercase() }
            val detail = listOf("query", "action", "location", "message", "value", "text", "label", "title", "item")
                .firstNotNullOfOrNull { k -> (args[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
            return if (detail != null) "$base: ${detail.take(40)}" else base
        }
    }
}
