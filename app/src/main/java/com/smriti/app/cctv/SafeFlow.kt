package com.smriti.app.cctv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * SafeFlow AI — Predict and Prevent Site Hazards.
 * Monitors areas for developing safety risks: overcrowding, blocked exits,
 * spills/trip hazards, or people entering restricted work zones.
 * Ranks conditions by urgency: CRITICAL, HIGH, MEDIUM, LOW.
 */
class SafeFlow {

    enum class Urgency {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    enum class HazardType {
        OVERCROWDING,
        BLOCKED_EXIT,
        RESTRICTED_ZONE_INTRUSION,
        TRIP_HAZARD,
        LOITERING_AFTER_HOURS,
        ILLEGAL_PARKING_FIRE_LANE,
        UNAUTHORIZED_VEHICLE
    }

    data class HazardAlert(
        val id: String = java.util.UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val camera: String,
        val type: HazardType,
        val urgency: Urgency,
        val title: String,
        val description: String,
        val recommendedAction: String,
        val personCount: Int = 0,
        val vehicleCount: Int = 0,
        val resolved: Boolean = false
    )

    data class ZoneRules(
        val maxPersons: Int = 6,
        val maxVehicles: Int = 2,
        val restrictedHoursStart: Int = 20, // 8 PM
        val restrictedHoursEnd: Int = 6,    // 6 AM
        val restrictedZone: Boolean = false,
        val isFireLane: Boolean = false
    )

    private val _hazards = MutableStateFlow<List<HazardAlert>>(emptyList())
    val hazards: StateFlow<List<HazardAlert>> = _hazards.asStateFlow()

    private val zoneRules = mapOf(
        "Camera 1: Gate & Driveway" to ZoneRules(maxPersons = 4, maxVehicles = 2, isFireLane = true),
        "Camera 2: Parking Area" to ZoneRules(maxPersons = 8, maxVehicles = 10),
        "Camera 3: Loading Bay" to ZoneRules(maxPersons = 6, maxVehicles = 3, restrictedZone = true),
        "Camera 4: Reception" to ZoneRules(maxPersons = 6, maxVehicles = 0)
    )

    init {
        seedDemoData()
    }

