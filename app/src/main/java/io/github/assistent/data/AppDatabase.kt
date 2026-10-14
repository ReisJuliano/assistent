package io.github.assistent.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Long-term facts the assistant keeps about the user and the house. */
@Serializable
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val tags: String = "",
    val source: String = "assistant",
    val personId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val message: String,
    val triggerAt: Long,
    /** "reminder" (notification + voice) or "alarm" (rings until dismissed). */
    val kind: String = "reminder",
    /** "none", "daily", "weekdays" or "weekly". */
    val repeat: String = "none",
    val active: Boolean = true,
    val type: String = "generic",
    val personId: Long? = null,
    val requireConfirm: Boolean = false,
    val nagEveryMinutes: Int = 0,
    val escalateToPersonId: Long? = null,
    val escalateAfterMinutes: Int = 0,
    val animation: String = "",
    /** Exact words to say when it fires (`{n}` = person's name, `{msg}` = message); empty = generated. */
    val phrase: String = "",
    /** Style hint for the generated line, e.g. "motivacional", "como um pirata". */
    val style: String = "",
    val lastAckAt: Long? = null,
    val firstFiredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "timers")
data class TimerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val endsAt: Long,
    val durationMs: Long,
    val personId: Long? = null,
    val room: String = "",
    /** active, done or cancelled. */
    val state: String = "active",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startAt: Long,
    val endAt: Long? = null,
    val allDay: Boolean = false,
    val location: String = "",
    val notes: String = "",
    val personId: Long? = null,
    val remindMinutesBefore: Int = 0,
    /** none, daily, weekly, monthly or yearly. */
    val recurrence: String = "none",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toPersonId: Long? = null,
    val toRoom: String = "",
    val fromPersonId: Long? = null,
    val fromName: String = "",
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val deliverAfter: Long? = null,
    val deliveredAt: Long? = null,
    val channel: String = "local",
)

@Serializable
@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val personId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "list_items")
data class ListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val text: String,
    val done: Boolean = false,
    val qty: String = "",
    val addedBy: String = "",
    val personId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "sleep")
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO local date yyyy-MM-dd. */
    val date: String,
    val personId: Long? = null,
    val bedtime: Long? = null,
    val wakeTime: Long? = null,
    val interruptions: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "quiz_scores")
data class QuizScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long? = null,
    val topic: String = "",
    val correct: Int = 0,
    val total: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "companion_tokens")
data class CompanionTokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tokenHash: String,
    val personId: Long? = null,
    val scopes: String = "",
    val revokedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "speaker_profiles")
data class SpeakerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    /** JSON array of voice embeddings or enrollment metadata. */
    val embeddings: String = "[]",
    val model: String = "sherpa-onnx",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val kind: String,
    val text: String,
    val imageUrl: String = "",
    val room: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Someone the robot knows by face. Embeddings: several samples, each a JSON float array. */
@Serializable
@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val embeddings: String = "[]",
    /** "MM-dd", optional, for birthday greetings. */
    val birthday: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0,
)

