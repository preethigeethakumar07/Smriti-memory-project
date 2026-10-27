package com.smriti.app

import com.smriti.app.security.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 1).toByte() }

    @Test fun roundTrip() {
        val plain = "a blurred thumbnail".toByteArray()
        val blob = Crypto.encrypt(key, plain)
        assertTrue(Crypto.isEncrypted(blob))
        assertArrayEquals(plain, Crypto.decrypt(key, blob))
    }

    @Test fun emptyAndLargePayloads() {
        assertArrayEquals(ByteArray(0), Crypto.decrypt(key, Crypto.encrypt(key, ByteArray(0))))
        val big = ByteArray(200_000) { (it * 31).toByte() }
        assertArrayEquals(big, Crypto.decrypt(key, Crypto.encrypt(key, big)))
    }

    @Test fun cipherTextIsNotThePlainText() {
        val plain = ByteArray(1000) { 7 }
        val blob = Crypto.encrypt(key, plain)
        assertEquals(plain.size + 4 + 12 + 16, blob.size)
        // no run of the plain bytes shows through
        assertFalse(blob.toList().windowed(16).any { w -> w.all { it == 7.toByte() } })
    }

    @Test fun everyEncryptionUsesANewNonce() {
        val a = Crypto.encrypt(key, "same".toByteArray())
        val b = Crypto.encrypt(key, "same".toByteArray())
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun wrongKeyFails() {
        assertNull(Crypto.decrypt(other, Crypto.encrypt(key, "secret".toByteArray())))
    }

    @Test fun anyChangedByteIsDetected() {
        val blob = Crypto.encrypt(key, "secret picture".toByteArray())
        for (i in blob.indices step 3) {
            val bad = blob.copyOf(); bad[i] = (bad[i].toInt() xor 0x01).toByte()
            assertNull("byte $i", Crypto.decrypt(key, bad))
        }
    }

    @Test fun plainFilesAreNotMistakenForEncrypted() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100)
        assertFalse(Crypto.isEncrypted(jpeg))
        assertNull(Crypto.decrypt(key, jpeg))
        assertFalse(Crypto.isEncrypted(ByteArray(10)))
    }

    @Test fun wrongKeySizeIsRejected() {
        assertNull(Crypto.decrypt(ByteArray(16), Crypto.encrypt(key, "x".toByteArray())))
        var failed = false
        try { Crypto.encrypt(ByteArray(16), "x".toByteArray()) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun hexIsPrintableAndFixedLength() {
        val h = Crypto.hex(Crypto.randomBytes(32))
        assertEquals(64, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(h, Crypto.hex(Crypto.randomBytes(32)))
    }
}
