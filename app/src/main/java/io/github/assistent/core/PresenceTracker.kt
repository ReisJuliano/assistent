package io.github.assistent.core

import io.github.assistent.data.PersonDao
import io.github.assistent.data.PersonEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the best local guess of who the robot is addressing right now.
 *
 * Face recognition is the first signal today; mesh and future voice ID can feed the same tracker so the
 * brain and tools do not need to know where the identity came from.
 */
class PresenceTracker(
    private val people: PersonDao,
    private val scope: CoroutineScope,
) {
    private val seenAt = mutableMapOf<Long, Long>()
    private val _currentPerson = MutableStateFlow<PersonEntity?>(null)
    val currentPerson: StateFlow<PersonEntity?> = _currentPerson

    init {
        scope.launch {
            while (true) {
                delay(STALE_AFTER_MS / 3)
                val person = _currentPerson.value ?: continue
                if (System.currentTimeMillis() - lastSeen(person.id) > STALE_AFTER_MS) _currentPerson.value = null
            }
        }
    }

    fun onSeen(person: PersonEntity, at: Long = System.currentTimeMillis()) {
        seenAt[person.id] = at
        _currentPerson.value = person
    }

    /** A camera frame with no trusted match must revoke visual identity immediately. */
    fun clear() {
        _currentPerson.value = null
    }

    fun onSeenByName(name: String, at: Long = System.currentTimeMillis()) {
        if (name.isBlank()) return
        scope.launch(Dispatchers.IO) {
            people.byName(name)?.let { onSeen(it, at) }
        }
    }

    fun lastSeen(personId: Long): Long = seenAt[personId] ?: 0L

    companion object {
        private const val STALE_AFTER_MS = 8_000L
    }
}
