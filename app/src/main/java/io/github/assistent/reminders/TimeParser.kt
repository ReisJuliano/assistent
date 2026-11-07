package io.github.assistent.reminders

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object TimeParser {
    data class ParseResult(val triggerAt: Long, val repeat: String = "none")

    fun parse(input: String): ParseResult? {
        val text = normalize(input)
        val repeat = extractRepeat(text)
        val at = tryRelative(text)
            ?: tryAbsoluteTime(text)
            ?: tryNamedPeriod(text)
            ?: tryWeekday(text)
            ?: tryDayRef(text)
        return at?.let { ParseResult(it, repeat) }
    }

    fun extractRepeat(input: String): String {
        val t = normalize(input)
        return when {
            "todo dia" in t || "todos os dias" in t || "diariamente" in t -> "daily"
            "toda semana" in t || "semanalmente" in t || "todas as semanas" in t -> "weekly"
            "dias uteis" in t || "todo dia util" in t || "dias uteis" in t -> "weekdays"
            else -> "none"
        }
    }

    private fun normalize(s: String) = s.lowercase()
        .replace('à', 'a').replace('ã', 'a').replace('ç', 'c')
        .replace('é', 'e').replace('ê', 'e').replace('ó', 'o')
        .replace('ú', 'u').replace('í', 'i').replace('â', 'a')
        .replace('á', 'a').replace('ô', 'o').replace('õ', 'o')

    // "daqui a 5 minutos", "daqui a 2 horas"
    private fun tryRelative(text: String): Long? {
        val r = Regex("""daqui\s+[ah]\s+(\d+)\s+(minuto|minutos|hora|horas|segundo|segundos)""")
        val m = r.find(text) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val ms = when {
            m.groupValues[2].startsWith("minuto") -> n * 60_000L
            m.groupValues[2].startsWith("hora") -> n * 3_600_000L
            m.groupValues[2].startsWith("segundo") -> n * 1_000L
            else -> return null
        }
        return System.currentTimeMillis() + ms
    }

    // "19:30", "19h30", "19h", "às 7:30 da tarde"
    private fun tryAbsoluteTime(text: String): Long? {
        val r = Regex("""(\d{1,2})(?:h(\d{2})|:(\d{2})|h)(?:\s+da\s+(manha|tarde|noite|madrugada))?""")
        val m = r.find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: m.groupValues[3].toIntOrNull() ?: 0
        val period = m.groupValues[4]
        when {
            period == "tarde" || period == "noite" -> if (hour in 1..11) hour += 12
            period == "madrugada" -> if (hour == 12) hour = 0
        }
        if (hour > 23 || min > 59) return null
        return toEpoch(LocalTime.of(hour, min))
    }

    // "manhã", "tarde", "noite", "almoço" (no digits in input)
    private fun tryNamedPeriod(text: String): Long? {
        if (Regex("""\d""").containsMatchIn(text)) return null
        return when {
            "manha" in text -> toEpoch(LocalTime.of(9, 0))
            "almoco" in text -> toEpoch(LocalTime.of(12, 0))
            "tarde" in text -> toEpoch(LocalTime.of(15, 0))
            "noite" in text -> toEpoch(LocalTime.of(20, 0))
            else -> null
        }
    }

    // "próxima segunda", "na quarta"
    private fun tryWeekday(text: String): Long? {
        val days = mapOf(
            "segunda" to DayOfWeek.MONDAY,
            "terca" to DayOfWeek.TUESDAY,
            "quarta" to DayOfWeek.WEDNESDAY,
            "quinta" to DayOfWeek.THURSDAY,
            "sexta" to DayOfWeek.FRIDAY,
            "sabado" to DayOfWeek.SATURDAY,
            "domingo" to DayOfWeek.SUNDAY,
        )
        for ((name, dow) in days) {
            if (name in text) {
                val next = LocalDate.now().with(TemporalAdjusters.next(dow))
                return next.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }

    // "amanhã", "hoje"
    private fun tryDayRef(text: String): Long? = when {
        "amanha" in text -> LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        "hoje" in text -> toEpoch(LocalTime.of(9, 0))
        else -> null
    }

    private fun toEpoch(time: LocalTime): Long {
        val zone = ZoneId.systemDefault()
        var dt = LocalDateTime.of(LocalDate.now(), time)
        // If the time already passed today, schedule for tomorrow
        if (dt.atZone(zone).toInstant().toEpochMilli() <= System.currentTimeMillis() + 60_000) {
            dt = dt.plusDays(1)
        }
        return dt.atZone(zone).toInstant().toEpochMilli()
    }
}
