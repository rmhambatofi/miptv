package com.miptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

// --- Films ---

fun LazyListScope.moviesSection(state: LiveState, movies: LazyPagingItems<Movie>, playback: Playback?, actions: VodActions) {
    if (state.ready && !state.hasXtream) { noProviderItem("films", actions); return }
    val sheet = state.movieSheet
    if (sheet != null) { movieSheet(state, sheet, playback, actions); return }
    item { SectionTitle("Catégories") }
    item {
        LazyRow(modifier = Modifier.testTag("movie-category-strip"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.movieCategoryList, key = { it.id }) { item ->
                FilterChip(selected = state.movieCategoryId == item.id, onClick = { actions.movieCategory(item.id) }, label = { Text(item.name) }, modifier = Modifier.testTag("movie-category-${item.id}"))
            }
        }
    }
    item { FilterField(state.movieFilter, "Filtrer les films", "movie-filter", actions.movieFilter) }
    item { SectionTitle("Films · ${state.visibleMovieCount}") }
    if (state.visibleMovieCount == 0) item {
        Text(
            when {
                state.movieFilter.isNotBlank() -> "Aucun film ne correspond au filtre."
                state.movieCategoryId == Category.FAVORITES.id -> "Aucun film favori. Touchez l'étoile d'une fiche pour l'ajouter."
                else -> "Aucun film dans cette catégorie."
            },
            modifier = Modifier.testTag("empty-movies")
        )
    }
    posterGrid(movies.itemCount, key = { index -> movies.peek(index)?.id ?: "movie-$index" }) { index ->
        movies[index]?.let { movie -> PosterCard(movie.title, movie.posterUrl, movie.movieSubtitle(), state.isFavorite(movie.id), "movie-${movie.id}", { actions.toggleFavorite(movie.id) }) { actions.openMovie(movie) } }
    }
}

private fun LazyListScope.movieSheet(state: LiveState, sheet: MovieSheet, playback: Playback?, actions: VodActions) {
    val movie = sheet.movie
    item { SheetHeader(movie.title, actions.closeSheet) }
    val playing = playback?.takeIf { it.item.id == movie.id }
    if (playing != null) item { VodPlayer(playing, actions.fullscreen, actions.stop, onNext = null) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (playing == null) Poster(movie.posterUrl, Modifier.width(120.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                movie.movieSubtitle()?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("movie-meta")) }
                Text(state.categoryNames[movie.categoryId] ?: M3uParser.DEFAULT_GROUP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sheet.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Fiche en cours de chargement…", style = MaterialTheme.typography.bodySmall)
                }
                sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sheet-error")) }
                FavoriteButton(state.isFavorite(movie.id), Modifier.testTag("favorite-${movie.id}")) { actions.toggleFavorite(movie.id) }
            }
        }
    }
    item {
        val resume = state.resumable(movie.id)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (resume != null) {
                Button(onClick = { actions.playMovie(movie, false) }, modifier = Modifier.testTag("resume-movie")) { Text(resumeLabel(resume.positionMs)) }
                OutlinedButton(onClick = { actions.playMovie(movie, true) }, modifier = Modifier.testTag("play-movie")) { Text("Recommencer") }
            } else Button(onClick = { actions.playMovie(movie, false) }, modifier = Modifier.testTag("play-movie")) {
                Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Lire")
            }
        }
    }
    item { Text(movie.synopsis ?: if (sheet.loading) "" else "Aucun synopsis fourni.", modifier = Modifier.testTag("movie-synopsis")) }
}

// --- Séries ---

fun LazyListScope.seriesSection(state: LiveState, series: LazyPagingItems<Series>, playback: Playback?, actions: VodActions, nextEpisode: Episode?) {
    if (state.ready && !state.hasXtream) { noProviderItem("séries", actions); return }
    val sheet = state.seriesSheet
    if (sheet != null) { seriesSheet(state, sheet, playback, actions, nextEpisode); return }
    item { SectionTitle("Catégories") }
    item {
        LazyRow(modifier = Modifier.testTag("series-category-strip"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.seriesCategoryList, key = { it.id }) { item ->
                FilterChip(selected = state.seriesCategoryId == item.id, onClick = { actions.seriesCategory(item.id) }, label = { Text(item.name) }, modifier = Modifier.testTag("series-category-${item.id}"))
            }
        }
    }
    item { FilterField(state.seriesFilter, "Filtrer les séries", "series-filter", actions.seriesFilter) }
    item { SectionTitle("Séries · ${state.visibleSeriesCount}") }
    if (state.visibleSeriesCount == 0) item {
        Text(
            when {
                state.seriesFilter.isNotBlank() -> "Aucune série ne correspond au filtre."
                state.seriesCategoryId == Category.FAVORITES.id -> "Aucune série favorite. Touchez l'étoile d'une fiche pour l'ajouter."
                else -> "Aucune série dans cette catégorie."
            },
            modifier = Modifier.testTag("empty-series")
        )
    }
    posterGrid(series.itemCount, key = { index -> series.peek(index)?.id ?: "series-$index" }) { index ->
        series[index]?.let { item -> PosterCard(item.title, item.posterUrl, item.year?.toString(), state.isFavorite(item.id), "series-${item.id}", { actions.toggleFavorite(item.id) }) { actions.openSeries(item) } }
    }
}

