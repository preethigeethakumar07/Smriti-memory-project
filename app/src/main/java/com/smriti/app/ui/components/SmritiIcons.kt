package com.smriti.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Smriti's own icons, drawn as vectors so they take the same tint and 3D tile as the Material ones. */
object SmritiIcons {

    /** Spectacles with an AI sparkle above the bridge: the "smart glasses" source. */
    val GlassesAi: ImageVector by lazy {
        ImageVector.Builder("GlassesAi", 24.dp, 24.dp, 24f, 24f).apply {
            val ink = SolidColor(Color.Black)

            // two round lenses
            fun lens(cx: Float, cy: Float, r: Float) = path(stroke = ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(cx - r, cy)
                arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
                arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
                close()
            }
            lens(6.6f, 15.2f, 3.9f)
            lens(17.4f, 15.2f, 3.9f)

            // bridge and temple arms
            path(stroke = ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
                moveTo(10.5f, 14.4f); quadTo(12f, 13.2f, 13.5f, 14.4f)
                moveTo(2.8f, 14.2f); lineTo(1.6f, 11.8f)
                moveTo(21.2f, 14.2f); lineTo(22.4f, 11.8f)
            }

            // the AI sparkle (four-point star) above the bridge, plus a small one
            path(fill = ink) {
                moveTo(12f, 2.0f); lineTo(13.15f, 4.85f); lineTo(16f, 6f); lineTo(13.15f, 7.15f); lineTo(12f, 10f)
                lineTo(10.85f, 7.15f); lineTo(8f, 6f); lineTo(10.85f, 4.85f); close()
            }
            path(fill = ink) {
                moveTo(18.6f, 3.2f); lineTo(19.1f, 4.4f); lineTo(20.3f, 4.9f); lineTo(19.1f, 5.4f); lineTo(18.6f, 6.6f)
                lineTo(18.1f, 5.4f); lineTo(16.9f, 4.9f); lineTo(18.1f, 4.4f); close()
            }
        }.build()
    }

    /** CCTV Surveillance Camera with an AI sparkle: the "CCTV AI" multi-camera source. */
    val CctvCamera: ImageVector by lazy {
        ImageVector.Builder("CctvCamera", 24.dp, 24.dp, 24f, 24f).apply {
            val ink = SolidColor(Color.Black)

            // Wall mounting bracket and pivot arm
            path(stroke = ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Vertical wall base plate
                moveTo(2.5f, 7.5f)
                lineTo(2.5f, 16.5f)
                // Bracket arm extending to camera swivel
                moveTo(2.5f, 12f)
                lineTo(6f, 12f)
                lineTo(8.5f, 9.5f)
            }

            // Top protective sunshade / visor
            path(stroke = ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 7.5f)
                lineTo(19f, 10f)
            }

            // Camera body housing
            path(stroke = ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7.5f, 9.5f)
                lineTo(17.5f, 11.5f)
                lineTo(16.5f, 16f)
                lineTo(8f, 14f)
                close()
            }

            // Front lens rim
            path(stroke = ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17.5f, 11.5f)
                lineTo(19.5f, 12f)
                lineTo(18.5f, 16.5f)
                lineTo(16.5f, 16f)
            }

            // Camera status LED / lens dot
            path(fill = ink) {
                moveTo(12.5f, 12.8f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, 2.2f, 0f)
                arcToRelative(1.1f, 1.1f, 0f, true, true, -2.2f, 0f)
                close()
            }

            // The AI sparkle (four-point star) above the camera
            path(fill = ink) {
                moveTo(20f, 1.5f)
                lineTo(20.9f, 3.4f)
                lineTo(22.8f, 4.3f)
                lineTo(20.9f, 5.2f)
                lineTo(20f, 7.1f)
                lineTo(19.1f, 5.2f)
                lineTo(17.2f, 4.3f)
                lineTo(19.1f, 3.4f)
                close()
            }
        }.build()
    }
}
