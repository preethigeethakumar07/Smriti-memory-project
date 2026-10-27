package com.smriti.app.cctv

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * SceneGuard AI — Remember What Changed.
 * Learns the normal baseline state of a monitored area and identifies meaningful changes:
 * missing laptops, newly placed unattended items, open emergency doors, or moved equipment.
 */
class SceneGuard {

    enum class ChangeType {
        MISSING_OBJECT,
        NEW_UNATTENDED_OBJECT,
        DOOR_OR_EXIT_OPEN,
        EQUIPMENT_MOVED
    }

    data class SceneChange(
        val id: String = java.util.UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val camera: String,
        val type: ChangeType,
        val objectName: String,
        val details: String,
        val urgency: String = "MEDIUM", // "LOW", "MEDIUM", "HIGH"
        val beforeAt: Long = 0L,
        val afterAt: Long = System.currentTimeMillis(),
        val beforeThumbnail: Bitmap? = null,
        val afterThumbnail: Bitmap? = null
    )

    data class Baseline(
        val camera: String,
        val establishedAt: Long = System.currentTimeMillis(),
        val objects: Set<String>,
        val personCapacity: Int = 5,
        val exitsNormalState: String = "CLOSED"
    )

    private val _baselines = MutableStateFlow<Map<String, Baseline>>(emptyMap())
    val baselines: StateFlow<Map<String, Baseline>> = _baselines.asStateFlow()

    private val _changes = MutableStateFlow<List<SceneChange>>(emptyList())
    val changes: StateFlow<List<SceneChange>> = _changes.asStateFlow()

    init {
        seedDemoData()
    }

    /**
     * Establish or update the baseline normal state for a camera view.
     */
    fun setBaseline(camera: String, detectedObjects: List<String>) {
        val objects = detectedObjects.map { it.lowercase().trim() }.filter { it.isNotBlank() }.toSet()
        val baseline = Baseline(camera = camera, objects = objects)
        _baselines.update { it + (camera to baseline) }
    }

    /**
     * Compare currently detected objects against the baseline for the camera.
     */
    fun evaluateChanges(camera: String, currentObjects: List<String>, currentThumbnail: Bitmap? = null): List<SceneChange> {
        val baseline = _baselines.value[camera] ?: return emptyList()
        val cur = currentObjects.map { it.lowercase().trim() }.toSet()
        val now = System.currentTimeMillis()
        val detected = mutableListOf<SceneChange>()

        // 1. Missing objects (present in baseline, but absent in current)
        val missing = baseline.objects - cur
        for (item in missing) {
            // Ignore temporary entities like person
            if (item == "person" || item == "face") continue
            val change = SceneChange(
                camera = camera,
                type = ChangeType.MISSING_OBJECT,
                objectName = item,
                details = "$item missing from normal baseline position in $camera",
                urgency = if (item in listOf("laptop", "cash", "keys", "phone", "bag")) "HIGH" else "MEDIUM",
                beforeAt = baseline.establishedAt,
                afterAt = now,
                afterThumbnail = currentThumbnail
            )
            detected.add(change)
        }

        // 2. New unattended objects (not in baseline)
        val newObjects = cur - baseline.objects
        for (item in newObjects) {
            if (item == "person" || item == "face") continue
            val change = SceneChange(
                camera = camera,
                type = ChangeType.NEW_UNATTENDED_OBJECT,
                objectName = item,
                details = "New unattended $item appeared in $camera",
                urgency = if (item in listOf("bag", "backpack", "suitcase", "box", "package")) "HIGH" else "LOW",
                beforeAt = baseline.establishedAt,
                afterAt = now,
                afterThumbnail = currentThumbnail
            )
            detected.add(change)
        }

        if (detected.isNotEmpty()) {
            _changes.update { (detected + it).take(50) }
        }
        return detected
    }

    private fun seedDemoData() {
        val now = System.currentTimeMillis()
        val m = 60_000L

        val receptionBaseline = Baseline(
            camera = "Camera 1: Reception",
            establishedAt = now - 120 * m,
            objects = setOf("laptop", "desk", "chair", "phone", "monitor")
        )

        val labBaseline = Baseline(
            camera = "Camera 3: Office B",
            establishedAt = now - 240 * m,
            objects = setOf("microscope", "terminal", "cabinet", "first_aid_kit")
        )

        _baselines.value = mapOf(
            "Camera 1: Reception" to receptionBaseline,
            "Camera 3: Office B" to labBaseline
        )

        _changes.value = listOf(
            SceneChange(
                timestamp = now - 25 * m,
                camera = "Camera 1: Reception",
                type = ChangeType.MISSING_OBJECT,
                objectName = "laptop",
                details = "Dell XPS Laptop missing from Reception counter since 10:15 AM",
                urgency = "HIGH",
                beforeAt = now - 120 * m,
                afterAt = now - 25 * m
            ),
            SceneChange(
                timestamp = now - 15 * m,
                camera = "Camera 2: Corridor",
                type = ChangeType.DOOR_OR_EXIT_OPEN,
                objectName = "emergency_exit",
                details = "Emergency Fire Exit Door prop opened in Corridor",
                urgency = "HIGH",
                beforeAt = now - 60 * m,
                afterAt = now - 15 * m
            ),
            SceneChange(
                timestamp = now - 5 * m,
                camera = "Camera 1: Reception",
                type = ChangeType.NEW_UNATTENDED_OBJECT,
                objectName = "backpack",
                details = "Unattended black backpack left near main entrance bench for > 15 mins",
                urgency = "HIGH",
                beforeAt = now - 120 * m,
                afterAt = now - 5 * m
            )
        )
    }
}
