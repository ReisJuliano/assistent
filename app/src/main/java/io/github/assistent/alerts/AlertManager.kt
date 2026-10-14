package io.github.assistent.alerts

import io.github.assistent.core.AssistantRuntime
import io.github.assistent.core.Emotion
import io.github.assistent.data.AlertDao
import io.github.assistent.data.AlertEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlertManager(private val dao: AlertDao) {
    var runtime: AssistantRuntime? = null

    suspend fun create(source: String, kind: String, text: String, imageUrl: String = "", room: String = ""): AlertEntity {
        val alert = AlertEntity(source = source, kind = kind, text = text, imageUrl = imageUrl, room = room)
        val saved = alert.copy(id = withContext(Dispatchers.IO) { dao.insert(alert) })
        runtime?.let { rt ->
            if (room.isNotBlank() && rt.mesh?.sayInRoom(room, text) == true) return saved
            rt.say(text, Emotion.SURPRISED)
        }
        return saved
    }

    suspend fun recent(limit: Int = 50) = withContext(Dispatchers.IO) { dao.recent(limit) }
}
