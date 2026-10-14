package io.github.assistent.core

import android.util.Log
import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.LlmClient
import io.github.assistent.config.ConfigStore
import io.github.assistent.data.PersonEntity
import io.github.assistent.data.ReminderDao
import io.github.assistent.vision.People
import io.github.assistent.vision.Sighting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Says hi when someone walks by: a speech bubble (and optionally voice) with a catchphrase that fits the
 * moment. Known faces get their name; strangers get a friendly line without one.
 */
class Greeter(
    private val config: ConfigStore,
    private val runtime: AssistantRuntime,
    private val people: People,
    private val reminders: ReminderDao,
    private val llm: LlmClient,
    private val events: EventDispatcher,
    private val scope: CoroutineScope,
) {
    private val lastGreet = mutableMapOf<String, Long>()
    private val recent = ArrayDeque<String>()
    private var unknownStreak = 0
    private var lastKnownAt = 0L
    private var lastKnownPerson: PersonEntity? = null
    private var visiblePersonId: Long? = null
    private var lastSeenWrite = mutableMapOf<Long, Long>()

    /** Called whenever a known person is recognized (mesh presence). */
    var onKnownSeen: (String) -> Unit = {}
    var onKnownPersonSeen: (PersonEntity) -> Unit = {}
    var onNoKnownPersonSeen: () -> Unit = {}

    /** Called when the tracker confirms that no face remains visible. */
    fun onFaceGone() {
        lastKnownPerson = null
        lastKnownAt = 0L
        visiblePersonId = null
    }

    /** A different ML Kit tracking target entered the frame. */
    fun onFaceTargetChanged() {
        lastKnownPerson = null
        lastKnownAt = 0L
        visiblePersonId = null
    }

    fun onSighting(s: Sighting) {
        val cfg = config.current.people
        val now = System.currentTimeMillis()

        // A model score is not trustworthy when the camera image is dark.
        // Do this before the identity lock too, so a previous person's name
        // cannot leak through a low-light frame.
        if (runtime.cameraBrightness < LOW_LIGHT_BRIGHTNESS) {
            runtime.onUnknownFace(s.similarity)
            onNoKnownPersonSeen()
            return
        }
        val person = s.person
        if (person != null) {
            val reappeared = visiblePersonId != person.id
            visiblePersonId = person.id
            unknownStreak = 0
            lastKnownAt = now
            lastKnownPerson = person
            runtime.onFaceRecognition(person.name, s.similarity)
            onKnownPersonSeen(person)
            onKnownSeen(person.name)
            val previousSeen = person.lastSeen
            // Keep "last seen" fresh (throttled) so "away" means really not seen.
            if (now - (lastSeenWrite[person.id] ?: 0L) > 30_000) {
                lastSeenWrite[person.id] = now
                scope.launch { people.markSeen(person) }
            }
            if (!cfg.greet) return
            val away = if (previousSeen == 0L) null else (now - previousSeen) / 60_000
            val key = "p${person.id}"
            // A new camera appearance is enough context for a greeting. Keep
            // one-minute debounce so recognition frames never repeat it.
            if (!reappeared) return
            if (now - (lastGreet[key] ?: 0L) < 60_000L) return
            greet(key, person.name, away, person.birthday)
        } else {
            // A single bad embedding is common when the same face turns or gets small.
            // FaceTracker will clear this lock when the face leaves the frame entirely.
            val locked = lastKnownPerson
            if (locked != null && now - lastKnownAt < IDENTITY_LOCK_MS) {
                runtime.onFaceRecognition(locked.name, s.similarity)
                return
            }
            runtime.onUnknownFace(s.similarity)
            onNoKnownPersonSeen()
            // Strangers: require a stable sighting and no known face just now (a bad angle of a known face is not a stranger).
            unknownStreak++
            if (!cfg.greet || !cfg.greetUnknown || unknownStreak < 4 || now - lastKnownAt < 120_000) return
            if (now - (lastGreet["unknown"] ?: 0L) < cfg.greetAfterMinutes * 60_000L) return
            greet("unknown", null, null, "")
        }
    }

    /** For the settings button / API: greet the owner (or a stranger) right now. */
    fun testGreeting() {
        val name = config.current.personality.userName.ifBlank { null }
        greet("test${System.nanoTime()}", name, 60L * 30, "")
    }

    private fun greet(key: String, name: String?, minutesAway: Long?, birthday: String) {
        val s = runtime.state.value
        if (s.activity != Activity.IDLE) return // never interrupt a conversation, never wake it up
        lastGreet[key] = System.currentTimeMillis()
        scope.launch {
            val now = LocalDateTime.now()
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val remindersToday = runCatching {
                reminders.active().count { java.time.Instant.ofEpochMilli(it.triggerAt).atZone(zone).toLocalDate() == today }
            }.getOrDefault(0)
            val ctx = GreetContext(
                name = name, now = now, ambient = s.ambient, minutesAway = minutesAway, remindersToday = remindersToday,
                isBirthday = birthday.isNotBlank() && birthday == today.format(DateTimeFormatter.ofPattern("MM-dd")),
                assistantName = config.current.personality.name,
            )
            val text = (if (config.current.people.greetWithAi) aiLine(ctx) else null) ?: Catchphrases.pick(ctx, recent)
            recent.addLast(text)
            while (recent.size > 15) recent.removeFirst()
            events.emit("greet", buildJsonObject { put("person", name ?: ""); put("text", text) })
            runtime.showBubble(text, speak = config.current.people.greetSpeak)
        }
    }

    private suspend fun aiLine(c: GreetContext): String? = runCatching {
        val cfg = config.current
        val who = c.name ?: "uma pessoa que você ainda não conhece (não use nome)"
        val prompt = buildString {
            append("Você é ${cfg.personality.name}, um robozinho de rosto animado, ${cfg.personality.traits}. ")
            append("Escreva UMA frase curta (no máximo 12 palavras) em ${cfg.personality.language}, divertida e natural, puxando assunto com $who que acabou de passar na sua frente. ")
            append("Contexto: ${c.now.format(DateTimeFormatter.ofPattern("EEEE HH:mm"))}, clima ${c.ambient.condition}")
            c.ambient.temperature?.let { append(", ${it.toInt()}°C") }
            c.minutesAway?.let { append(", não via essa pessoa há $it minutos") }
            if (c.isBirthday) append(", é aniversário dela")
            if (c.remindersToday > 0) append(", ela tem ${c.remindersToday} lembretes hoje")
            append(". Responda só a frase, sem aspas.")
        }
        withTimeoutOrNull(8_000) {
            llm.complete(cfg.llm, listOf(ChatMessage("user", prompt)), emptyList()).content
                ?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it.length < 140 }
        }
    }.onFailure { Log.w("Greeter", "ai line failed: ${it.message}") }.getOrNull()

    companion object {
        private const val IDENTITY_LOCK_MS = 12_000L
        private const val LOW_LIGHT_BRIGHTNESS = 28f
    }
}
