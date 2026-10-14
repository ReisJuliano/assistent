package io.github.assistent.tools

import io.github.assistent.calendar.CalendarRepository
import io.github.assistent.core.PresenceTracker
import io.github.assistent.data.EventEntity
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AddEventTool(private val calendar: CalendarRepository, private val presence: PresenceTracker) : Tool {
    override val name = "add_event"
    override val description = "Add an internal calendar event. Dates must be local ISO-8601, e.g. 2026-10-14T15:00."
    override val parameters = params {
        string("title", "Event title")
        string("start", "Start date-time, ISO-8601")
        string("end", "End date-time, ISO-8601", required = false)
        string("location", "Location", required = false)
        string("notes", "Notes", required = false)
        string("recurrence", "Repetition", required = false, enum = listOf("none", "daily", "weekly", "monthly", "yearly"))
        number("remind_minutes_before", "Reminder minutes before", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val title = args.str("title") ?: return "missing title"
        val start = args.str("start")?.let(::parseDateTime) ?: return "missing or invalid start"
        val event = calendar.add(
            EventEntity(
                title = title,
                startAt = start,
                endAt = args.str("end")?.let(::parseDateTime),
                location = args.str("location").orEmpty(),
                notes = args.str("notes").orEmpty(),
                personId = presence.currentPerson.value?.id,
                recurrence = args.str("recurrence") ?: "none",
                remindMinutesBefore = args.num("remind_minutes_before")?.toInt() ?: 0,
            ),
        )
        return "created event #${event.id} for ${fmtEventTime(event.startAt)}"
    }
}

class ListEventsTool(private val calendar: CalendarRepository, private val presence: PresenceTracker) : Tool {
    override val name = "list_events"
    override val description = "List internal calendar events between two ISO-8601 date-times."
    override val parameters = params {
        string("from", "Start of range, ISO-8601")
        string("to", "End of range, ISO-8601")
    }

    override suspend fun execute(args: JsonObject): String {
        val from = args.str("from")?.let(::parseDateTime) ?: return "missing or invalid from"
        val to = args.str("to")?.let(::parseDateTime) ?: return "missing or invalid to"
        val events = calendar.between(from, to, presence.currentPerson.value?.id)
        return if (events.isEmpty()) "none" else events.joinToString("\n", transform = ::eventLine)
    }
}

class WhatsNextTool(private val calendar: CalendarRepository) : Tool {
    override val name = "whats_next"
    override val description = "Show the next internal calendar events."
    override val parameters = params()

    override suspend fun execute(args: JsonObject): String {
        val events = calendar.upcoming()
        return if (events.isEmpty()) "nothing scheduled" else events.joinToString("\n", transform = ::eventLine)
    }
}

class DeleteEventTool(private val calendar: CalendarRepository) : Tool {
    override val name = "delete_event"
    override val description = "Delete an internal calendar event by id."
    override val parameters = params { integer("id", "Event id") }

    override suspend fun execute(args: JsonObject): String =
        if (calendar.delete(args.num("id")?.toLong() ?: -1)) "deleted" else "not found"
}

internal fun parseDateTime(s: String): Long? = runCatching {
    OffsetDateTime.parse(s).toInstant().toEpochMilli()
}.recoverCatching {
    LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

internal fun fmtEventTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("EEE dd/MM/yyyy HH:mm"))

private fun eventLine(e: EventEntity): String =
    "#${e.id} ${fmtEventTime(e.startAt)} ${e.title}" + if (e.location.isNotBlank()) " @ ${e.location}" else ""
