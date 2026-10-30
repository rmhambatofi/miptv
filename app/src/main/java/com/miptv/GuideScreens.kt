package com.miptv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

/** « Journal de 20h », a thin progress bar, « Ensuite · 20:35 Météo »: under a channel name wherever a programme is known. */
@Composable
fun NowLine(nowNext: NowNext, nowMs: Long, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(modifier.testTag("now-line"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(nowNext.now.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(progress = { nowNext.now.progress(nowMs) }, modifier = Modifier.fillMaxWidth().height(3.dp), trackColor = tint.copy(alpha = 0.25f))
        nextLabel(nowNext)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Small « Guide » button next to a channel. */
@Composable
fun GuideButton(channel: Channel, modifier: Modifier = Modifier, onOpen: (Channel) -> Unit) {
    IconButton(onClick = { onOpen(channel) }, modifier = modifier) {
        Icon(Icons.Default.DateRange, contentDescription = "Guide des programmes", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Number of lazy items the guide sheet places before its programme list (header, player, day chips, title). */
const val GUIDE_HEADER_ITEMS = 4

/**
 * The guide of one channel: header with the channel, the inline player when it is the one playing (or « Lire »),
 * day chips, then the programmes of the day with the one on air highlighted. Descriptions unfold on tap.
 */
fun LazyListScope.guideSection(state: LiveState, sheet: GuideSheet, playback: Playback?, actions: GuideActions, fullscreen: (Boolean) -> Unit, toggleFavorite: (Channel) -> Unit) {
    val channel = sheet.channel
    item {
        Row(Modifier.fillMaxWidth().testTag("guide-sheet"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = actions.close, modifier = Modifier.testTag("close-guide")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
            ChannelLogo(channel)
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.testTag("guide-title"))
                Text(state.categoryNames[channel.categoryId] ?: M3uParser.DEFAULT_GROUP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FavoriteButton(state.isFavorite(channel), Modifier.testTag("guide-favorite")) { toggleFavorite(channel) }
        }
    }
    item {
        if (playback != null && state.selected?.id == channel.id) Preview(playback, state.isFavorite(channel), fullscreen, state.nowNext[channel.id], state.nowMs) { toggleFavorite(channel) }
        else Button(onClick = { actions.play(channel) }, modifier = Modifier.testTag("guide-play")) {
            Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Lire")
        }
    }
    item {
        LazyRow(modifier = Modifier.testTag("guide-days"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.guideDays, key = { it }) { day ->
                FilterChip(selected = sheet.dayStartMs == day, onClick = { actions.day(day) }, label = { Text(dayLabel(day, state.nowMs)) }, modifier = Modifier.testTag("guide-day-$day"))
            }
        }
    }
    item { SectionTitle("Programmes · ${sheet.programmes.size}") }
    val source = state.sources.firstOrNull { it.id == channel.sourceId }
    when {
        sheet.loading -> item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Chargement du guide…") } }
        sheet.programmes.isEmpty() -> item {
            Text(
                when {
                    source != null && !source.hasGuide -> "Aucun guide pour cette source. Ajoutez l'adresse XMLTV de la playlist dans Sources."
                    source?.kind == SourceKind.XTREAM -> "Aucun programme connu pour cette chaîne. Les favoris reçoivent le guide complet à l'actualisation ; les autres chaînes, les prochains programmes à la lecture."
                    else -> "Aucun programme ce jour."
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
        colors = CardDefaults.cardColors(containerColor = if (live) Color(0xFF3B2534) else MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(formatSlot(programme), style = MaterialTheme.typography.bodySmall, color = if (past) Color(0xFF5A6070) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                Column(Modifier.weight(1f)) {
                    Text(programme.title, style = MaterialTheme.typography.titleSmall, color = if (past) Color(0xFF8A90A0) else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    listOfNotNull(if (live) "En ce moment" else null, programme.episode, programme.category).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            if (live) LinearProgressIndicator(progress = { programme.progress(nowMs) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            programme.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("programme-description")) }
        }
    }
}

/** Accueil: favourite channels with a programme on air. Tap plays; the small button opens the guide. */
fun LazyListScope.homeNowRow(state: LiveState, actions: GuideActions, onPlay: (Channel) -> Unit) {
    val onAir = state.onAirFavorites
    if (onAir.isEmpty()) return
    item { SectionTitle("En ce moment sur vos favoris") }
    item {
        LazyRow(modifier = Modifier.testTag("now-row"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(onAir, key = { it.first.id }) { (channel, nowNext) ->
                Card(onClick = { onPlay(channel) }, modifier = Modifier.width(240.dp).testTag("now-${channel.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChannelLogo(channel, Modifier.size(36.dp))
                            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            GuideButton(channel, Modifier.size(32.dp).testTag("now-guide-${channel.id}"), actions.open)
                        }
                        NowLine(nowNext, state.nowMs)
                    }
                }
            }
        }
    }
}

/** Recherche: programmes to come whose title matches; a hit opens the channel's guide on its day. */
fun LazyListScope.searchProgrammeSection(state: LiveState, actions: GuideActions) {
    if (!state.hasGuide) return
    item { SectionTitle("Programmes · ${state.searchProgrammeCount}") }
    if (state.searchProgrammeCount == 0) item { Text("Aucun programme.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    (if (live) "En ce moment" else dayLabel(dayStart(hit.programme.startMs), state.nowMs) + " " + formatTime(hit.programme.startMs)) + " · " + hit.channel.name,
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
                label = { Text("Guide des programmes (XMLTV)") }, placeholder = { Text("https://exemple.tld/guide.xml.gz") },
                supportingText = { Text("Préremplie par l'en-tête url-tvg de la playlist, quand il existe.") },
                modifier = Modifier.fillMaxWidth().testTag("epg-url-${source.id}")
            )
        }
        Text(
            when {
                !source.hasGuide -> "Aucun guide."
                source.epgRefreshedAt == null -> if (source.kind == SourceKind.XTREAM) "Guide des favoris jamais chargé." else "Guide jamais chargé."
                else -> (if (source.kind == SourceKind.XTREAM) "Guide des favoris · " else "Guide · ") +
                    "${source.programmeCount} programmes · actualisé le ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(source.epgRefreshedAt))}"
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("epg-status-${source.id}")
        )
        source.epgError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("epg-error-${source.id}")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (source.kind != SourceKind.XTREAM && url.trim() != source.epgUrl.orEmpty()) {
                TextButton(onClick = { actions.setEpgUrl(source.id, url) }, enabled = !busy, modifier = Modifier.testTag("save-epg-${source.id}")) { Text(if (url.isBlank()) "Retirer le guide" else "Enregistrer le guide") }
            }
            if (source.hasGuide) {
                TextButton(onClick = { actions.refreshEpg(source.id) }, enabled = !busy, modifier = Modifier.testTag("refresh-epg-${source.id}")) { Text("Actualiser le guide") }
            }
        }
    }
}
