package io.github.assistent.core

import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.data.PersonDao
import io.github.assistent.data.PersonEntity
import io.github.assistent.voice.VoiceIdentityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Who is around the house", from weak signals: a voice talking in the room (speaker ID on the last few seconds of
 * speech the microphone heard) and a body wearing someone's outfit of the day. These mark presence (for "where is
 * Ana?", the mesh and events) but never replace the trusted face identity used in conversation.
 */
class RoomPresence(
    private val config: ConfigStore,
    private val voice: VoiceIdentityManager,
    private val people: PersonDao,
    private val presence: PresenceTracker,
    private val events: EventDispatcher,
    private val scope: CoroutineScope,
    /** Last seconds of microphone audio (16 kHz). */
    private val recentAudio: (Float) -> FloatArray?,
    private val musicPlaying: () -> Boolean,
) {
    /** Also tell the other devices (set by the app graph). */
    var report: (String) -> Unit = {}
    @Volatile private var lastVoiceTry = 0L
    private val lastNoticed = HashMap<Long, Long>()

    /** YAMNet heard speech in the room. */
    fun onSpeechHeard() {
        val now = System.currentTimeMillis()
        if (!config.current.people.voiceInRoom || musicPlaying() || now - lastVoiceTry < 15_000) return
        lastVoiceTry = now
        scope.launch(Dispatchers.Default) {
            try {
                val audio = recentAudio(3f) ?: return@launch
                val m = voice.identify(audio)
                m.person?.let { noticed(it, "voice", m.score) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "voice in room: ${e.message}")
            }
        }
    }

    /** A body on camera wears this person's outfit of the day. */
    fun onOutfit(personId: Long, score: Float) {
        scope.launch(Dispatchers.IO) { people.get(personId)?.let { noticed(it, "outfit", score) } }
    }

    private fun noticed(person: PersonEntity, source: String, score: Float) {
        val now = System.currentTimeMillis()
        synchronized(lastNoticed) {
            if (now - (lastNoticed[person.id] ?: 0L) < 30_000) return
            lastNoticed[person.id] = now
        }
        Log.d(TAG, "noticed ${person.name} by $source (${"%.2f".format(score)})")
        presence.onNoticed(person, now)
        report(person.name)
        events.emit("person_noticed", buildJsonObject { put("name", person.name); put("source", source); put("score", score) })
    }

    private companion object { const val TAG = "RoomPresence" }
}
