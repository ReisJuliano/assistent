package io.github.assistent.timers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.assistent.AssistentApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        val graph = (context.applicationContext as AssistentApp).graph
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                graph.timers.onFired(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "timer_id"
    }
}