@Dao
interface PersonDao {
    @Insert suspend fun insert(p: PersonEntity): Long
    @Update suspend fun update(p: PersonEntity)
    @Query("SELECT * FROM people ORDER BY name") suspend fun all(): List<PersonEntity>
    @Query("SELECT * FROM people WHERE id = :id") suspend fun get(id: Long): PersonEntity?
    @Query("SELECT * FROM people WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun byName(name: String): PersonEntity?
    @Query("DELETE FROM people WHERE id = :id") suspend fun delete(id: Long): Int
}

@Dao
interface MemoryDao {
    @Insert suspend fun insert(m: MemoryEntity): Long
    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE personId = :personId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForPerson(personId: Long, limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE personId IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForHouse(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories ORDER BY createdAt DESC") fun observeAll(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :term || '%' OR tags LIKE '%' || :term || '%' ORDER BY createdAt DESC LIMIT 50")
    suspend fun searchTerm(term: String): List<MemoryEntity>
    @Query(
        "SELECT * FROM memories WHERE (personId = :personId OR personId IS NULL) " +
            "AND (content LIKE '%' || :term || '%' OR tags LIKE '%' || :term || '%') ORDER BY createdAt DESC LIMIT 50",
    )
    suspend fun searchTermForPerson(term: String, personId: Long): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE id = :id") suspend fun get(id: Long): MemoryEntity?
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("DELETE FROM memories") suspend fun clear()
}

@Dao
interface ReminderDao {
    @Insert suspend fun insert(r: ReminderEntity): Long
    @Update suspend fun update(r: ReminderEntity)
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun get(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminders WHERE active = 1 ORDER BY triggerAt") suspend fun active(): List<ReminderEntity>
    @Query("SELECT * FROM reminders ORDER BY triggerAt DESC LIMIT 200") suspend fun all(): List<ReminderEntity>
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun delete(id: Long): Int
}

@Dao
interface TimerDao {
    @Insert suspend fun insert(t: TimerEntity): Long
    @Update suspend fun update(t: TimerEntity)
    @Query("SELECT * FROM timers WHERE id = :id") suspend fun get(id: Long): TimerEntity?
    @Query("SELECT * FROM timers WHERE state = 'active' ORDER BY endsAt") suspend fun active(): List<TimerEntity>
    @Query("SELECT * FROM timers ORDER BY createdAt DESC LIMIT 200") suspend fun all(): List<TimerEntity>
    @Query("DELETE FROM timers WHERE id = :id") suspend fun delete(id: Long): Int
}

@Dao
interface EventDao {
    @Insert suspend fun insert(e: EventEntity): Long
    @Update suspend fun update(e: EventEntity)
    @Query("SELECT * FROM events WHERE id = :id") suspend fun get(id: Long): EventEntity?
    @Query("SELECT * FROM events WHERE startAt >= :from AND startAt <= :to ORDER BY startAt") suspend fun between(from: Long, to: Long): List<EventEntity>
    @Query("SELECT * FROM events WHERE (personId = :personId OR personId IS NULL) AND startAt >= :from AND startAt <= :to ORDER BY startAt")
    suspend fun betweenForPerson(from: Long, to: Long, personId: Long): List<EventEntity>
    @Query("SELECT * FROM events WHERE startAt >= :now ORDER BY startAt LIMIT :limit") suspend fun upcoming(now: Long, limit: Int): List<EventEntity>
    @Query("DELETE FROM events WHERE id = :id") suspend fun delete(id: Long): Int
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(m: MessageEntity): Long
    @Update suspend fun update(m: MessageEntity)
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun get(id: Long): MessageEntity?
    @Query("SELECT * FROM messages WHERE deliveredAt IS NULL ORDER BY createdAt DESC") suspend fun pending(): List<MessageEntity>
    @Query(
        "SELECT * FROM messages WHERE deliveredAt IS NULL AND (toPersonId = :personId OR toPersonId IS NULL) " +
            "AND (deliverAfter IS NULL OR deliverAfter <= :now) ORDER BY createdAt",
    )
    suspend fun pendingForPerson(personId: Long, now: Long): List<MessageEntity>
    @Query("DELETE FROM messages WHERE id = :id") suspend fun delete(id: Long): Int
}

@Dao
interface ListDao {
    @Insert suspend fun insertList(l: ListEntity): Long
    @Insert suspend fun insertItem(i: ListItemEntity): Long
    @Update suspend fun updateItem(i: ListItemEntity)
    @Query("SELECT * FROM lists ORDER BY name") suspend fun lists(): List<ListEntity>
    @Query("SELECT * FROM lists WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun listByName(name: String): ListEntity?
    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY done, createdAt") suspend fun items(listId: Long): List<ListItemEntity>
    @Query("SELECT * FROM list_items WHERE id = :id") suspend fun item(id: Long): ListItemEntity?
    @Query("DELETE FROM list_items WHERE id = :id") suspend fun deleteItem(id: Long): Int
}

@Dao
interface SleepDao {
    @Insert suspend fun insert(s: SleepEntity): Long
    @Query("SELECT * FROM sleep WHERE personId IS :personId ORDER BY date DESC LIMIT :days") suspend fun recent(personId: Long?, days: Int): List<SleepEntity>
}

@Dao
interface QuizDao {
    @Insert suspend fun insert(s: QuizScoreEntity): Long
    @Query("SELECT * FROM quiz_scores WHERE (personId IS :personId OR (:personId IS NULL AND personId IS NULL)) AND topic = :topic LIMIT 1")
    suspend fun byTopic(personId: Long?, topic: String): QuizScoreEntity?
    @Update suspend fun update(s: QuizScoreEntity)
    @Query("SELECT * FROM quiz_scores ORDER BY updatedAt DESC LIMIT 100") suspend fun all(): List<QuizScoreEntity>
}

@Dao
interface CompanionTokenDao {
    @Insert suspend fun insert(t: CompanionTokenEntity): Long
    @Query("SELECT * FROM companion_tokens ORDER BY createdAt DESC") suspend fun all(): List<CompanionTokenEntity>
    @Query("SELECT * FROM companion_tokens WHERE id = :id") suspend fun get(id: Long): CompanionTokenEntity?
    @Query("SELECT * FROM companion_tokens WHERE tokenHash = :hash AND revokedAt IS NULL LIMIT 1") suspend fun activeByHash(hash: String): CompanionTokenEntity?
    @Update suspend fun update(t: CompanionTokenEntity)
}

@Dao
interface SpeakerProfileDao {
    @Insert suspend fun insert(p: SpeakerProfileEntity): Long
    @Update suspend fun update(p: SpeakerProfileEntity)
    @Query("SELECT * FROM speaker_profiles WHERE personId = :personId LIMIT 1") suspend fun byPerson(personId: Long): SpeakerProfileEntity?
    @Query("SELECT * FROM speaker_profiles") suspend fun all(): List<SpeakerProfileEntity>
}

@Dao
interface AlertDao {
    @Insert suspend fun insert(a: AlertEntity): Long
    @Query("SELECT * FROM alerts ORDER BY createdAt DESC LIMIT :limit") suspend fun recent(limit: Int): List<AlertEntity>
}

@Database(
    entities = [
        MemoryEntity::class, ReminderEntity::class, PersonEntity::class, TimerEntity::class, EventEntity::class, MessageEntity::class,
        ListEntity::class, ListItemEntity::class, SleepEntity::class, QuizScoreEntity::class, CompanionTokenEntity::class,
        SpeakerProfileEntity::class, AlertEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memories(): MemoryDao
    abstract fun reminders(): ReminderDao
    abstract fun people(): PersonDao
    abstract fun timers(): TimerDao
    abstract fun events(): EventDao
    abstract fun messages(): MessageDao
    abstract fun lists(): ListDao
    abstract fun sleep(): SleepDao
    abstract fun quizzes(): QuizDao
    abstract fun companionTokens(): CompanionTokenDao
    abstract fun speakerProfiles(): SpeakerProfileDao
    abstract fun alerts(): AlertDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`embeddings` TEXT NOT NULL, `birthday` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL)",
                )
            }
        }
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `personId` INTEGER")
            }
        }
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `timers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `label` TEXT NOT NULL, " +
                        "`endsAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `personId` INTEGER, `room` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                        "`startAt` INTEGER NOT NULL, `endAt` INTEGER, `allDay` INTEGER NOT NULL, `location` TEXT NOT NULL, " +
                        "`notes` TEXT NOT NULL, `personId` INTEGER, `remindMinutesBefore` INTEGER NOT NULL, `recurrence` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `toPersonId` INTEGER, " +
                        "`toRoom` TEXT NOT NULL, `fromPersonId` INTEGER, `fromName` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `deliverAfter` INTEGER, `deliveredAt` INTEGER, `channel` TEXT NOT NULL)",
                )
            }
        }
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`personId` INTEGER, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `list_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `listId` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, `done` INTEGER NOT NULL, `qty` TEXT NOT NULL, `addedBy` TEXT NOT NULL, `personId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'generic'")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `personId` INTEGER")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `requireConfirm` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `nagEveryMinutes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `escalateToPersonId` INTEGER")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `escalateAfterMinutes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `animation` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `lastAckAt` INTEGER")
            }
        }
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sleep` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `personId` INTEGER, `bedtime` INTEGER, `wakeTime` INTEGER, `interruptions` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `quiz_scores` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER, `topic` TEXT NOT NULL, `correct` INTEGER NOT NULL, `total` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `companion_tokens` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `tokenHash` TEXT NOT NULL, `personId` INTEGER, `scopes` TEXT NOT NULL, `revokedAt` INTEGER, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `speaker_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` INTEGER NOT NULL, `embeddings` TEXT NOT NULL, `model` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `alerts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `imageUrl` TEXT NOT NULL, `room` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `firstFiredAt` INTEGER")
            }
        }

        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `phrase` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `style` TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "assistent.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                ).build()
    }
}

