package com.smriti.app.cctv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * VoiceOps AI — A Hands-Free CCTV Investigator.
 * Maintains multi-turn conversational context so investigators can operate
 * surveillance by voice or text:
 * "Show me the last time the blue bag was seen" -> "What happened to it after that?" -> "Who took it?"
 */
class VoiceOps(
    private val eventGraph: EventGraph,
    private val query2Evidence: Query2Evidence,
    private val sceneGuard: SceneGuard,
    private val safeFlow: SafeFlow
) {

    data class ConversationTurn(
        val role: String, // "user" or "smriti"
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val evidenceReport: Query2Evidence.EvidenceReport? = null,
        val switchedCamera: String? = null
    )

    data class ContextState(
        val focusedEntity: String? = null,
        val focusedCamera: String = "Camera 1: Reception",
        val activeTimeFilter: String? = null
    )

    private val _history = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val history: StateFlow<List<ConversationTurn>> = _history.asStateFlow()

    private val _context = MutableStateFlow(ContextState())
    val context: StateFlow<ContextState> = _context.asStateFlow()

    init {
        seedDemoConversation()
    }

    /**
     * Process a voice or text turn from the operator.
     */
    fun onUserQuery(rawText: String): ConversationTurn {
        val userTurn = ConversationTurn(role = "user", text = rawText)
        _history.update { it + userTurn }

        val q = rawText.lowercase().trim()
        val curContext = _context.value

        // 1. Camera switching commands
        if (q.contains("switch to") || q.contains("show camera") || q.contains("open camera") || q.contains("view camera")) {
            val targetCam = when {
                q.contains("gate") || q.contains("driveway") || q.contains("cam 1") || q.contains("camera 1") -> "Camera 1: Gate & Driveway"
                q.contains("park") || q.contains("lot") || q.contains("cam 2") || q.contains("camera 2") -> "Camera 2: Parking Area"
                q.contains("loading") || q.contains("bay") || q.contains("dock") || q.contains("cam 3") || q.contains("camera 3") -> "Camera 3: Loading Bay"
                q.contains("reception") || q.contains("lobby") || q.contains("cam 4") || q.contains("camera 4") -> "Camera 4: Reception"
                else -> curContext.focusedCamera
            }
            _context.update { it.copy(focusedCamera = targetCam) }
            val reply = "Switched live surveillance monitor to $targetCam."
            val smritiTurn = ConversationTurn(role = "smriti", text = reply, switchedCamera = targetCam)
            _history.update { it + smritiTurn }
            return smritiTurn
        }

        // 2. Resolve pronouns and contextual references ("it", "that person", "after that", "then what")
        val resolvedQuery = if ((q.contains(" it") || q.contains("that") || q.contains("after that") || q.contains("who took it") || q.contains("who drove it")) && curContext.focusedEntity != null) {
            "$rawText regarding ${curContext.focusedEntity}"
        } else {
            rawText
        }

        // Detect new entity focus
        val newEntity = when {
            q.contains("suv") || q.contains("white suv") -> "vehicle_#KA-03-9122"
            q.contains("van") || q.contains("delivery van") -> "vehicle_#DL-01-4402"
            q.contains("vehicle") || q.contains("car") -> "vehicle"
            q.contains("parcel") || q.contains("package") -> "parcel_#104"
            q.contains("person") || q.contains("driver") -> "person_#15"
            else -> curContext.focusedEntity
        }
        _context.update { it.copy(focusedEntity = newEntity) }

        // Execute evidence investigation
        val report = query2Evidence.investigate(resolvedQuery)
        val smritiTurn = ConversationTurn(
            role = "smriti",
            text = report.directAnswer,
            evidenceReport = report
        )
        _history.update { it + smritiTurn }
        return smritiTurn
    }

    fun clearHistory() {
        _history.value = emptyList()
        _context.value = ContextState()
    }

    private fun seedDemoConversation() {
        _history.value = listOf(
            ConversationTurn(
                role = "user",
                text = "Show me the last time parcel #104 was seen."
            ),
            ConversationTurn(
                role = "smriti",
                text = "Parcel #104 was last confirmed at Desk 4 in Camera 3: Office B.",
                evidenceReport = query2Evidence.lastReport.value
            )
        )
        _context.value = ContextState(focusedEntity = "parcel_#104", focusedCamera = "Camera 3: Office B")
    }
}
