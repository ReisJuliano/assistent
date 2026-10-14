package io.github.assistent.companion

import io.github.assistent.config.ConfigStore
import io.github.assistent.data.CompanionTokenDao
import io.github.assistent.data.CompanionTokenEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class CompanionTokenManager(private val dao: CompanionTokenDao) {
    suspend fun create(name: String, personId: Long?, scopes: String = "chat,lists,events,messages,reminders,timers"): Pair<CompanionTokenEntity, String> {
        val token = ConfigStore.newToken()
        val entity = CompanionTokenEntity(name = name.trim().ifBlank { "Celular" }, tokenHash = sha256(token), personId = personId, scopes = scopes)
        val saved = entity.copy(id = withContext(Dispatchers.IO) { dao.insert(entity) })
        return saved to token
    }

    suspend fun list(): List<CompanionTokenEntity> = withContext(Dispatchers.IO) { dao.all() }

    suspend fun revoke(id: Long): Boolean = withContext(Dispatchers.IO) {
        val t = dao.get(id) ?: return@withContext false
        dao.update(t.copy(revokedAt = System.currentTimeMillis()))
        true
    }

    suspend fun accepts(token: String): Boolean =
        token.isNotBlank() && withContext(Dispatchers.IO) { dao.activeByHash(sha256(token)) != null }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
