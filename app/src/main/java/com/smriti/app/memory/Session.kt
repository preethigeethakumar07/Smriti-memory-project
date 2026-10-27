package com.smriti.app.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One camera session: from the moment the phone (or glasses) camera opened in Live until it closed. Its moments carry this
 * session's id, so a question can be asked about this recording alone or about all of them (one shared index, filtered).
 * Only text and small blurred pictures are kept, never the video itself.
 */
@Entity(tableName = "camera_session")
class CameraSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    /** phone | glasses */
    val source: String,
    /** the place Smriti saw most, or "" */
    val place: String = "",
    /** thumbnail file name of the picture shown on the card */
    val cover: String = "",
    /** how many windows (moments) were indexed */
    val segments: Int = 0,
    val hasSound: Boolean = false,
    /** true while recording; a session still open after a crash is closed on the next start */
    val open: Boolean = true,
    /** what the user asked to find before recording (empty for an ordinary session) */
    val target: String = "",
    /** the answer Smriti gave about [target] when the recording ended */
    val result: String = ""
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
}

@Dao
interface CameraSessionDao {
    @Insert
    suspend fun insert(s: CameraSessionEntity): Long

    @Update
    suspend fun update(s: CameraSessionEntity)

    @Query("SELECT * FROM camera_session ORDER BY startedAt DESC")
    fun all(): Flow<List<CameraSessionEntity>>

    @Query("SELECT * FROM camera_session ORDER BY startedAt DESC")
    suspend fun list(): List<CameraSessionEntity>

    @Query("SELECT * FROM camera_session WHERE id = :id")
    suspend fun byId(id: Long): CameraSessionEntity?

    @Query("SELECT * FROM camera_session WHERE open = 1")
    suspend fun openOnes(): List<CameraSessionEntity>

    @Query("DELETE FROM camera_session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM camera_session")
    suspend fun deleteAll(): Int

    @Query("SELECT id FROM camera_session WHERE endedAt < :before")
    suspend fun idsEndedBefore(before: Long): List<Long>
}
