package com.smriti.app.security

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/** The database key from the vault, held in memory only for as long as the app runs (it is read from the vault at every start). */
object DbKeys {
    @Volatile var passphrase: ByteArray? = null
}

/**
 * The memory database is a SQLCipher file (AES-256) under a random key that lives in the phone's secure hardware (see [KeyVault]), so a
 * copy of the app's folder, a backup or a file dump has nothing readable in it: what was seen and heard, the chat, the walk guide's saved
 * places. [prepare] runs once at start, before Room opens the file:
 *  - a new database is left to Room, which creates it encrypted;
 *  - a plain database from an earlier version is converted: its data is exported into an encrypted copy, the copy is opened with the key
 *    and every table's row count, the schema version and an integrity check are compared with the original, and only then does the copy
 *    take the original's place. The plain file is kept until Room has opened the encrypted one once ([opened]), then overwritten with
 *    zeros; if Room cannot open it, [rollback] puts the plain file back. If anything at all goes wrong the plain database stays the
 *    database and the next start tries again;
 *  - an encrypted file that cannot be opened with this phone's key (the key is lost when the secure hardware is reset) is set aside, so
 *    the app starts fresh instead of crashing on every start.
 */
object DatabaseGuard {

    /** [key] is what Room must open the database with (null: open it as it is, not encrypted); [plainOld] the plain copy waiting to be overwritten */
    class Prepared(val key: ByteArray?, val note: String, val plainOld: File? = null)

    private const val TAG = "SmritiSecurity"
    private var loaded = false

    private fun load() {
        if (!loaded) { System.loadLibrary("sqlcipher"); loaded = true }
    }

