package io.github.assistent.tools

import io.github.assistent.core.PresenceTracker
import io.github.assistent.timers.TimerManager
import kotlinx.serialization.json.JsonObject
import java.time.Duration

class StartTimerTool(private val timers: TimerManager, private val presence: PresenceTracker) : Tool {
    override val name = "start_timer"
    override val description = "Start a countdown timer. Give seconds or minutes and a short label."
    override val parameters = params {
        string("label", "Short label, e.g. arroz, cha, forno", required = false)
        number("seconds", "Seconds from now", required = false)
        number("minutes", "Minutes from now", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val seconds = args.num("seconds") ?: args.num("minutes")?.times(60) ?: return "need seconds or minutes"
        val durationMs = (seconds * 1000).toLong().coerceAtLeast(1_000)
        val timer = timers.create(args.str("label") ?: "timer", durationMs, personId = presence.currentPerson.value?.id)
        return "timer #${timer.id} '${timer.label}' ends at ${fmt(timer.endsAt)}"
    }
}

class ListTimersTool(private val timers: TimerManager) : Tool {
    override val name = "list_timers"
    override val description = "List active countdown timers."
    override val parameters = params()

    override suspend fun execute(args: JsonObject): String {
        val list = timers.list()
        return if (list.isEmpty()) "none" else list.joinToString("\n") {
            val left = Duration.ofMillis((it.endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            "#${it.id} ${it.label}: ${left.toMinutes()}m ${left.toSecondsPart()}s"
        }
    }
}

class CancelTimerTool(private val timers: TimerManager) : Tool {
    override val name = "cancel_timer"
    override val description = "Cancel a countdown timer by id."
    override val parameters = params { integer("id", "Timer id") }

    override suspend fun execute(args: JsonObject): String =
        if (timers.cancel(args.num("id")?.toLong() ?: -1)) "cancelled" else "not found"
}
