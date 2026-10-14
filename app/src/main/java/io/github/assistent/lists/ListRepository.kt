package io.github.assistent.lists

import io.github.assistent.data.ListDao
import io.github.assistent.data.ListEntity
import io.github.assistent.data.ListItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ListRepository(private val dao: ListDao) {
    suspend fun allLists() = withContext(Dispatchers.IO) { dao.lists() }

    suspend fun getOrCreateList(name: String, personId: Long? = null): ListEntity = withContext(Dispatchers.IO) {
        dao.listByName(name) ?: ListEntity(name = name.trim().ifBlank { "compras" }, personId = personId).let {
            it.copy(id = dao.insertList(it))
        }
    }

    suspend fun add(listName: String, text: String, qty: String = "", addedBy: String = "", personId: Long? = null): ListItemEntity {
        val list = getOrCreateList(listName, personId)
        return withContext(Dispatchers.IO) {
            val item = ListItemEntity(listId = list.id, text = text.trim(), qty = qty, addedBy = addedBy, personId = personId)
            item.copy(id = dao.insertItem(item))
        }
    }

    suspend fun items(listName: String): List<ListItemEntity> {
        val list = getOrCreateList(listName)
        return withContext(Dispatchers.IO) { dao.items(list.id) }
    }

    suspend fun check(id: Long, done: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val item = dao.item(id) ?: return@withContext false
        dao.updateItem(item.copy(done = done))
        true
    }

    suspend fun remove(id: Long): Boolean = withContext(Dispatchers.IO) { dao.deleteItem(id) > 0 }
}
