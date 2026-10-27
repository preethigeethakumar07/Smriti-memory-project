@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.smriti.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smriti.app.move.MapMath
import com.smriti.app.move.Pt
import com.smriti.app.move.Trail
import com.smriti.app.ui.components.glass
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.RaceRed
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// fixed colours, so the three lines can always be told apart whatever the theme (its "good" colour is blue)
private val WalkedColor = Color(0xFF3B8EEA)
private val BackColor = Color(0xFF2FBF71)
private val RouteColor = Color(0xFFF2A93B)

/** How the path map is zoomed and moved; kept by the card so that its buttons can change it too. */
@Stable
class MapViewState {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    fun reset() { zoom = 1f; pan = Offset.Zero }

    /** zooms by [factor] keeping the point under [centroid] where it is ([center] is the middle of the canvas) */
    fun zoomAbout(factor: Float, centroid: Offset, center: Offset) {
        val newZoom = (zoom * factor).coerceIn(0.25f, 16f)
        val eff = newZoom / zoom
        pan = pan * eff + (centroid - center) * (1f - eff)
        zoom = newZoom
    }
}

/**
 * A walk drawn as a path, like a GPS track: where the person went (blue), where they have walked since being led back (green), the way
 * they are being led along (amber, dashed), with an arrow every so often showing which way each line runs, the start, the place they are
 * going to, and a marker for where they are now with the direction they face. North is up (or the map turns so that "ahead" is up),
 * with a scale bar and a grid of round distances. There are no map tiles: the app never goes online, so this is a plan of the walk, not
 * a street map. Two fingers zoom and move it (one finger scrolls the screen it sits in); double-tap fits the whole walk again.
 */
