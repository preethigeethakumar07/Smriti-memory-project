package com.smriti.app.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

/**
 * Privacy stage that runs BEFORE any model sees the frame and long before anything is stored.
 * Faces are pixelated in a RAM-only copy; we keep only a count ("2 people nearby"), never an
 * identity, embedding or crop.
 */
class Redactor {

    data class Result(val bitmap: Bitmap, val faces: Int)

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.06f)
            .build()
    )

    suspend fun redact(src: Bitmap): Result {
        val faces = detector.process(InputImage.fromBitmap(src, 0)).await()
        if (faces.isEmpty()) return Result(src, 0)
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        for (f in faces) pixelate(out, f.boundingBox)
        return Result(out, faces.size)
    }

    fun close() = detector.close()

    companion object {
        /** Replaces the box (plus 20% padding) with 6x6 blocks, in place. Exposed so tests can verify it directly. */
        fun pixelate(bmp: Bitmap, box: Rect) {
            val padX = (box.width() * 0.2f).toInt()
            val padY = (box.height() * 0.2f).toInt()
            val r = Rect(
                (box.left - padX).coerceAtLeast(0),
                (box.top - padY).coerceAtLeast(0),
                (box.right + padX).coerceAtMost(bmp.width),
                (box.bottom + padY).coerceAtMost(bmp.height)
            )
            if (r.width() < 2 || r.height() < 2) return
            val region = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            val tiny = Bitmap.createScaledBitmap(region, 6, 6, true)
            val blocky = Bitmap.createScaledBitmap(tiny, r.width(), r.height(), false)
            Canvas(bmp).drawBitmap(blocky, r.left.toFloat(), r.top.toFloat(), Paint())
            region.recycle(); tiny.recycle(); blocky.recycle()
        }
    }
}
