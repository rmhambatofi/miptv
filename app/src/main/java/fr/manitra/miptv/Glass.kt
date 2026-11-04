package fr.manitra.miptv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The liquid glass option: on top of the theme chosen (dark or light), bars and buttons become translucent glass
 * with a sheen at the top and a bright edge. It changes no colour of the theme itself.
 */
enum class Glass {
    /** Bars, menus and floating controls. */
    REGULAR,

    /** Small tiles and cards. */
    THIN,

    /** A selected item, tinted with the accent. */
    TINT,

    /** Over a video: dark glass whatever the theme. */
    MEDIA
}

/** The liquid glass option chosen in the settings, kept in the app's preferences; off until chosen. */
object GlassChoice {
    private const val PREFERENCES = "settings"
    private const val KEY = "glass"

    fun get(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/** What the floating bar blurs: the content of the screen, while the glass option is on. */
val LocalGlassSource = staticCompositionLocalOf<HazeState?> { null }

/** Height the floating bar covers at the bottom of the screen, which the lists scroll past. */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

/** One glass recipe: a translucent fill, a sheen from top to bottom, an edge bright at the top, a soft shadow. */
class GlassLook(val fill: Color, val sheenTop: Color, val sheenBottom: Color, val edgeTop: Color, val edgeBottom: Color, val shadow: Color, val elevation: Dp)

private val White = Color.White

/** The recipe of [kind] on [colors]; [Glass.MEDIA] always takes the dark one. */
fun glassLook(kind: Glass, colors: ThemeColors): GlassLook {
    val dark = kind == Glass.MEDIA || !colors.light
    val accent = colors.accent
    return when {
        kind == Glass.MEDIA -> GlassLook(DarkColors.ink.copy(alpha = 0.34f), White.copy(alpha = 0.18f), White.copy(alpha = 0.04f), White.copy(alpha = 0.34f), White.copy(alpha = 0.08f), Color.Black, 8.dp)
        kind == Glass.TINT && dark -> GlassLook(accent.copy(alpha = 0.22f), White.copy(alpha = 0.20f), Color.Transparent, accent.copy(alpha = 0.55f), White.copy(alpha = 0.08f), accent, 0.dp)
        kind == Glass.TINT -> GlassLook(accent.copy(alpha = 0.14f), White.copy(alpha = 0.60f), White.copy(alpha = 0.10f), accent.copy(alpha = 0.30f), White.copy(alpha = 0.50f), accent, 0.dp)
        kind == Glass.THIN && dark -> GlassLook(Color(0xFF242938).copy(alpha = 0.40f), White.copy(alpha = 0.12f), White.copy(alpha = 0.03f), White.copy(alpha = 0.22f), White.copy(alpha = 0.05f), Color.Black, 0.dp)
        kind == Glass.THIN -> GlassLook(White.copy(alpha = 0.55f), White.copy(alpha = 0.70f), White.copy(alpha = 0.30f), White, colors.text.copy(alpha = 0.06f), colors.text, 0.dp)
        dark -> GlassLook(Color(0xFF1A1E2A).copy(alpha = 0.46f), White.copy(alpha = 0.16f), White.copy(alpha = 0.02f), White.copy(alpha = 0.32f), White.copy(alpha = 0.06f), Color.Black, 12.dp)
        else -> GlassLook(White.copy(alpha = 0.55f), White.copy(alpha = 0.80f), White.copy(alpha = 0.40f), White, colors.text.copy(alpha = 0.08f), colors.text, 8.dp)
    }
}

/**
 * Glass of [kind] in [shape]. With [blur], and a screen content to blur ([LocalGlassSource]), what lies behind is
 * blurred (Android 12 and later; before, the fill only).
 */
@Composable
fun Modifier.glass(shape: Shape, kind: Glass = Glass.REGULAR, blur: Boolean = false): Modifier {
    val look = glassLook(kind, Palette.colors)
    val source = LocalGlassSource.current
    val base = (if (look.elevation > 0.dp) shadow(look.elevation, shape, ambientColor = look.shadow, spotColor = look.shadow) else this).clip(shape)
    val filled = if (blur && source != null) {
        base.hazeEffect(source, HazeStyle(backgroundColor = Palette.Ink, tint = HazeTint(look.fill), blurRadius = 22.dp, noiseFactor = 0f))
    } else base.background(look.fill)
    return filled
        .background(Brush.verticalGradient(listOf(look.sheenTop, look.sheenBottom)))
        .border(1.dp, Brush.verticalGradient(listOf(look.edgeTop, look.edgeBottom)), shape)
}

/** Glass of [kind] while the option is on, else the plain [fallback] fill of the theme. */
@Composable
fun Modifier.surface(shape: Shape, fallback: Color, kind: Glass = Glass.REGULAR, blur: Boolean = false): Modifier =
    if (Palette.glass) glass(shape, kind, blur) else clip(shape).background(fallback)
