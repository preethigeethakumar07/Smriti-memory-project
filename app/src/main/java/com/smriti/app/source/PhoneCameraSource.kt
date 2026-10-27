package com.smriti.app.source

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.os.Handler
import android.os.Looper
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.smriti.app.core.FrameRates
import com.smriti.app.pipeline.EventType
import com.smriti.app.pipeline.PipelineBus
import com.smriti.app.pipeline.PipelineEvent
import com.smriti.app.pipeline.Stage
import com.smriti.app.pipeline.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** What the UI and the dashboard can say about the camera. */
data class CameraStatus(val state: String = "off", val error: String? = null, val retries: Int = 0) {
    val usable: Boolean get() = state == "open"
}

/**
 * Phone camera, bound once for the whole session so background memory keeps running when the user
 * is on another tab. The on-screen preview is attached only while the Home tab is visible.
 *
 * Failures are reported, not swallowed: bind errors and CameraX state errors become events and a
 * [status], and a recoverable failure (camera busy in another app, for example) is retried with backoff.
 */
class PhoneCameraSource(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
        )
        .build()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
        )
        .build()

    private val _status = MutableStateFlow(CameraStatus())
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    @Volatile var running = false
        private set

    private var owner: LifecycleOwner? = null
    private var onFrame: ((ImageProxy) -> Unit)? = null
    private var camera: Camera? = null
    private var retries = 0
    private val stateObserver = Observer<CameraState> { s -> onCameraState(s) }

    fun start(owner: LifecycleOwner, onFrame: (ImageProxy) -> Unit) {
        if (running) return
        this.owner = owner
        this.onFrame = onFrame
        retries = 0
        running = true
        bind()
    }

    /** the frame rates this camera offers, read once */
    private val offeredRates: List<FrameRates.Range> by lazy {
        runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { FrameRates.Range(it.lower, it.upper) }
        }.getOrDefault(emptyList())
    }
    @Volatile private var wantFps = 0

    /**
     * Asks the camera for about [fps] frames a second (the nearest the device offers). The camera service was measured at 90 to 130 %
     * of a CPU core at full rate, so Home runs it at 10 fps and Live at 24. It can be changed while the camera is open.
     */
    fun setFrameRate(fps: Int) {
        wantFps = fps
        main.post { applyFrameRate() }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFrameRate() {
        val c = camera ?: return
        val want = wantFps
        if (want <= 0) return
        val r = FrameRates.pick(offeredRates, want) ?: return
        runCatching {
            Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(r.lower, r.upper)).build()
            )
            PipelineBus.info("camera", "frame rate ${r.lower}-${r.upper} fps")
        }.onFailure { report("frame rate not applied: ${it.message}") }
    }

    /** Torch through the camera that is already open (a second app-level torch request would be refused while CameraX holds the camera). */
    fun setTorch(on: Boolean): Boolean {
        val c = camera ?: return false
        if (!c.cameraInfo.hasFlashUnit()) return false
        c.cameraControl.enableTorch(on)
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        camera?.cameraInfo?.cameraState?.removeObserver(stateObserver)
        camera = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                analysis.clearAnalyzer()
                future.get().unbindAll()
            }.onFailure { report("unbind failed: ${it.message}") }
            publish("off")
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind() {
        val o = owner ?: return
        val cb = onFrame ?: return
        publish("opening")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running) return@addListener
            try {
                val provider = future.get()
                analysis.setAnalyzer(executor) { proxy -> cb(proxy) }
                provider.unbindAll()
                camera?.cameraInfo?.cameraState?.removeObserver(stateObserver)
                camera = provider.bindToLifecycle(o, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera?.cameraInfo?.cameraState?.observe(o, stateObserver)
                applyFrameRate()
            } catch (e: Throwable) {
                report("bind failed: ${e.javaClass.simpleName}: ${e.message}")
                scheduleRetry("bind failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun onCameraState(s: CameraState) {
        val err = s.error
        if (err != null) {
            val text = "camera error ${errorName(err.code)}"
            report(text)
            publish("error", text)
            // a recoverable error (camera in use, disabled by policy, fatal) is retried; CameraX also self-heals some
            scheduleRetry(text)
            return
        }
        when (s.type) {
            CameraState.Type.OPEN -> { retries = 0; publish("open") }
            CameraState.Type.OPENING, CameraState.Type.PENDING_OPEN -> publish("opening")
            CameraState.Type.CLOSING, CameraState.Type.CLOSED -> if (running) publish("closed")
        }
    }

    private fun scheduleRetry(reason: String) {
        if (!running) return
        if (retries >= MAX_RETRIES) {
            report("giving up after $retries retries ($reason)")
            return
        }
        retries++
        val delay = 1500L * retries
        PipelineBus.warn("camera", "retry $retries in ${delay} ms ($reason)")
        main.postDelayed({ if (running) bind() }, delay)
    }

    private fun publish(state: String, error: String? = null) {
        if (_status.value.state == state && _status.value.error == error) return
        _status.value = CameraStatus(state, error, retries)
        PipelineBus.emit(
            PipelineEvent(
                EventType.CAMERA, stage = Stage.CAMERA,
                status = when (state) { "open" -> Status.OK; "error" -> Status.ERROR; else -> Status.START },
                message = state + (error?.let { ": $it" } ?: ""), data = mapOf("state" to state, "retries" to retries)
            )
        )
    }

    private fun report(message: String) = PipelineBus.error("camera", message)

    private fun errorName(code: Int) = when (code) {
        CameraState.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "OTHER_RECOVERABLE"
        CameraState.ERROR_STREAM_CONFIG -> "STREAM_CONFIG"
        CameraState.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "CAMERA_FATAL"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "DO_NOT_DISTURB"
        else -> "CODE_$code"
    }

    /** Main thread. Pass null when the preview view leaves the screen. */
    fun attachPreview(view: PreviewView?) {
        preview.surfaceProvider = view?.surfaceProvider
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
