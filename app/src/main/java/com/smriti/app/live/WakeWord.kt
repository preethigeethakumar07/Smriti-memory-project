package com.smriti.app.live

import android.content.Context
import com.smriti.app.util.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * "Hey Smriti" detection on a speech transcript. Speech recognisers spell the name many ways
 * ("smitty", "smrithi", "smirti"), so the name is matched loosely, and it must come right after a greeting
 * or be the first word, so ordinary talk about memory does not wake the app.
 */
object WakeWord {
    private val greetings = setOf("hey", "hay", "hi", "hello", "ok", "okay", "o", "a", "he", "yo")
    private val spellings = setOf("smriti", "smrithi", "smirti", "smritee", "smrity", "smrithy", "smitty", "smriti's", "smritti", "samriti", "srimiti", "smitti", "smirty", "smrity", "smruti", "sumriti")

    /** The words after "hey Smriti" ("where is my mouse"), or "" for just the wake phrase, or null when not woken. */
    fun match(text: String): String? {
        val tokens = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (i in tokens.indices) {
            val t = tokens[i]
            val isName = t in spellings || (t.length >= 4 && distance(t, "smriti") <= 2)
            if (!isName) continue
            val afterGreeting = i > 0 && tokens[i - 1] in greetings
            if (i == 0 || afterGreeting) return tokens.drop(i + 1).joinToString(" ")
        }
        return null
    }

    internal fun distance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return dp[a.length][b.length]
    }
}

/** Keeps a recogniser open and calls [onWake] when it hears "hey Smriti". Main-thread only. */
class WakeWordListener(context: Context, private val scope: CoroutineScope, private val onWake: (rest: String) -> Unit) {

    private val speech = SpeechInput(context)
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start(strictOffline: Boolean) {
        speech.strictOffline = strictOffline
        if (running) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        speech.stop()
    }

    private suspend fun loop() {
        var failures = 0
        while (scope.isActive) {
            val r = listenOnce()
            when {
                r.rest != null -> { speech.stop(); onWake(r.rest); return }
                r.error -> { failures++; delay(minOf(10_000L, 1_000L * failures)) }
                else -> { failures = 0; delay(200) }
            }
        }
    }

    private class Result(val rest: String?, val error: Boolean)

    private suspend fun listenOnce(): Result = suspendCancellableCoroutine { cont ->
        fun finish(r: Result) { if (cont.isActive) cont.resume(r) }
        speech.listen(
            languageTag = "en",
            onResult = { t -> finish(Result(WakeWord.match(t), false)) },
            onError = { msg ->
                val quiet = msg.contains("error 6") || msg.contains("error 7") || msg.contains("Didn't catch") || msg.contains("error 8")
                finish(Result(null, !quiet))
            },
            onPartial = { p -> WakeWord.match(p)?.let { finish(Result(it, false)) } }
        )
        cont.invokeOnCancellation { speech.stop() }
    }
}
