package com.smriti.app.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smriti.app.pipeline.PipelineBus
import java.io.File

/**
 * Every remembered picture is stored through here: encrypted with the picture key from [KeyVault] and written in one step (a temporary
 * file, then a rename, so a stop in the middle never leaves a half-written picture). Reading accepts both the new encrypted files and
 * the plain JPEGs written by earlier versions, so nothing breaks while [migrate] converts the old ones in the background.
 */
object SecureFiles {
    @Volatile private var key: ByteArray? = null

    val ready: Boolean get() = key != null

    fun init(fileKey: ByteArray?) { key = fileKey }

    /** writes [plain] (a JPEG) encrypted; if no key is available the picture is kept plain rather than lost */
    fun write(file: File, plain: ByteArray) {
        val k = key
        val data = if (k != null) Crypto.encrypt(k, plain) else plain.also { PipelineBus.warn("security", "picture key missing: stored unencrypted") }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) { file.writeBytes(data); tmp.delete() }
    }

    /** the plain bytes of a stored picture, or null when it is missing or cannot be decrypted */
    fun read(file: File): ByteArray? {
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!Crypto.isEncrypted(raw)) return raw                  // a picture from an earlier version
        val k = key ?: return null
        return Crypto.decrypt(k, raw)
    }

    fun decodeBitmap(file: File): Bitmap? = read(file)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    fun decodeBitmap(path: String): Bitmap? = decodeBitmap(File(path))

    fun isEncrypted(file: File): Boolean = runCatching { file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }.let { head -> head.contentEquals(byteArrayOf(0x53, 0x4D, 0x54, 0x31)) } }.getOrDefault(false)

    /** encrypts every plain picture in [dir]; returns how many were converted */
    fun migrate(dir: File): Int {
        if (key == null) return 0
        var n = 0
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.forEach { f ->
            val raw = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
            if (!Crypto.isEncrypted(raw)) { runCatching { write(f, raw); n++ } }
        }
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        return n
    }
}
