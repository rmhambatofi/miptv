package com.miptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The palette of the UX refresh: dark ground, one surface, a raised tone and the pink accent. */
object Palette {
    val Ink = Color(0xFF0B0D12)
    val Panel = Color(0xFF181C26)
    val Raised = Color(0xFF232838)
    val Accent = Color(0xFFFF647C)
    val AccentSoft = Color(0xFF3B2534)
    val Text = Color(0xFFF5F5FA)
    val Muted = Color(0xFFBCC1D0)
    val Dim = Color(0xFF5A6070)
    val Link = Color(0xFFA8B8FF)
}

/** Stroke icons drawn from the design's 24 × 24 paths; tinted like any [Icon]. */
object MiptvIcons {
    val Home = stroke("home", "m3 11 9-8 9 8v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z")
    val Tv = stroke("tv", rect(2f, 6f, 20f, 13f, 2f), "m8 2 4 4 4-4")
    val Film = stroke("film", rect(3f, 4f, 18f, 16f, 2f), "M3 9h18M3 15h18M8 4v16M16 4v16")
    val Series = stroke("series", rect(3f, 8f, 18f, 12f, 2f), "M7 4h10M9 14l4 2-4 2z")
    val Search = stroke("search", circle(11f, 11f, 7f), "m20 20-4-4")
    val Settings = stroke(
        "settings", circle(12f, 12f, 3f),
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"
    )
    val Back = stroke("back", "M19 12H5M12 19l-7-7 7-7")
    val ChevronRight = stroke("chevron-right", "m9 6 6 6-6 6")
    val ChevronDown = stroke("chevron-down", "m6 9 6 6 6-6")
    val Close = stroke("close", "M18 6 6 18M6 6l12 12")
    val Menu = stroke("menu", "M4 7h16M4 12h16M4 17h16")
    val Grid = stroke("grid", rect(3f, 3f, 7f, 7f, 1f), rect(14f, 3f, 7f, 7f, 1f), rect(3f, 14f, 7f, 7f, 1f), rect(14f, 14f, 7f, 7f, 1f))
    val List = stroke("list", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01")
    val Bookmark = stroke("bookmark", BOOKMARK)
    val BookmarkFilled = filled("bookmark-filled", BOOKMARK, outline = true)
    val Calendar = stroke("calendar", rect(3f, 4f, 18f, 18f, 2f), "M3 10h18M8 2v4M16 2v4")
    val Share = stroke("share", circle(18f, 5f, 3f), circle(6f, 12f, 3f), circle(18f, 19f, 3f), "m8.6 13.5 6.8 4M15.4 6.5l-6.8 4")
    val Plus = stroke("plus", "M12 5v14M5 12h14", width = 2.5f)
    val Check = stroke("check", "m5 12 5 5L20 7", width = 2.5f)
    val Restart = stroke("restart", "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5")
    val Play = filled("play", "M7 4v16l13-8z")
    val Pause = filled("pause", rect(6f, 4f, 4f, 16f, 1f), rect(14f, 4f, 4f, 16f, 1f))
    val Previous = filled("previous", "M6 5h2v14H6zM19 5 9 12l10 7z")
    val Next = filled("next", "M16 5h2v14h-2zM5 5l10 7-10 7z")
    val Volume = stroke("volume", "M11 5 6 9H2v6h4l5 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a9 9 0 0 1 0 14")
    val Muted = stroke("muted", "M11 5 6 9H2v6h4l5 4zM22 9l-6 6M16 9l6 6")
    val EnterFullscreen = stroke("enter-fullscreen", "M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5")
    val ExitFullscreen = stroke("exit-fullscreen", "M8 3v5H3M16 3v5h5M8 21v-5H3M16 21v-5h5")
}

private const val BOOKMARK = "M6 3h12a1 1 0 0 1 1 1v17l-7-4-7 4V4a1 1 0 0 1 1-1z"

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()

private fun filled(name: String, vararg paths: String, outline: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), fill = SolidColor(Color.Black), stroke = if (outline) SolidColor(Color.Black) else null, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

/** « FR | Cinéma » → zone « FR » and name « Cinéma »; other names have no zone. */
fun splitZone(name: String): Pair<String?, String> {
    val match = Regex("""^\s*([A-Za-z]{2,4})\s*[|:]\s*(.+)$""").find(name) ?: return null to name
    return match.groupValues[1].uppercase() to match.groupValues[2].trim()
}

/** Bar of a screen without a back button: the brand, the screen title centred, the Sources gear. */
@Composable
fun AppHeader(title: String, sourcesOpen: Boolean, onSources: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().height(64.dp).background(Palette.Panel).padding(start = 20.dp, end = 8.dp)) {
        Text("miptv", color = Palette.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
        IconButton(onClick = { onSources(!sourcesOpen) }, modifier = Modifier.align(Alignment.CenterEnd).testTag("open-sources")) {
            Icon(MiptvIcons.Settings, contentDescription = "Sources", tint = if (sourcesOpen) Palette.Accent else Palette.Muted)
        }
    }
}

/** Bar of a nested screen: back arrow, title, then [actions]. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit, backTag: String, backLabel: String = "Retour", titleTag: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(Palette.Panel).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) { Icon(MiptvIcons.Back, contentDescription = backLabel) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).then(if (titleTag != null) Modifier.testTag(titleTag) else Modifier))
        actions()
    }
}

/** The five tabs, with the soft accent pill behind the current one. */
@Composable
fun MiptvNavBar(current: Destination?, onNavigate: (Destination) -> Unit) {
    NavigationBar(
        containerColor = Palette.Panel,
        modifier = Modifier.drawBehind { drawLine(Palette.Raised, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
    ) {
        Destination.entries.forEach { destination ->
            val icon = when (destination) {
                Destination.HOME -> MiptvIcons.Home
                Destination.TV -> MiptvIcons.Tv
                Destination.MOVIES -> MiptvIcons.Film
                Destination.SERIES -> MiptvIcons.Series
                Destination.SEARCH -> MiptvIcons.Search
            }
            val selected = current == destination
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(destination) },
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                label = { Text(destination.label, maxLines = 1, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Palette.Accent, selectedTextColor = Palette.Text, indicatorColor = Palette.AccentSoft,
                    unselectedIconColor = Palette.Muted, unselectedTextColor = Palette.Muted
                ),
                modifier = Modifier.testTag("nav-${destination.name}")
            )
        }
    }
}

/** Thin rounded progress line in the accent, on a raised track. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Palette.Raised) {
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Palette.Accent))
    }
}

/** White pill for the main action of a screen, translucent for the secondary one. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = true, height: Dp = 44.dp) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(if (primary) Palette.Text else Palette.Text.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
    ) {
        val color = if (primary) Palette.Ink else Palette.Text
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (height > 48.dp) 20.dp else 18.dp))
        Text(text, color = color, fontSize = if (height > 48.dp) 17.sp else 15.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** Icon over a short label, for the action rows of the film and series sheets. */
@Composable
fun SheetAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Palette.Text) {
    Column(
        modifier.height(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Carousel position dots; the current one is larger and white. */
@Composable
fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(Modifier.size(if (index == current) 8.dp else 6.dp).clip(CircleShape).background(if (index == current) Palette.Text else Palette.Dim))
        }
    }
}

/** « ● DIRECT » badge over a live picture. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Palette.Ink.copy(alpha = 0.75f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
        Text("DIRECT", color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/** Uppercase section title, optionally tappable with a chevron. */
@Composable
fun RowTitle(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title.uppercase(), fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onClick != null) Icon(MiptvIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
