package com.smriti.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import com.smriti.app.ui.theme.RaceBlue
import com.smriti.app.ui.theme.RaceDeep
import com.smriti.app.ui.theme.RaceRed
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Smriti eye: an outline plus a pupil that "blinks" as [openness] goes from 0 to 1. */
@Composable
fun SmritiLogo(size: Dp, openness: Float = 1f, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2
        val cy = h / 2
        val rx = w * 0.42f
        val ry = h * 0.26f * openness.coerceIn(0.05f, 1f)
        val path = Path().apply {
            moveTo(cx - rx, cy)
            quadraticTo(cx, cy - ry * 2f, cx + rx, cy)
            quadraticTo(cx, cy + ry * 2f, cx - rx, cy)
            close()
        }
        drawPath(path, color, style = Stroke(width = w * 0.045f, cap = StrokeCap.Round))
        drawCircle(color, radius = w * 0.1f * openness.coerceIn(0.3f, 1f), center = Offset(cx, cy))
    }
}

/** The motorsport tri-stripe (light blue, deep blue, red) used as chrome, never as data colour. */
@Composable
fun TriStripe(modifier: Modifier = Modifier, width: Dp = 44.dp, height: Dp = 3.dp) {
    Row(modifier.width(width).height(height).clip(RoundedCornerShape(2.dp))) {
        Box(Modifier.weight(1f).height(height).background(RaceBlue))
        Box(Modifier.weight(1f).height(height).background(RaceDeep))
        Box(Modifier.weight(1f).height(height).background(RaceRed))
    }
}

/** Icon in a ringed, softly lit tile: the same look as a node on the website's flow diagram. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 44.dp) {
    Icon3D(icon, modifier, tint, size)
}

@Composable
fun SmritiCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .glass(18.dp)
            .background(Brush.verticalGradient(listOf(scheme.surfaceVariant.copy(alpha = 0.85f), scheme.surface.copy(alpha = 0.92f))))
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides scheme.onSurface
        ) { Column(Modifier.padding(16.dp)) { content() } }
    }
}

private fun iconFor(label: String): ImageVector {
    val t = label.lowercase()
    return when {
        "inference" in t -> Icons.Default.Memory
        "compute" in t -> Icons.Default.Speed
        "power" in t -> Icons.Default.BatteryChargingFull
        "camera" in t -> Icons.Default.CameraAlt
        "device sensors" in t -> Icons.Default.Sensors
        "models" in t -> Icons.Default.ViewInAr
        "network" in t -> Icons.Default.Shield
        "profile" in t -> Icons.Default.Person
        "appearance" in t -> Icons.Default.Palette
        "permission" in t -> Icons.Default.VerifiedUser
        "capture" in t -> Icons.Default.Videocam
        "haptic" in t -> Icons.Default.Vibration
        "notification" in t -> Icons.Default.Notifications
        "privacy" in t -> Icons.Default.Lock
        "maintenance" in t -> Icons.Default.Build
        "developer" in t -> Icons.Default.Code
        "about" in t -> Icons.Default.Info
        "live feed" in t -> Icons.Default.Visibility
        "connection" in t -> Icons.Default.Link
        "source" in t -> Icons.Default.SwapHoriz
        "how to" in t -> Icons.Default.Help
        else -> Icons.Default.Bolt
    }
}

/** Section heading: a small 3D icon, the title, and a fading rule. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 2.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon3D(iconFor(text), size = 32.dp, glow = false)
        Spacer(Modifier.width(10.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.weight(1f).height(1.dp)
                .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), Color.Transparent)))
        )
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, accent: Color? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = accent ?: MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tiny history chart. [max] fixes the scale (e.g. 100 for percentages); null autoscales. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, max: Float? = null, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        if (values.size < 2) return@Canvas
        val top = max ?: (values.max().coerceAtLeast(1f) * 1.15f)
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / top).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
        drawRect(color.copy(alpha = 0.06f), size = Size(size.width, size.height))
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun KeyValueRow(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val haptics = com.smriti.app.util.LocalHaptics.current
        Switch(checked = checked, onCheckedChange = { haptics?.click(); onChange(it) }, enabled = enabled)
    }
}

@Composable
fun ClickRow(title: String, subtitle: String? = null, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableH(onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact dropdown used for language, theme, interval etc. */
@Composable
fun <T> DropdownRow(title: String, current: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickableH { open = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (label, value) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = { if (label == current) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { open = false; onSelect(value) }
                    )
                }
            }
        }
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
