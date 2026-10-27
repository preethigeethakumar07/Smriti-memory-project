package com.smriti.app

import com.smriti.app.security.DbFiles
import com.smriti.app.security.DbKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DbFilesTest {
    private lateinit var dir: File

    @Before fun makeDir() { dir = Files.createTempDirectory("dbfiles").toFile() }
    @After fun removeDir() { dir.deleteRecursively() }

    private val plainHeader = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
    private fun plainFile(name: String, extra: Int = 200) = File(dir, name).also { it.writeBytes(plainHeader + ByteArray(extra) { 7 }) }
    private fun encryptedFile(name: String) = File(dir, name).also { it.writeBytes(ByteArray(300) { (it * 31 + 5).toByte() }) }

    @Test fun whatIsAtAPath() {
        assertEquals(DbKind.MISSING, DbFiles.kindOf(File(dir, "none.db")))
        assertEquals(DbKind.MISSING, DbFiles.kindOf(File(dir, "empty.db").also { it.writeBytes(ByteArray(0)) }))
        assertEquals(DbKind.PLAIN, DbFiles.kindOf(plainFile("plain.db")))
        assertEquals(DbKind.OTHER, DbFiles.kindOf(encryptedFile("enc.db")))
        assertEquals(DbKind.OTHER, DbFiles.kindOf(File(dir, "short.db").also { it.writeBytes(byteArrayOf(1, 2, 3)) }))
    }

    @Test fun theRawKeyIsTheSecretInSqlcipherRawFormWithItsQuotes() {
        val secret = "ab".repeat(32).toByteArray(Charsets.US_ASCII)
        val raw = String(DbFiles.rawKey(secret), Charsets.US_ASCII)
        assertEquals("x'" + "ab".repeat(32) + "'", raw)
        assertEquals(64 + 3, raw.length)
    }

    @Test fun aHalfMadeEncryptedCopyIsThrownAway() {
        File(dir, DbFiles.TMP).writeBytes(ByteArray(10))
        val did = DbFiles.recover(dir) { true }
        assertNotNull(did)
        assertFalse(File(dir, DbFiles.TMP).exists())
    }

    @Test fun thePlainCopyIsOverwrittenOnceTheEncryptedOneOpens() {
        encryptedFile(DbFiles.NAME); plainFile(DbFiles.OLD)
        val did = DbFiles.recover(dir) { true }
        assertNotNull(did)
        assertFalse("the plain copy is gone", File(dir, DbFiles.OLD).exists())
        assertTrue(File(dir, DbFiles.NAME).exists())
    }

    @Test fun thePlainCopyIsKeptWhileTheEncryptedOneDoesNotOpen() {
        encryptedFile(DbFiles.NAME); plainFile(DbFiles.OLD)
        assertNull(DbFiles.recover(dir) { false })
        assertTrue(File(dir, DbFiles.OLD).exists())
    }

    @Test fun aStopBetweenTheTwoRenamesPutsThePlainDatabaseBack() {
        plainFile(DbFiles.OLD)
        val did = DbFiles.recover(dir) { true }
        assertNotNull(did)
        assertEquals(DbKind.PLAIN, DbFiles.kindOf(File(dir, DbFiles.NAME)))
        assertFalse(File(dir, DbFiles.OLD).exists())
    }

    @Test fun nothingToDoIsNothing() {
        encryptedFile(DbFiles.NAME)
        assertNull(DbFiles.recover(dir) { true })
        assertTrue(File(dir, DbFiles.NAME).exists())
    }

    @Test fun shreddingDeletesTheFile() {
        val f = plainFile("gone.db", extra = 200_000)
        DbFiles.shred(f)
        assertFalse(f.exists())
        DbFiles.shred(File(dir, "never-existed.db"))             // not an error
    }

    @Test fun anUnreadableDatabaseIsSetAsideNotDeleted() {
        encryptedFile(DbFiles.NAME)
        File(dir, DbFiles.NAME + "-wal").writeBytes(ByteArray(5)); File(dir, DbFiles.NAME + "-shm").writeBytes(ByteArray(5))
        val aside = DbFiles.setAside(dir, 12345L)
        assertTrue(aside.exists()); assertTrue(aside.name.contains("unreadable"))
        assertFalse(File(dir, DbFiles.NAME).exists())
        assertFalse(File(dir, DbFiles.NAME + "-wal").exists()); assertFalse(File(dir, DbFiles.NAME + "-shm").exists())
    }
}
