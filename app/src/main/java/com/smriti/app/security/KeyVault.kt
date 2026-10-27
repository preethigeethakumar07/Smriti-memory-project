package com.smriti.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Where Smriti's secrets live. A master key is made inside the phone's secure hardware (the StrongBox chip when the phone has one,
 * otherwise the trusted execution environment) and can never be read out. It encrypts a small vault file holding two random secrets:
 * the passphrase of the encrypted database and the key that encrypts the pictures. Copying the app's folder, a backup or a rooted
 * file dump therefore gives nothing readable: the master key does not leave the chip.
 *
 * The master key does not ask for a fingerprint, so Smriti can still remember in the background; the optional app lock (fingerprint,
 * face or PIN) is what stops someone who is holding the unlocked phone.
 */
class KeyVault(private val context: Context) {

    class Keys(
        /** the database passphrase as 64 printable ASCII bytes (hex of 32 random bytes) */
        val dbPassphrase: ByteArray,
        /** the 32 byte key for pictures */
        val fileKey: ByteArray,
        /** where the master key lives: "StrongBox", "secure hardware (TEE)" or "software" */
        val level: String,
        /** true when this run created the vault (first start after install or upgrade) */
        val created: Boolean
    )

    class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val file = File(context.filesDir, "vault.bin")

    fun exists() = file.exists()

    fun open(): Keys {
        val master = masterKey()
        val level = level(master)
        if (file.exists()) {
            try {
                val blob = file.readBytes()
                require(blob.size > 1 + 12 + 16 && blob[0] == VERSION)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, blob.copyOfRange(1, 13)))
                val plain = c.doFinal(blob, 13, blob.size - 13)
                require(plain.size == PASS_BYTES + Crypto.KEY_BYTES)
                return Keys(plain.copyOfRange(0, PASS_BYTES), plain.copyOfRange(PASS_BYTES, plain.size), level, created = false)
            } catch (e: Exception) {
                throw VaultException("the vault could not be opened (${e.javaClass.simpleName})", e)
            }
        }
        val pass = Crypto.hex(Crypto.randomBytes(32)).toByteArray(Charsets.US_ASCII)
        val fileKey = Crypto.randomBytes(Crypto.KEY_BYTES)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, master)
        val ct = c.doFinal(pass + fileKey)
        val tmp = File(file.parentFile, "vault.bin.tmp")
        tmp.writeBytes(byteArrayOf(VERSION) + c.iv + ct)
        check(tmp.renameTo(file)) { "vault not saved" }
        return Keys(pass, fileKey, level, created = true)
    }

    /** removes the vault (used only when it can no longer be opened and the data it protected is already unreadable) */
    fun discard() { file.delete() }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return generate(strongBox = true) ?: generate(strongBox = false) ?: throw VaultException("no master key could be made")
    }

    private fun generate(strongBox: Boolean): SecretKey? = try {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()
        )
        kg.generateKey()
    } catch (_: StrongBoxUnavailableException) {
        null
    } catch (e: Exception) {
        if (strongBox) null else throw e
    }

    private fun level(key: SecretKey): String = try {
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "secure hardware (TEE)"
            else -> "software"
        }
    } catch (_: Exception) { "unknown" }

    private companion object {
        const val ALIAS = "smriti_master_v1"
        const val VERSION: Byte = 1
        const val PASS_BYTES = 64
    }
}
