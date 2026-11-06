package fr.manitra.miptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
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
 * The materials of the liquid glass theme ([GlassColors]): bars and buttons are translucent glass with a sheen at
 * the top and a bright edge, over the lit backdrop of [Modifier.glassBackdrop].
 */
enum class Glass {
    /** Bars, menus and floating controls. */
    REGULAR,

    /** Small tiles and cards. */
    THIN,

    /** A selected item, tinted with the accent. */
    TINT,

    /** Over a video. */
    MEDIA
}

/** What the floating bar blurs: the content of the screen, in the liquid glass theme. */
val LocalGlassSource = staticCompositionLocalOf<HazeState?> { null }

/** Height the floating bar covers at the bottom of the screen, which the lists scroll past. */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

/** One glass recipe: a translucent fill, a sheen from top to bottom, an edge bright at the top, a soft shadow. */
class GlassLook(val fill: Color, val sheenTop: Color, val sheenBottom: Color, val edgeTop: Color, val edgeBottom: Color, val shadow: Color, val elevation: Dp)

private val White = Color.White

/** The recipe of [kind], selections tinted with the accent of [colors]. */
fun glassLook(kind: Glass, colors: ThemeColors): GlassLook {
    val accent = colors.accent
    return when (kind) {
        Glass.MEDIA -> GlassLook(DarkColors.ink.copy(alpha = 0.34f), White.copy(alpha = 0.18f), White.copy(alpha = 0.04f), White.copy(alpha = 0.34f), White.copy(alpha = 0.08f), Color.Black, 8.dp)
        Glass.TINT -> GlassLook(accent.copy(alpha = 0.22f), White.copy(alpha = 0.20f), Color.Transparent, accent.copy(alpha = 0.55f), White.copy(alpha = 0.08f), accent, 0.dp)
        Glass.THIN -> GlassLook(White.copy(alpha = 0.08f), White.copy(alpha = 0.10f), White.copy(alpha = 0.02f), White.copy(alpha = 0.24f), White.copy(alpha = 0.06f), Color.Black, 0.dp)
        Glass.REGULAR -> GlassLook(Color(0xFF1C2244).copy(alpha = 0.42f), White.copy(alpha = 0.16f), White.copy(alpha = 0.03f), White.copy(alpha = 0.32f), White.copy(alpha = 0.06f), Color.Black, 12.dp)
    }
}

/**
 * The ground of the liquid glass theme: night blue, lit in blue from the top, a violet halo in the middle and a rose
 * glow rising from the bottom, for the glass above to catch.
 */
fun Modifier.glassBackdrop(): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(0f to Color(0xFF2B4DB5), 0.22f to Color(0xFF2C3796), 0.5f to Color(0xFF1C2244), 0.78f to Color(0xFF1A1E33), 1f to Color(0xFF2E2238)))
    drawRect(Brush.radialGradient(listOf(Color(0xFF3F6FE6).copy(alpha = 0.55f), Color.Transparent), Offset(w * 0.5f, 0f), w * 0.95f))
    drawRect(Brush.radialGradient(listOf(Color(0xFF4B45B8).copy(alpha = 0.45f), Color.Transparent), Offset(w * 0.6f, h * 0.32f), w * 0.6f))
    drawRect(Brush.radialGradient(listOf(Color(0xFF8E3456).copy(alpha = 0.65f), Color.Transparent), Offset(w * 0.3f, h), w * 1.05f))
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

/** In the liquid glass theme, the picture or ground of a hero melts into the backdrop at its bottom. */
fun Modifier.fadeIntoGlass(): Modifier = if (!Palette.glass) this else graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    drawRect(Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
}

/** Glass of [kind] in the liquid glass theme, else the plain [fallback] fill of the theme. */
@Composable
fun Modifier.surface(shape: Shape, fallback: Color, kind: Glass = Glass.REGULAR, blur: Boolean = false): Modifier =
    if (Palette.glass) glass(shape, kind, blur) else clip(shape).background(fallback)