private fun LazyListScope.seriesSheet(state: LiveState, sheet: SeriesSheet, playback: Playback?, actions: VodActions, nextEpisode: Episode?) {
    val series = sheet.series
    item { SheetHeader(series.title, actions.closeSheet) }
    val playing = playback?.takeIf { p -> sheet.info?.episodes?.any { it.id == p.item.id } == true }
    if (playing != null) item { VodPlayer(playing, actions.fullscreen, actions.stop, onNext = if (nextEpisode != null) actions.playNext else null) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (playing == null) Poster(series.posterUrl, Modifier.width(120.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(series.year?.toString(), series.episodeRunTimeSeconds?.let { "${formatDuration(it)} par épisode" }, series.rating?.let { "★ ${formatRating(it)}" })
                    .joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(state.categoryNames[series.categoryId] ?: M3uParser.DEFAULT_GROUP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sheet.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Saisons en cours de chargement…", style = MaterialTheme.typography.bodySmall)
                }
                sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sheet-error")) }
                FavoriteButton(state.isFavorite(series.id), Modifier.testTag("favorite-${series.id}")) { actions.toggleFavorite(series.id) }
            }
        }
    }
    series.synopsis?.let { item { Text(it, modifier = Modifier.testTag("series-synopsis")) } }
    val info = sheet.info ?: return
    if (info.seasons.isEmpty()) { item { Text("Aucun épisode disponible chez le fournisseur.", modifier = Modifier.testTag("empty-seasons")) }; return }
    item {
        LazyRow(modifier = Modifier.testTag("season-strip"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(info.seasons, key = { it.number }) { season ->
                FilterChip(selected = sheet.season == season.number, onClick = { actions.selectSeason(season.number) }, label = { Text("${season.name} · ${season.episodeCount}") }, modifier = Modifier.testTag("season-${season.number}"))
            }
        }
    }
    items(sheet.episodes, key = { it.id }) { episode ->
        val progress = state.progress[episode.id]
        val current = playing?.item?.id == episode.id
        Card(onClick = { actions.playEpisode(episode) }, modifier = Modifier.fillMaxWidth().testTag("episode-${episode.id}"),
            colors = CardDefaults.cardColors(containerColor = if (current) Color(0xFF3B2534) else MaterialTheme.colorScheme.surface)) {
            Column {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(episode.thumbnailUrl, Modifier.width(96.dp).aspectRatio(16f / 9f))
                    Column(Modifier.weight(1f)) {
                        Text("${episode.number}. ${episode.title}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val detail = listOfNotNull(
                            episode.durationSeconds?.let(::formatDuration),
                            when {
                                current -> "En lecture"
                                progress?.finished == true -> "Vu"
                                progress != null && progress.positionMs > 0 -> resumeLabel(progress.positionMs)
                                else -> null
                            }
                        ).joinToString(" · ")
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = "Lire", tint = MaterialTheme.colorScheme.primary)
                }
                if (progress != null && progress.durationMs > 0) {
                    LinearProgressIndicator(progress = { (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
    }
}

// --- Accueil rows and grouped search ---

fun LazyListScope.homeVodRows(state: LiveState, actions: VodActions) {
    if (!state.hasXtream) return
    if (state.resume.isNotEmpty()) {
        item { SectionTitle("Reprendre") }
        item {
            LazyRow(modifier = Modifier.testTag("resume-row"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.resume, key = { it.item.id }) { entry ->
                    Card(onClick = { actions.resume(entry) }, modifier = Modifier.width(220.dp).testTag("resume-${entry.item.id}")) {
                        Column {
                            Thumbnail(entry.posterUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            LinearProgressIndicator(progress = { (entry.progress.positionMs.toFloat() / entry.progress.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
                            Column(Modifier.padding(12.dp)) {
                                Text(entry.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.item.subtitle?.let { "$it · " }.orEmpty() + resumeLabel(entry.progress.positionMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.recentMovies.isNotEmpty()) {
        item { SectionTitle("Films récents") }
        item {
            LazyRow(modifier = Modifier.testTag("recent-movies"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.recentMovies, key = { it.id }) { movie ->
                    Box(Modifier.width(120.dp)) { PosterCard(movie.title, movie.posterUrl, movie.year?.toString(), state.isFavorite(movie.id), "recent-${movie.id}", null) { actions.openMovie(movie) } }
                }
            }
        }
    }
    if (state.recentSeries.isNotEmpty()) {
        item { SectionTitle("Séries récentes") }
        item {
            LazyRow(modifier = Modifier.testTag("recent-series"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.recentSeries, key = { it.id }) { series ->
                    Box(Modifier.width(120.dp)) { PosterCard(series.title, series.posterUrl, series.year?.toString(), state.isFavorite(series.id), "recent-${series.id}", null) { actions.openSeries(series) } }
                }
            }
        }
    }
}

/** Films and series matching the search, as compact rows under their own headings. */
fun LazyListScope.searchVodSections(state: LiveState, movies: LazyPagingItems<Movie>, series: LazyPagingItems<Series>, actions: VodActions) {
    if (!state.hasXtream) return
    item { SectionTitle("Films · ${state.searchMovieCount}") }
    if (state.searchMovieCount == 0) item { Text("Aucun film.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(movies.itemCount, key = { index -> "search-" + (movies.peek(index)?.id ?: "movie-$index") }) { index ->
        movies[index]?.let { movie -> SearchRow(movie.title, movie.movieSubtitle(), movie.posterUrl, "search-${movie.id}") { actions.openMovie(movie) } }
    }
    item { SectionTitle("Séries · ${state.searchSeriesCount}") }
    if (state.searchSeriesCount == 0) item { Text("Aucune série.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(series.itemCount, key = { index -> "search-" + (series.peek(index)?.id ?: "series-$index") }) { index ->
        series[index]?.let { item -> SearchRow(item.title, item.year?.toString(), item.posterUrl, "search-${item.id}") { actions.openSeries(item) } }
    }
}

// --- building blocks ---

private fun LazyListScope.noProviderItem(what: String, actions: VodActions) {
    item {
        Card(Modifier.fillMaxWidth().testTag("no-provider")) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Aucun fournisseur Xtream", style = MaterialTheme.typography.titleLarge)
                Text("Les $what viennent du catalogue d'un fournisseur Xtream Codes. Ajoutez le vôtre avec son serveur, votre identifiant et votre mot de passe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { actions.openSources(true) }, modifier = Modifier.testTag("add-provider")) { Text("Ajouter un fournisseur") }
            }
        }
    }
}

/** Rows of [COLUMNS] posters inside the screen's single lazy column; each row reads its items from the paged list. */
private fun LazyListScope.posterGrid(count: Int, key: (Int) -> Any, cell: @Composable (Int) -> Unit) {
    val rows = (count + COLUMNS - 1) / COLUMNS
    items(rows, key = { row -> "row-" + key(row * COLUMNS) }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (column in 0 until COLUMNS) {
                val index = row * COLUMNS + column
                Box(Modifier.weight(1f)) { if (index < count) cell(index) }
            }
        }
    }
}

@Composable
private fun PosterCard(title: String, posterUrl: String?, subtitle: String?, favorite: Boolean, tag: String, onToggleFavorite: (() -> Unit)?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).testTag(tag)) {
        Box {
            Poster(posterUrl, Modifier.fillMaxWidth())
            if (onToggleFavorite != null) FavoriteButton(favorite, Modifier.align(Alignment.TopEnd).size(32.dp).testTag("favorite-$tag"), onToggleFavorite)
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** 2:3 poster with a placeholder while loading or when the provider has none. */
@Composable
fun Poster(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF232838)), contentAlignment = Alignment.Center) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                error = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                loading = { Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF3A4052)) }
            )
        } else Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Thumbnail(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF232838)), contentAlignment = Alignment.Center) {
        if (url != null) SubcomposeAsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            error = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, loading = { })
        else Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchRow(title: String, subtitle: String?, posterUrl: String?, tag: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(posterUrl, Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("close-sheet")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("sheet-title"))
    }
}

@Composable
fun FilterField(value: String, label: String, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, contentDescription = "Effacer") } },
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
