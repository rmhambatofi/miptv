package com.miptv

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import coil.compose.SubcomposeAsyncImage

/** Callbacks of the Films and Séries tabs, the sheets and the VOD rows of Accueil and Recherche. */
class VodActions(
    val movieCategory: (String) -> Unit,
    val movieFilter: (String) -> Unit,
    val seriesCategory: (String) -> Unit,
    val seriesFilter: (String) -> Unit,
    val openMovie: (Movie) -> Unit,
    val openSeries: (Series) -> Unit,
    val closeSheet: () -> Unit,
    val selectSeason: (Int) -> Unit,
    val playMovie: (Movie, Boolean) -> Unit,
    val playEpisode: (Episode) -> Unit,
    val playNext: () -> Unit,
    val stop: () -> Unit,
    val resume: (ResumeItem) -> Unit,
    val toggleFavorite: (String) -> Unit,
    val fullscreen: (Boolean) -> Unit,
    val openSources: (Boolean) -> Unit
)

private const val COLUMNS = 3

private val Gutter = Modifier.padding(horizontal = 16.dp)

/** The player when it plays this sheet's film. */
private fun MovieSheet.playing(playback: Playback?): Playback? = playback?.takeIf { it.item.id == movie.id }

/** The player when it plays one of this sheet's episodes. */
private fun SeriesSheet.playing(playback: Playback?): Playback? = playback?.takeIf { p -> info?.episodes?.any { it.id == p.item.id } == true }

// --- Films ---

@Composable
fun ColumnScope.MoviesScreen(
    state: LiveState, movies: LazyPagingItems<Movie>, playback: Playback?, actions: VodActions, listState: LazyListState,
    sideways: Boolean = false, onPlayerTap: (Boolean) -> Unit = {}
) {
    val sheet = state.movieSheet
    if (sheet != null && !(state.ready && !state.hasXtream)) {
        val playing = sheet.playing(playback)
        if (sideways && playing != null) SidewaysVodPlayer(playing, actions.fullscreen, actions.stop, onNext = null, onPlayerTap, Modifier.fillMaxWidth().weight(1f))
        else ContentList(listState) { movieSheet(state, sheet, playback, actions) }
        return
    }
    CatalogueScreen(
        title = "Films", tag = "movie", categories = state.movieCategoryList, categoryId = state.movieCategoryId, onCategory = actions.movieCategory,
        filter = state.movieFilter, filterLabel = "Filtrer les films", onFilter = actions.movieFilter, available = !(state.ready && !state.hasXtream), listState = listState
    ) {
        if (state.ready && !state.hasXtream) { noProviderItem("films", actions); return@CatalogueScreen }
        if (state.movieCategoryId == Category.ALL.id && state.movieFilter.isBlank() && state.recentMovies.isNotEmpty()) item(key = "hero") {
            CatalogueHero(state.recentMovies.take(5).map { movie ->
                HeroEntry(movie.id, movie.title, listOfNotNull(state.categoryNames[movie.categoryId]?.let { splitZone(it).second }, movie.year?.toString(), movie.durationSeconds?.let(::formatDuration)).joinToString(" · "), movie.posterUrl, null) { actions.openMovie(movie) }
            }, "Visuel du film")
        }
        item { RowTitle("${state.categoryNames[state.movieCategoryId] ?: Category.ALL.name} · ${state.visibleMovieCount}", Gutter.padding(top = 8.dp)) }
        if (state.visibleMovieCount == 0) item {
            Text(
                when {
                    state.movieFilter.isNotBlank() -> "Aucun film ne correspond au filtre."
                    state.movieCategoryId == Category.FAVORITES.id -> "Aucun film dans votre liste. Touchez « Ma liste » sur une fiche pour l'ajouter."
                    else -> "Aucun film dans cette catégorie."
                },
                modifier = Gutter.testTag("empty-movies")
            )
        }
        posterGrid(movies.itemCount, key = { index -> movies.peek(index)?.id ?: "movie-$index" }) { index ->
            movies[index]?.let { movie -> PosterCard(movie.title, movie.posterUrl, listOfNotNull(state.categoryNames[movie.categoryId]?.let { splitZone(it).second }, movie.year?.toString()).joinToString(" · "), "movie-${movie.id}") { actions.openMovie(movie) } }
        }
    }
}

