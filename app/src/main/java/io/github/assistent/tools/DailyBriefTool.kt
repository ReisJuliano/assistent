package io.github.assistent.tools

import io.github.assistent.core.AssistantRuntime
import io.github.assistent.core.DailyBrief
import kotlinx.serialization.json.JsonObject

class DailyBriefTool(
    private val brief: DailyBrief,
    private val runtime: AssistantRuntime,
) : Tool {
    override val name = "daily_brief"
    override val description = "Return today's agenda, reminders, timers, messages and lists. Use for 'o que tenho hoje?', 'resumo do dia', 'e amanhã?'."
    override val parameters = params {
        number("days_ahead", "0 = today, 1 = tomorrow, etc.", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val daysAhead = args.str("days_ahead")?.toIntOrNull()?.coerceIn(0, 7) ?: 0
        val weather = runtime.state.value.ambient.let {
            buildString {
                it.temperature?.let { t -> append("${t.toInt()}°C") }
                if (isNotEmpty()) append(", ")
                append(it.condition)
            }.takeIf { it.isNotBlank() }
        }
        val b = brief.build(daysAhead, weather)
        return brief.toJson(b).toString()
    }
}
