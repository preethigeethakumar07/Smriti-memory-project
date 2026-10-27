package com.smriti.app.pipeline

enum class EventType { FRAME_START, GATE, STAGE, FRAME_END, MODEL_SWAP, QUERY, CAMERA, CONFIG, SYSTEM, LOG, SENSITIVE, SPATIAL }

enum class Status { START, OK, SKIPPED, ERROR, DISABLED }

/** Stage ids. The live dashboard draws one node per id, so these are part of the wire protocol. */
object Stage {
    const val CAMERA = "camera"
    const val GATE = "gate"
    const val PRIVACY = "privacy"
    const val DETECT = "detect"
    const val LABEL = "label"
    const val OCR = "ocr"
    const val ZONE = "zone"
    const val COMPOSE = "compose"
    const val STORE = "store"
    const val LEDGER = "ledger"

    // question path
    const val MIC = "mic"
    const val SPEECH = "speech"
    const val INTENT = "intent"
    const val RETRIEVE = "retrieve"
    const val ANSWER = "answer"
    const val VOICE = "voice"

    val FRAME_STAGES = listOf(CAMERA, GATE, PRIVACY, DETECT, LABEL, OCR, ZONE, COMPOSE, STORE, LEDGER)
    val QUERY_STAGES = listOf(MIC, SPEECH, INTENT, RETRIEVE, ANSWER, VOICE)
}

/**
 * One thing that happened in the backend. Deliberately generic (type + stage + status + a small
 * data map) so the same record drives the log, the metrics and the animated flow on the dashboard.
 * Never carries pixels: only counts, labels, durations and short text.
 */
data class PipelineEvent(
    val type: EventType,
    val t: Long = System.currentTimeMillis(),
    val frameId: Long = 0,
    val stage: String? = null,
    val status: Status? = null,
    val durMs: Long = 0,
    val message: String = "",
    val data: Map<String, Any> = emptyMap()
)
