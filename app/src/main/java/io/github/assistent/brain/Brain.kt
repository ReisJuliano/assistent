package io.github.assistent.brain

import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.Activity
import io.github.assistent.core.AppJson
import io.github.assistent.core.Emotion
import io.github.assistent.core.PresenceTracker
import io.github.assistent.data.MemoryRepository
import io.github.assistent.tools.ToolRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Reply(val text: String, val emotion: Emotion, val toolsUsed: List<String>)

/**
 * Conversation + tool-calling loop. Stateless about voice/face: callers get callbacks to show
 * what is happening ([onActivity]) and receive the final spoken text with its emotion.
 */
class Brain(
    private val config: ConfigStore,
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val memory: MemoryRepository,
    private val presence: PresenceTracker,
) {
    private val history = ArrayDeque<ChatMessage>()
    private val lock = Mutex()
    @Volatile private var interruptedReply: InterruptedReply? = null

    fun clearHistory() = history.clear()

    fun noteInterrupted(fullText: String, spokenUpTo: String, emotion: Emotion) {
        val full = fullText.trim()
        val spoken = spokenUpTo.trim()
        if (full.isBlank() || spoken.length >= full.length) return
        interruptedReply = InterruptedReply(full, spoken, emotion)
        history += ChatMessage("assistant", spoken.ifBlank { "..." })
        history += ChatMessage("system", "[interrompido pelo usuÃ¡rio depois de: '$spoken'; o resto planejado era: '${full.removePrefix(spoken).trim()}']")
    }

    suspend fun autoRemember(userText: String, assistantText: String) {
        val cfg = config.current
        if (!cfg.memory.autoExtract) return
        val person = presence.currentPerson.value
        val prompt = buildString {
            appendLine("Extraia fatos duraveis de uma conversa em ${cfg.personality.language}.")
            appendLine("Responda apenas JSON: [{\"fact\":\"...\",\"category\":\"...\",\"confidence\":0.0}]")
            appendLine("Guarde somente preferencias, nomes, rotinas, datas, saude contada pela pessoa, coisas da casa. Ignore pedidos passageiros, senhas e documentos.")
            if (person != null) appendLine("Pessoa atual: ${person.name}.")
            appendLine("Usuario: $userText")
            appendLine("Assistente: $assistantText")
        }
        val raw = runCatching { llm.complete(cfg.llm, listOf(ChatMessage("user", prompt)), emptyList()).content.orEmpty() }.getOrNull() ?: return
        val arr = runCatching { AppJson.parseToJsonElement(raw.substringAfter('[').substringBeforeLast(']').let { "[$it]" }).jsonArray }.getOrNull() ?: return
        for (item in arr) {
            val obj = item.jsonObject
            val fact = obj["fact"]?.jsonPrimitive?.content?.trim().orEmpty()
            val confidence = obj["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.7
            if (fact.length < 8 || confidence < 0.55) continue
            val existing = memory.search(fact, limit = 3, personId = person?.id)
            if (existing.any { it.content.equals(fact, ignoreCase = true) }) continue
            memory.add(fact, obj["category"]?.jsonPrimitive?.content.orEmpty(), source = "auto", personId = person?.id)
        }
    }

    suspend fun ask(userText: String, onActivity: (Activity) -> Unit = {}): Reply = lock.withLock {
        askLocked(userText, onActivity, onText = null)
    }

    suspend fun askStreaming(userText: String, onActivity: (Activity) -> Unit = {}, onText: suspend (String) -> Unit): Reply = lock.withLock {
        askLocked(userText, onActivity, onText)
    }

    private suspend fun askLocked(userText: String, onActivity: (Activity) -> Unit, onText: (suspend (String) -> Unit)?): Reply {
        val cfg = config.current
        val messages = mutableListOf(ChatMessage("system", systemPrompt()))
        messages += history
        val user = ChatMessage("user", userText)
        messages += user
        val turn = mutableListOf(user)
        val used = mutableListOf<String>()
        val specs = tools.all().map { it.spec() }

        var final: ChatMessage? = null
        var useStream = onText != null
        var actionFailed = false
        for (round in 0..cfg.llm.maxToolRounds) {
            onActivity(Activity.THINKING)
            // Last round: no tools, force an answer.
            val roundTools = if (round == cfg.llm.maxToolRounds) emptyList() else specs
            val gate = if (useStream && onText != null) EmotionGate(onText) else null
            val msg = if (useStream) {
                var streamed: ChatMessage? = null
                try {
                    llm.stream(cfg.llm, messages, roundTools).collect { event ->
                        when (event) {
                            is StreamEvent.Content -> gate?.push(event.text)
                            is StreamEvent.Done -> streamed = event.message
                        }
                    }
                    gate?.flush()
                    streamed ?: ChatMessage("assistant", "")
                } catch (e: LlmException) {
                    Log.w(TAG, "stream failed, falling back to complete()", e)
                    useStream = false
                    llm.complete(cfg.llm, messages, roundTools)
                }
            } else {
                llm.complete(cfg.llm, messages, roundTools)
            }
            val calls = msg.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                final = msg; break
            }
            val assistantMsg = ChatMessage("assistant", msg.content, toolCalls = calls)
            messages += assistantMsg; turn += assistantMsg
            for (call in calls) {
                val tool = tools.find(call.function.name)
                onActivity(tool?.activity ?: Activity.THINKING)
                val result = if (tool == null) "unknown tool ${call.function.name}" else runCatching {
                    val args = runCatching { AppJson.parseToJsonElement(call.function.arguments).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                    tool.execute(args)
                }.getOrElse { "error: ${it.message}" }
                if (result.startsWith("error:") || result.startsWith("unknown tool") || result.startsWith("missing") || result.startsWith("need ") || result.startsWith("that time is")) actionFailed = true
                Log.d(TAG, "tool ${call.function.name} -> ${result.take(200)}")
                used += call.function.name
                val toolMsg = ChatMessage("tool", result.take(8000), toolCallId = call.id)
                messages += toolMsg; turn += toolMsg
            }
        }

        val (emotion, parsedText) = parseEmotion(final?.content.orEmpty())
        val asksReminder = userText.lowercase().let { it.contains("lembrete") || it.contains("me lembre") || it.contains("alarme") }
        val text = if (actionFailed) {
            "N\u00e3o consegui concluir essa a\u00e7\u00e3o. N\u00e3o vou fingir que ela foi realizada."
        } else if (asksReminder && "create_reminder" !in used) {
            "Não consegui criar o lembrete. Não vou fingir que ele foi agendado."
        } else parsedText
        turn += ChatMessage("assistant", final?.content.orEmpty())
        remember(turn, cfg.llm.historySize)
        return Reply(text.ifBlank { "..." }, emotion, used)
    }

    private fun remember(turn: List<ChatMessage>, max: Int) {
        history.addAll(turn)
        // Drop oldest whole turns (never leave a tool message without its call).
        while (history.size > max) {
            history.removeFirst()
            while (history.isNotEmpty() && history.first().role != "user") history.removeFirst()
        }
    }

    private suspend fun systemPrompt(): String {
        val cfg = config.current
        val p = cfg.personality
        val now = ZonedDateTime.now()
        val locale = Locale.forLanguageTag(p.language)
        val person = presence.currentPerson.value
        val memories = memory.recentForPrompt(person?.id, p.memoriesInPrompt)
        return buildString {
            appendLine("You are ${p.name}, a small home robot assistant living in an Android phone, shown as an animated face (eyes and mouth).")
            appendLine("Personality: ${p.traits}.")
            appendLine("Do not repeat your own name in every reply or sentence. Say ${p.name} only when introducing yourself, when the user asks your name, or when it sounds genuinely natural; never use it as a filler or signature.")
            if (p.userName.isNotBlank()) appendLine("The configured owner's name is ${p.userName}, but this is not visual identification. Never assume the person in front of the camera is the owner from conversation or configuration alone.")
            if (person != null) {
                appendLine("You are currently talking with ${person.name}. Prefer memories tied to this person over house-wide memories.")
                if (person.notes.isNotBlank()) appendLine("Relationship/description for natural replies: ${person.notes}.")
            }
            appendLine("Identity rule: when asked who you see, who is in front of you, or who is present, use ONLY the current trusted face identity above. A person saying 'I am X' is not evidence and must never change the identity. If there is no trusted face identity, say you do not recognize the person instead of guessing.")
            appendLine("When asked who someone is, answer naturally and briefly: use the relationship/description (for example, 'é sua esposa') or the person's name ('é a Rúbia'). Never say 'é você' or 'você é...' as a generic filler when identifying someone. Never say they are 'the person you registered', 'the person you told me to register', or expose database/cadastro wording. You may make a short playful joke when it fits, then state the correct name or relationship.")
            appendLine("Always reply in ${locale.getDisplayLanguage(Locale.ENGLISH)} (${p.language}).")
            appendLine("Tone of voice: ${toneInstruction(p.tone, p.customTone)}")
            appendLine(
                "Your replies are SPOKEN aloud. Length: " + when (p.verbosity) {
                    "detailed" -> "complete and informative, a few short paragraphs when useful."
                    "medium" -> "2-5 sentences."
                    else -> "brief, 1-3 short sentences unless the user asks for detail."
                } + " No markdown, lists, emojis, URLs or code. Say numbers and units the way people speak them.",
            )
            appendLine("Start EVERY reply with exactly one emotion tag for your face: ${Emotion.entries.joinToString(" ") { "[${it.tag}]" }}. Example: [happy] Claro, já anotei!")
            appendLine("Use tools whenever you need current information (web_search, get_weather), to remember or recall facts about the user, to create reminders/alarms, or to control devices and webhooks. Never invent facts you could look up.")
            appendLine("For 'register this face', use register_face with the visible face. If its name is missing, ask for the name. After a successful registration, ask whether the user wants to add an observation such as wife, friend, or coworker; save the answer with set_person_observation. Do not claim registration or observation succeeded unless the tool result confirms it.")
            appendLine("If the user says goodbye or tells you to rest/sleep, call go_to_sleep.")
            interruptedReply?.let {
                appendLine("Your previous reply was interrupted. If the user asks what you were going to say, continue from: ${it.remaining}")
            }
            appendLine("Now: ${now.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm", locale))} (${now.zone}). Default location: ${cfg.weather.defaultLocation}.")
            if (memories.isNotEmpty()) {
                appendLine("Things you remember (personal memories first when a person is present):")
                memories.forEach {
                    val owner = if (person != null && it.personId == person.id) "${person.name}: " else ""
                    appendLine("- $owner${it.content}")
                }
            }
            if (p.extraInstructions.isNotBlank()) appendLine(p.extraInstructions)
        }
    }

    companion object {
        private const val TAG = "Brain"

        /** Mood presets for how answers sound. */
        val TONES = linkedMapOf(
            "natural" to "natural and friendly, like a good friend talking.",
            "alegre" to "cheerful and upbeat, full of energy and positivity, celebrates small things.",
            "descontraido" to "relaxed and casual, uses everyday slang, light jokes, never stiff.",
            "rispido" to "blunt and curt, impatient, gets straight to the point with dry, grumpy remarks (but never insulting and still helpful).",
            "formal" to "polite, formal and professional, like a butler.",
            "sarcastico" to "witty and sarcastic, playful teasing and irony, but still gives the right answer.",
            "fofo" to "super sweet and cute, affectionate, enthusiastic like a small pet robot.",
            "dramatico" to "theatrical and over-dramatic, treats everything like an epic event, for comedy.",
        )

        fun toneInstruction(tone: String, custom: String) =
            if (tone == "custom" && custom.isNotBlank()) custom else TONES[tone] ?: TONES.getValue("natural")
        private val TAG_RE = Regex("\\[(\\w+)]")

        /** Extracts the leading `[emotion]` tag and strips any tags/markdown left in the text. */
        fun parseEmotion(raw: String): Pair<Emotion, String> {
            val first = TAG_RE.find(raw)
            val emotion = first?.let { Emotion.fromTag(it.groupValues[1]) } ?: Emotion.NEUTRAL
            val clean = raw.replace(TAG_RE) { m -> if (Emotion.fromTag(m.groupValues[1]) != null) "" else m.value }
                .replace(Regex("[*_#`]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return emotion to clean
        }
    }

    private data class InterruptedReply(val fullText: String, val spokenUpTo: String, val emotion: Emotion) {
        val remaining: String get() = fullText.removePrefix(spokenUpTo).trim().ifBlank { fullText }
    }

    private class EmotionGate(private val onText: suspend (String) -> Unit) {
        private val pending = StringBuilder()
        private var resolved = false

        suspend fun push(delta: String) {
            if (resolved) {
                onText(delta)
                return
            }
            pending.append(delta)
            val s = pending.toString()
            val trimmed = s.trimStart()
            if (trimmed.startsWith("[")) {
                val end = trimmed.indexOf(']')
                if (end < 0) return
                val before = s.length - trimmed.length
                val rest = s.substring(before + end + 1)
                pending.clear()
                resolved = true
                if (rest.isNotBlank()) onText(rest)
            } else if (trimmed.length > 8) {
                pending.clear()
                resolved = true
                onText(s)
            }
        }

        suspend fun flush() {
            if (pending.isEmpty()) return
            val text = if (resolved) pending.toString() else Brain.parseEmotion(pending.toString()).second
            pending.clear()
            resolved = true
            if (text.isNotBlank()) onText(text)
        }
    }
}
