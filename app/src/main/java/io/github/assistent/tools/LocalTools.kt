package io.github.assistent.tools

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import io.github.assistent.core.PresenceTracker
import io.github.assistent.data.MemoryRepository
import io.github.assistent.reminders.ReminderScheduler
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- memory

class RememberTool(private val memory: MemoryRepository, private val presence: PresenceTracker) : Tool {
    override val name = "remember"
    override val description = "Save a fact for the long term (user preferences, names, birthdays, where things are, anything the user asks to remember)."
    override val parameters = params {
        string("content", "The fact, written so it makes sense alone later, e.g. 'A senha do wifi da casa é ...'")
        string("tags", "Comma separated keywords", required = false)
        string("person", "Known person this memory belongs to. Omit for the current recognized person, or use 'house' for everyone.", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val content = args.str("content") ?: return "missing content"
        val person = args.str("person")
        val personId = resolvePerson(person) ?: if (!person.isNullOrBlank() && !isHouse(person)) return "unknown person '$person'" else null
        val id = memory.add(content, args.str("tags").orEmpty(), personId = personId)
        return "saved memory #$id"
    }

    private suspend fun resolvePerson(name: String?): Long? {
        if (name.isNullOrBlank()) return presence.currentPerson.value?.id
        if (isHouse(name)) return null
        return memory.personIdByName(name)
    }

    private fun isHouse(name: String) = name.equals("house", ignoreCase = true) || name.equals("casa", ignoreCase = true)
}

class RecallTool(private val memory: MemoryRepository, private val presence: PresenceTracker) : Tool {
    override val name = "recall"
    override val description = "Search saved memories by keywords."
    override val parameters = params {
        string("query", "Keywords")
        string("person", "Known person to search for. Omit for the current recognized person plus house memories, or use 'house' for all memories.", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val person = args.str("person")
        val personId = resolvePerson(person) ?: if (!person.isNullOrBlank() && !isHouse(person)) return "unknown person '$person'" else null
        val hits = memory.search(args.str("query").orEmpty(), personId = personId)
        return if (hits.isEmpty()) "nothing found" else hits.joinToString("\n") { "#${it.id}: ${it.content}" }
    }

    private suspend fun resolvePerson(name: String?): Long? {
        if (name.isNullOrBlank()) return presence.currentPerson.value?.id
        if (isHouse(name)) return null
        return memory.personIdByName(name)
    }

    private fun isHouse(name: String) = name.equals("house", ignoreCase = true) || name.equals("casa", ignoreCase = true)
}

class ForgetTool(private val memory: MemoryRepository) : Tool {
    override val name = "forget"
    override val description = "Delete a saved memory by id (use recall first to find the id)."
    override val parameters = params { integer("id", "Memory id") }

    override suspend fun execute(args: JsonObject): String =
        if (memory.delete(args.num("id")?.toLong() ?: -1)) "deleted" else "not found"
}

// ---------------------------------------------------------------- reminders & alarms

class CreateReminderTool(private val scheduler: ReminderScheduler, private val presence: PresenceTracker) : Tool {
    override val name = "create_reminder"
    override val description = "Create a reminder or alarm. Give either 'datetime' (local ISO, e.g. 2026-10-14T07:30) or 'in_minutes'."
    override val parameters = params {
        string("message", "What to say when it fires")
        string("datetime", "Local date-time ISO-8601", required = false)
        number("in_minutes", "Minutes from now", required = false)
        string("kind", "reminder = notification + voice; alarm = rings until dismissed", required = false, enum = listOf("reminder", "alarm"))
        string("repeat", "Repetition", required = false, enum = listOf("none", "daily", "weekdays", "weekly"))
        string("type", "Reminder type", required = false, enum = listOf("generic", "medicine", "water", "food", "exercise", "custom"))
        boolean("confirm", "Require spoken/tap/app confirmation", required = false)
        number("nag_every_minutes", "Repeat until confirmed every N minutes", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val message = args.str("message") ?: return "missing message"
        val at = args.num("in_minutes")?.let { System.currentTimeMillis() + (it * 60_000).toLong() }
            ?: args.str("datetime")?.let(::parseDateTime)
            ?: return "need datetime or in_minutes"
        if (at < System.currentTimeMillis() - 60_000) return "that time is in the past"
        val r = scheduler.create(
            message,
            at,
            args.str("kind") ?: "reminder",
            args.str("repeat") ?: "none",
            type = args.str("type") ?: "generic",
            personId = presence.currentPerson.value?.id,
            requireConfirm = args.bool("confirm") ?: false,
            nagEveryMinutes = args.num("nag_every_minutes")?.toInt() ?: 0,
            animation = args.str("type") ?: "",
        )
        return "created #${r.id} for ${fmt(r.triggerAt)} (${r.kind}, repeat ${r.repeat})"
    }

    private fun parseDateTime(s: String): Long? = runCatching {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.recoverCatching {
        LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

class ListRemindersTool(private val scheduler: ReminderScheduler) : Tool {
    override val name = "list_reminders"
    override val description = "List active reminders and alarms."
    override val parameters = params()

    override suspend fun execute(args: JsonObject): String {
        val list = scheduler.list()
        return if (list.isEmpty()) "none" else list.joinToString("\n") { "#${it.id} ${fmt(it.triggerAt)} [${it.kind}, ${it.repeat}] ${it.message}" }
    }
}

class CancelReminderTool(private val scheduler: ReminderScheduler) : Tool {
    override val name = "cancel_reminder"
    override val description = "Cancel a reminder/alarm by id."
    override val parameters = params { integer("id", "Reminder id") }

    override suspend fun execute(args: JsonObject): String =
        if (scheduler.cancel(args.num("id")?.toLong() ?: -1)) "cancelled" else "not found"
}

internal fun fmt(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("EEE dd/MM/yyyy HH:mm"))

// ---------------------------------------------------------------- device

class DeviceTool(private val context: Context) : Tool {
    override val name = "device"
    override val description = "Control this phone: flashlight on/off, media volume (0-100), open a URL, open an app by name, battery status."
    override val parameters = params {
        string("action", "What to do", enum = listOf("flashlight_on", "flashlight_off", "set_volume", "open_url", "open_app", "battery"))
        string("value", "Volume 0-100, URL, or app name, depending on action", required = false)
    }

    override suspend fun execute(args: JsonObject): String {
        val value = args.str("value").orEmpty()
        return when (args.str("action")) {
            "flashlight_on" -> torch(true)
            "flashlight_off" -> torch(false)
            "set_volume" -> {
                val am = context.getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val v = (value.toIntOrNull() ?: return "volume must be 0-100").coerceIn(0, 100)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * v / 100, 0)
                "volume set to $v%"
            }
            "open_url" -> start(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            "open_app" -> openApp(value)
            "battery" -> {
                val bm = context.getSystemService(BatteryManager::class.java)
                "battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%, charging=${bm.isCharging}"
            }
            else -> "unknown action"
        }
    }

    private fun torch(on: Boolean): String {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "no flashlight"
        cm.setTorchMode(id, on)
        return if (on) "flashlight on" else "flashlight off"
    }

    private fun openApp(name: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "app '$name' not found"
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "cannot launch"
        return start(intent).let { "opened ${match.loadLabel(pm)}" }
    }

    private fun start(intent: Intent): String = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok"
    }.getOrElse { "failed: ${it.message}" }
}
