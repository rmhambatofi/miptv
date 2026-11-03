package fr.manitra.miptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Callbacks of the programme guide: the channel sheet, the home row, the search group and the source cards. */
class GuideActions(
    val open: (Channel) -> Unit,
    val openAt: (ProgrammeHit) -> Unit,
    val day: (Long) -> Unit,
    val close: () -> Unit,
    val play: (Channel) -> Unit,
    val setEpgUrl: (Long, String?) -> Unit,
    val refreshEpg: (Long) -> Unit,
    val tick: () -> Unit
)

/** « Journal de 20h », then a short progress bar and « À suivre 20:35 · Météo »: under a channel name wherever a programme is known. */
@Composable
fun NowLine(nowNext: NowNext, nowMs: Long, modifier: Modifier = Modifier, tint: Color = Palette.Muted) {
    Column(modifier.testTag("now-line"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(nowNext.now.title, fontSize = 13.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressLine(nowNext.now.progress(nowMs), Modifier.width(72.dp))
            nextLabel(nowNext)?.let { Text(it, fontSize = 12.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Small « Guide » button next to a channel. */
@Composable
fun GuideButton(channel: Channel, modifier: Modifier = Modifier, onOpen: (Channel) -> Unit) {
    IconButton(onClick = { onOpen(channel) }, modifier = modifier) {
        Icon(MiptvIcons.Calendar, contentDescription = tr.programmeGuide, tint = Palette.Muted, modifier = Modifier.size(22.dp))
    }
}

/** Number of lazy items the guide sheet places before its programme list (header, player, limited-guide note, day chips, title). */
const val GUIDE_HEADER_ITEMS = 5

/**
 * The guide of one channel: header with the channel, the inline player when it is the one playing (or « Lire »),
 * day chips, then the programmes of the day with the one on air highlighted. Descriptions unfold on tap.
 */
fun LazyListScope.guideSection(state: LiveState, sheet: GuideSheet, playback: Playback?, actions: GuideActions, fullscreen: (Boolean) -> Unit, toggleFavorite: (Channel) -> Unit) {
    val channel = sheet.channel
    item {
        Row(Modifier.fillMaxWidth().testTag("guide-sheet"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = actions.close, modifier = Modifier.testTag("close-guide")) { Icon(MiptvIcons.Back, contentDescription = tr.back) }
            ChannelLogo(channel)
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.testTag("guide-title"))
                Text(state.categoryNames[channel.categoryId] ?: M3uParser.DEFAULT_GROUP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FavoriteButton(state.isFavorite(channel), Modifier.testTag("guide-favorite")) { toggleFavorite(channel) }
        }
    }
    item {
        if (playback != null && state.selected?.id == channel.id) {
            Preview(playback, state.isFavorite(channel), fullscreen, state.nowNext[channel.id], state.nowMs, channel = channel, subtitle = state.categoryNames[channel.categoryId]) { toggleFavorite(channel) }
        } else PillButton(tr.watch, { actions.play(channel) }, Modifier.testTag("guide-play"), icon = MiptvIcons.Play)
    }
    val source = state.sources.firstOrNull { it.id == channel.sourceId }
    item {
        // A provider's full guide follows its favourites; other channels only get the next few programmes.
        if (source?.kind == SourceKind.XTREAM && source.hasGuide && !channel.tvgId.isNullOrBlank() && !state.isFavorite(channel)) {
            Card(Modifier.fillMaxWidth().testTag("guide-limited"), colors = CardDefaults.cardColors(containerColor = Palette.Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr.guideLimited, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { toggleFavorite(channel) }, modifier = Modifier.testTag("guide-limited-favorite")) { Text(tr.addFavorite) }
                }
            }
        }
    }
    val end = sheet.guideEndMs
    item {
        LazyRow(modifier = Modifier.testTag("guide-days"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.guideDays, key = { it }) { day ->
                // Days the known guide does not reach stay selectable, but faded.
                val beyond = end != null && day >= end
                FilterChip(
                    selected = sheet.dayStartMs == day, onClick = { actions.day(day) }, label = { Text(dayLabel(day, state.nowMs)) },
                    modifier = Modifier.alpha(if (beyond) 0.45f else 1f).semantics { if (beyond) stateDescription = tr.noProgrammeState }.testTag("guide-day-$day")
                )
            }
        }
    }
    item { SectionTitle("${tr.programmes} · ${sheet.programmes.size}") }
    when {
        sheet.loading -> item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(tr.loadingGuide) } }
        sheet.programmes.isEmpty() -> item {
            Text(
                when {
                    end != null && sheet.dayStartMs >= end ->
                        tr.guideEndsAt(dayLabel(dayStart(end), state.nowMs), formatTime(end))
                    source != null && !source.hasGuide -> tr.noGuideForSource
                    source?.kind == SourceKind.XTREAM -> tr.noProgrammeXtream
                    else -> tr.noProgrammeToday
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("guide-empty")
            )
        }
    }
    items(sheet.programmes, key = { it.startMs }) { programme -> ProgrammeRow(programme, state.nowMs) }
}

@Composable
private fun ProgrammeRow(programme: Programme, nowMs: Long) {
    val live = programme.isLive(nowMs)
    val past = programme.stopMs <= nowMs
    var expanded by rememberSaveable(programme.startMs) { mutableStateOf(false) }
    Card(
        onClick = { if (programme.description != null) expanded = !expanded },
        modifier = Modifier.fillMaxWidth().testTag("programme-${programme.startMs}"),
        colors = CardDefaults.cardColors(containerColor = if (live) Palette.AccentSoft else Palette.Panel)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(formatSlot(programme), style = MaterialTheme.typography.bodySmall, color = if (past) Palette.Dim else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                Column(Modifier.weight(1f)) {
                    Text(programme.title, style = MaterialTheme.typography.titleSmall, color = if (past) Palette.colors.faded else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(if (live) tr.onNow else null, programme.episode, programme.category).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (live) ProgressLine(programme.progress(nowMs), Modifier.fillMaxWidth())
            programme.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("programme-description")) }
        }
    }
}

/** Recherche: programmes to come whose title matches; a hit opens the channel's guide on its day. */
fun LazyListScope.searchProgrammeSection(state: LiveState, actions: GuideActions) {
    if (!state.hasGuide) return
    item { SectionTitle("${tr.programmes} · ${state.searchProgrammeCount}") }
    if (state.searchProgrammeCount == 0) item { Text(tr.noProgramme, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(state.searchProgrammes, key = { "hit-${it.channel.id}-${it.programme.startMs}" }) { hit ->
        val live = hit.programme.isLive(state.nowMs)
        Row(
            Modifier.fillMaxWidth().clickable { actions.openAt(hit) }.padding(vertical = 8.dp).testTag("hit-${hit.channel.id}-${hit.programme.startMs}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(hit.channel, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(hit.programme.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (live) tr.onNow else dayLabel(dayStart(hit.programme.startMs), state.nowMs) + " " + formatTime(hit.programme.startMs)) + " · " + hit.channel.name,
                    style = MaterialTheme.typography.bodySmall, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Guide block of a source card: address field for playlists, status line and « Actualiser le guide ». */
@Composable
fun SourceGuide(source: Source, busy: Boolean, actions: GuideActions) {
    // Keyed by the stored address too: a card reused for another source with the same id must not restore stale text.
    var url by rememberSaveable(source.id, source.epgUrl, key = "epg-url-${source.id}-${source.epgUrl}") { mutableStateOf(source.epgUrl.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (source.kind != SourceKind.XTREAM) {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text(tr.epgFieldLabel) }, placeholder = { Text(tr.epgPlaceholder) },
                supportingText = { Text(tr.epgFieldHint) },
                modifier = Modifier.fillMaxWidth().testTag("epg-url-${source.id}")
            )
        }
        Text(
            when {
                !source.hasGuide -> tr.noGuide
                source.epgRefreshedAt == null -> if (source.kind == SourceKind.XTREAM) tr.favoritesGuideNeverLoaded else tr.guideNeverLoaded
                else -> tr.guideStatus(source.kind == SourceKind.XTREAM, source.programmeCount, formatDateTime(source.epgRefreshedAt))
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("epg-status-${source.id}")
        )
        source.epgError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("epg-error-${source.id}")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (source.kind != SourceKind.XTREAM && url.trim() != source.epgUrl.orEmpty()) {
                TextButton(onClick = { actions.setEpgUrl(source.id, url) }, enabled = !busy, modifier = Modifier.testTag("save-epg-${source.id}")) { Text(if (url.isBlank()) tr.removeGuide else tr.saveGuide) }
            }
            if (source.hasGuide) {
                TextButton(onClick = { actions.refreshEpg(source.id) }, enabled = !busy, modifier = Modifier.testTag("refresh-epg-${source.id}")) { Text(tr.refreshGuide) }
            }
        }
    }
}
