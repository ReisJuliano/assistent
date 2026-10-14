package io.github.assistent.core

import android.util.Log
import io.github.assistent.brain.Brain
import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.LlmClient
import io.github.assistent.brain.LlmQuota
import io.github.assistent.config.ConfigStore
import io.github.assistent.data.ReminderEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/**
 * Turns a reminder into the line the robot actually says, so "tomar remédio" doesn't sound the same every day.
 *
 * Order: the reminder's own [ReminderEntity.phrase] (exact words) → a fresh AI line in the configured tone and the
 * reminder's [ReminderEntity.style] (short timeout, skipped while rate-limited) → hand-written templates per type.
 * Insistence grows with the nag count. The reminder content itself is always kept.
 */
class ReminderPhrases(private val config: ConfigStore, private val llm: LlmClient) {
    private val recent = ArrayDeque<String>()

    suspend fun line(r: ReminderEntity, owner: String, nag: Int = 0): String {
        if (r.phrase.isNotBlank()) return fill(r.phrase, owner, r.message)
        val cfg = config.current
        if (cfg.reminders.aiPhrases && (cfg.llm.provider == "chatgpt" || cfg.llm.baseUrl.isNotBlank()) && !LlmQuota.blocked) {
            ai(r, owner, nag)?.let { return it }
        }
        return template(r.type, owner, r.message, nag, recent)
    }

    private suspend fun ai(r: ReminderEntity, owner: String, nag: Int): String? = try {
        val cfg = config.current
        val p = cfg.personality
        val prompt = buildString {
            appendLine("You are ${p.name}, a small home robot. Write ONE spoken sentence in ${p.language} to deliver this reminder.")
            appendLine("Reminder: \"${r.message}\" (type: ${r.type}, ${if (r.kind == "alarm") "alarm" else "reminder"}). Time now: ${LocalTime.now().withSecond(0).withNano(0)}.")
            if (owner.isNotBlank()) appendLine("Address it to $owner by name.")
            appendLine("Tone: ${Brain.toneInstruction(p.tone, p.customTone)}")
            if (r.style.isNotBlank()) appendLine("Style requested by the user: ${r.style}.")
            if (nag > 0) appendLine("This is reminder number ${nag + 1}: it was not confirmed yet, so be more insistent (still kind).")
            if (r.type == "medicine") appendLine("It is about medicine: keep the exact medicine name/dose from the reminder, no jokes about health.")
            appendLine("Keep every concrete detail of the reminder (what, how much). Max 22 words. No emojis, quotes, markdown or emotion tags. Reply with the sentence only.")
        }
        val raw = withTimeoutOrNull(8_000) {
            llm.complete(cfg.llm, listOf(ChatMessage("user", prompt)), emptyList()).content
        }
        raw?.let { Brain.parseEmotion(it).second.trim().trim('"', '“', '”') }?.takeIf { it.length in 4..220 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("ReminderPhrases", "AI line failed: ${e.message}")
        null
    }

    companion object {
        /** `{n}` = person's name, `{msg}` = reminder text. */
        val TEMPLATES = mapOf(
            "medicine" to listOf(
                "{n}, hora do remédio: {msg}.",
                "Ei {n}, não esquece: {msg}. Sua saúde agradece!",
                "Plim! Lembrete de remédio, {n}: {msg}.",
                "{n}, pausa rapidinha: {msg}.",
            ),
            "water" to listOf(
                "{n}, bora se hidratar? {msg}.",
                "Glub glub! {n}, {msg}.",
                "Ei {n}, um copo d'água agora cai bem: {msg}.",
                "Hora da água, {n}! {msg}.",
            ),
            "food" to listOf(
                "{n}, hora de comer: {msg}.",
                "Minha barriga de robô roncou por você, {n}: {msg}.",
                "Ei {n}, pausa pra comida! {msg}.",
            ),
            "exercise" to listOf(
                "{n}, bora mexer esse corpo! {msg}.",
                "Hora de alongar, {n}: {msg}.",
                "Levanta um pouquinho, {n}! {msg}.",
            ),
            "generic" to listOf(
                "{n}, lembrete: {msg}.",
                "Ei {n}, você me pediu pra lembrar: {msg}.",
                "Plim! {n}, {msg}.",
                "Opa, {n}! Não esquece: {msg}.",
                "Psiu, {n}: {msg}.",
            ),
        )

        val NAGS = listOf(
            "{n}, ainda não confirmou: {msg}.",
            "Ei {n}, de novo: {msg}. Me avisa quando fizer!",
            "{n}, tô insistindo porque é importante: {msg}.",
        )

        /** Picks a template for the type (or a nag line) avoiding the last few used, then fills it in. */
        fun template(type: String, owner: String, message: String, nag: Int, recent: ArrayDeque<String> = ArrayDeque()): String {
            val pool = if (nag > 0) NAGS else TEMPLATES[type] ?: TEMPLATES.getValue("generic")
            val choice = pool.filterNot { it in recent }.ifEmpty { pool }.random()
            recent.addLast(choice)
            while (recent.size > 3) recent.removeFirst()
            return fill(choice, owner, message)
        }

        /** Fills `{n}`/`{msg}`; without a name, drops the vocative cleanly ("{n}, hora..." → "Hora..."). */
        fun fill(template: String, owner: String, message: String): String {
            val msg = message.trim().trimEnd('.', '!')
            var s = template.replace("{msg}", msg)
            if (owner.isNotBlank()) return s.replace("{n}", owner.trim())
            s = s.replace(Regex("\\{n},\\s*"), "")
                .replace(Regex(",?\\s*\\{n}"), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            return s.replaceFirstChar { it.uppercase() }
        }
    }
}
