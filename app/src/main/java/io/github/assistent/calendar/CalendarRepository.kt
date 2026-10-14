package io.github.assistent.calendar

import io.github.assistent.data.EventDao
import io.github.assistent.data.EventEntity

class CalendarRepository(private val dao: EventDao) {
    suspend fun add(event: EventEntity): EventEntity = event.copy(id = dao.insert(event)).also { saved ->
        if (saved.id != event.id) Unit
    }

    suspend fun update(event: EventEntity): Boolean {
        if (dao.get(event.id) == null) return false
        dao.update(event)
        return true
    }

    suspend fun get(id: Long) = dao.get(id)
    suspend fun delete(id: Long) = dao.delete(id) > 0
    suspend fun between(from: Long, to: Long, personId: Long? = null): List<EventEntity> =
        if (personId == null) dao.between(from, to) else dao.betweenForPerson(from, to, personId)

    suspend fun upcoming(limit: Int = 5) = dao.upcoming(System.currentTimeMillis(), limit)
}
