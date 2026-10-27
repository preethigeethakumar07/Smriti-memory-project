package com.smriti.app.memory

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One remembered moment. Text only: there is deliberately no image, thumbnail or face column. */
@Entity(tableName = "memories", indices = [Index("timestamp")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val caption: String,
    /** comma separated, lowercase */
    val objects: String,
    val ocrText: String,
    val peopleCount: Int,
    val zone: String,
    val backend: String,
    val latencyMs: Long,
    /** phone | glasses */
    val source: String = "phone",
    /** the moment this note stopped being true (it is extended while the same scene keeps being seen); 0 = same as timestamp */
    @ColumnInfo(defaultValue = "0") val endAt: Long = 0,
    /** how much the picture was changing, 0..1 (from the gate) */
    @ColumnInfo(defaultValue = "0") val motion: Float = 0f,
    /** how many things were related to each other in view, 0..1 */
    @ColumnInfo(defaultValue = "0") val density: Float = 0f,
    /** how many frames agreed with this note */
    @ColumnInfo(defaultValue = "1") val frames: Int = 1
)

/** "Where did I last see X": one row per object, updated only when it is seen again. */
@Entity(tableName = "ledger")
data class LedgerEntity(
    @PrimaryKey val item: String,
    val lastSeen: Long,
    val zone: String,
    val lastContext: String,
    val seenCount: Int,
    /** estimated position in the room (East, North, Up in metres from where the phone was held); see spatial/Spatial3D.kt */
    @ColumnInfo(defaultValue = "0") val px: Float = 0f,
    @ColumnInfo(defaultValue = "0") val py: Float = 0f,
    @ColumnInfo(defaultValue = "0") val pz: Float = 0f,
    /** estimated distance in metres, 0 = unknown */
    @ColumnInfo(defaultValue = "0") val dist: Float = 0f,
    /** compass bearing and elevation from the phone to the object */
    @ColumnInfo(defaultValue = "0") val az: Float = 0f,
    @ColumnInfo(defaultValue = "0") val el: Float = 0f,
    /** 1 when the phone had a compass, so the bearing means something */
    @ColumnInfo(defaultValue = "0") val hasHeading: Int = 0
)

/** A named place, taught by the user, matched later by a colour signature (no image kept). */
@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val signature: String
)

/** One run of the app by one user. Text only: who, when, how much. Shown on the dashboard's Users page. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val userName: String,
    val language: String,
    val device: String,
    val appVersion: String,
    val consentVersion: Int,
    val framesProcessed: Long = 0,
    val notesStored: Int = 0,
    val errors: Int = 0
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt, framesProcessed = :frames, notesStored = :notes, errors = :errors, userName = :user, language = :language WHERE id = :id")
    suspend fun update(id: Long, endedAt: Long, frames: Long, notes: Int, errors: Int, user: String, language: String)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE startedAt < :before")
    suspend fun deleteBefore(before: Long): Int
}

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(m: MemoryEntity): Long

    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<MemoryEntity>>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM memories WHERE timestamp >= :since")
    suspend fun deleteSince(since: Long): Int

    @Query("DELETE FROM memories WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long): Int

    /** The same scene was seen again: stretch the note instead of adding a copy. */
    @Query("UPDATE memories SET endAt = :endAt, frames = frames + 1, motion = MAX(motion, :motion), density = MAX(density, :density) WHERE id = :id")
    suspend fun extend(id: Long, endAt: Long, motion: Float, density: Float)

    @Query("SELECT COUNT(*) FROM memories")
    fun count(): Flow<Int>

    @Query("SELECT COALESCE(SUM(LENGTH(caption) + LENGTH(objects) + LENGTH(ocrText)), 0) FROM memories")
    suspend fun textBytes(): Long
}

@Dao
interface LedgerDao {
    @Upsert
    suspend fun upsert(e: LedgerEntity)

    @Query("SELECT * FROM ledger WHERE item = :item")
    suspend fun get(item: String): LedgerEntity?

    @Query("SELECT * FROM ledger ORDER BY lastSeen DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LedgerEntity>

    @Query("DELETE FROM ledger")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM ledger WHERE lastSeen >= :since")
    suspend fun deleteSince(since: Long): Int

    @Query("DELETE FROM ledger WHERE lastSeen < :before")
    suspend fun deleteBefore(before: Long): Int
}

@Dao
interface ZoneDao {
    @Insert
    suspend fun insert(z: ZoneEntity)

    @Query("SELECT * FROM zones")
    suspend fun all(): List<ZoneEntity>
}
