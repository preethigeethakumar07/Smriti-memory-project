package com.smriti.app.perception

/** What one frame turned into: text only, safe to store. */
data class Perception(
    val caption: String,
    val objects: List<String>,
    val ocrText: String,
    val backend: String,
    /** object -> "on the table, next to the cup" (relations seen in this frame) */
    val where: Map<String, String> = emptyMap(),
    /** best guess of the room, "" when unknown */
    val room: String = "",
    /** how much the picture changed since the last analysed frame, 0..1 */
    val motion: Float = 0f,
    /** how many related things were in view, 0..1 */
    val density: Float = 0f,
    /** object -> estimated position in the room, for things the detector boxed while the phone orientation was known */
    val spatial: Map<String, com.smriti.app.spatial.Point3> = emptyMap()
)
