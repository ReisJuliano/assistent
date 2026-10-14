package io.github.assistent.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.assistent.AssistentApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        val graph = (context.applicationContext as AssistentApp).graph
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val r = graph.db.reminders().get(id) ?: return@launch
                graph.reminders.notify(r)
                graph.reminders.onFired(r)
                graph.runtime.onReminder(r)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as AssistentApp).graph
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                graph.reminders.rescheduleAll()
                graph.timers.rescheduleAll()
                // Just updated itself: reopen the face (allowed from background with "display over other apps").
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && android.provider.Settings.canDrawOverlays(context)) {
                    context.startActivity(
                        Intent(context, io.github.assistent.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