/** Keyword search with simple scoring; good enough for a personal memory of hundreds of items. */
class MemoryRepository(private val dao: MemoryDao, private val people: PersonDao) {
    /** Called for every new memory meant to be shared (other devices of the mesh). */
    var onAdded: (MemoryEntity) -> Unit = {}

    suspend fun add(content: String, tags: String = "", source: String = "assistant", personId: Long? = null, share: Boolean = true): Long {
        val m = MemoryEntity(content = content.trim(), tags = tags.trim(), source = source, personId = personId)
        val id = dao.insert(m)
        if (share) onAdded(m.copy(id = id))
        return id
    }

    suspend fun recent(limit: Int) = dao.recent(limit)
    suspend fun recentForPrompt(personId: Long?, limit: Int): List<MemoryEntity> {
        if (personId == null) return recent(limit)
        val personal = dao.recentForPerson(personId, limit)
        if (personal.size >= limit) return personal
        return (personal + dao.recentForHouse(limit - personal.size)).distinctBy { it.id }
    }
    suspend fun delete(id: Long) = dao.delete(id) > 0
    suspend fun clear() = dao.clear()
    suspend fun personIdByName(name: String): Long? = people.byName(name.trim())?.id

    suspend fun search(query: String, limit: Int = 10, personId: Long? = null): List<MemoryEntity> {
        val raw = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.distinct()
        val terms = raw.map(::normalize).distinct()
        if (terms.isEmpty()) return if (personId == null) dao.recent(limit) else recentForPrompt(personId, limit)
        val hits = (raw + terms).distinct().flatMap {
            if (personId == null) dao.searchTerm(it) else dao.searchTermForPerson(it, personId)
        }.distinctBy { it.id }
        return hits
            .map { m -> m to terms.count { normalize(m.content + " " + m.tags).contains(it) } }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit)
            .map { it.first }
    }

    private fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
