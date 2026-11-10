package io.github.assistent.tools

import io.github.assistent.core.DiaryManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReadDiaryTool(private val diary: DiaryManager) : Tool {
    override val name = "read_diary"
    override val description = "Read the robot's own diary entries. Use to answer 'como foi seu dia', 'no que você andou pensando', 'o que aconteceu essa semana'."
    override val parameters = params {
        number("days", "How many days back to read (1 = only today)", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val days = args.str("days")?.toIntOrNull()?.coerceIn(1, 30) ?: 3
        val entries = diary.recent(days)
        if (entries.isEmpty()) return "Sem entradas de diário ainda."
        return buildJsonArray {
            entries.forEach { e ->
                add(buildJsonObject {
                    put("date", e.date); put("mood", e.mood); put("text", e.text)
                })
            }
        }.toString()
    }
}
