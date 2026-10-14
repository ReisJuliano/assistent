package io.github.assistent.quiz

import io.github.assistent.data.QuizDao
import io.github.assistent.data.QuizScoreEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QuizRepository(private val dao: QuizDao) {
    suspend fun record(personId: Long?, topic: String, correct: Boolean): QuizScoreEntity = withContext(Dispatchers.IO) {
        val current = dao.byTopic(personId, topic) ?: QuizScoreEntity(personId = personId, topic = topic)
        val next = current.copy(
            correct = current.correct + if (correct) 1 else 0,
            total = current.total + 1,
            updatedAt = System.currentTimeMillis(),
        )
        if (current.id == 0L) next.copy(id = dao.insert(next)) else next.also { dao.update(it) }
    }

    suspend fun all() = withContext(Dispatchers.IO) { dao.all() }
}