private fun LazyListScope.movieSheet(state: LiveState, sheet: MovieSheet, playback: Playback?, actions: VodActions) {
    val movie = sheet.movie
    val playing = sheet.playing(playback)
    val resume = state.resumable(movie.id)
    val meta = listOfNotNull(state.categoryNames[movie.categoryId]?.let { splitZone(it).second }, movie.movieSubtitle()).joinToString(" · ")
    if (playing != null) {
        item { SheetBar(movie.title, actions.closeSheet) }
        item { VodPlayer(playing, actions.fullscreen, actions.stop, onNext = null) }
        item { Text(meta, color = Palette.Muted, fontSize = 13.sp, modifier = Gutter.testTag("movie-meta")) }
    } else item {
        SheetHero(movie.title, meta, "movie-meta", movie.posterUrl, "Jaquette du film", 520.dp, actions.closeSheet) {
            if (resume != null) PillButton(resumeLabel(resume.positionMs), { actions.playMovie(movie, false) }, Modifier.fillMaxWidth().testTag("resume-movie"), icon = MiptvIcons.Play, height = 56.dp)
            else PillButton("Regarder maintenant", { actions.playMovie(movie, false) }, Modifier.fillMaxWidth().testTag("play-movie"), icon = MiptvIcons.Play, height = 56.dp)
        }
    }
    item {
        ActionRow {
            ListAction(state.isFavorite(movie.id), "favorite-${movie.id}") { actions.toggleFavorite(movie.id) }
            if (resume != null) SheetAction("Recommencer", MiptvIcons.Restart, { actions.playMovie(movie, true) }, Modifier.weight(1f).testTag("play-movie"))
            ShareAction(movie.title, listOfNotNull(movie.year?.toString(), movie.durationSeconds?.let(::formatDuration)).joinToString(" · "))
        }
    }
    sheetStatus(sheet.loading, "Fiche en cours de chargement…", sheet.error)
    item {
        Column(Gutter, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(movie.synopsis ?: if (sheet.loading) "" else "Aucun synopsis fourni.", fontSize = 14.sp, lineHeight = 21.sp, color = Palette.Muted, modifier = Modifier.testTag("movie-synopsis"))
            Detail("Catégorie", state.categoryNames[movie.categoryId] ?: M3uParser.DEFAULT_GROUP)
        }
    }
}

// --- Séries ---

@Composable
fun ColumnScope.SeriesScreen(
    state: LiveState, series: LazyPagingItems<Series>, playback: Playback?, actions: VodActions, nextEpisode: Episode?, listState: LazyListState,
    sideways: Boolean = false, onPlayerTap: (Boolean) -> Unit = {}
) {
    val sheet = state.seriesSheet
    if (sheet != null && !(state.ready && !state.hasXtream)) {
        val playing = sheet.playing(playback)
        if (sideways && playing != null) {
            SidewaysVodPlayer(playing, actions.fullscreen, actions.stop, onNext = if (nextEpisode != null) actions.playNext else null, onPlayerTap, Modifier.fillMaxWidth().weight(1f))
        } else ContentList(listState) { seriesSheet(state, sheet, playback, actions, nextEpisode) }
        return
    }
    CatalogueScreen(
        title = "Séries", tag = "series", categories = state.seriesCategoryList, categoryId = state.seriesCategoryId, onCategory = actions.seriesCategory,
        filter = state.seriesFilter, filterLabel = "Filtrer les séries", onFilter = actions.seriesFilter, available = !(state.ready && !state.hasXtream), listState = listState
    ) {
        if (state.ready && !state.hasXtream) { noProviderItem("séries", actions); return@CatalogueScreen }
        if (state.seriesCategoryId == Category.ALL.id && state.seriesFilter.isBlank() && state.recentSeries.isNotEmpty()) item(key = "hero") {
            CatalogueHero(state.recentSeries.take(5).map { item ->
                HeroEntry(item.id, item.title, listOfNotNull(state.categoryNames[item.categoryId]?.let { splitZone(it).second }, item.year?.toString()).joinToString(" · "), item.posterUrl, "NOUVEAUX ÉPISODES") { actions.openSeries(item) }
            }, "Visuel de la série")
        }
        item { RowTitle("${state.categoryNames[state.seriesCategoryId] ?: Category.ALL.name} · ${state.visibleSeriesCount}", Gutter.padding(top = 8.dp)) }
        if (state.visibleSeriesCount == 0) item {
            Text(
                when {
                    state.seriesFilter.isNotBlank() -> "Aucune série ne correspond au filtre."
                    state.seriesCategoryId == Category.FAVORITES.id -> "Aucune série dans votre liste. Touchez « Ma liste » sur une fiche pour l'ajouter."
                    else -> "Aucune série dans cette catégorie."
                },
                modifier = Gutter.testTag("empty-series")
            )
        }
        posterGrid(series.itemCount, key = { index -> series.peek(index)?.id ?: "series-$index" }) { index ->
            series[index]?.let { item -> PosterCard(item.title, item.posterUrl, listOfNotNull(state.categoryNames[item.categoryId]?.let { splitZone(it).second }, item.year?.toString()).joinToString(" · "), "series-${item.id}") { actions.openSeries(item) } }
        }
    }
}

