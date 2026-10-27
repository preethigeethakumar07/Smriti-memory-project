package com.smriti.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "iQOO Racing": the same palette as the judges' website (dashboard/src/styles.css):
 * carbon black, white type, and the motorsport tri-stripe of light blue, deep blue and red.
 */
val RaceBlue = Color(0xFF4AA8FF)
val RaceDeep = Color(0xFF1B3FA0)
val RaceRed = Color(0xFFE4262C)

val Accents = listOf(
    "Racing blue" to RaceBlue,
    "Deep blue" to Color(0xFF5B7CFF),
    "Racing red" to Color(0xFFFF4B52),
    "Silver" to Color(0xFFC9D2E0)
)

val Carbon = Color(0xFF07090D)
val Panel = Color(0xFF0E131B)
val Panel2 = Color(0xFF131A25)
val Edge = Color(0xFF263042)

val Ink = Carbon
val Good = RaceBlue                 // "ok / live" is blue, like the website; green is not part of the palette
val Warn = Color(0xFFF2B84B)        // the one off-palette colour: a warning must not look like "ok"
val Bad = Color(0xFFFF4B52)

private val SmritiShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/** Condensed, uppercase-friendly headings with tabular-looking weight; body keeps the system face for legibility. */
private val base = Typography()
private val SmritiType = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
)

@Composable
fun SmritiTheme(theme: String, accentIndex: Int, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val accent = Accents[accentIndex.coerceIn(0, Accents.lastIndex)].second
    // The system bars must follow OUR theme (set in Settings), not only the phone's: dark icons on a dark page were invisible.
    val view = androidx.compose.ui.platform.LocalView.current
    val barBackground = if (dark) Carbon else Color(0xFFF4F7FC)
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(barBackground.toArgb()))
        }
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF04101F),
            secondary = accent,
            secondaryContainer = lerp(Carbon, accent, 0.22f),
            onSecondaryContainer = Color.White,
            primaryContainer = lerp(Carbon, accent, 0.22f),
            onPrimaryContainer = Color.White,
            background = Carbon,
            surface = Panel,
            surfaceVariant = Panel2,
            surfaceContainer = Panel,
            surfaceContainerHigh = Panel2,
            onSurface = Color(0xFFEDF1F7),
            onSurfaceVariant = Color(0xFF8B96A9),
            outline = Color(0xFF34425A),
            outlineVariant = Edge,
            error = Bad
        )
    } else {
        lightColorScheme(
            primary = lerp(accent, Color.Black, 0.35f),
            onPrimary = Color.White,
            secondary = lerp(accent, Color.Black, 0.35f),
            secondaryContainer = lerp(Color.White, accent, 0.3f),
            onSecondaryContainer = Color(0xFF07090D),
            primaryContainer = lerp(Color.White, accent, 0.3f),
            onPrimaryContainer = Color(0xFF07090D),
            background = Color(0xFFF3F6FB),
            surface = Color.White,
            surfaceVariant = Color(0xFFE8EDF5),
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFE8EDF5),
            onSurface = Color(0xFF07090D),
            onSurfaceVariant = Color(0xFF4B5563),
            outline = Color(0xFFC3CCDA),
            outlineVariant = Color(0xFFDCE3EE)
        )
    }
    MaterialTheme(colorScheme = scheme, shapes = SmritiShapes, typography = SmritiType, content = content)
}
