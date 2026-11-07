package io.github.assistent.reminders

import io.github.assistent.data.PersonDao

/**
 * Conversational reminder creation without LLM — handles common patterns directly.
 * State persists across wake-phrase interactions; reset() clears it.
 */
class ReminderWizard(
    private val reminders: ReminderScheduler,
    private val people: PersonDao,
) {
    private enum class State { IDLE, NEED_WHAT, NEED_WHEN, NEED_REPEAT }

    private var state = State.IDLE
    private var pendingWhat = ""
    private var pendingWhen = 0L

    val isActive get() = state != State.IDLE

    fun reset() {
        state = State.IDLE
        pendingWhat = ""
        pendingWhen = 0L
    }

    /** Returns a spoken reply if the wizard handled the input, null to let Brain handle it. */
    suspend fun tryHandle(input: String): String? = when (state) {
        State.IDLE -> tryStart(input)
        State.NEED_WHAT -> captureWhat(input)
        State.NEED_WHEN -> captureWhen(input)
        State.NEED_REPEAT -> captureRepeat(input)
    }

    private suspend fun tryStart(text: String): String? {
        val low = normalize(text)

        // "se você ver João me lembra de ligar pra ele"
        val personRe = Regex(
            """(?:se (?:voce|você) ver|quando (?:voce |você )?ver)\s+(\w+)\s+(?:me lembr[ae]|me avi[sz]a)(?:\s+(?:de|que|sobre))?\s+(.+)""",
            RegexOption.IGNORE_CASE,
        )
        personRe.find(text)?.let { m ->
            val name = m.groupValues[1]
            val msg = m.groupValues[2].trim()
            if (msg.isNotBlank()) {
                val person = people.byName(name)
                reminders.createPersonTriggered(msg, name, person?.id)
                return "Tá! Vou te lembrar sobre \"$msg\" quando ver ${person?.name ?: name}."
            }
        }

        // "criar lembrete" / "novo lembrete"
        if (Regex("""(?:criar|novo|adicionar)\s+(?:um\s+)?lembrete""").containsMatchIn(low)) {
            state = State.NEED_WHAT
            return "O que você quer lembrar?"
        }

        // "me lembra de X" or "me lembra X"
        val lembrarRe = Regex("""me\s+lembr[ae](?:\s+de)?\s+(.+)""", RegexOption.IGNORE_CASE)
        lembrarRe.find(text)?.let { m ->
            val rest = m.groupValues[1].trim()
            val timeResult = TimeParser.parse(rest)
            if (timeResult != null && timeResult.triggerAt > System.currentTimeMillis()) {
                reminders.create(rest, timeResult.triggerAt, repeat = timeResult.repeat)
                return "Lembrete criado!"
            }
            if (rest.isNotBlank()) {
                pendingWhat = rest
                state = State.NEED_WHEN
                return "Quando você quer ser lembrado sobre isso?"
            }
        }

        return null
    }

    private fun captureWhat(text: String): String {
        pendingWhat = text.trim()
        state = State.NEED_WHEN
        return "Quando? Diga um horário, 'manhã', 'tarde', 'noite' ou 'daqui a X minutos'."
    }

    private suspend fun captureWhen(text: String): String? {
        val low = normalize(text)
        if (low in listOf("cancelar", "cancel", "esquece", "nao", "não") || "cancel" in low || "esquece" in low) {
            reset()
            return "Tudo bem, lembrete cancelado."
        }
        val result = TimeParser.parse(text)
        if (result == null) {
            // If input doesn't look time-related, bail to Brain
            if (!Regex("""\d|manha|tarde|noite|amanha|semana|hora|minuto""").containsMatchIn(low)) {
                reset()
                return null
            }
            return "Não entendi o horário. Pode dizer 'às 19h', 'manhã', 'tarde', 'noite' ou 'daqui a 30 minutos'?"
        }
        pendingWhen = result.triggerAt
        if (result.repeat != "none") {
            reminders.create(pendingWhat, pendingWhen, repeat = result.repeat)
            reset()
            return "Pronto! Lembrete de \"$pendingWhat\" criado."
        }
        state = State.NEED_REPEAT
        return "Repete? Pode dizer 'todo dia', 'toda semana', 'dias úteis' ou 'não'."
    }

    private suspend fun captureRepeat(text: String): String {
        val low = normalize(text)
        val repeat = when {
            "todo dia" in low || "todos os dias" in low || "diariamente" in low -> "daily"
            "toda semana" in low || "semanalmente" in low -> "weekly"
            "dias uteis" in low -> "weekdays"
            else -> "none"
        }
        reminders.create(pendingWhat, pendingWhen, repeat = repeat)
        val repeatText = when (repeat) {
            "daily" -> " todo dia"
            "weekly" -> " toda semana"
            "weekdays" -> " nos dias úteis"
            else -> ""
        }
        reset()
        return "Pronto! Lembrete de \"$pendingWhat\"$repeatText criado."
    }

    private fun normalize(s: String) = s.lowercase()
        .replace('à', 'a').replace('ã', 'a').replace('ç', 'c')
        .replace('é', 'e').replace('ê', 'e').replace('ó', 'o')
        .replace('ú', 'u').replace('í', 'i').replace('â', 'a')
        .replace('á', 'a').replace('ô', 'o').replace('õ', 'o')
}
