package com.smriti.app.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.net.HttpURLConnection
import java.net.URL

enum class LinkState { OFF, CONNECTING, LIVE, ERROR }

data class GlassesStatus(
    val state: LinkState = LinkState.OFF,
    val fps: Float = 0f,
    val latencyMs: Long = 0,
    val frames: Long = 0,
    val bytes: Long = 0,
    val lastFrameAt: Long = 0,
    val message: String = "Not connected"
)

/**
 * ESP32-CAM glasses over the local network. Polls the stock CameraWebServer `/capture` JPEG endpoint
 * (about 2 fps, robust to Wi-Fi hiccups) and feeds each frame into the same pipeline as the phone
 * camera. Every connection is vetted by [LanGuard]: a non-local host is refused before any network call.
 */
class Esp32Source(
    private val scope: CoroutineScope,
    private val onFrame: (Bitmap) -> Unit
) {
    private val _status = MutableStateFlow(GlassesStatus())
    val status: StateFlow<GlassesStatus> = _status.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private var job: Job? = null

    fun start(host: String, port: Int, path: String) {
        stop()
        val addr = LanGuard.resolveAllowed(host)
        if (addr == null) {
            _status.value = GlassesStatus(LinkState.ERROR, message = "Refused: \"$host\" is not a local address. Smriti only talks to devices on your own network.")
            return
        }
        val url = URL("http", addr.hostAddress, port, if (path.startsWith("/")) path else "/$path")
        _status.value = GlassesStatus(LinkState.CONNECTING, message = "Connecting to ${addr.hostAddress}...")
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            var frames = 0L
            var bytes = 0L
            val windowStart = SystemClock.elapsedRealtime()
            while (isActive) {
                val t0 = SystemClock.elapsedRealtime()
                val jpeg = runCatching { fetch(url) }
                val data = jpeg.getOrNull()
                val bmp = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp != null) {
                    backoff = 1000L
                    frames++
                    bytes += data.size
                    val now = SystemClock.elapsedRealtime()
                    _preview.value = bmp
                    _status.update {
                        GlassesStatus(
                            LinkState.LIVE,
                            fps = frames * 1000f / (now - windowStart).coerceAtLeast(1),
                            latencyMs = now - t0, frames = frames, bytes = bytes, lastFrameAt = now,
                            message = "Live from ${addr.hostAddress}"
                        )
                    }
                    onFrame(bmp)
                    delay(350)
                } else {
                    _status.update {
                        it.copy(
                            state = LinkState.ERROR,
                            message = "No frame from ${addr.hostAddress}:$port (${jpeg.exceptionOrNull()?.message ?: "bad image"}). Retrying..."
                        )
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(5000L)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _preview.value = null
        _status.value = GlassesStatus()
    }

    private fun fetch(url: URL): ByteArray {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2000
            c.readTimeout = 3000
            c.requestMethod = "GET"
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** One-off reachability test for the "Test connection" button. */
    suspend fun test(host: String, port: Int, path: String): String {
        val addr = LanGuard.resolveAllowed(host)
            ?: return "Refused: not a local address."
        val url = URL("http", addr.hostAddress, port, if (path.startsWith("/")) path else "/$path")
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            runCatching { fetch(url) }.fold(
                onSuccess = { "OK: ${it.size / 1024} KB image in ${SystemClock.elapsedRealtime() - t0} ms" },
                onFailure = { "Failed: ${it.message}" }
            )
        }
    }
}
