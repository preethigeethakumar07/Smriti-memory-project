package com.smriti.app.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The three on-device models live in the app's private folder (filesDir/models). The release APK cannot be reached with
 * `adb run-as`, so the app can also import a model file the user picks with the system file picker.
 */
object ModelStore {

    class Spec(
        val id: String,
        val title: String,
        val role: String,
        val approxMb: Int,
        /** true if a file with this name is this model */
        val matches: (String) -> Boolean,
        /** the name the app looks for after import (kept as the picked name unless the app needs an exact one) */
        val saveAs: (String) -> String
    )

    val specs = listOf(
        Spec("embed", "EmbeddingGemma-2 (NPU build)", "search by meaning, pictures and sound", 560,
            { it.contains("embeddinggemma", true) && it.endsWith(".litertlm", true) }, { it }),
        Spec("brain", "Gemma-4 E2B", "answers, vision, speech, languages", 2470,
            { it.startsWith("gemma-4", true) && it.endsWith(".litertlm", true) }, { it }),
        Spec("actions", "MobileActions-270M", "phone actions (flashlight, calendar, email...)", 280,
            { it.contains("mobile_actions", true) && it.endsWith(".litertlm", true) }, { "mobile_actions.litertlm" })
    )

    fun dir(context: Context) = File(context.filesDir, "models").also { it.mkdirs() }

    /** the installed file for a model, or null */
    fun installed(context: Context, spec: Spec): File? =
        dir(context).listFiles { f -> spec.matches(f.name) && f.length() > 1_000_000 }?.maxByOrNull { it.lastModified() }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    sealed interface Result {
        data class Ok(val file: File) : Result
        data class Wrong(val picked: String) : Result
        data class Failed(val why: String) : Result
    }

    /** Copies the picked file into the app, reporting megabytes copied. Does nothing if the name does not match [spec]. */
    fun import(context: Context, uri: Uri, spec: Spec, onProgress: (copiedMb: Long) -> Unit): Result {
        val picked = displayName(context, uri) ?: return Result.Failed("could not read the file name")
        if (!spec.matches(picked)) return Result.Wrong(picked)
        val target = File(dir(context), spec.saveAs(picked))
        val tmp = File(dir(context), target.name + ".part")
        return try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); total += n
                        if (total - lastReport >= 8L shl 20) { lastReport = total; onProgress(total shr 20) }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return Result.Failed("could not save the model")
            onProgress(target.length() shr 20)
            Result.Ok(target)
        } catch (t: Throwable) {
            tmp.delete()
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }
}
