package com.smriti.app.security

import java.io.File
import java.io.RandomAccessFile

/** What is at a database path: nothing, a plain SQLite file, or anything else (an encrypted file, or a damaged one). */
enum class DbKind { MISSING, PLAIN, OTHER }

/**
 * The file-level part of the database encryption: what is at a path, putting the files back in order after a conversion that was
 * interrupted, and overwriting a plain copy. No database code in here, so it is unit-tested on a computer; the database part is
 * [DatabaseGuard].
 */
object DbFiles {
    const val NAME = "smriti.db"
    /** the encrypted copy while it is being made and checked */
    const val TMP = "smriti.db.enc"
    /** the plain database while it waits to be overwritten, after the encrypted copy has taken its place */
    const val OLD = "smriti.db.plain-old"

    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    /**
     * SQLCipher's raw key form of a secret that is already 256 random bits written as 64 hex characters: x'...'. It skips the slow
     * stretching meant for human passwords, so the database opens in milliseconds.
     */
    fun rawKey(passphrase: ByteArray): ByteArray = "x'".toByteArray(Charsets.US_ASCII) + passphrase + "'".toByteArray(Charsets.US_ASCII)

    fun kindOf(f: File): DbKind {
        if (!f.exists() || f.length() == 0L) return DbKind.MISSING
        val head = ByteArray(PLAIN_HEADER.size)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(-1)
        return if (n == head.size && head.contentEquals(PLAIN_HEADER)) DbKind.PLAIN else DbKind.OTHER
    }

    /** the files SQLite keeps next to a database while it is open */
    fun removeSidecars(dir: File) {
        for (suffix in listOf("-wal", "-shm", "-journal")) File(dir, NAME + suffix).delete()
    }

    /**
     * Puts the files in order at start-up after a conversion that stopped half way:
     *  - a half-made encrypted copy is thrown away (it was never used);
     *  - when the plain copy is still waiting and the encrypted database is in place and opens, the plain copy is overwritten;
     *  - when the plain copy is waiting and there is no database at all (stopped between the two renames), it is put back.
     * Returns what it did, or null when there was nothing to do.
     */
    fun recover(dir: File, encryptedOpens: (File) -> Boolean): String? {
        val db = File(dir, NAME); val old = File(dir, OLD); val tmp = File(dir, TMP)
        var did: String? = null
        if (tmp.exists()) { tmp.delete(); did = "removed a half-made encrypted copy" }
        if (old.exists()) {
            when (kindOf(db)) {
                DbKind.OTHER -> if (encryptedOpens(db)) { shred(old); did = "overwrote the plain copy left by an earlier conversion" }
                DbKind.MISSING -> if (old.renameTo(db)) did = "put the plain database back after an interrupted conversion"
                DbKind.PLAIN -> {}
            }
        }
        return did
    }

    /** overwrites a file with zeros and deletes it (best effort: flash storage may keep old blocks, the phone's own file encryption is the main protection) */
    fun shred(f: File) {
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                val zeros = ByteArray(64 * 1024)
                var left = raf.length()
                raf.seek(0)
                while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); raf.write(zeros, 0, n); left -= n }
                raf.fd.sync()
            }
        }
        f.delete()
    }

    /** moves an unreadable database out of the way (kept, still encrypted, in case the key turns up) so the app can start fresh; returns the new name */
    fun setAside(dir: File, nowMs: Long): File {
        val db = File(dir, NAME)
        val aside = File(dir, "$NAME.unreadable-$nowMs")
        db.renameTo(aside)
        removeSidecars(dir)
        return aside
    }
}
