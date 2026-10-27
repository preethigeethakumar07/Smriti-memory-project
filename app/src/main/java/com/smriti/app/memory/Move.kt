package com.smriti.app.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A place the person can be guided to (their home, a shop, a friend's house), saved from where they stood. */
@Entity(tableName = "move_place")
data class MovePlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "home" for where the person lives, "other" for any other saved place */
    val kind: String,
    val lat: Double,
    val lon: Double,
    /** how close counts as arrived, in metres */
    val radiusM: Int,
    /** the strongest Wi-Fi networks seen here ("id,id,..."), to tell the place from the signal when GPS is weak */
    val wifi: String,
    val createdAt: Long
)

/** A walk that was recorded, so the same way can be walked back (or forward) without a map. */
@Entity(tableName = "move_route")
data class MoveRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "trip" (walked outdoors, with a GPS start) or "route" (recorded without GPS) */
    val kind: String,
    /** where the walk began on the map; 0, 0 when there was no GPS */
    val originLat: Double,
    val originLon: Double,
    val hasGps: Boolean,
    /** the walk as metres east and north of its start: "x,y;x,y;..." */
    val points: String,
    val lengthM: Double,
    val createdAt: Long
)

@Dao
interface MoveDao {
    @Insert suspend fun insertPlace(p: MovePlaceEntity): Long

    @Query("SELECT * FROM move_place ORDER BY CASE WHEN kind = 'home' THEN 0 ELSE 1 END, createdAt DESC")
    fun placesFlow(): Flow<List<MovePlaceEntity>>

    @Query("SELECT * FROM move_place ORDER BY CASE WHEN kind = 'home' THEN 0 ELSE 1 END, createdAt DESC")
    suspend fun places(): List<MovePlaceEntity>

    @Query("SELECT * FROM move_place WHERE id = :id")
    suspend fun place(id: Long): MovePlaceEntity?

    @Query("SELECT * FROM move_place WHERE kind = 'home' ORDER BY createdAt DESC LIMIT 1")
    suspend fun home(): MovePlaceEntity?

    /** a new Home replaces the old one */
    @Query("DELETE FROM move_place WHERE kind = 'home'")
    suspend fun clearHome()

    @Query("DELETE FROM move_place WHERE id = :id")
    suspend fun deletePlace(id: Long)

    @Query("DELETE FROM move_place")
    suspend fun deleteAllPlaces()

    @Insert suspend fun insertRoute(r: MoveRouteEntity): Long

    @Update suspend fun updateRoute(r: MoveRouteEntity)

    @Query("SELECT * FROM move_route ORDER BY createdAt DESC")
    fun routesFlow(): Flow<List<MoveRouteEntity>>

    @Query("SELECT * FROM move_route WHERE id = :id")
    suspend fun route(id: Long): MoveRouteEntity?

    @Query("SELECT * FROM move_route WHERE kind = 'trip' ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastTrip(): MoveRouteEntity?

    @Query("UPDATE move_route SET name = :name WHERE id = :id")
    suspend fun renameRoute(id: Long, name: String)

    @Query("DELETE FROM move_route WHERE id = :id")
    suspend fun deleteRoute(id: Long)

    @Query("DELETE FROM move_route")
    suspend fun deleteAllRoutes()
}