private fun LazyListScope.seriesSheet(state: LiveState, sheet: SeriesSheet, playback: Playback?, actions: VodActions, nextEpisode: Episode?) {
    val series = sheet.series
    val info = sheet.info
    val playing = sheet.playing(playback)
    val meta = listOfNotNull(
        state.categoryNames[series.categoryId]?.let { splitZone(it).second }, series.year?.toString(),
        info?.seasons?.size?.takeIf { it > 0 }?.let { if (it > 1) "$it saisons" else "1 saison" },
        info?.episodes?.size?.takeIf { it > 0 }?.let { if (it > 1) "$it épisodes" else "1 épisode" },
        series.rating?.let { "★ ${formatRating(it)}" }
    ).joinToString(" · ")
    // The episode to go on with: the last one left unfinished, else the first.
    val resumeEpisode = info?.episodes?.filter { state.resumable(it.id) != null }?.maxByOrNull { state.progress[it.id]?.updatedAt ?: 0L }
    val start = resumeEpisode ?: info?.episodes?.firstOrNull()
    if (playing != null) {
        item { SheetBar(series.title, actions.closeSheet) }
        item { VodPlayer(playing, actions.fullscreen, actions.stop, onNext = if (nextEpisode != null) actions.playNext else null) }
    } else item {
        SheetHero(series.title, meta, "series-meta", series.posterUrl, "Visuel de la série", 400.dp, actions.closeSheet) {
            if (start != null) PillButton(
                (if (resumeEpisode != null) "Reprendre" else "Regarder") + " S${start.season} · E${start.number}", { actions.playEpisode(start) },
                Modifier.fillMaxWidth().testTag(if (resumeEpisode != null) "resume-series" else "play-series"), icon = MiptvIcons.Play, height = 56.dp
            )
        }
    }
    item {
        ActionRow {
            ListAction(state.isFavorite(series.id), "favorite-${series.id}") { actions.toggleFavorite(series.id) }
            ShareAction(series.title, series.year?.toString().orEmpty())
        }
    }
    sheetStatus(sheet.loading, "Saisons en cours de chargement…", sheet.error)
    series.synopsis?.let { item { Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = Palette.Muted, modifier = Gutter.testTag("series-synopsis")) } }
    if (info == null) return
    if (info.seasons.isEmpty()) { item { Text("Aucun épisode disponible chez le fournisseur.", modifier = Gutter.testTag("empty-seasons")) }; return }
    item {
        LazyRow(modifier = Modifier.testTag("season-strip"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(info.seasons, key = { it.number }) { season ->
                val selected = sheet.season == season.number
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) Palette.AccentSoft else Palette.Panel)
                        .clickable { actions.selectSeason(season.number) }.padding(horizontal = 16.dp).testTag("season-${season.number}"),
                    contentAlignment = Alignment.Center
                ) { Text("${season.name} · ${season.episodeCount}", color = if (selected) Palette.Text else Palette.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
    items(sheet.episodes, key = { it.id }) { episode ->
        val progress = state.progress[episode.id]
        val current = playing?.item?.id == episode.id
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (current) Palette.Panel else Color.Transparent)
                .clickable { actions.playEpisode(episode) }.padding(8.dp).testTag("episode-${episode.id}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(112.dp).height(64.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
                episode.thumbnailUrl?.let { SubcomposeAsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), error = { }, loading = { }) }
                Icon(MiptvIcons.Play, contentDescription = "Lire", tint = Palette.Text, modifier = Modifier.size(22.dp))
                if (progress != null && progress.durationMs > 0) ProgressLine(
                    if (progress.finished) 1f else progress.positionMs.toFloat() / progress.durationMs,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(), track = Palette.Text.copy(alpha = 0.2f)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("E${episode.number} · ${episode.title}", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(
                    episode.durationSeconds?.let(::formatDuration),
                    when {
                        current -> "En lecture"
                        progress?.finished == true -> "Vu"
                        progress != null && progress.positionMs > 0 -> resumeLabel(progress.positionMs)
                        else -> null
                    }
                ).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = if (current) Palette.Accent else Palette.Muted, maxLines = 1)
                episode.synopsis?.let { Text(it, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// --- Accueil rows and grouped search ---

fun LazyListScope.homeVodRows(state: LiveState, actions: VodActions) {
    if (!state.hasXtream) return
    if (state.resume.isNotEmpty()) item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Continuer le visionnage", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Gutter)
            LazyRow(modifier = Modifier.testTag("resume-row"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.resume, key = { it.item.id }) { entry ->
                    Column(Modifier.width(236.dp).clip(RoundedCornerShape(10.dp)).clickable { actions.resume(entry) }.testTag("resume-${entry.item.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Raised)) {
                            entry.posterUrl?.let { SubcomposeAsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), error = { }, loading = { }) }
                            val left = ((entry.progress.durationMs - entry.progress.positionMs) / 1000).toInt().coerceAtLeast(60)
                            Text("Reste ${formatDuration(left)}", color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Ink.copy(alpha = 0.75f)).padding(horizontal = 7.dp, vertical = 3.dp))
                            ProgressLine(entry.progress.positionMs.toFloat() / entry.progress.durationMs, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), height = 4.dp, track = Palette.Text.copy(alpha = 0.2f))
                        }
                        Text(entry.item.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.item.subtitle?.let { "$it · " }.orEmpty() + resumeLabel(entry.progress.positionMs), fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (state.recentMovies.isNotEmpty()) posterRow("Films récents", "recent-movies") {
        items(state.recentMovies, key = { it.id }) { movie -> Box(Modifier.width(120.dp)) { PosterCard(movie.title, movie.posterUrl, movie.year?.toString(), "recent-${movie.id}") { actions.openMovie(movie) } } }
    }
    if (state.recentSeries.isNotEmpty()) posterRow("Séries récentes", "recent-series") {
        items(state.recentSeries, key = { it.id }) { series -> Box(Modifier.width(120.dp)) { PosterCard(series.title, series.posterUrl, series.year?.toString(), "recent-${series.id}") { actions.openSeries(series) } } }
    }
}

private fun LazyListScope.posterRow(title: String, tag: String, content: LazyListScope.() -> Unit) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Gutter)
            LazyRow(modifier = Modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** Films and series matching the search, as compact rows under their own headings. */
fun LazyListScope.searchVodSections(state: LiveState, movies: LazyPagingItems<Movie>, series: LazyPagingItems<Series>, actions: VodActions) {
    if (!state.hasXtream) return
    item { SectionTitle("Films · ${state.searchMovieCount}") }
    if (state.searchMovieCount == 0) item { Text("Aucun film.", color = Palette.Muted) }
    items(movies.itemCount, key = { index -> "search-" + (movies.peek(index)?.id ?: "movie-$index") }) { index ->
        movies[index]?.let { movie -> SearchRow(movie.title, movie.movieSubtitle(), movie.posterUrl, "search-${movie.id}") { actions.openMovie(movie) } }
    }
    item { SectionTitle("Séries · ${state.searchSeriesCount}") }
    if (state.searchSeriesCount == 0) item { Text("Aucune série.", color = Palette.Muted) }
    items(series.itemCount, key = { index -> "search-" + (series.peek(index)?.id ?: "series-$index") }) { index ->
        series[index]?.let { item -> SearchRow(item.title, item.year?.toString(), item.posterUrl, "search-${item.id}") { actions.openSeries(item) } }
    }
}

// --- building blocks ---

/**
 * Films or Séries: big title and the « Catégories » pill in the bar (with a filter button), the list below;
 * the pill opens the categories over the screen.
 */
@Composable
private fun ColumnScope.CatalogueScreen(
    title: String, tag: String, categories: List<Category>, categoryId: String, onCategory: (String) -> Unit,
    filter: String, filterLabel: String, onFilter: (String) -> Unit, available: Boolean, listState: LazyListState, content: LazyListScope.() -> Unit
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).background(Palette.Panel).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (available) {
                    IconButton(onClick = { searching = !searching }, modifier = Modifier.testTag("$tag-filter-toggle")) {
                        Icon(MiptvIcons.Search, contentDescription = filterLabel, tint = if (searching || filter.isNotEmpty()) Palette.Accent else Palette.Muted)
                    }
                    Row(
                        Modifier.height(44.dp).widthIn(max = 200.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Raised).clickable { menuOpen = true }
                            .padding(start = 16.dp, end = 14.dp).testTag("$tag-category-pill"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(MiptvIcons.Menu, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(categories.firstOrNull { it.id == categoryId }?.name ?: Category.ALL.name, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(MiptvIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            ContentList(listState) {
                if (available && (searching || filter.isNotEmpty())) item { Box(Gutter.padding(top = 12.dp)) { FilterField(filter, filterLabel, "$tag-filter", onFilter) } }
                content()
            }
        }
        if (menuOpen) CategoryMenu(categories, categoryId, tag, { menuOpen = false }) { id -> onCategory(id); menuOpen = false }
    }
}

@Composable
private fun BoxScope.CategoryMenu(categories: List<Category>, selectedId: String, tag: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    Box(Modifier.matchParentSize().background(Palette.Ink.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    Column(
        Modifier.align(Alignment.TopEnd).padding(12.dp).width(300.dp).heightIn(max = 620.dp).shadow(16.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp)).background(Palette.Panel).padding(8.dp).testTag("$tag-category-menu")
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Catégories", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(MiptvIcons.Close, contentDescription = "Fermer", tint = Palette.Muted) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(categories, key = { it.id }) { category ->
                val selected = category.id == selectedId
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) Palette.AccentSoft else Color.Transparent)
                        .clickable { onPick(category.id) }.padding(horizontal = 14.dp).testTag("$tag-category-${category.id}"),
                    contentAlignment = Alignment.CenterStart
                ) { Text(category.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private class HeroEntry(val key: String, val title: String, val meta: String, val imageUrl: String?, val label: String?, val open: () -> Unit)

/** The newest titles of a tab, turning on their own over a 230 dp band. */
@Composable
private fun CatalogueHero(entries: List<HeroEntry>, placeholder: String) {
    val pager = rememberPagerState { entries.size }
    LaunchedEffect(pager, entries.size) {
        while (entries.size > 1) {
            kotlinx.coroutines.delay(6_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % entries.size)
        }
    }
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { entries[it].key }) { page ->
            val entry = entries[page]
            Box(Modifier.fillMaxSize().clickable(onClick = entry.open)) {
                Backdrop(entry.imageUrl, placeholder)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Palette.Ink)))
                Column(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entry.label?.let { Text(it, color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
                    Text(entry.title, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 31.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.meta.isNotEmpty()) Text(entry.meta, fontSize = 13.sp, color = Palette.Muted)
                }
            }
        }
        PagerDots(entries.size, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

/** The top of a sheet: full-bleed picture, round back button, centred title and meta, then [action]. */
@Composable
private fun SheetHero(title: String, meta: String, metaTag: String, imageUrl: String?, placeholder: String, height: Dp, onBack: () -> Unit, action: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(height)) {
        Backdrop(imageUrl, placeholder)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Palette.Ink)))
        IconButton(onClick = onBack, modifier = Modifier.padding(14.dp).size(44.dp).clip(CircleShape).background(Palette.Ink.copy(alpha = 0.6f)).testTag("close-sheet")) {
            Icon(MiptvIcons.Back, contentDescription = "Retour")
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 35.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("sheet-title"))
            if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.testTag(metaTag))
            action()
        }
    }
}

/** The bar of a sheet whose picture gave way to the player. */
@Composable
private fun SheetBar(title: String, onBack: () -> Unit) {
    BackHeader(title, onBack, "close-sheet", titleTag = "sheet-title")
}

@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun RowScope.ListAction(inList: Boolean, tag: String, onToggle: () -> Unit) {
    SheetAction(if (inList) "Dans ma liste" else "Ma liste", if (inList) MiptvIcons.Check else MiptvIcons.Plus, onToggle,
        Modifier.weight(1f).testTag(tag), tint = if (inList) Palette.Accent else Palette.Text)
}

/** Shares the title only: never a stream address, which may carry credentials. */
@Composable
private fun RowScope.ShareAction(title: String, detail: String) {
    val context = LocalContext.current
    SheetAction("Partager", MiptvIcons.Share, {
        val text = if (detail.isEmpty()) title else "$title ($detail)"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }, Modifier.weight(1f).testTag("share"))
}

private fun LazyListScope.sheetStatus(loading: Boolean, message: String, error: String?) {
    if (loading) item {
        Row(Gutter, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(message, fontSize = 13.sp, color = Palette.Muted)
        }
    }
    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Gutter.testTag("sheet-error")) } }
}

@Composable
private fun Detail(label: String, value: String) {
    Text(buildString { append(label); append(" · "); append(value) }, fontSize = 13.sp, color = Palette.Muted)
}

private fun LazyListScope.noProviderItem(what: String, actions: VodActions) {
    item {
        Card(Gutter.padding(top = 16.dp).fillMaxWidth().testTag("no-provider")) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Aucun fournisseur Xtream", style = MaterialTheme.typography.titleLarge)
                Text("Les $what viennent du catalogue d'un fournisseur Xtream Codes. Ajoutez le vôtre avec son serveur, votre identifiant et votre mot de passe.", color = Palette.Muted)
                PillButton("Ajouter un fournisseur", { actions.openSources(true) }, Modifier.testTag("add-provider"), icon = MiptvIcons.Plus)
            }
        }
    }
}