    fun prepare(context: Context, passphrase: ByteArray?): Prepared {
        val db = context.getDatabasePath(DbFiles.NAME)
        val dir = db.parentFile ?: return report(Prepared(null, "no database folder"), false)
        dir.mkdirs()
        val key = passphrase?.let { DbFiles.rawKey(it) }
        return try {
            if (key != null) load()
            DbFiles.recover(dir) { f -> key != null && canOpen(f, key) }?.let { Log.i(TAG, "database: $it") }
            when (DbFiles.kindOf(db)) {
                DbKind.MISSING ->
                    if (key != null) report(Prepared(key, "new database, encrypted"), true) else report(Prepared(null, "no key: the database is not encrypted"), false)
                DbKind.PLAIN ->
                    if (key == null) report(Prepared(null, "no key: the database is not encrypted"), false) else convert(db, key)
                DbKind.OTHER -> when {
                    key == null -> report(Prepared(null, "the database is encrypted but the key is not available"), false)
                    canOpen(db, key) -> report(Prepared(key, "database encrypted"), true)
                    else -> {
                        val aside = DbFiles.setAside(dir, System.currentTimeMillis())
                        Log.e(TAG, "the database cannot be opened with this phone's key; set aside as ${aside.name}")
                        report(Prepared(key, "the old database could not be opened with this phone's key and was set aside; started a new one"), true)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "database encryption: ${t.javaClass.simpleName}: ${t.message}")
            // never stop the app over this: a plain database is used as it is, an encrypted one is opened with its key
            if (DbFiles.kindOf(db) == DbKind.OTHER && key != null) report(Prepared(key, "encrypted (the check failed: ${t.message})"), true)
            else report(Prepared(null, "not encrypted: ${t.javaClass.simpleName} ${t.message}"), false)
        }
    }

    /** Room opened the encrypted database: the plain copy is no longer needed. */
    fun opened(p: Prepared) {
        val old = p.plainOld ?: return
        DbFiles.shred(old)
        SecurityState.databaseNote = p.note + "; the plain copy was overwritten"
    }

    /**
     * Room could not open the encrypted database right after a conversion: the plain database is put back so nothing is lost.
     * Returns true when it was put back (and Room should open it as a plain file).
     */
    fun rollback(context: Context, p: Prepared, cause: Throwable): Boolean {
        val old = p.plainOld ?: return false
        val db = context.getDatabasePath(DbFiles.NAME)
        val dir = db.parentFile ?: return false
        Log.e(TAG, "the encrypted database did not open (${cause.javaClass.simpleName}: ${cause.message}); going back to the plain one")
        DbFiles.removeSidecars(dir)
        db.delete()
        val ok = old.renameTo(db)
        SecurityState.databaseEncrypted = false
        SecurityState.databaseNote = "the encrypted database did not open (${cause.message}); the plain one was kept"
        return ok
    }

    // ---- the conversion ---------------------------------------------------------------------------------------------

    private fun convert(db: File, key: ByteArray): Prepared {
        val dir = db.parentFile!!
        val tmp = File(dir, DbFiles.TMP).also { it.delete() }
        // the key as an SQL string: x'HEX' with its quotes doubled
        val keyLiteral = "'" + String(key, Charsets.US_ASCII).replace("'", "''") + "'"
        var before: Map<String, Long> = emptyMap()
        var version = 0
        // CREATE_IF_NECESSARY is needed even though the plain file exists: ATTACH creates the new encrypted file with the main connection's flags
        val plain = SQLiteDatabase.openDatabase(db.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null)
        try {
            plain.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { it.moveToFirst() }
            version = plain.version
            before = counts(plain)
            plain.execSQL("ATTACH DATABASE '" + tmp.path.replace("'", "''") + "' AS encrypted KEY " + keyLiteral)
            plain.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
            plain.execSQL("PRAGMA encrypted.user_version = $version")
            plain.execSQL("DETACH DATABASE encrypted")
        } catch (t: Throwable) {
            runCatching { plain.close() }
            tmp.delete()
            throw t
        }
        plain.close()

        // the copy is opened with the key and compared with the original before it is trusted
        try {
            val enc = SQLiteDatabase.openDatabase(tmp.path, key, null, SQLiteDatabase.OPEN_READWRITE, null)
            try {
                val intact = enc.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { it.moveToFirst() && it.getString(0) == "ok" }
                check(intact) { "the encrypted copy failed its integrity check" }
                check(enc.version == version) { "the schema version differs (${enc.version} instead of $version)" }
                val after = counts(enc)
                check(after == before) { "row counts differ: $before and $after" }
            } finally {
                enc.close()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }

        // swap: the plain file steps aside (kept until Room has opened the new one), the encrypted copy takes its name
        val old = File(dir, DbFiles.OLD)
        DbFiles.removeSidecars(dir)
        check(db.renameTo(old)) { "the plain database could not be moved aside" }
        if (!tmp.renameTo(db)) {
            old.renameTo(db)
            error("the encrypted database could not be put in place")
        }
        File(dir, DbFiles.TMP + "-wal").delete(); File(dir, DbFiles.TMP + "-shm").delete(); File(dir, DbFiles.TMP + "-journal").delete()
        val rows = before.values.sum()
        Log.i(TAG, "database converted: $rows rows in ${before.size} tables")
        return report(Prepared(key, "converted $rows rows in ${before.size} tables to an encrypted database", old), true)
    }

    private fun counts(db: SQLiteDatabase): Map<String, Long> {
        val names = ArrayList<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name", emptyArray<String>())
            .use { c -> while (c.moveToNext()) names += c.getString(0) }
        val out = LinkedHashMap<String, Long>()
        for (n in names) {
            db.rawQuery("SELECT COUNT(*) FROM \"" + n.replace("\"", "\"\"") + "\"", emptyArray<String>()).use { c -> c.moveToFirst(); out[n] = c.getLong(0) }
        }
        return out
    }

    private fun canOpen(f: File, key: ByteArray): Boolean = try {
        val db = SQLiteDatabase.openDatabase(f.path, key, null, SQLiteDatabase.OPEN_READWRITE, null)
        try { db.rawQuery("SELECT COUNT(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() } } finally { db.close() }
    } catch (_: Throwable) {
        false
    }

    private fun report(p: Prepared, encrypted: Boolean): Prepared {
        SecurityState.databaseEncrypted = encrypted
        SecurityState.databaseNote = p.note
        return p
    }
}
