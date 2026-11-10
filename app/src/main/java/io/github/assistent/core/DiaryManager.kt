package io.github.assistent.core

import io.github.assistent.alerts.AlertManager
import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.LlmClient
import io.github.assistent.calendar.CalendarRepository
import io.github.assistent.config.ConfigStore
import io.github.assistent.data.DiaryDao
import io.github.assistent.data.DiaryEntity
import io.github.assistent.messages.MessageManager
import io.github.assistent.reminders.ReminderScheduler
import io.github.assistent.sleep.SleepTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale

/**
 * Writes a nightly diary entry once per day at the configured hour.
 * Ticked by [LifeDirector] every 30 s.
 */
class DiaryManager(
    private val dao: DiaryDao,
    private val config: ConfigStore,
    private val llm: LlmClient,
    private val calendar: CalendarRepository,
    private val reminders: ReminderScheduler,
    private val messages: MessageManager,
    private val sleep: SleepTracker,
    private val alerts: AlertManager,
    private val events: EventDispatcher,
    private val scope: CoroutineScope,
) {
    private var wroteForDay = ""

    fun tick() {
        val cfg = config.current.life
        if (!cfg.diary) return
        val now = LocalTime.now()
        val today = LocalDate.now().toString()
        if (now.hour < cfg.diaryHour || wroteForDay == today) return
        wroteForDay = today
        scope.launch { writeDay(today) }
    }

    suspend fun writeDay(date: String): DiaryEntity? {
        val existing = dao.get(date)
        val cfg = config.current
        // Gather context — cheap local queries only, no LLM calls for context.
        val fmt = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"))
        val dayStart = java.time.LocalDate.parse(date).atStartOfDay()
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dayEnd = dayStart + 86_400_000L
        val calEvents = runCatching { calendar.between(dayStart, dayEnd) }.getOrElse { emptyList() }
        val reminders = runCatching { reminders.list(false) }.getOrElse { emptyList() }
            .filter { (it.firstFiredAt ?: 0L) in dayStart..dayEnd }
        val msgs = runCatching { messages.pending() }.getOrElse { emptyList() }
        val sleepReport = runCatching { sleep.report(1) }.getOrElse { "" }
        val alertList = runCatching { alerts.recent(10) }.getOrElse { emptyList() }
            .filter { it.createdAt in dayStart..dayEnd }
        val p = cfg.personality
        val contextLines = buildList {
            if (calEvents.isNotEmpty()) add("Compromissos: " + calEvents.joinToString("; ") { "${it.title} às ${fmt.format(Date(it.startAt))}" })
            if (reminders.isNotEmpty()) add("Lembretes disparados: " + reminders.joinToString("; ") { it.message })
            if (msgs.isNotEmpty()) add("Recados pendentes: ${msgs.size}")
            if (sleepReport.isNotBlank()) add("Sono: $sleepReport")
            if (alertList.isNotEmpty()) add("Alertas: " + alertList.joinToString("; ") { it.text })
        }
        val ctx = if (contextLines.isEmpty()) "A casa ficou quieta hoje." else contextLines.joinToString(" | ")
        val prompt = buildString {
            appendLine("Você é ${p.name}, um robô assistente. Escreva UMA entrada de diário em 1ª pessoa em português sobre o dia $date.")
            appendLine("Tom: ${p.traits}. Máximo 3 frases curtas e naturais, sem markdown.")
            appendLine("Termine com [mood:palavra] onde palavra é uma de: alegre, calmo, curioso, cansado, animado, pensativo.")
            appendLine("Contexto do dia: $ctx")
        }
        val result = withTimeoutOrNull(12_000) {
            runCatching {
                llm.complete(cfg.llm, listOf(ChatMessage("user", prompt)), emptyList()).content.orEmpty().trim()
            }.getOrNull()
        } ?: return null
        // Extract [mood:word]
        val moodMatch = Regex("""\[mood:(\w+)\]""").find(result)
        val mood = moodMatch?.groupValues?.get(1) ?: ""
        val text = result.replace(Regex("""\[mood:\w+\]"""), "").trim()
        val entry = if (existing != null) {
            existing.copy(text = text, mood = mood, createdAt = System.currentTimeMillis())
                .also { dao.update(it) }
        } else {
            val id = dao.insert(DiaryEntity(date = date, text = text, mood = mood))
            DiaryEntity(id = id, date = date, text = text, mood = mood)
        }
        events.emit("diary_written", buildJsonObject {
            put("date", date); put("mood", mood)
        })
        return entry
    }

    suspend fun recent(days: Int = 7) = dao.recent(days)
    suspend fun get(date: String) = dao.get(date)
    suspend fun delete(id: Long) = dao.delete(id)
}
