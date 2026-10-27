package com.smriti.app.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemoryEntity::class, LedgerEntity::class, ZoneEntity::class, SessionEntity::class, ChatEntity::class, VisualEntity::class, CameraSessionEntity::class,
        MovePlaceEntity::class, MoveRouteEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun moveDao(): MoveDao
    abstract fun visualDao(): VisualDao
    abstract fun cameraSessionDao(): CameraSessionDao
    abstract fun chatDao(): ChatDao
    abstract fun sessionDao(): SessionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun zoneDao(): ZoneDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Throwaway database for tests. */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()

        /** 3 -> 4 adds the chat table and keeps every stored memory. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `live` INTEGER NOT NULL)"
                )
            }
        }

        /** 4 -> 5: scenes need how long a note lasted, how much moved and how much interacted. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN endAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN motion REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN density REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN frames INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** 5 -> 6: where in the room each thing was (estimated position, distance and bearing). */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                for (c in listOf("px", "py", "pz", "dist", "az", "el")) db.execSQL("ALTER TABLE ledger ADD COLUMN $c REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ledger ADD COLUMN hasHeading INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 6 -> 7: remembered moments (redacted thumbnail + meaning vector) and a picture on chat answers. */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `visual` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `place` TEXT NOT NULL, `caption` TEXT NOT NULL, `thumb` TEXT NOT NULL, `vec` BLOB NOT NULL, `kind` TEXT NOT NULL)"
                )
                db.execSQL("ALTER TABLE chat ADD COLUMN imagePath TEXT")
            }
        }

        /** 7 -> 8: camera sessions, and which session (and window) each remembered moment belongs to. Nothing is deleted. */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `camera_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `place` TEXT NOT NULL, `cover` TEXT NOT NULL, `segments` INTEGER NOT NULL, `hasSound` INTEGER NOT NULL, `open` INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE visual ADD COLUMN sessionId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE visual ADD COLUMN endAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE visual ADD COLUMN heard TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 8 -> 9: what a recording was made to find (and what Smriti answered), and the moments a chat answer points to. */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE camera_session ADD COLUMN target TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE camera_session ADD COLUMN result TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chat ADD COLUMN refs TEXT")
            }
        }

        /** 9 -> 10: the walk guide's saved places and walks (see move/). Nothing that was stored is touched. */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `move_place` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `radiusM` INTEGER NOT NULL, `wifi` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `move_route` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`originLat` REAL NOT NULL, `originLon` REAL NOT NULL, `hasGps` INTEGER NOT NULL, `points` TEXT NOT NULL, `lengthM` REAL NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: open(context).also { instance = it }
        }

        private fun build(context: Context, key: ByteArray?): AppDatabase {
            val b = Room.databaseBuilder(context, AppDatabase::class.java, "smriti.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                // only a database older than the first migration may be thrown away; a missing migration from any later version must stop loudly, never wipe the memories
                .fallbackToDestructiveMigrationFrom(1, 2)
            // encrypted with SQLCipher under the key from the vault (null only when the vault could not be opened)
            if (key != null) b.openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(key))
            return b.build()
        }

        /** makes sure the file is encrypted (converting a plain one from an earlier version) and opens it; see security/DatabaseGuard.kt */
        private fun open(context: Context): AppDatabase {
            val app = context.applicationContext
            val prepared = com.smriti.app.security.DatabaseGuard.prepare(app, com.smriti.app.security.DbKeys.passphrase)
            val db = build(app, prepared.key)
            if (prepared.key == null) return db
            return try {
                // opened now rather than on the first query, so a conversion that went wrong is undone at once
                db.openHelper.writableDatabase
                com.smriti.app.security.DatabaseGuard.opened(prepared)
                db
            } catch (t: Throwable) {
                runCatching { db.close() }
                if (!com.smriti.app.security.DatabaseGuard.rollback(app, prepared, t)) throw t
                build(app, null)
            }
        }
    }
}
