package io.github.assistent.timers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.assistent.MainActivity
import io.github.assistent.data.TimerDao
import io.github.assistent.data.TimerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimerManager(
    private val context: Context,
    private val dao: TimerDao,
    private val scope: CoroutineScope,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val _active = MutableStateFlow<List<TimerEntity>>(emptyList())
    val active: StateFlow<List<TimerEntity>> = _active
    var onDone: suspend (TimerEntity) -> Unit = {}

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
    }

    suspend fun create(label: String, durationMs: Long, personId: Long? = null, room: String = ""): TimerEntity = withContext(Dispatchers.IO) {
        val clean = label.trim().ifBlank { "timer" }
        val entity = TimerEntity(label = clean, endsAt = System.currentTimeMillis() + durationMs.coerceAtLeast(1_000), durationMs = durationMs, personId = personId, room = room)
        val saved = entity.copy(id = dao.insert(entity))
        schedule(saved)
        refresh()
        saved
    }

    suspend fun list(all: Boolean = false): List<TimerEntity> = withContext(Dispatchers.IO) {
        if (all) dao.all() else dao.active()
    }

    suspend fun cancel(id: Long): Boolean = withContext(Dispatchers.IO) {
        val t = dao.get(id) ?: return@withContext false
        alarms.cancel(pending(id))
        dao.update(t.copy(state = "cancelled"))
        refresh()
        true
    }

    suspend fun onFired(id: Long) {
        val t = withContext(Dispatchers.IO) { dao.get(id) } ?: return
        if (t.state != "active") return
        withContext(Dispatchers.IO) { dao.update(t.copy(state = "done")) }
        refresh()
        onDone(t)
    }

    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        dao.active().forEach(::schedule)
        refresh()
    }

    private fun schedule(t: TimerEntity) {
        val at = maxOf(t.endsAt, System.currentTimeMillis() + 500)
        val show = PendingIntent.getActivity(
            context, t.id.toInt(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val canExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(t.id))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(t.id))
    }

    private fun pending(id: Long) = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, TimerReceiver::class.java).putExtra(TimerReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private suspend fun refresh() {
        _active.value = withContext(Dispatchers.IO) { dao.active() }
    }
}
