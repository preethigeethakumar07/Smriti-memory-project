package com.smriti.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One-shot voice query. Follows the approach that already works in the MemoryLens reference app:
 *  1. Try the on-device recogniser first (audio never leaves the phone).
 *  2. Do not force a language the phone has no pack for: an English choice uses the phone's own language,
 *     because forcing "en-IN" on a phone with only "en-US" gives ERROR_LANGUAGE_UNAVAILABLE (13).
 *  3. If on-device recognition is missing or fails, fall back to the phone's system recogniser, unless
 *     [strictOffline] is on. [path] says which one answered, so the UI can be honest about it.
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /** "on-device" or "system" (the phone's speech service, which may use the network). */
    @Volatile var path: String = "on-device"
        private set

    /** Never fall back to the system service. */
    @Volatile var strictOffline = false

    private var forceSystem = false

    val onDeviceAvailable: Boolean
        get() = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /** Must be called on the main thread. */
    fun listen(languageTag: String, onResult: (String) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit = {}, onPartial: (String) -> Unit = {}) {
        stop()
        val useOnDevice = onDeviceAvailable && !forceSystem
        val r = when {
            useOnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            strictOffline -> { onError("Offline speech pack missing. Install it in Settings > Language, or turn off Strict offline voice."); return }
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> { onError("No speech recognizer on this phone. Type your question instead."); return }
        }
        path = if (useOnDevice) "on-device" else "system"
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onError("Didn't catch that.") else onResult(text)
            }

            override fun onError(error: Int) {
                // 12 / 13: this phone has no on-device pack for the language. Switch once, then listen again.
                val noPack = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (useOnDevice && noPack && !strictOffline) {
                    forceSystem = true
                    listen(languageTag, onResult, onError, onLevel, onPartial)
                    return
                }
                onError("Speech error $error")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            // rmsdB is roughly -2 (silence) to 10 (loud); the Live dial wants 0..1
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)   // so the words can appear while you are still speaking
            // English: let the phone use its own language. Other languages: ask for the one the user chose.
            if (!languageTag.startsWith("en", ignoreCase = true)) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
