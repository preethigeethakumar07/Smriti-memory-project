package com.smriti.app.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One line of the conversation. Text only, like everything else we keep.
 * role: "user" | "smriti" | "event" (a divider such as "Live session, 2:14").
 */
@Entity(tableName = "chat")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val role: String,
    val text: String,
    /** phone | glasses */
    val source: String,
    /** true when it was spoken in a Live session */
    val live: Boolean,
    /** a remembered moment shown with the answer (privacy-redacted thumbnail in the app's private folder) */
    val imagePath: String? = null,
    /** moments this answer points to, encoded by MomentRef.encode (tap to open them) */
    val refs: String? = null
)

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(m: ChatEntity): Long

    @Query("SELECT * FROM (SELECT * FROM chat ORDER BY timestamp DESC, id DESC LIMIT 300) ORDER BY timestamp ASC, id ASC")
    fun recent(): Flow<List<ChatEntity>>

    @Query("DELETE FROM chat")
    suspend fun clear()

    @Query("DELETE FROM chat WHERE timestamp >= :since")
    suspend fun clearSince(since: Long)
}

class ChatRepository(private val dao: ChatDao) {
    val messages: Flow<List<ChatEntity>> = dao.recent()

    suspend fun add(role: String, text: String, source: String, live: Boolean = false, imagePath: String? = null, refs: String? = null) {
        if (text.isBlank()) return
        dao.insert(ChatEntity(timestamp = System.currentTimeMillis(), role = role, text = text.trim(), source = source, live = live, imagePath = imagePath, refs = refs))
    }

    suspend fun clear() = dao.clear()
    suspend fun clearSince(since: Long) = dao.clearSince(since)
}
