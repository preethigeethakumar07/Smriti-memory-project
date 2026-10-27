package com.smriti.app.live

import com.smriti.app.ai.MomentRef
import com.smriti.app.ai.MomentSearch
import com.smriti.app.ai.RangeMerge
import com.smriti.app.ai.SessionRules
import com.smriti.app.core.SmritiEngine
import com.smriti.app.data.AppSettings
import com.smriti.app.memory.CameraSessionEntity
import com.smriti.app.memory.ChatRepository
import com.smriti.app.memory.VisualEntity
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.util.Haptics
import com.smriti.app.util.TtsManager
import kotlinx.coroutines.flow.StateFlow

/**
 * The end of a "look for it" recording. The user asked first ("where is my pill") and then filmed; while filming, the watcher
 * confirmed some windows. Now those are merged into time ranges (or, if none were confirmed live, the whole recording is searched
 * once), Gemma-4 writes the answer from the best picture, and the answer goes to the chat with a tappable time range.
 */
class LookAnalysis(
    private val engine: SmritiEngine,
    private val chat: ChatRepository,
    private val settings: StateFlow<AppSettings?>,
    private val haptics: Haptics,
    private val open: (MomentRef) -> Unit
) {
    suspend fun run(session: CameraSessionEntity, confirmed: List<VisualEntity>) {
        val target = session.target
        PipelineBus.info("look", "analysing the recording for \"$target\" (${confirmed.size} confirmed live)")
        chat.add("event", "Looking for \"$target\": checking your recording...", session.source, live = true)

        val found: List<MomentSearch.Found> =
            if (confirmed.isNotEmpty()) {
                val byId = confirmed.associateBy { it.id }
                RangeMerge.merge(confirmed.map { RangeMerge.Win(it.id, it.sessionId, it.timestamp, if (it.endAt > it.timestamp) it.endAt else it.timestamp + SessionRules.WINDOW_MS, 1f) })
                    .mapNotNull { r -> byId[r.bestId]?.let { MomentSearch.Found(r, it, r.ids.mapNotNull { id -> byId[id] }) } }
            } else engine.momentSearch.find(target, sessionId = session.id)

        val describe = { f: MomentSearch.Found -> "${SessionRules.clock(f.range.start - session.startedAt)} - ${SessionRules.clock(f.range.end - session.startedAt)} into the recording" }
        val refs = found.take(3).map { f ->
            MomentRef(session.id, f.range.start - session.startedAt, f.range.end - session.startedAt, SessionRules.label(session.startedAt, session.endedAt))
        }
        val text: String
        var image: String? = null
        if (found.isEmpty()) {
            text = "I didn't see \"$target\" in this recording. Try again and move the camera slowly around the place."
            haptics.warn()
        } else {
            val said = engine.momentSearch.answer("Where is $target?", found, describe).text
            text = said?.takeIf { it.isNotBlank() } ?: "I saw \"$target\" ${describe(found.first())}."
            image = engine.visual.fileFor(found.first().best.thumb).absolutePath
            haptics.confirm()
        }
        engine.saveSessionResult(session.id, text)
        chat.add("smriti", text, session.source, live = true, imagePath = image, refs = MomentRef.encode(refs.ifEmpty { listOf(MomentRef(session.id, 0, (session.endedAt - session.startedAt), "Open the recording")) }))
        if (settings.value?.voiceReply == true) TtsManager.speak(text.lineSequence().first().take(220), engine.speakTag())
        refs.firstOrNull()?.let(open)
    }
}
