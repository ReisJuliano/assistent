package io.github.assistent.messages

import io.github.assistent.data.MessageDao
import io.github.assistent.data.MessageEntity
import io.github.assistent.data.PersonDao
import io.github.assistent.data.PersonEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessageManager(
    private val dao: MessageDao,
    private val people: PersonDao,
    private val scope: CoroutineScope,
) {
    var onDeliver: suspend (PersonEntity, MessageEntity) -> Unit = { _, _ -> }

    suspend fun findPerson(name: String): PersonEntity? = withContext(Dispatchers.IO) { people.byName(name) }

    suspend fun leave(toPersonName: String?, text: String, fromName: String = "", deliverAfter: Long? = null): MessageEntity = withContext(Dispatchers.IO) {
        val to = toPersonName?.takeIf { it.isNotBlank() }?.let { people.byName(it) }
        val entity = MessageEntity(toPersonId = to?.id, fromName = fromName, text = text.trim(), deliverAfter = deliverAfter)
        entity.copy(id = dao.insert(entity))
    }

    suspend fun pending(): List<MessageEntity> = withContext(Dispatchers.IO) { dao.pending() }
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) { dao.delete(id) > 0 }

    suspend fun deliverFor(person: PersonEntity) {
        val due = withContext(Dispatchers.IO) { dao.pendingForPerson(person.id, System.currentTimeMillis()) }
        for (m in due) {
            onDeliver(person, m)
            withContext(Dispatchers.IO) { dao.update(m.copy(deliveredAt = System.currentTimeMillis())) }
        }
    }
}
