package com.smriti.app.models

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

enum class Role { DETECTOR, LABELER, OCR, CAPTIONER }

/** What the dashboard shows for each candidate model. */
data class ModelSpec(
    val id: String,
    val role: Role,
    val name: String,
    val backend: String,
    val note: String,
    /** false = listed so judges can see the roadmap, but cannot be selected yet */
    val available: Boolean = true
)

data class DetectedObject(val label: String, val confidence: Float, val box: RectF)
data class Label(val text: String, val confidence: Float)

/** Everything the pipeline needs from a model, so an NPU model can be dropped in behind the same role. */
interface VisionModel {
    val spec: ModelSpec
    suspend fun load() {}
    fun close() {}
}

interface DetectorModel : VisionModel {
    suspend fun detect(frame: Bitmap): List<DetectedObject>
}

interface LabelerModel : VisionModel {
    suspend fun label(frame: Bitmap): List<Label>
}

interface OcrModel : VisionModel {
    /** Returns only lines the recognizer is confident about, so blurry screens do not become "notes". */
    suspend fun read(frame: Bitmap): String
}

// ---------------------------------------------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------------------------------------------

object ModelCatalog {
    private const val ML = "ML Kit (CPU/GPU, managed)"

    val specs: List<ModelSpec> = listOf(
        ModelSpec("mp-efficientdet", Role.DETECTOR, "EfficientDet-Lite2 (COCO 80)", "MediaPipe (CPU, bundled)", "80 everyday objects with boxes: bottle, cup, phone, book, remote, scissors..."),
        ModelSpec("mlkit-objects", Role.DETECTOR, "ML Kit object detector", ML, "boxes + 5 coarse categories"),
        ModelSpec("mlkit-labeler-balanced", Role.LABELER, "ML Kit image labeler (balanced)", ML, "400+ labels, confidence 0.6"),
        ModelSpec("mlkit-labeler-strict", Role.LABELER, "ML Kit image labeler (strict)", ML, "same model, confidence 0.8: fewer, safer labels"),
        ModelSpec("mlkit-ocr", Role.OCR, "ML Kit text recognizer (Latin)", ML, "reads signs and screens"),
        ModelSpec("npu-vlm", Role.CAPTIONER, "NPU vision-language captioner", "Hexagon NPU", "pending the iQOO 15 benchmark", available = false)
    )

    fun byRole(role: Role) = specs.filter { it.role == role }
    fun find(id: String) = specs.firstOrNull { it.id == id }

    fun create(id: String): VisionModel = when (id) {
        "mp-efficientdet" -> MediaPipeDetector(find(id)!!)
        "mlkit-objects" -> MlKitObjectDetector(find(id)!!)
        "mlkit-labeler-balanced" -> MlKitLabeler(find(id)!!, 0.6f)
        "mlkit-labeler-strict" -> MlKitLabeler(find(id)!!, 0.8f)
        "mlkit-ocr" -> MlKitOcr(find(id)!!)
        else -> error("No runtime for model '$id'")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// ML Kit implementations (bundled models, work offline)
// ---------------------------------------------------------------------------------------------------------------

/**
 * COCO detector (EfficientDet-Lite2) through MediaPipe Tasks, the same model the AssembleFix app ships.
 * Gives real class names for everyday things, which ML Kit's coarse 5-category detector cannot.
 */
class MediaPipeDetector(override val spec: ModelSpec, private val context: android.content.Context = com.smriti.app.SmritiApp.appContext) : DetectorModel {
    private var detector: com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector? = null

    override suspend fun load() {
        val hasAsset = runCatching {
            context.assets.open("models/efficientdet_lite2.tflite").use { true }
        }.getOrDefault(false)
        if (!hasAsset) {
            throw java.io.FileNotFoundException("Asset models/efficientdet_lite2.tflite not found in APK assets")
        }
        val base = com.google.mediapipe.tasks.core.BaseOptions.builder().setModelAssetPath("models/efficientdet_lite2.tflite").build()
        detector = com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.createFromOptions(
            context,
            com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                .setScoreThreshold(0.30f)
                .setMaxResults(12)
                .build()
        )
    }

    override suspend fun detect(frame: Bitmap): List<DetectedObject> {
        val d = detector ?: return emptyList()
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        val result = d.detect(com.google.mediapipe.framework.image.BitmapImageBuilder(frame).build())
        return result.detections().mapNotNull { det ->
            val c = det.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val b = det.boundingBox()
            DetectedObject(c.categoryName().ifBlank { "object" }.lowercase(), c.score(), RectF(b.left / w, b.top / h, b.right / w, b.bottom / h))
        }
    }

    override fun close() { runCatching { detector?.close() }; detector = null }
}

class MlKitObjectDetector(override val spec: ModelSpec) : DetectorModel {
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    override suspend fun detect(frame: Bitmap): List<DetectedObject> {
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        return detector.process(InputImage.fromBitmap(frame, 0)).await().map { o ->
            val best = o.labels.maxByOrNull { it.confidence }
            val b = o.boundingBox
            DetectedObject(
                label = best?.text?.lowercase() ?: "object",
                confidence = best?.confidence ?: 0f,
                box = RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)
            )
        }
    }

    override fun close() = detector.close()
}

class MlKitLabeler(override val spec: ModelSpec, threshold: Float) : LabelerModel {
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(threshold).build())

    override suspend fun label(frame: Bitmap): List<Label> =
        labeler.process(InputImage.fromBitmap(frame, 0)).await().map { Label(it.text.lowercase(), it.confidence) }

    override fun close() = labeler.close()
}

class MlKitOcr(override val spec: ModelSpec) : OcrModel {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun read(frame: Bitmap): String =
        recognizer.process(InputImage.fromBitmap(frame, 0)).await().textBlocks
            .flatMap { it.lines }
            .filter { it.confidence >= MIN_CONFIDENCE && it.text.count(Char::isLetterOrDigit) >= 3 }
            .joinToString(" ") { it.text }

    override fun close() = recognizer.close()

    private companion object {
        const val MIN_CONFIDENCE = 0.8f
    }
}
