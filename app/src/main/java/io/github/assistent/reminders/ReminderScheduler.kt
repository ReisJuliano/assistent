package io.github.assistent.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.assistent.MainActivity
import io.github.assistent.R
import io.github.assistent.data.ReminderDao
import io.github.assistent.data.ReminderEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

class ReminderScheduler(private val context: Context, private val dao: ReminderDao) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    var onEscalate: suspend (ReminderEntity) -> Unit = {}

    init {
        createChannels(context)
    }

    suspend fun create(
        message: String,
        triggerAt: Long,
        kind: String = "reminder",
        repeat: String = "none",
        type: String = "generic",
        personId: Long? = null,
        requireConfirm: Boolean = false,
        nagEveryMinutes: Int = 0,
        escalateToPersonId: Long? = null,
        escalateAfterMinutes: Int = 0,
        animation: String = "",
    ): ReminderEntity {
        val entity = ReminderEntity(
            message = message,
            triggerAt = triggerAt,
            kind = kind,
            repeat = repeat,
            type = type,
            personId = personId,
            requireConfirm = requireConfirm,
            nagEveryMinutes = nagEveryMinutes,
            escalateToPersonId = escalateToPersonId,
            escalateAfterMinutes = escalateAfterMinutes,
            animation = animation,
        )
        val saved = entity.copy(id = dao.insert(entity))
        schedule(saved)
        return saved
    }

    suspend fun acknowledge(id: Long): Boolean {
        val r = dao.get(id) ?: return false
        val now = System.currentTimeMillis()
        val next = if (r.repeat == "none") null else nextOccurrence(r.copy(triggerAt = r.firstFiredAt ?: r.triggerAt))
        val updated = if (next == null) {
            r.copy(lastAckAt = now, active = false, firstFiredAt = null)
        } else {
            r.copy(lastAckAt = now, active = true, firstFiredAt = null, triggerAt = next)
        }
        dao.update(updated)
        if (next != null) schedule(updated)
        dismiss(id)
        return true
    }

    suspend fun cancel(id: Long): Boolean {
        alarms.cancel(pending(id))
        return dao.delete(id) > 0
    }

    suspend fun list(onlyActive: Boolean = true) = if (onlyActive) dao.active() else dao.all()

    suspend fun rescheduleAll() = dao.active().forEach(::schedule)

    /** Called when an alarm fires: computes next occurrence or deactivates. */
    suspend fun onFired(r: ReminderEntity) {
        if (r.requireConfirm && r.lastAckAt == null) {
            val now = System.currentTimeMillis()
            val first = r.firstFiredAt ?: r.triggerAt
            if (r.escalateAfterMinutes > 0 && r.escalateToPersonId != null && now - first >= r.escalateAfterMinutes * 60_000L) {
                onEscalate(r)
            }
            val nextNag = now + (if (r.nagEveryMinutes > 0) r.nagEveryMinutes else 5) * 60_000L
            val updated = r.copy(firstFiredAt = first, triggerAt = nextNag, active = true)
            dao.update(updated)
            schedule(updated)
            return
        }
        val next = nextOccurrence(r)
        if (next == null) dao.update(r.copy(active = false))
        else r.copy(triggerAt = next).also { dao.update(it); schedule(it) }
    }

    fun schedule(r: ReminderEntity) {
        if (!r.active) return
        val at = maxOf(r.triggerAt, System.currentTimeMillis() + 1000)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) {
            val show = PendingIntent.getActivity(
                context, r.id.toInt(), Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(r.id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(r.id))
        }
    }

    private fun pending(id: Long) = PendingIntent.getBroadcast(
        context, id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun nextOccurrence(r: ReminderEntity): Long? {
        val zone = ZoneId.systemDefault()
        var t = Instant.ofEpochMilli(r.triggerAt).atZone(zone)
        val now = System.currentTimeMillis()
        val step: () -> Unit = when (r.repeat) {
            "daily" -> { { t = t.plusDays(1) } }
            "weekly" -> { { t = t.plusWeeks(1) } }
            "weekdays" -> { {
                do { t = t.plusDays(1) } while (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY)
            } }
            else -> return null
        }
        do { step() } while (t.toInstant().toEpochMilli() <= now)
        return t.toInstant().toEpochMilli()
    }

    fun notify(r: ReminderEntity) {
        val alarm = r.kind == "alarm"
        val open = PendingIntent.getActivity(
            context, r.id.toInt(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, if (alarm) CH_ALARM else CH_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (alarm) "Alarme" else "Lembrete")
            .setContentText(r.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.message))
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .apply { if (alarm) setFullScreenIntent(open, true) }
            .build()
        if (alarm) n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_BASE + r.id.toInt(), n)
    }

    fun dismiss(id: Long) = context.getSystemService(NotificationManager::class.java).cancel(NOTIF_BASE + id.toInt())

    companion object {
        const val CH_REMINDER = "reminders"
        const val CH_ALARM = "alarms"
        const val CH_SERVICE = "assistant"
        private const val NOTIF_BASE = 10_000

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "Lembretes", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(
                NotificationChannel(CH_ALARM, "Alarmes", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                    )
                    enableVibration(true)
                },
            )
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Assistente ativo", NotificationManager.IMPORTANCE_MIN))
        }
    }
}
