package com.miptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey

/**
 * The TV tab in three levels: the list of categories, the channels of one category, then the player on top
 * with the categories and their channels under it. Back lowers the player to a mini player over the list it
 * covered; only its close button stops the channel. [onPlayerTap] receives whether a touch on the picture showed its controls.
 */
@Composable
fun ColumnScope.TvScreen(
    state: LiveState, channels: LazyPagingItems<Channel>, playback: Playback?, actions: PhoneActions, listState: LazyListState,
    onPlayerTap: (Boolean) -> Unit = {}
) {
    // The lists keep their place while the player covers them: back returns to the category or channel left.
    val categoriesList = rememberLazyListState()
    val channelsList = key(state.categoryId) { rememberLazyListState() }
    when {
        state.ready && !state.hasSources -> {
            AppHeader("TV en direct", state.sourcesOpen, actions.openSources)
            ContentList(listState, padded = true) { noSourceItem(actions) }
        }
        playback != null && state.selected != null && state.playerOpen -> PlayerPage(state, state.selected, channels, playback, actions, listState, onPlayerTap)
        else -> {
            Column(Modifier.weight(1f)) {
                if (state.channelsOpen) ChannelsPage(state, channels, actions, channelsList) else CategoriesPage(state, actions, categoriesList)
            }
            if (playback != null && state.selected != null) MiniPlayer(state.selected, state, playback, actions)
        }
    }
}

private fun LazyListScope.noSourceItem(actions: PhoneActions) {
    item {
        Card(Modifier.fillMaxWidth().testTag("no-source")) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Aucune source configurée", style = MaterialTheme.typography.titleLarge)
                Text("Ajoutez une playlist M3U par adresse ou depuis un fichier pour afficher vos chaînes.", color = Palette.Muted)
                PillButton("Ajouter une source", { actions.openSources(true) }, Modifier.testTag("add-source"), icon = MiptvIcons.Plus)
            }
        }
    }
}

// --- Level 1: categories ---

