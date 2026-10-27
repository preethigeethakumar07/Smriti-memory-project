package com.smriti.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Synthetic frames used to warm every model before the first real frame, and as known inputs for
 * tests. Realistic size and content (shapes + large printed text) so the detector, labeler, face
 * and text paths all do real work, instead of exiting early on an empty image.
 */
object WarmupFrames {

    fun scene(width: Int = 640, height: Int = 480, text: String = "SMRITI HELLO WORLD"): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(235, 235, 230))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = Color.rgb(150, 110, 80)                       // a table-like band
        c.drawRect(0f, height * 0.62f, width.toFloat(), height.toFloat(), p)
        p.color = Color.rgb(60, 90, 160)                        // a book-like block
        c.drawRect(RectF(width * 0.08f, height * 0.40f, width * 0.30f, height * 0.62f), p)
        p.color = Color.rgb(200, 60, 60)                        // a cup-like shape
        c.drawOval(RectF(width * 0.70f, height * 0.42f, width * 0.86f, height * 0.62f), p)
        p.color = Color.rgb(225, 180, 150)                      // a head-like oval
        c.drawOval(RectF(width * 0.42f, height * 0.12f, width * 0.58f, height * 0.40f), p)

        p.color = Color.BLACK
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = height * 0.11f
        c.drawText(text, width * 0.06f, height * 0.88f, p)
        return bmp
    }

    /** White page with large black text: the OCR path should read it reliably. */
    fun textPage(text: String, width: Int = 800, height: Int = 300): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            textSize = height * 0.28f
        }
        c.drawText(text, 24f, height * 0.60f, p)
        return bmp
    }

    fun blank(width: Int = 640, height: Int = 480, color: Int = Color.DKGRAY): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return bmp
    }
}
