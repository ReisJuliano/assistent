package io.github.assistent.core

import io.github.assistent.alerts.AlertManager
import io.github.assistent.calendar.CalendarRepository
import io.github.assistent.data.AlertEntity
import io.github.assistent.data.EventEntity
import io.github.assistent.data.MessageEntity
import io.github.assistent.data.ReminderEntity
import io.github.assistent.data.TimerEntity
import io.github.assistent.lists.ListRepository
import io.github.assistent.messages.MessageManager
import io.github.assistent.reminders.ReminderScheduler
import io.github.assistent.sleep.SleepTracker
import io.github.assistent.timers.TimerManager
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

data class Brief(
    val events: List<EventEntity>,
    val reminders: List<ReminderEntity>,
    val timers: List<TimerEntity>,
    val messages: List<MessageEntity>,
    val listsWithItems: Map<String, List<String>>,
    val sleep: String?,
    val weather: String?,
    val alerts: List<AlertEntity>,
)

class DailyBrief(
    private val calendar: CalendarRepository,
    private val reminders: ReminderScheduler,
    private val timers: TimerManager,
    private val messages: MessageManager,
    private val lists: ListRepository,
    private val sleep: SleepTracker,
    private val alerts: AlertManager,
) {
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"))

    suspend fun build(daysAhead: Int = 0, weatherSummary: String? = null): Brief {
        val day = LocalDate.now().plusDays(daysAhead.toLong())
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = start + 86_400_000L

        val events = runCatching { calendar.between(start, end) }.getOrElse { emptyList() }
            .sortedBy { it.startAt }

        val activeReminders = runCatching { reminders.list(true) }.getOrElse { emptyList() }
            .filter { it.triggerAt in start..end }

        val activeTimers = runCatching { timers.list(false) }.getOrElse { emptyList() }
            .filter { it.state == "active" }

        val pendingMessages = runCatching { messages.pending() }.getOrElse { emptyList() }

        val allLists = runCatching { lists.allLists() }.getOrElse { emptyList() }
        val listsWithItems = allLists.associate { list ->
            val items = runCatching { lists.items(list.name) }.getOrElse { emptyList() }
                .filter { !it.done }
                .map { it.text }
            list.name to items
        }.filter { it.value.isNotEmpty() }

        val sleepReport = runCatching { sleep.report(1) }.getOrNull()?.takeIf { it.isNotBlank() }

        val recentAlerts = runCatching { alerts.recent(20) }.getOrElse { emptyList() }
            .filter { it.createdAt >= System.currentTimeMillis() - 86_400_000L }

        return Brief(events, activeReminders, activeTimers, pendingMessages, listsWithItems, sleepReport, weatherSummary, recentAlerts)
    }

    fun toJson(brief: Brief) = buildJsonObject {
        put("events", buildJsonArray {
            brief.events.forEach { e ->
                add(buildJsonObject {
                    put("title", e.title); put("time", timeFmt.format(Date(e.startAt)))
                })
            }
        })
        put("reminders", buildJsonArray {
            brief.reminders.forEach { r ->
                add(buildJsonObject { put("message", r.message); put("time", timeFmt.format(Date(r.triggerAt))) })
            }
        })
        put("timers", buildJsonArray {
            brief.timers.forEach { t ->
                add(buildJsonObject {
                    put("label", t.label)
                    put("endsIn", ((t.endsAt - System.currentTimeMillis()) / 60_000).toString() + " min")
                })
            }
        })
        put("messages", brief.messages.size)
        put("lists", buildJsonObject {
            brief.listsWithItems.forEach { (name, items) -> put(name, items.joinToString(", ")) }
        })
        brief.sleep?.let { put("sleep", it) }
        brief.weather?.let { put("weather", it) }
        put("alerts", brief.alerts.size)
    }

    fun toSpoken(brief: Brief): String {
        val parts = mutableListOf<String>()
        if (brief.events.isEmpty() && brief.reminders.isEmpty() && brief.timers.isEmpty() &&
            brief.messages.isEmpty() && brief.listsWithItems.isEmpty()
        ) return "Seu dia tá livre, sem nada marcado."

        if (brief.events.isNotEmpty()) {
            val evs = brief.events.take(3).joinToString(", ") { "${it.title} às ${timeFmt.format(Date(it.startAt))}" }
            parts += "Agenda: $evs."
        }
        if (brief.reminders.isNotEmpty()) {
            val rs = brief.reminders.take(2).joinToString(" e ") { it.message }
            parts += "Lembretes: $rs."
        }
        if (brief.timers.isNotEmpty()) {
            val ts = brief.timers.take(2).joinToString(", ") {
                val min = (it.endsAt - System.currentTimeMillis()) / 60_000
                "${it.label} em ${min} min"
            }
            parts += "Timers: $ts."
        }
        if (brief.messages.isNotEmpty()) parts += "${brief.messages.size} recado(s) pendente(s)."
        if (brief.listsWithItems.isNotEmpty()) {
            val ls = brief.listsWithItems.entries.take(2).joinToString("; ") { "${it.key}: ${it.value.take(3).joinToString(", ")}" }
            parts += "Listas: $ls."
        }
        brief.weather?.let { parts += it }
        return parts.joinToString(" ")
    }
}
