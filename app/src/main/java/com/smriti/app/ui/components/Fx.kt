package com.smriti.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.RaceRed

/**
 * A 3D "clay" icon: a lit body (light top-left to deep bottom-right), a glossy highlight, a soft contact shadow
 * and a coloured glow, with the glyph embossed on top. Same colours as the website, so the two read as one product.
 */
@Composable
fun Icon3D(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 48.dp,
    glow: Boolean = true
) {
    val corner = size * 0.30f
    val shape = RoundedCornerShape(corner)
    val light = lerp(tint, Color.White, 0.38f)
    val deep = lerp(tint, Color.Black, 0.55f)
    Box(
        modifier
            .size(size)
            .then(
                if (glow) Modifier.shadow(size * 0.28f, shape, ambientColor = tint, spotColor = tint) else Modifier
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(light, tint, deep), Offset(0f, 0f), Offset(size.value * 3f, size.value * 3f)))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.65f), Color.Transparent, Color.Black.copy(alpha = 0.35f))), shape),
        contentAlignment = Alignment.Center
    ) {
        // glossy highlight across the upper half
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawOval(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), 0f, this.size.height * 0.55f),
                    topLeft = Offset(this.size.width * 0.08f, -this.size.height * 0.28f),
                    size = androidx.compose.ui.geometry.Size(this.size.width * 0.84f, this.size.height * 0.80f)
                )
            }
        )
        // emboss: dark copy offset down-right, then the white glyph
        Icon(icon, null, Modifier.size(size * 0.50f).offset2(1.5.dp, 2.dp), tint = Color.Black.copy(alpha = 0.35f))
        Icon(icon, null, Modifier.size(size * 0.50f), tint = Color.White)
    }
}

private fun Modifier.offset2(x: Dp, y: Dp): Modifier = this.then(Modifier.offset(x, y))

/** The page backdrop: carbon with a cool blue light from the top-right and a faint red one from the bottom-left. */
@Composable
fun RacingBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.3f
    Box(
        modifier.background(scheme.background).drawBehind {
            if (dark) {
                drawCircle(
                    Brush.radialGradient(listOf(RaceBlue.copy(alpha = 0.22f), Color.Transparent), Offset(size.width * 0.95f, size.height * 0.02f), size.width * 0.9f),
                    radius = size.width * 0.9f, center = Offset(size.width * 0.95f, size.height * 0.02f)
                )
                drawCircle(
                    Brush.radialGradient(listOf(RaceRed.copy(alpha = 0.12f), Color.Transparent), Offset(size.width * 0.0f, size.height * 0.98f), size.width * 0.8f),
                    radius = size.width * 0.8f, center = Offset(size.width * 0.0f, size.height * 0.98f)
                )
            }
        }
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides scheme.onBackground
        ) { content() }
    }
}

private fun Color.luminance(): Float = (0.2126f * red + 0.7152f * green + 0.0722f * blue)

/** Clickable that also gives a light haptic tick (strength and on/off come from Settings). */
fun Modifier.clickableH(onClick: () -> Unit): Modifier = this.composed {
    val h = com.smriti.app.util.LocalHaptics.current
    this.clickable { h?.click(); onClick() }
}

/** A breathing glow ring, used behind the mic and the live indicator. */
@Composable
fun PulseRing(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, ringSize: Dp = 96.dp) {
    val t = rememberInfiniteTransition(label = "ring")
    val s by t.animateFloat(0.82f, 1.12f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "s")
    val a by t.animateFloat(0.10f, 0.32f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "a")
    Canvas(modifier.size(ringSize)) {
        val r = this.size.minDimension / 2 * s
        drawCircle(color.copy(alpha = a), radius = r)
        drawCircle(color.copy(alpha = a * 1.6f), radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
}

/** Glass panel: translucent, lit border (bright at the top-left), soft depth shadow. */
fun Modifier.glass(corner: Dp = 18.dp, tint: Color = Color.White): Modifier {
    val shape = RoundedCornerShape(corner)
    // no elevation shadow: on translucent fills it draws a visible box inside the panel on some phones
    return this
        .clip(shape)
        .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.10f), tint.copy(alpha = 0.03f))))
        .border(1.dp, Brush.linearGradient(listOf(tint.copy(alpha = 0.40f), tint.copy(alpha = 0.05f), tint.copy(alpha = 0.18f))), shape)
}
