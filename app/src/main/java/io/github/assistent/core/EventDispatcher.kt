package io.github.assistent.core

import android.util.Log
import io.github.assistent.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AppEvent(val event: String, val data: JsonElement = JsonNull, val ts: Long = System.currentTimeMillis())

/**
 * Fan-out of assistant events: in-process subscribers (SSE stream of the local API)
 * and user-configured outgoing webhooks.
 */
class EventDispatcher(
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = _events

    fun emit(event: String, data: JsonElement = JsonNull) {
        val e = AppEvent(event, data)
        _events.tryEmit(e)
        val cfg = config.current.events
        if (cfg.webhookUrls.isEmpty() || (cfg.events.isNotEmpty() && event !in cfg.events)) return
        val body = AppJson.encodeToString(AppEvent.serializer(), e).toRequestBody(JSON)
        scope.launch {
            for (url in cfg.webhookUrls) {
                runCatching {
                    http.newCall(Request.Builder().url(url).post(body).build()).execute().close()
                }.onFailure { Log.w(TAG, "event webhook $url failed: ${it.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "Events"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
