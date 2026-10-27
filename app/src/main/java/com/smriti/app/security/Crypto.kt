package com.smriti.app.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for small blobs (the blurred thumbnails). Layout of an encrypted blob: the four bytes "SMT1", a fresh random 12 byte
 * nonce, then the ciphertext with its 16 byte authentication tag. The header is also authenticated, so a changed byte anywhere makes
 * decryption fail instead of returning garbage. Plain Kotlin on javax.crypto, so it is unit-tested on the JVM.
 */
object Crypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    const val KEY_BYTES = 32

    fun isEncrypted(data: ByteArray): Boolean =
        data.size >= MAGIC.size + NONCE + TAG_BYTES && MAGIC.indices.all { data[it] == MAGIC[it] }

    fun encrypt(key: ByteArray, plain: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(key.size == KEY_BYTES) { "key must be 32 bytes" }
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(MAGIC)
        return MAGIC + nonce + c.doFinal(plain)
    }

    /** the plain bytes, or null when [blob] is not encrypted, the key is wrong, or the blob was changed */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? {
        if (key.size != KEY_BYTES || !isEncrypted(blob)) return null
        return try {
            val nonce = blob.copyOfRange(MAGIC.size, MAGIC.size + NONCE)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(MAGIC)
            c.doFinal(blob, MAGIC.size + NONCE, blob.size - MAGIC.size - NONCE)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** [n] random bytes from the system's secure random */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** lower-case hex, used for the database passphrase (printable, so it works as a text key everywhere) */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