@Composable
fun PathMap(trail: Trail, modifier: Modifier = Modifier, headingUp: Boolean = false, view: MapViewState = remember { MapViewState() }) {
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurface
    val muted = scheme.onSurfaceVariant

    Canvas(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface.copy(alpha = 0.55f))
            // two fingers zoom and move the map; one finger is left alone, so the screen it sits in can still be scrolled
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (z != 1f || p != Offset.Zero) {
                                view.zoomAbout(z, centroid, Offset(size.width / 2f, size.height / 2f))
                                view.pan += p
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { view.reset() }) }
    ) {
        val w = size.width
        val h = size.height
        val zoom = view.zoom
        val pan = view.pan
        val pts = MapMath.extent(trail)
        if (pts.isEmpty()) {
            val t = measurer.measure("Your path is drawn here while you walk", TextStyle(color = muted, fontSize = 13.sp))
            drawText(t, topLeft = Offset((w - t.size.width) / 2f, (h - t.size.height) / 2f))
            return@Canvas
        }

        val here = trail.here
        val heading = trail.headingDeg
        val turn = if (headingUp && heading != null) (-heading).toFloat() else 0f
        // when the map turns, fit it as if it were square so that no point is turned off the screen
        val fitW = if (turn != 0f) min(w, h) else w
        val fitH = if (turn != 0f) min(w, h) else h
        val v = MapMath.fit(pts, fitW.toDouble(), fitH.toDouble(), 30.dp.toPx().toDouble())
        val s = (v.scale * zoom).toFloat()

        fun sx(x: Double) = w / 2f + ((x - v.cx) * s).toFloat() + pan.x
        fun sy(y: Double) = h / 2f - ((y - v.cy) * s).toFloat() + pan.y
        fun at(p: Pt) = Offset(sx(p.x), sy(p.y))
        val pivot = here?.let { at(it) } ?: Offset(w / 2f, h / 2f)
        // where a point of the (turned) map lands on the screen, for the labels, which must not turn with it
        val turnRad = Math.toRadians(turn.toDouble())
        fun turned(o: Offset): Offset {
            val dx = o.x - pivot.x; val dy = o.y - pivot.y
            return Offset(pivot.x + (dx * cos(turnRad) - dy * sin(turnRad)).toFloat(), pivot.y + (dx * sin(turnRad) + dy * cos(turnRad)).toFloat())
        }

        val lineW = 4.dp.toPx()
        val arrowSize = 11.dp.toPx()
        val arrowGap = 60.dp.toPx().toDouble()

        fun arrow(x: Float, y: Float, ang: Float, size: Float, color: Color) {
            val dx = cos(ang); val dy = sin(ang)
            val path = Path().apply {
                moveTo(x + dx * size * 0.7f, y + dy * size * 0.7f)
                lineTo(x - dx * size * 0.5f - dy * size * 0.5f, y - dy * size * 0.5f + dx * size * 0.5f)
                lineTo(x - dx * size * 0.5f + dy * size * 0.5f, y - dy * size * 0.5f - dx * size * 0.5f)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.95f))
            drawPath(path, color, style = Stroke(width = 2f, join = StrokeJoin.Round))
        }

        fun line(points: List<Pt>, color: Color, dashed: Boolean) {
            if (points.size < 2) return
            val path = Path().apply {
                moveTo(sx(points[0].x), sy(points[0].y))
                for (i in 1 until points.size) lineTo(sx(points[i].x), sy(points[i].y))
            }
            drawPath(
                path, color,
                style = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(18f, 12f)) else null)
            )
            for (m in MapMath.arrowMarks(points.map { sx(it.x).toDouble() to sy(it.y).toDouble() }, arrowGap)) arrow(m.x.toFloat(), m.y.toFloat(), m.angle.toFloat(), arrowSize, color)
        }

        rotate(turn, pivot) {
            // a grid of round distances
            val step = MapMath.gridStep(s.toDouble(), 56.0)
            val halfDiag = hypot(w, h) / (2f * s)
            val wx = v.cx - pan.x / s
            val wy = v.cy + pan.y / s
            val grid = ink.copy(alpha = 0.07f)
            for (i in floor((wx - halfDiag) / step).toInt()..ceil((wx + halfDiag) / step).toInt()) {
                val x = sx(i * step)
                drawLine(grid, Offset(x, sy(wy + halfDiag)), Offset(x, sy(wy - halfDiag)), 1f)
            }
            for (j in floor((wy - halfDiag) / step).toInt()..ceil((wy + halfDiag) / step).toInt()) {
                val y = sy(j * step)
                drawLine(grid, Offset(sx(wx - halfDiag), y), Offset(sx(wx + halfDiag), y), 1f)
            }

            // the three lines, each with its arrows
            line(trail.out, WalkedColor, dashed = false)
            line(trail.back, BackColor, dashed = false)
            line(trail.route, RouteColor, dashed = true)
            // no route to follow, only a place to go to: a straight dashed line there
            val dest = trail.dest
            if (trail.route.isEmpty() && dest != null && here != null) line(listOf(here, dest), RouteColor, dashed = true)

            trail.start?.let {
                val o = at(it)
                drawCircle(Color.White, 10.dp.toPx(), o)
                drawCircle(BackColor, 7.dp.toPx(), o)
            }
            if (dest != null) {
                val o = at(dest)
                drawCircle(Color.White, 12.dp.toPx(), o)
                drawCircle(RaceRed, 9.dp.toPx(), o)
            }
            if (here != null) {
                val o = at(here)
                drawCircle(RaceBlue.copy(alpha = 0.16f), maxOf(trail.sigmaM.toFloat() * s, 14.dp.toPx()), o)
                drawCircle(Color.White, 9.dp.toPx(), o)
                drawCircle(RaceBlue, 6.dp.toPx(), o)
                if (heading != null) {
                    val a = Math.toRadians(heading)
                    // north is up on the screen: a heading of 0 points up, 90 points right
                    val ang = atan2f((-cos(a)).toFloat(), sin(a).toFloat())
                    arrow(o.x + (sin(a) * 17.dp.toPx()).toFloat(), o.y - (cos(a) * 17.dp.toPx()).toFloat(), ang, 14.dp.toPx(), RaceBlue)
                }
            }
        }

        // labels and the scale: drawn upright
        val label = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        trail.start?.let { p ->
            val o = turned(at(p)); val t = measurer.measure("Start", label)
            drawText(t, topLeft = Offset(o.x + 12.dp.toPx(), o.y - t.size.height / 2f))
        }
        trail.dest?.let { p ->
            val o = turned(at(p)); val t = measurer.measure(trail.destLabel.ifBlank { "Going here" }, label)
            drawText(t, topLeft = Offset(o.x + 14.dp.toPx(), o.y - t.size.height / 2f))
        }

        val bar = MapMath.barLength(s.toDouble(), 130.dp.toPx().toDouble())
        val barPx = (bar * s).toFloat()
        val bx = 16.dp.toPx(); val by = h - 18.dp.toPx()
        drawLine(ink, Offset(bx, by), Offset(bx + barPx, by), 3f)
        drawLine(ink, Offset(bx, by - 6.dp.toPx()), Offset(bx, by + 2.dp.toPx()), 3f)
        drawLine(ink, Offset(bx + barPx, by - 6.dp.toPx()), Offset(bx + barPx, by + 2.dp.toPx()), 3f)
        val bt = measurer.measure(MapMath.label(bar), TextStyle(color = ink, fontSize = 11.sp))
        drawText(bt, topLeft = Offset(bx, by - 8.dp.toPx() - bt.size.height))

        // north
        val nc = Offset(w - 26.dp.toPx(), 28.dp.toPx())
        drawCircle(ink.copy(alpha = 0.10f), 16.dp.toPx(), nc)
        val na = turnRad
        val tip = Offset(nc.x + (sin(na) * 11.dp.toPx()).toFloat(), nc.y - (cos(na) * 11.dp.toPx()).toFloat())
        drawLine(RaceRed, nc, tip, 3f, cap = StrokeCap.Round)
        val nt = measurer.measure("N", TextStyle(color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold))
        drawText(nt, topLeft = Offset(nc.x - nt.size.width / 2f + (sin(na) * 22.dp.toPx()).toFloat(), nc.y - nt.size.height / 2f - (cos(na) * 22.dp.toPx()).toFloat()))
    }
}

private fun atan2f(y: Float, x: Float): Float = kotlin.math.atan2(y, x)

/** The path drawing in a card, with its controls and a line saying what each colour is. */
@Composable
fun PathCard(title: String, trail: Trail, modifier: Modifier = Modifier, canTurn: Boolean = true) {
    var headingUp by remember { mutableStateOf(false) }
    val view = remember { MapViewState() }
    Column(modifier.fillMaxWidth().glass(18.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (canTurn && trail.headingDeg != null) {
                FilterChip(selected = headingUp, onClick = { headingUp = !headingUp }, label = { Text("Heading up") })
                Spacer(Modifier.width(4.dp))
            }
        }
        PathMap(trail, Modifier.fillMaxWidth().height(300.dp), headingUp, view)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { view.zoomAbout(0.7f, Offset.Zero, Offset.Zero) }) { Text("−  Zoom out") }
            TextButton(onClick = { view.zoomAbout(1.4f, Offset.Zero, Offset.Zero) }) { Text("+  Zoom in") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { view.reset() }) { Text("Fit") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (trail.out.size >= 2) LegendItem(WalkedColor, "where you walked")
            if (trail.back.size >= 2) LegendItem(BackColor, "the way back so far")
            if (trail.route.size >= 2 || trail.dest != null) LegendItem(RouteColor, "the way to go")
        }
        Text("Arrows show the direction. Two fingers zoom, double-tap fits. A plan of the walk, not a street map.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
