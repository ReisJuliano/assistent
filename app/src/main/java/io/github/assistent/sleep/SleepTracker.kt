package io.github.assistent.sleep

import io.github.assistent.core.PresenceTracker
import io.github.assistent.data.SleepDao
import io.github.assistent.data.SleepEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class SleepTracker(private val dao: SleepDao, private val presence: PresenceTracker) {
    private var sleptAt: Long? = null

    suspend fun onSleep() {
        sleptAt = System.currentTimeMillis()
    }

    suspend fun onWake() {
        val bed = sleptAt ?: return
        val wake = System.currentTimeMillis()
        sleptAt = null
        val date = Instant.ofEpochMilli(wake).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        withContext(Dispatchers.IO) {
            dao.insert(SleepEntity(date = date, personId = presence.currentPerson.value?.id, bedtime = bed, wakeTime = wake))
        }
    }

    suspend fun report(days: Int = 7): String {
        val rows = withContext(Dispatchers.IO) { dao.recent(presence.currentPerson.value?.id, days.coerceIn(1, 30)) }
        if (rows.isEmpty()) return "Ainda nao tenho dados suficientes. E uma estimativa, nao uma medida medica."
        val lines = rows.joinToString("\n") { s ->
            val hours = if (s.bedtime != null && s.wakeTime != null) {
                ChronoUnit.MINUTES.between(Instant.ofEpochMilli(s.bedtime), Instant.ofEpochMilli(s.wakeTime)) / 60.0
            } else null
            "${s.date}: " + (hours?.let { "%.1f h estimadas".format(it) } ?: "incompleto")
        }
        return "Estimativa de sono (nao e medida medica):\n$lines"
    }
}