/** Rows of [COLUMNS] posters inside the screen's single lazy column; each row reads its items from the paged list. */
private fun LazyListScope.posterGrid(count: Int, key: (Int) -> Any, cell: @Composable (Int) -> Unit) {
    val rows = (count + COLUMNS - 1) / COLUMNS
    items(rows, key = { row -> "row-" + key(row * COLUMNS) }) { row ->
        Row(Gutter.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (column in 0 until COLUMNS) {
                val index = row * COLUMNS + column
                Box(Modifier.weight(1f)) { if (index < count) cell(index) }
            }
        }
    }
}

@Composable
private fun PosterCard(title: String, posterUrl: String?, subtitle: String?, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Poster(posterUrl, Modifier.fillMaxWidth(), title)
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** 2:3 poster; while loading or when the provider has none, the title on a tinted card. */
@Composable
fun Poster(url: String?, modifier: Modifier = Modifier, title: String? = null) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Palette.Raised), contentAlignment = Alignment.BottomStart) {
        val fallback = @Composable {
            if (title != null) Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
        }
        if (url != null) SubcomposeAsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), error = { fallback() }, loading = { fallback() })
        else fallback()
    }
}

@Composable
private fun SearchRow(title: String, subtitle: String?, posterUrl: String?, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Poster(posterUrl, Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, fontSize = 13.sp, color = Palette.Muted, maxLines = 1) }
        }
    }
}

@Composable
fun FilterField(value: String, label: String, tag: String, onChange: (String) -> Unit) {
    TextField(value = value, onValueChange = onChange, singleLine = true, shape = RoundedCornerShape(28.dp),
        placeholder = { Text(label) },
        leadingIcon = { Icon(MiptvIcons.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(MiptvIcons.Close, contentDescription = "Effacer") } },
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Palette.Panel, unfocusedContainerColor = Palette.Panel),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
fun SectionTitle(title: String) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