    /**
     * Inspect a processed frame from a camera for safety hazards.
     */
    fun evaluateSafety(
        camera: String,
        personCount: Int,
        detectedObjects: List<String>,
        vehicleCount: Int = 0,
        vehicles: List<String> = emptyList()
    ): List<HazardAlert> {
        val rules = zoneRules[camera] ?: ZoneRules()
        val alerts = mutableListOf<HazardAlert>()
        val now = System.currentTimeMillis()

        // 1. Overcrowding check
        if (personCount > rules.maxPersons) {
            val ratio = personCount.toFloat() / rules.maxPersons
            val urgency = if (ratio >= 2.0f) Urgency.CRITICAL else if (ratio >= 1.5f) Urgency.HIGH else Urgency.MEDIUM
            alerts.add(
                HazardAlert(
                    camera = camera,
                    type = HazardType.OVERCROWDING,
                    urgency = urgency,
                    title = "Overcrowding Alert ($personCount people)",
                    description = "$personCount people detected in $camera (Zone limit is ${rules.maxPersons}). Risk of bottleneck/stampede.",
                    recommendedAction = "Dispatch floor marshal or open secondary egress lane.",
                    personCount = personCount,
                    vehicleCount = vehicleCount
                )
            )
        }

        // 2. Blocked Exit / Hallway Hazard
        val obstructions = detectedObjects.filter { it in listOf("box", "package", "cart", "ladder", "crate", "furniture") }
        if (obstructions.isNotEmpty() && (camera.contains("Corridor", ignoreCase = true) || camera.contains("Gate", ignoreCase = true))) {
            alerts.add(
                HazardAlert(
                    camera = camera,
                    type = HazardType.BLOCKED_EXIT,
                    urgency = Urgency.HIGH,
                    title = "Exit Route Obstructed",
                    description = "Objects (${obstructions.joinToString()}) detected obstructing emergency walkway/egress in $camera.",
                    recommendedAction = "Immediately clear the walkway per safety protocol.",
                    vehicleCount = vehicleCount
                )
            )
        }

        // 3. Illegal Vehicle Parking in Fire / Emergency Access Lane
        if (rules.isFireLane && vehicleCount > 0) {
            alerts.add(
                HazardAlert(
                    camera = camera,
                    type = HazardType.ILLEGAL_PARKING_FIRE_LANE,
                    urgency = Urgency.CRITICAL,
                    title = "Vehicle Blocking Emergency Fire Lane",
                    description = "$vehicleCount vehicle(s) (${if (vehicles.isNotEmpty()) vehicles.joinToString() else "Detected Vehicle"}) stationary in marked emergency fire access lane in $camera.",
                    recommendedAction = "Dispatch security to notify vehicle owner for immediate relocation.",
                    personCount = personCount,
                    vehicleCount = vehicleCount
                )
            )
        }

        // 4. Restricted Zone Intrusion (After-Hours)
        if (rules.restrictedZone && (personCount > 0 || vehicleCount > 0)) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val isAfterHours = hour >= rules.restrictedHoursStart || hour < rules.restrictedHoursEnd
            if (isAfterHours) {
                alerts.add(
                    HazardAlert(
                        camera = camera,
                        type = HazardType.RESTRICTED_ZONE_INTRUSION,
                        urgency = Urgency.CRITICAL,
                        title = "Unauthorized After-Hours Entry",
                        description = "Unauthorized activity ($personCount person(s), $vehicleCount vehicle(s)) in restricted zone ($camera) during lockdown hours.",
                        recommendedAction = "Verify credentials and dispatch site patrol.",
                        personCount = personCount,
                        vehicleCount = vehicleCount
                    )
                )
            }
        }

        if (alerts.isNotEmpty()) {
            _hazards.update { current ->
                // Avoid rapid spamming of duplicate type in same camera within 30s
                val recent = current.filter { now - it.timestamp < 30_000L }
                val newUnique = alerts.filter { a -> recent.none { it.camera == a.camera && it.type == a.type } }
                (newUnique + current).take(50)
            }
        }

        return alerts
    }

    fun dismissHazard(id: String) {
        _hazards.update { list -> list.filter { it.id != id } }
    }

    private fun seedDemoData() {
        val now = System.currentTimeMillis()
        val m = 60_000L

        _hazards.value = listOf(
            HazardAlert(
                timestamp = now - 4 * m,
                camera = "Camera 1: Gate & Driveway",
                type = HazardType.ILLEGAL_PARKING_FIRE_LANE,
                urgency = Urgency.CRITICAL,
                title = "Vehicle Parked in Emergency Fire Lane",
                description = "White SUV parked directly in front of primary hydrant and emergency gate egress lane.",
                recommendedAction = "Dispatch gate security to request driver move to Parking Bay P-12.",
                vehicleCount = 1
            ),
            HazardAlert(
                timestamp = now - 10 * m,
                camera = "Camera 4: Reception",
                type = HazardType.OVERCROWDING,
                urgency = Urgency.HIGH,
                title = "Reception Lobby Overcrowding (8 people)",
                description = "8 visitors crowded around front registration counter (zone safe limit is 6).",
                recommendedAction = "Activate auxiliary check-in kiosk or direct queue to waiting lounge.",
                personCount = 8
            ),
            HazardAlert(
                timestamp = now - 22 * m,
                camera = "Camera 3: Loading Bay",
                type = HazardType.BLOCKED_EXIT,
                urgency = Urgency.MEDIUM,
                title = "Loading Dock Ramp Pallet Obstruction",
                description = "Unattended wooden pallet resting on pedestrian safety walkway beside Dock 2.",
                recommendedAction = "Instruct warehouse forklift operator to clear staging lane."
            )
        )
    }
}
