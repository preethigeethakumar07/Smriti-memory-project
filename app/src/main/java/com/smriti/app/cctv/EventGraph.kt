package com.smriti.app.cctv

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * EventGraph AI — CCTV with Connected Event Memory.
 * Builds a timeline connecting people, objects, locations, and actions across cameras and time.
 * Traces object and person journeys, linking observations across multiple camera zones.
 */
class EventGraph {

    data class EventNode(
        val id: String = java.util.UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val camera: String,
        val entityId: String,       // e.g. "person_#1", "blue_bag_#3", "parcel_#2"
        val entityType: String,     // "person", "bag", "package", "laptop", etc.
        val action: String,         // "ENTERED", "STATIONARY", "PICKED_UP", "PLACED", "EXITED"
        val details: String,
        val confidence: Float = 0.9f,
        val thumbnail: Bitmap? = null
    )

    data class EntityJourney(
        val entityId: String,
        val entityType: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val currentCamera: String,
        val path: List<EventNode>,
        val status: String = "ACTIVE"   // "ACTIVE", "DEPARTED", "STATIONARY"
    )

    private val _events = MutableStateFlow<List<EventNode>>(emptyList())
    val events: StateFlow<List<EventNode>> = _events.asStateFlow()

    private val _journeys = MutableStateFlow<Map<String, EntityJourney>>(emptyMap())
    val journeys: StateFlow<Map<String, EntityJourney>> = _journeys.asStateFlow()

    private val maxEvents = 200

    init {
        // Seed initial realistic events for immediate demonstration
        seedDemoData()
    }

    /**
     * Record a new event observation from a camera feed.
     */
    fun recordEvent(
        camera: String,
        entityType: String,
        entityId: String,
        action: String,
        details: String,
        confidence: Float = 0.92f,
        thumbnail: Bitmap? = null
    ) {
        val node = EventNode(
            camera = camera,
            entityId = entityId,
            entityType = entityType,
            action = action,
            details = details,
            confidence = confidence,
            thumbnail = thumbnail
        )

        _events.update { list -> (listOf(node) + list).take(maxEvents) }

        _journeys.update { map ->
            val existing = map[entityId]
            val updatedJourney = if (existing == null) {
                EntityJourney(
                    entityId = entityId,
                    entityType = entityType,
                    firstSeenAt = node.timestamp,
                    lastSeenAt = node.timestamp,
                    currentCamera = camera,
                    path = listOf(node),
                    status = if (action == "EXITED") "DEPARTED" else "ACTIVE"
                )
            } else {
                existing.copy(
                    lastSeenAt = node.timestamp,
                    currentCamera = camera,
                    path = existing.path + node,
                    status = if (action == "EXITED") "DEPARTED" else "ACTIVE"
                )
            }
            map + (entityId to updatedJourney)
        }
    }

    /**
     * Trace an object or person's entire journey across cameras.
     * e.g., "The parcel left at reception — where did it go?"
     */
    fun traceEntity(entityQuery: String): List<EntityJourney> {
        val q = entityQuery.lowercase().trim()
        return _journeys.value.values.filter { j ->
            j.entityId.lowercase().contains(q) || j.entityType.lowercase().contains(q)
        }.sortedByDescending { it.lastSeenAt }
    }

    /**
     * Find the last confirmed sighting of an item or person.
     */
    fun lastSighting(entityQuery: String): EventNode? {
        val q = entityQuery.lowercase().trim()
        return _events.value.firstOrNull { e ->
            e.entityId.lowercase().contains(q) ||
            e.entityType.lowercase().contains(q) ||
            e.details.lowercase().contains(q)
        }
    }

    private fun seedDemoData() {
        val now = System.currentTimeMillis()
        val m = 60_000L
        val demoEvents = listOf(
            EventNode(
                timestamp = now - 25 * m,
                camera = "Camera 1: Gate & Driveway",
                entityId = "vehicle_#KA-03-9122",
                entityType = "vehicle",
                action = "ENTERED",
                details = "White SUV (#KA-03-9122) entered main perimeter gate",
                confidence = 0.96f
            ),
            EventNode(
                timestamp = now - 21 * m,
                camera = "Camera 2: Parking Area",
                entityId = "vehicle_#KA-03-9122",
                entityType = "vehicle",
                action = "PARKED",
                details = "White SUV parked in Bay P-14; driver (person_#15) exited vehicle",
                confidence = 0.94f
            ),
            EventNode(
                timestamp = now - 18 * m,
                camera = "Camera 4: Reception",
                entityId = "person_#15",
                entityType = "person",
                action = "ENTERED",
                details = "Driver of White SUV (person_#15) checked in at Reception counter",
                confidence = 0.91f
            ),
            EventNode(
                timestamp = now - 14 * m,
                camera = "Camera 1: Gate & Driveway",
                entityId = "vehicle_#DL-01-4402",
                entityType = "vehicle",
                action = "ENTERED",
                details = "Blue Delivery Van (#DL-01-4402) entered via delivery lane",
                confidence = 0.95f
            ),
            EventNode(
                timestamp = now - 10 * m,
                camera = "Camera 3: Loading Bay",
                entityId = "vehicle_#DL-01-4402",
                entityType = "vehicle",
                action = "STATIONARY",
                details = "Delivery Van docked at Loading Dock 2; crew began unloading pallets",
                confidence = 0.97f
            ),
            EventNode(
                timestamp = now - 6 * m,
                camera = "Camera 4: Reception",
                entityId = "parcel_#104",
                entityType = "parcel",
                action = "PLACED",
                details = "Brown delivery parcel deposited at reception intake desk",
                confidence = 0.93f
            )
        )

        _events.value = demoEvents
        _journeys.value = mapOf(
            "vehicle_#KA-03-9122" to EntityJourney(
                entityId = "vehicle_#KA-03-9122",
                entityType = "vehicle",
                firstSeenAt = now - 25 * m,
                lastSeenAt = now - 21 * m,
                currentCamera = "Camera 2: Parking Area",
                path = demoEvents.filter { it.entityId == "vehicle_#KA-03-9122" },
                status = "STATIONARY"
            ),
            "vehicle_#DL-01-4402" to EntityJourney(
                entityId = "vehicle_#DL-01-4402",
                entityType = "vehicle",
                firstSeenAt = now - 14 * m,
                lastSeenAt = now - 10 * m,
                currentCamera = "Camera 3: Loading Bay",
                path = demoEvents.filter { it.entityId == "vehicle_#DL-01-4402" },
                status = "ACTIVE"
            ),
            "person_#15" to EntityJourney(
                entityId = "person_#15",
                entityType = "person",
                firstSeenAt = now - 21 * m,
                lastSeenAt = now - 18 * m,
                currentCamera = "Camera 4: Reception",
                path = demoEvents.filter { it.entityId == "person_#15" || it.details.contains("person_#15") },
                status = "ACTIVE"
            ),
            "parcel_#104" to EntityJourney(
                entityId = "parcel_#104",
                entityType = "parcel",
                firstSeenAt = now - 6 * m,
                lastSeenAt = now - 6 * m,
                currentCamera = "Camera 4: Reception",
                path = demoEvents.filter { it.entityId == "parcel_#104" },
                status = "STATIONARY"
            )
        )
    }
}
