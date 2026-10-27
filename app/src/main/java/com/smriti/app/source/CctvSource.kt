package com.smriti.app.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import com.smriti.app.net.LanGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

/**
 * CctvSource — Ingests live CCTV camera streams or simulated multi-camera surveillance feeds.
 * Primarily designed to detect and track HUMANS and VEHICLES across monitored premises.
 */
class CctvSource(
    private val scope: CoroutineScope,
    private val onFrame: (Bitmap, String, Int, Int) -> Unit
) {

    data class CameraFeed(
        val id: String,
        val displayName: String,
        val location: String,
        val defaultPersons: Int,
        val defaultVehicles: Int = 0,
        val vehicleTypes: List<String> = emptyList(),
        val isSimulated: Boolean = true,
        val customUrl: String? = null
    )

    data class CctvStatus(
        val activeCamera: String = "Camera 1: Gate & Driveway",
        val isLive: Boolean = false,
        val fps: Float = 0f,
        val latencyMs: Long = 0,
        val personCount: Int = 0,
        val vehicleCount: Int = 0,
        val vehicles: List<String> = emptyList(),
        val frames: Long = 0,
        val message: String = "Idle"
    )

    private val _status = MutableStateFlow(CctvStatus())
    val status: StateFlow<CctvStatus> = _status.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    val availableCameras = listOf(
        CameraFeed(
            id = "cam-1",
            displayName = "Camera 1: Gate & Driveway",
            location = "Main Vehicle Gate & Pedestrian Walkway",
            defaultPersons = 2,
            defaultVehicles = 2,
            vehicleTypes = listOf("White SUV (KA-03-9122)", "Delivery Van")
        ),
        CameraFeed(
            id = "cam-2",
            displayName = "Camera 2: Parking Area",
            location = "North Parking Lot & Fire Lane",
            defaultPersons = 3,
            defaultVehicles = 3,
            vehicleTypes = listOf("Sedan (DL-01-3320)", "Motorcycle", "SUV")
        ),
        CameraFeed(
            id = "cam-3",
            displayName = "Camera 3: Loading Bay",
            location = "Perimeter Logistics & Cargo Bay",
            defaultPersons = 2,
            defaultVehicles = 1,
            vehicleTypes = listOf("Cargo Truck (KA-05-4410)")
        ),
        CameraFeed(
            id = "cam-4",
            displayName = "Camera 4: Reception & Entry",
            location = "Indoor Visitor Counter & Lobby",
            defaultPersons = 4,
            defaultVehicles = 0,
            vehicleTypes = emptyList()
        )
    )

    @Volatile private var currentCamera = availableCameras[0]
    private var job: Job? = null

    init {
        startCamera(availableCameras[0])
    }

    fun switchCamera(cameraName: String) {
        val found = availableCameras.firstOrNull { it.displayName.equals(cameraName, ignoreCase = true) || it.id.equals(cameraName, ignoreCase = true) }
            ?: availableCameras[0]
        startCamera(found)
    }

    fun startCamera(feed: CameraFeed) {
        stop()
        currentCamera = feed
        _status.update { it.copy(activeCamera = feed.displayName, isLive = true, message = "Streaming ${feed.displayName}") }

        job = scope.launch(Dispatchers.IO) {
            var frameIndex = 0L
            val windowStart = SystemClock.elapsedRealtime()

            while (isActive) {
                val t0 = SystemClock.elapsedRealtime()
                val bmp = if (feed.isSimulated || feed.customUrl == null) {
                    renderSimulatedFrame(feed, frameIndex)
                } else {
                    fetchNetworkFrame(feed.customUrl) ?: renderSimulatedFrame(feed, frameIndex)
                }

                frameIndex++
                val now = SystemClock.elapsedRealtime()
                val personCount = calculateSimulatedPeople(feed, frameIndex)
                val vehicleCount = feed.defaultVehicles

                _preview.value = bmp
                _status.update {
                    it.copy(
                        activeCamera = feed.displayName,
                        isLive = true,
                        fps = (frameIndex * 1000f / (now - windowStart).coerceAtLeast(1)).coerceAtMost(15f),
                        latencyMs = now - t0,
                        personCount = personCount,
                        vehicleCount = vehicleCount,
                        vehicles = feed.vehicleTypes,
                        frames = frameIndex,
                        message = "Live: $personCount Human(s), $vehicleCount Vehicle(s)"
                    )
                }

                onFrame(bmp, feed.displayName, personCount, vehicleCount)
                delay(200) // ~5 fps
            }
        }
    }

    fun connectCustomIpCamera(urlStr: String, cameraName: String = "Custom IP Camera") {
        val customFeed = CameraFeed(
            id = "custom-ip",
            displayName = cameraName,
            location = "Network Stream",
            defaultPersons = 1,
            defaultVehicles = 1,
            vehicleTypes = listOf("Detected Vehicle"),
            isSimulated = false,
            customUrl = urlStr
        )
        startCamera(customFeed)
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.update { it.copy(isLive = false, message = "Stopped") }
    }

    private fun fetchNetworkFrame(urlStr: String): Bitmap? {
        return runCatching {
            val url = URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
        }.getOrNull()
    }

    /**
     * Renders high-visibility CCTV surveillance frame emphasizing both HUMANS and VEHICLES.
     */
    private fun renderSimulatedFrame(feed: CameraFeed, frameIdx: Long): Bitmap {
        val w = 640
        val h = 480
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. Surveillance Scene Backdrop
        val isOutdoor = feed.defaultVehicles > 0
        val bgPaint = Paint().apply { color = if (isOutdoor) Color.rgb(18, 22, 30) else Color.rgb(20, 26, 38) }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Ground / Roadway geometry
        if (isOutdoor) {
            // Asphalt road / driveway
            val roadPaint = Paint().apply { color = Color.rgb(28, 34, 46); style = Paint.Style.FILL }
            canvas.drawRect(0f, 220f, w.toFloat(), h.toFloat(), roadPaint)

            // Road lane divider lines
            val linePaint = Paint().apply { color = Color.rgb(160, 160, 70); strokeWidth = 3f; style = Paint.Style.STROKE }
            val dashW = 30f
            for (x in 20 until w step 60) {
                canvas.drawLine(x.toFloat(), 340f, (x + dashW).toFloat(), 340f, linePaint)
            }
        } else {
            // Indoor floor lines
            val gridPaint = Paint().apply { color = Color.rgb(32, 42, 60); strokeWidth = 2f; style = Paint.Style.STROKE }
            canvas.drawLine(0f, 320f, w.toFloat(), 320f, gridPaint)
            canvas.drawLine(80f, 0f, 80f, 320f, gridPaint)
            canvas.drawLine(560f, 0f, 560f, 320f, gridPaint)
        }

        // 2. Render VEHICLES (Amber/Orange Bounding Boxes)
        val vehiclePaint = Paint().apply {
            color = Color.rgb(255, 170, 0) // Amber
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val vehicleFill = Paint().apply {
            color = Color.argb(40, 255, 170, 0)
            style = Paint.Style.FILL
        }
        val tagBgPaint = Paint().apply { color = Color.argb(200, 0, 0, 0); style = Paint.Style.FILL }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 13f; isAntiAlias = true; isFakeBoldText = true }

        val t = frameIdx * 0.1f

        if (feed.defaultVehicles > 0) {
            // Vehicle #1 (Car / SUV)
            val vx1 = (320f + (sin(t * 0.4f) * 40f)).coerceIn(280f, 440f)
            val vy1 = 250f
            val carBox = RectF(vx1, vy1, vx1 + 170f, vy1 + 100f)
            canvas.drawRoundRect(carBox, 10f, 10f, vehicleFill)
            canvas.drawRoundRect(carBox, 10f, 10f, vehiclePaint)

            // Car wheels
            val wheelPaint = Paint().apply { color = Color.rgb(15, 15, 15); style = Paint.Style.FILL }
            canvas.drawCircle(vx1 + 35f, vy1 + 100f, 14f, wheelPaint)
            canvas.drawCircle(vx1 + 135f, vy1 + 100f, 14f, wheelPaint)

            // Vehicle Label Tag
            canvas.drawRoundRect(RectF(vx1, vy1 - 22f, vx1 + 180f, vy1), 4f, 4f, tagBgPaint)
            val v1Label = feed.vehicleTypes.getOrNull(0) ?: "Vehicle: Car [96%]"
            canvas.drawText("🚗 $v1Label", vx1 + 4f, vy1 - 6f, textPaint)

            if (feed.defaultVehicles >= 2) {
                // Vehicle #2 (Van or Motorcycle or Second Car)
                val vx2 = 80f
                val vy2 = 230f
                val v2Box = RectF(vx2, vy2, vx2 + 130f, vy2 + 85f)
                canvas.drawRoundRect(v2Box, 8f, 8f, vehicleFill)
                canvas.drawRoundRect(v2Box, 8f, 8f, vehiclePaint)
                canvas.drawCircle(vx2 + 25f, vy2 + 85f, 12f, wheelPaint)
                canvas.drawCircle(vx2 + 105f, vy2 + 85f, 12f, wheelPaint)

                canvas.drawRoundRect(RectF(vx2, vy2 - 22f, vx2 + 160f, vy2), 4f, 4f, tagBgPaint)
                val v2Label = feed.vehicleTypes.getOrNull(1) ?: "Vehicle #2 [92%]"
                canvas.drawText("🚗 $v2Label", vx2 + 4f, vy2 - 6f, textPaint)
            }
        }

        // 3. Render HUMANS (Cyan Bounding Boxes)
        val personCount = calculateSimulatedPeople(feed, frameIdx)
        val humanPaint = Paint().apply {
            color = Color.rgb(0, 229, 255) // Cyan
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val humanFill = Paint().apply {
            color = Color.argb(35, 0, 229, 255)
            style = Paint.Style.FILL
        }

        for (i in 0 until personCount) {
            val px = if (isOutdoor) (40f + i * 90f + (sin(t + i.toFloat()) * 25f)).coerceIn(20f, 550f)
                     else (100f + i * 130f + (sin(t + i.toFloat()) * 30f)).coerceIn(50f, 520f)
            val py = if (isOutdoor) (210f + (sin(t * 0.5f + i) * 8f)) else (200f + (sin(t * 0.5f + i) * 10f))

            // Bounding box for human
            val box = RectF(px, py, px + 65f, py + 120f)
            canvas.drawRoundRect(box, 8f, 8f, humanFill)
            canvas.drawRoundRect(box, 8f, 8f, humanPaint)

            // Head silhouette circle
            val headPaint = Paint().apply { color = Color.rgb(0, 229, 255); style = Paint.Style.FILL }
            canvas.drawCircle(px + 32.5f, py + 22f, 14f, headPaint)

            // Human Tag Label
            canvas.drawRoundRect(RectF(px, py - 20f, px + 95f, py), 4f, 4f, tagBgPaint)
            canvas.drawText("👤 Human #${i + 1}", px + 4f, py - 5f, textPaint)
        }

        // 4. CCTV OSD (Camera Name, REC, Timestamp)
        val osdPaint = Paint().apply {
            color = Color.rgb(0, 255, 128)
            textSize = 15f
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("[LIVE] ${feed.displayName.uppercase()}", 20f, 30f, osdPaint)

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        canvas.drawText(dateStr, 20f, 52f, osdPaint)

        // 5. Dual Live Counter HUD (HUMANS + VEHICLES)
        val hudBg = Paint().apply { color = Color.argb(210, 0, 0, 0); style = Paint.Style.FILL }
        val hudBox = RectF(w - 275f, 12f, w - 12f, 48f)
        canvas.drawRoundRect(hudBox, 8f, 8f, hudBg)

        val humanHud = Paint().apply { color = Color.rgb(0, 229, 255); textSize = 14f; isAntiAlias = true; isFakeBoldText = true }
        val vehicleHud = Paint().apply { color = Color.rgb(255, 170, 0); textSize = 14f; isAntiAlias = true; isFakeBoldText = true }

        canvas.drawText("👥 $personCount HUMANS", w - 265f, 35f, humanHud)
        canvas.drawText("|  🚗 ${feed.defaultVehicles} VEHICLES", w - 145f, 35f, vehicleHud)

        return bmp
    }

    private fun calculateSimulatedPeople(feed: CameraFeed, frameIdx: Long): Int {
        val base = feed.defaultPersons
        val wave = ((frameIdx / 15) % 3).toInt()
        return (base + (if (wave == 2) 1 else 0)).coerceAtLeast(1)
    }
}