@Composable
private fun ColumnScope.CategoriesPage(state: LiveState, actions: PhoneActions, listState: LazyListState) {
    var cover by rememberSaveable { mutableStateOf(false) }
    AppHeader("TV en direct", state.sourcesOpen, actions.openSources)
    Box(Modifier.weight(1f)) {
        ContentList(listState, spacing = 0.dp) {
            if (cover) {
                items(state.categories.chunked(2), key = { row -> "cover-" + row.first().id }) { row ->
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { category -> CategoryTile(category, Modifier.weight(1f)) { actions.openCategory(category.id) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                items(state.categories, key = { it.id }) { category -> CategoryRow(category) { actions.openCategory(category.id) } }
            }
            // Room for the floating switch over the last rows.
            item { Spacer(Modifier.height(72.dp)) }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).shadow(16.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Palette.Panel).padding(4.dp)
        ) {
            ViewChoice("Couverture", MiptvIcons.Grid, cover, Modifier.testTag("categories-cover")) { cover = true }
            ViewChoice("Liste", MiptvIcons.List, !cover, Modifier.testTag("categories-list")) { cover = false }
        }
    }
}

@Composable
private fun ViewChoice(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(50)).background(if (selected) Palette.AccentSoft else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val color = if (selected) Palette.Text else Palette.Muted
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CategoryRow(category: Category, onClick: () -> Unit) {
    val (zone, name) = splitZone(category.name)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp).height(56.dp)
            .drawBehind { drawLine(Palette.Raised, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .testTag("category-${category.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(zone.orEmpty(), color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(MiptvIcons.ChevronRight, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CategoryTile(category: Category, modifier: Modifier, onClick: () -> Unit) {
    val (zone, name) = splitZone(category.name)
    Column(
        modifier.height(96.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Panel).clickable(onClick = onClick).padding(14.dp).testTag("category-${category.id}"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(zone.orEmpty(), color = Palette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
    }
}

// --- Level 2: the channels of a category ---

@Composable
private fun ColumnScope.ChannelsPage(state: LiveState, channels: LazyPagingItems<Channel>, actions: PhoneActions, listState: LazyListState) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    val title = state.categoryNames[state.categoryId] ?: Category.ALL.name
    BackHeader(title, actions.closeCategory, "close-category", backLabel = "Retour aux catégories") {
        IconButton(onClick = { searching = !searching }, modifier = Modifier.testTag("toggle-filter")) {
            Icon(MiptvIcons.Search, contentDescription = "Rechercher dans la catégorie", tint = if (searching || state.filter.isNotEmpty()) Palette.Accent else Palette.Muted)
        }
        IconButton(onClick = { grid = !grid }, modifier = Modifier.testTag("toggle-view")) {
            Icon(if (grid) MiptvIcons.List else MiptvIcons.Grid, contentDescription = "Changer l'affichage", tint = Palette.Muted)
        }
    }
    ContentList(listState, spacing = 0.dp) {
        if (searching || state.filter.isNotEmpty()) item {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { FilterField(state.filter, "Filtrer les chaînes", "filter-input", actions.filter) }
        }
        item { Text("${state.visibleCount} chaînes", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
        if (state.visibleCount == 0) item {
            Text(
                when {
                    state.filter.isNotBlank() -> "Aucune chaîne ne correspond au filtre."
                    state.categoryId == Category.FAVORITES.id -> "Aucun favori. Touchez le signet d'une chaîne pour l'ajouter."
                    else -> "Aucune chaîne dans cette catégorie."
                },
                modifier = Modifier.padding(horizontal = 20.dp).testTag("empty-category")
            )
        }
        if (grid) {
            val rows = (channels.itemCount + 2) / 3
            items(rows, key = { row -> "grid-" + (channels.peek(row * 3)?.id ?: row) }) { row ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (column in 0 until 3) {
                        val index = row * 3 + column
                        Box(Modifier.weight(1f)) {
                            if (index < channels.itemCount) channels[index]?.let { channel -> ChannelTile(channel) { actions.select(channel) } }
                        }
                    }
                }
            }
        } else {
            items(channels.itemCount, key = channels.itemKey { it.id }) { index ->
                channels[index]?.let { channel ->
                    ChannelRow(channel, state, state.selected?.id == channel.id, actions.toggleFavorite, actions.guide.open, Modifier.padding(horizontal = 12.dp)) { actions.select(channel) }
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(channel: Channel, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(6.dp).testTag("channel-${channel.id}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ChannelLogo(channel, Modifier.fillMaxWidth().aspectRatio(1f), size = 96.dp, corner = 14.dp)
        Text(channel.name, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
    }
}

/**
 * A channel in a list: logo, name, then what it airs with the progress and what follows (only when a programme
 * is known; the category otherwise), the guide button and the bookmark.
 */
@Composable
fun ChannelRow(
    channel: Channel, state: LiveState, selected: Boolean, toggleFavorite: (Channel) -> Unit, openGuide: (Channel) -> Unit,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val nowNext = state.nowNext[channel.id]
    Row(
        modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) Palette.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 8.dp, end = 0.dp, top = 8.dp, bottom = 8.dp).testTag("channel-${channel.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ChannelLogo(channel, size = 56.dp, highlight = selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(channel.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Only when a programme is known: no reserved space otherwise.
            if (nowNext != null) NowLine(nowNext, state.nowMs)
            else Text(
                (if (selected) "En lecture · " else "") + (state.categoryNames[channel.categoryId] ?: M3uParser.DEFAULT_GROUP),
                fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Row {
            GuideButton(channel, Modifier.testTag("guide-${channel.id}"), openGuide)
            FavoriteButton(state.isFavorite(channel), Modifier.testTag("favorite-${channel.id}")) { toggleFavorite(channel) }
        }
    }
}

// --- Level 3: the player ---

@Composable
private fun ColumnScope.PlayerPage(
    state: LiveState, selected: Channel, channels: LazyPagingItems<Channel>, playback: Playback, actions: PhoneActions, listState: LazyListState,
    onTap: (Boolean) -> Unit
) {
    // The picture stays on top, the rest scrolls under it.
    LivePlayer(
        playback, actions.fullscreen, Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        onMinimize = actions.minimizePlayer, onGuide = { actions.guide.open(selected) }, onClose = actions.closePlayer, onTap = onTap
    )
    ContentList(listState, spacing = 0.dp) {
        item {
            Box(Modifier.drawBehind { drawLine(Palette.Raised, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }) {
                LiveInfo(selected.name, selected, state.categoryNames[selected.categoryId], state.isFavorite(selected), state.selectedNowNext, state.nowMs) { actions.toggleFavorite(selected) }
            }
        }
        item { PlayerCategories(state, actions.category) }
        item {
            Text("${state.visibleCount} chaînes".uppercase(), color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp))
        }
        items(channels.itemCount, key = channels.itemKey { "up-next-${it.id}" }) { index ->
            channels[index]?.let { channel -> UpNextRow(channel, state, channel.id == selected.id) { actions.select(channel) } }
        }
    }
}

/** Every category as a chip under the player: another category lists its channels while the channel keeps playing. */
@Composable
private fun PlayerCategories(state: LiveState, onCategory: (String) -> Unit) {
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = state.categories.indexOfFirst { it.id == state.categoryId }.coerceAtLeast(0))
    LazyRow(
        state = strip, modifier = Modifier.padding(top = 8.dp).testTag("player-categories"),
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.categories, key = { it.id }) { category ->
            FilterChip(
                selected = category.id == state.categoryId, onClick = { onCategory(category.id) },
                label = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.widthIn(max = 220.dp).testTag("player-category-${category.id}")
            )
        }
    }
}

/**
 * The channel playing under the lists, YouTube style: a touch opens the player again, pause and close stay at
 * hand. Shown once back has lowered the player.
 */
@Composable
private fun MiniPlayer(channel: Channel, state: LiveState, playback: Playback, actions: PhoneActions) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Palette.Panel)
            .drawBehind { drawLine(Palette.Raised, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .clickable(onClick = actions.expandPlayer).testTag("mini-player"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black)) {
            PlayerSurface(playback, fullscreen = false, onFullscreen = actions.fullscreen, modifier = Modifier.fillMaxSize(), controller = false)
            // The picture's view would keep the touch: a layer above it opens the player.
            Box(Modifier.matchParentSize().clickable(onClick = actions.expandPlayer))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (playback.failed) playback.status else state.nowNext[channel.id]?.now?.title ?: (state.categoryNames[channel.categoryId] ?: M3uParser.DEFAULT_GROUP),
                fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = playback::togglePlay, modifier = Modifier.testTag("mini-play-pause")) {
            Icon(if (playback.playing) MiptvIcons.Pause else MiptvIcons.Play, contentDescription = if (playback.playing) "Pause" else "Lecture")
        }
        IconButton(onClick = actions.closePlayer, modifier = Modifier.testTag("mini-close")) {
            Icon(MiptvIcons.Close, contentDescription = "Fermer le lecteur", tint = Palette.Muted)
        }
    }
}

@Composable
private fun UpNextRow(channel: Channel, state: LiveState, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(68.dp).clip(RoundedCornerShape(12.dp)).background(if (current) Palette.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp).testTag("channel-${channel.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ChannelLogo(channel, size = 48.dp, corner = 10.dp, highlight = current)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(channel.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.nowNext[channel.id]?.now?.title ?: (state.categoryNames[channel.categoryId] ?: M3uParser.DEFAULT_GROUP), fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Text("EN COURS", color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
