package com.smriti.app.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One remembered moment: a privacy-redacted thumbnail (faces blurred) plus the meaning-vector EmbeddingGemma made from it.
 * The raw camera frame is never stored; the thumbnail is small and lives only in the app's private folder.
 */
@Entity(tableName = "visual")
class VisualEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val source: String,
    val place: String,
    val caption: String,
    /** file name inside the app's private "thumbs" folder */
    val thumb: String,
    /** 768 floats, L2-normalised, little-endian */
    val vec: ByteArray,
    /** "image" for a camera moment, "image+sound" when sound was embedded with it */
    val kind: String = "image",
    /** the camera session this moment belongs to, 0 for ambient moments taken outside a session */
    val sessionId: Long = 0,
    /** end of the window this moment covers (timestamp is its start); 0 when it is a single instant */
    val endAt: Long = 0,
    /** words heard during the window, when the microphone was open (text only; the sound is never kept) */
    val heard: String = ""
)

/** The columns that decide how much room a moment takes, without loading its vector (used by the storage policy). */
data class VisualMeta(val id: Long, val timestamp: Long, val sessionId: Long, val thumb: String, val vecBytes: Int, val capBytes: Int)

@Dao
interface VisualDao {
    @Query("SELECT id, timestamp, sessionId, thumb, LENGTH(vec) AS vecBytes, LENGTH(caption) AS capBytes FROM visual")
    suspend fun meta(): List<VisualMeta>

    @Query("SELECT thumb FROM visual WHERE id IN (:ids)")
    suspend fun thumbsOf(ids: List<Long>): List<String>

    @Query("SELECT MIN(timestamp) FROM visual")
    suspend fun firstTimestamp(): Long?

    @Insert
    suspend fun insert(v: VisualEntity): Long

    @Query("SELECT * FROM visual ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<VisualEntity>

    @Query("SELECT * FROM visual ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): kotlinx.coroutines.flow.Flow<List<VisualEntity>>

    @Query("SELECT thumb FROM visual WHERE id = :id")
    suspend fun thumbOf(id: Long): String?

    @Query("SELECT caption FROM visual WHERE id = :id")
    suspend fun captionOf(id: Long): String?

    @Query("UPDATE visual SET caption = :caption WHERE id = :id")
    suspend fun setCaption(id: Long, caption: String)

    @Query("DELETE FROM visual WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM visual ORDER BY timestamp ASC LIMIT :n")
    suspend fun oldest(n: Int): List<VisualEntity>

    @Query("DELETE FROM visual WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM visual WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<VisualEntity>

    @Query("SELECT * FROM visual WHERE sessionId = :id ORDER BY timestamp ASC")
    suspend fun forSession(id: Long): List<VisualEntity>

    @Query("SELECT * FROM visual WHERE sessionId = :id ORDER BY timestamp ASC")
    fun forSessionFlow(id: Long): kotlinx.coroutines.flow.Flow<List<VisualEntity>>

    @Query("SELECT thumb FROM visual WHERE sessionId = :id")
    suspend fun thumbsOfSession(id: Long): List<String>

    @Query("DELETE FROM visual WHERE sessionId = :id")
    suspend fun deleteSession(id: Long): Int

    @Query("SELECT DISTINCT sessionId FROM visual WHERE sessionId != 0")
    suspend fun sessionIdsInUse(): List<Long>

    @Query("SELECT COUNT(*) FROM visual WHERE sessionId = :id")
    suspend fun countOfSession(id: Long): Int

    @Query("SELECT COUNT(*) FROM visual")
    suspend fun count(): Int

    @Query("SELECT thumb FROM visual WHERE timestamp >= :since")
    suspend fun thumbsSince(since: Long): List<String>

    @Query("DELETE FROM visual WHERE timestamp >= :since")
    suspend fun deleteSince(since: Long): Int

    @Query("SELECT thumb FROM visual WHERE timestamp < :before")
    suspend fun thumbsBefore(before: Long): List<String>

    @Query("DELETE FROM visual WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("SELECT thumb FROM visual")
    suspend fun allThumbs(): List<String>

    @Query("DELETE FROM visual")
    suspend fun deleteAll(): Int
}
