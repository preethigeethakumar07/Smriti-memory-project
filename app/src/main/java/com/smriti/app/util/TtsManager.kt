package com.smriti.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Thin wrapper over Android's on-device TextToSpeech. Language follows the user's setting. */
object TtsManager {

    private const val TAG = "TtsManager"
    private var tts: TextToSpeech? = null
    private var engineUp = false
    private var pendingTag: String? = null

    @Volatile var ready = false
        private set

    /** True when the chosen language has a voice installed on this phone. */
    @Volatile var languageSupported = true
        private set

    fun init(context: Context) {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engineUp = true
                tts?.setSpeechRate(0.95f)
                applyLanguage(pendingTag ?: Locale.getDefault().toLanguageTag())
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun setLanguage(tag: String) {
        pendingTag = tag
        if (engineUp) applyLanguage(tag)
    }

    private fun applyLanguage(tag: String) {
        activeTag = tag
        val r = tts?.setLanguage(Locale.forLanguageTag(tag))
        languageSupported = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!languageSupported) tts?.setLanguage(Locale.US)
        ready = true
    }

    private var activeTag: String? = null

    /** True when the phone has a voice for [tag] (so an answer written in that language can be read out properly). */
    fun hasVoice(tag: String): Boolean {
        val e = tts ?: return false
        if (!engineUp) return false
        val r = e.isLanguageAvailable(Locale.forLanguageTag(tag))
        return r >= TextToSpeech.LANG_AVAILABLE
    }

    /** Switches the voice only when the language actually changes. */
    private fun useLanguage(tag: String?) {
        val t = tag ?: pendingTag ?: Locale.getDefault().toLanguageTag()
        if (t == activeTag) return
        activeTag = t
        val r = tts?.setLanguage(Locale.forLanguageTag(t))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.US)
    }

    /** [tag] is the language the TEXT is written in, so an English answer is never read out with a Hindi voice. */
    fun speak(text: String, tag: String? = null) {
        if (!ready) return
        useLanguage(tag)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "smriti")
    }

    @Volatile private var finished: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Speaks and returns when the voice has finished (or after [maxMs]), so a Live loop never talks over itself. */
    suspend fun speakAndWait(text: String, tag: String? = null, maxMs: Long = 45_000) {
        val engine = tts
        if (!ready || engine == null) return
        useLanguage(tag)
        val d = kotlinx.coroutines.CompletableDeferred<Unit>()
        finished = d
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { d.complete(Unit) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { d.complete(Unit) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { d.complete(Unit) }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "smriti-live")
        kotlinx.coroutines.withTimeoutOrNull(maxMs) { d.await() }
    }

    /** Renders [text] to a WAV file with the phone's own voice (used to test speech recognition without a person speaking). */
    suspend fun synthesizeToFile(text: String, file: java.io.File, timeoutMs: Long = 15_000): Boolean {
        val engine = tts ?: return false
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { d.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { d.complete(false) }
        })
        val r = engine.synthesizeToFile(text, android.os.Bundle(), file, "smriti-synth")
        if (r != TextToSpeech.SUCCESS) return false
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { d.await() } == true
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        ready = false
    }
}
