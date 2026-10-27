package com.smriti.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.RaceDeep
import com.smriti.app.ui.theme.RaceRed
import kotlin.math.cos
import kotlin.math.sin

/** Two opposite corners cut at 45 degrees: the app's "mechanical" outline, used for the nav bar, tabs and panels. */
class ChamferShape(private val cut: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(c, 0f); lineTo(w, 0f); lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); lineTo(0f, c); close()
        })
    }
}

private val Soft = Color(0x14FFFFFF)

/** Arc gauge with tick marks, a lit sweep that turns red near the limit, and a glowing end dot. */
@Composable
fun GaugeArc(
    fraction: Float, centerText: String, label: String, modifier: Modifier = Modifier,
    size: Dp = 108.dp, color: Color = RaceBlue, sub: String? = null
) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "gauge")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = this.size.minDimension * 0.085f
                val inset = stroke / 2 + this.size.minDimension * 0.06f
                val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val start = 150f
                val sweep = 240f
                // ticks outside the arc
                val cx = this.size.width / 2
                val cy = this.size.height / 2
                val rOuter = this.size.minDimension / 2 - 1f
                for (i in 0..24) {
                    val a = Math.toRadians((start + sweep * i / 24f).toDouble())
                    val long = i % 6 == 0
                    val r1 = rOuter - (if (long) 7f else 4f)
                    drawLine(
                        if (i / 24f <= f) color.copy(alpha = 0.9f) else Color(0x33FFFFFF),
                        Offset(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat()),
                        Offset(cx + (rOuter * cos(a)).toFloat(), cy + (rOuter * sin(a)).toFloat()),
                        strokeWidth = if (long) 3f else 1.5f, cap = StrokeCap.Round
                    )
                }
                drawArc(Soft, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val hot = if (f > 0.85f) RaceRed else color
                drawArc(
                    Brush.sweepGradient(listOf(RaceDeep, color, hot, RaceDeep), Offset(cx, cy)),
                    start, sweep * f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
                )
                // glowing end dot
                val ang = Math.toRadians((start + sweep * f).toDouble())
                val rr = (arcSize.width) / 2
                val dot = Offset(cx + (rr * cos(ang)).toFloat(), cy + (rr * sin(ang)).toFloat())
                drawCircle(hot.copy(alpha = 0.30f), stroke * 1.25f, dot)
                drawCircle(Color.White, stroke * 0.36f, dot)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerText, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.19f).sp, color = MaterialTheme.colorScheme.onSurface)
                if (sub != null) Text(sub, fontSize = (size.value * 0.095f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
    }
}

/** Label, value and a track with a glowing fill and an end dot (same idea as a charge bar). */
@Composable
fun BarMeter(label: String, valueText: String, fraction: Float, modifier: Modifier = Modifier, color: Color = RaceBlue, sub: String? = null) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "bar")
    Column(modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueText, fontWeight = FontWeight.Bold, color = color, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(5.dp))
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Soft, size = size, cornerRadius = r)
            val w = (size.width * f).coerceAtLeast(size.height)
            drawRoundRect(Brush.horizontalGradient(listOf(RaceDeep, color)), size = Size(w, size.height), cornerRadius = r)
            drawCircle(color.copy(alpha = 0.35f), size.height * 0.95f, Offset(w - size.height / 2, size.height / 2))
            drawCircle(Color.White, size.height * 0.28f, Offset(w - size.height / 2, size.height / 2))
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Glowing area chart for a rolling history: grid, smooth line, fill, and a pulsing latest-value dot. */
@Composable
fun AreaChart(values: List<Float>, modifier: Modifier = Modifier, max: Float? = null, color: Color = RaceBlue, height: Dp = 96.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
        }
        if (values.size < 2) return@Canvas
        // fixed scale when the caller knows the range; otherwise zoom to the data so small changes are visible
        val hi0 = values.max()
        val lo0 = values.min()
        val pad = (hi0 - lo0).coerceAtLeast(hi0 * 0.08f).coerceAtLeast(0.5f) * 0.35f
        val top = max ?: (hi0 + pad)
        val lo = if (max == null) (lo0 - pad).coerceAtLeast(0f) else 0f
        val step = w / (values.size - 1)
        fun y(v: Float) = h - ((v - lo) / (top - lo).coerceAtLeast(0.001f)).coerceIn(0f, 1f) * (h - 6f) - 3f
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            if (i == 0) line.moveTo(x, y(v)) else {
                val px = (i - 1) * step
                val mx = (px + x) / 2
                line.cubicTo(mx, y(values[i - 1]), mx, y(v), x, y(v))
            }
        }
        val fill = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.40f), Color.Transparent)))
        drawPath(line, color.copy(alpha = 0.30f), style = Stroke(8f, cap = StrokeCap.Round))   // glow
        drawPath(line, color, style = Stroke(3f, cap = StrokeCap.Round))
        val last = Offset(w, y(values.last()))
        drawCircle(color.copy(alpha = 0.30f), 11f, last)
        drawCircle(Color.White, 4f, last)
    }
}

/** A titled chart tile with the current value on the right. */
@Composable
fun ChartCard(title: String, valueText: String, values: List<Float>, modifier: Modifier = Modifier, max: Float? = null, color: Color = RaceBlue, footnote: String? = null) {
    SmritiCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
            Text(valueText, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color)
        }
        Spacer(Modifier.height(10.dp))
        AreaChart(values, max = max, color = color)
        val lo = values.minOrNull(); val hi = values.maxOrNull()
        Row(Modifier.padding(top = 6.dp)) {
            Text(footnote ?: "last ${values.size} s", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lo != null && hi != null) Text("min ${"%.0f".format(lo)}   max ${"%.0f".format(hi)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Scrollable row of chamfered chips; the selected one is filled. */
@Composable
fun ChamferTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val shape = ChamferShape(8.dp)
            Box(
                Modifier.clip(shape)
                    .background(if (on) Brush.linearGradient(listOf(RaceBlue, RaceDeep)) else Brush.linearGradient(listOf(Color(0x14FFFFFF), Color(0x08FFFFFF))))
                    .border(1.dp, if (on) Color(0x66FFFFFF) else MaterialTheme.colorScheme.outlineVariant, shape)
                    .clickableH { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    label.uppercase(), fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, fontSize = 12.sp,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A big number with a caption, for counters. */
@Composable
fun BigStat(value: String, caption: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
        Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
    }
}
