package com.miptv

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.flow.Flow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as MiptvApplication
            val model: LiveViewModel = viewModel(factory = viewModelFactory {
                initializer { LiveViewModel(app.repository, createSavedStateHandle()) }
            })
            val state by model.state.collectAsStateWithLifecycle()
            FullscreenEffect(state.fullscreen)
            MiptvTheme { PhoneApp(state, model.actions(), model.feeds()) }
        }
    }
}

/** Landscape with hidden system bars while a channel plays fullscreen; restored on exit and on dispose. */
@Composable
fun FullscreenEffect(fullscreen: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val controller = WindowInsetsControllerCompat(activity.window, view)
        fun apply(on: Boolean) {
            activity.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            if (on) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        apply(fullscreen)
        onDispose { if (fullscreen) apply(false) }
    }
}

@Composable
fun MiptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF0B0D12), surface = Color(0xFF181C26),
        primary = Color(0xFFFF647C), onPrimary = Color(0xFF330010),
        secondary = Color(0xFFA8B8FF), onBackground = Color(0xFFF5F5FA),
        onSurface = Color(0xFFF5F5FA), onSurfaceVariant = Color(0xFFBCC1D0)
    ), content = content)
}

/** UI callbacks, grouped so screens and tests share one wiring. */
class PhoneActions(
    val navigate: (Destination) -> Unit,
    val category: (String) -> Unit,
    val select: (Channel) -> Unit,
    val search: (String) -> Unit,
    val filter: (String) -> Unit,
    val fullscreen: (Boolean) -> Unit,
    val toggleFavorite: (Channel) -> Unit,
    val openChannel: (Channel) -> Unit,
    val openSources: (Boolean) -> Unit,
    val addUrlSource: (String, String) -> Unit,
    val addFileSource: (String, String) -> Unit,
    val refreshSource: (Long) -> Unit,
    val removeSource: (Long) -> Unit,
    val sources: SourceActions,
    val vod: VodActions,
    val guide: GuideActions,
    /** Turns a stored reference into the URL the player opens; null when the credentials are gone. */
    val resolveStream: suspend (String) -> String?,
    val saveProgress: (String, Long, Long) -> Unit,
    val nextEpisode: () -> Episode?
)

fun LiveViewModel.guideActions() = GuideActions(
    open = { channel -> openGuide(channel) }, openAt = { hit -> openGuide(hit) }, day = ::guideDay, close = ::closeGuide, play = ::playFromGuide,
    setEpgUrl = ::setEpgUrl, refreshEpg = ::refreshEpg, tick = ::tick
)

fun LiveViewModel.actions() = PhoneActions(
    ::navigate, ::category, ::select, ::search, ::filter, ::fullscreen, ::toggleFavorite, ::openChannel, ::openSources, ::addUrlSource, ::addFileSource, ::refreshSource, ::removeSource,
    sources = SourceActions(::addUrlSource, ::addFileSource, ::addXtreamSource, ::checkXtreamAccount, ::clearAccountCheck, ::refreshSource, ::removeSource, guideActions()),
    vod = VodActions(
        ::movieCategory, ::movieFilter, ::seriesCategory, ::seriesFilter, ::openMovie, ::openSeries, ::closeSheet, ::selectSeason,
        ::playMovie, { episode -> playEpisode(episode) }, ::playNextEpisode, ::stopVod, ::resume, { id -> toggleFavorite(id) }, ::fullscreen, ::openSources
    ),
    guide = guideActions(),
    resolveStream = ::resolveStream,
    saveProgress = ::saveProgress,
    nextEpisode = ::nextEpisode
)

/** Paged lists, collected by the screens that show them. */
class ChannelFeeds(
    val tv: Flow<PagingData<Channel>>, val search: Flow<PagingData<Channel>>, val home: Flow<PagingData<Channel>>,
    val movies: Flow<PagingData<Movie>>, val series: Flow<PagingData<Series>>,
    val searchMovies: Flow<PagingData<Movie>>, val searchSeries: Flow<PagingData<Series>>
)

fun LiveViewModel.feeds() = ChannelFeeds(channels, searchResults, homeChannels, movies, series, searchMovies, searchSeries)

/** The collected lists, passed down to the lazy sections. */
class ChannelPages(
    val tv: LazyPagingItems<Channel>, val search: LazyPagingItems<Channel>, val home: LazyPagingItems<Channel>,
    val movies: LazyPagingItems<Movie>, val series: LazyPagingItems<Series>,
    val searchMovies: LazyPagingItems<Movie>, val searchSeries: LazyPagingItems<Series>
)

@Composable
fun PhoneApp(state: LiveState, actions: PhoneActions, feeds: ChannelFeeds) {
    val pages = ChannelPages(
        feeds.tv.collectAsLazyPagingItems(), feeds.search.collectAsLazyPagingItems(), feeds.home.collectAsLazyPagingItems(),
        feeds.movies.collectAsLazyPagingItems(), feeds.series.collectAsLazyPagingItems(),
        feeds.searchMovies.collectAsLazyPagingItems(), feeds.searchSeries.collectAsLazyPagingItems()
    )
    // Hoisted above both layouts: switching to fullscreen re-attaches the surface, never the player. One player for TV and VOD.
    val playback = rememberPlayback(state.playItem, resolve = actions.resolveStream, onProgress = { id, position, duration -> actions.saveProgress(id, position, duration) })
    val nextEpisode = if (state.vod != null) actions.nextEpisode() else null
    // The guide's clock: one tick at each minute boundary while the screen is shown.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            actions.guide.tick()
        }
    }
    if (state.fullscreen && playback != null) {
        BackHandler { actions.fullscreen(false) }
        FullscreenPlayer(playback, onExit = { actions.fullscreen(false) }, onNext = if (nextEpisode != null) actions.vod.playNext else null, nowNext = state.selectedNowNext)
        return
    }
    val guideOpen = !state.sourcesOpen && state.guideSheet != null
    val sheetOpen = !state.sourcesOpen && !guideOpen && (state.movieSheet != null || state.seriesSheet != null)
    BackHandler(state.sourcesOpen) { actions.openSources(false) }
    BackHandler(guideOpen) { actions.guide.close() }
    BackHandler(sheetOpen) { actions.vod.closeSheet() }
    BackHandler(!state.sourcesOpen && !sheetOpen && !guideOpen && state.destination != Destination.HOME) { actions.navigate(Destination.HOME) }
    Scaffold(bottomBar = {
        NavigationBar {
            Destination.entries.forEach { destination ->
                val icon = when (destination) {
                    Destination.HOME -> Icons.Default.Home
                    Destination.TV -> Icons.Default.PlayArrow
                    Destination.MOVIES -> Icons.Default.Star
                    Destination.SERIES -> Icons.AutoMirrored.Filled.List
                    Destination.SEARCH -> Icons.Default.Search
                }
                NavigationBarItem(
                    selected = state.destination == destination && !state.sourcesOpen,
                    onClick = { actions.navigate(destination) },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(destination.label, maxLines = 1) },
                    modifier = Modifier.testTag("nav-${destination.name}")
                )
            }
        }
    }) { padding ->
        val guide = state.guideSheet
        val scrollState = key(state.destination, state.sourcesOpen, state.movieSheet?.movie?.id, state.seriesSheet?.series?.id, guide?.channel?.id, guide?.dayStartMs) { rememberLazyListState() }
        // Once the day's programmes arrive, show the one on air (or the searched one) first.
        LaunchedEffect(guideOpen, guide?.channel?.id, guide?.dayStartMs, guide?.loading) {
            if (!guideOpen || guide == null || guide.loading) return@LaunchedEffect
            val target = guide.focusMs ?: guide.programmes.firstOrNull { it.isLive(state.nowMs) }?.startMs ?: return@LaunchedEffect
            val index = guide.programmes.indexOfFirst { it.startMs == target }
            if (index > 0) scrollState.scrollToItem(1 + GUIDE_HEADER_ITEMS + index)
        }
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("content"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("miptv", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { actions.openSources(!state.sourcesOpen) }, modifier = Modifier.testTag("open-sources")) {
                        Icon(Icons.Default.Settings, contentDescription = "Sources", tint = if (state.sourcesOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!sheetOpen && !guideOpen) item { Text(if (state.sourcesOpen) "Sources" else state.destination.label, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
            when {
                state.sourcesOpen -> sourcesSection(state, actions.sources)
                guideOpen -> guideSection(state, guide!!, playback?.takeIf { state.selected != null }, actions.guide, actions.fullscreen, actions.toggleFavorite)
                state.destination == Destination.HOME -> homeSection(state, pages.home, actions)
                state.destination == Destination.TV -> tvSection(state, pages.tv, playback?.takeIf { state.selected != null }, actions)
                state.destination == Destination.MOVIES -> moviesSection(state, pages.movies, playback, actions.vod)
                state.destination == Destination.SERIES -> seriesSection(state, pages.series, playback, actions.vod, nextEpisode)
                else -> searchSection(state, pages, actions)
            }
        }
    }
}

private fun LazyListScope.homeSection(state: LiveState, channels: LazyPagingItems<Channel>, actions: PhoneActions) {
    item {
        Column(Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(Color(0xFF412036), Color(0xFF192844))), RoundedCornerShape(24.dp)
        ).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Votre cinéma de poche", style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    state.hasXtream -> "${state.channelCount} chaînes, ${state.movieCount} films et ${state.seriesCount} séries, depuis ${state.sources.size} source(s)."
                    state.hasSources -> "${state.channelCount} chaînes dans ${state.imported.size} catégories, depuis ${state.sources.size} source(s)."
                    else -> "Ajoutez votre playlist M3U ou votre fournisseur Xtream pour retrouver vos chaînes, films et séries ici."
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { actions.navigate(Destination.TV) }) { Text("Explorer la TV") }
                OutlinedButton(onClick = { actions.openSources(true) }, modifier = Modifier.testTag("home-sources")) { Text("Sources") }
            }
        }
    }
    homeNowRow(state, actions.guide, actions.openChannel)
    homeVodRows(state, actions.vod)
    if (state.hasSources) {
        item { SectionTitle("À découvrir") }
        items(minOf(10, channels.itemCount), key = channels.itemKey { "home-${it.id}" }) { index ->
            channels[index]?.let { channel -> ChannelCard(channel, state, false, actions.toggleFavorite, actions.guide.open) { actions.openChannel(channel) } }
        }
    }
}

private fun LazyListScope.tvSection(state: LiveState, channels: LazyPagingItems<Channel>, playback: Playback?, actions: PhoneActions) {
    if (state.ready && !state.hasSources) {
        item {
            Card(Modifier.fillMaxWidth().testTag("no-source")) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Aucune source configurée", style = MaterialTheme.typography.titleLarge)
                    Text("Ajoutez une playlist M3U par adresse ou depuis un fichier pour afficher vos chaînes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { actions.openSources(true) }, modifier = Modifier.testTag("add-source")) { Text("Ajouter une source") }
                }
            }
        }
        return
    }
    item {
        if (playback == null) {
            Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Votre aperçu", style = MaterialTheme.typography.titleLarge)
                    Text("Touchez une chaîne ci-dessous pour lancer la lecture.")
                }
            }
        } else Preview(playback, state.isFavorite(playback.item.id), actions.fullscreen, state.selectedNowNext, state.nowMs) { state.selected?.let(actions.toggleFavorite) }
    }
    item { SectionTitle("Catégories") }
    item {
        LazyRow(modifier = Modifier.testTag("category-strip"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.categories, key = { it.id }) { item ->
                FilterChip(selected = state.categoryId == item.id, onClick = { actions.category(item.id) }, label = { Text(item.name) }, modifier = Modifier.testTag("category-${item.id}"))
            }
        }
    }
    item {
        OutlinedTextField(value = state.filter, onValueChange = actions.filter, singleLine = true,
            label = { Text("Filtrer les chaînes") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (state.filter.isNotEmpty()) IconButton(onClick = { actions.filter("") }) { Icon(Icons.Default.Close, contentDescription = "Effacer") } },
            modifier = Modifier.fillMaxWidth().testTag("filter-input"))
    }
    item { SectionTitle("Chaînes · ${state.visibleCount}") }
    if (state.visibleCount == 0) item {
        Text(
            when {
                state.filter.isNotBlank() -> "Aucune chaîne ne correspond au filtre."
                state.categoryId == Category.FAVORITES.id -> "Aucun favori. Touchez l'étoile d'une chaîne pour l'ajouter."
                else -> "Aucune chaîne dans cette catégorie."
            },
            modifier = Modifier.testTag("empty-category")
        )
    }
    items(channels.itemCount, key = channels.itemKey { it.id }) { index ->
        channels[index]?.let { channel -> ChannelCard(channel, state, state.selected?.id == channel.id, actions.toggleFavorite, actions.guide.open) { actions.select(channel) } }
    }
}

/** Results grouped as channels, films and series; without an Xtream source only channels are searched. */
private fun LazyListScope.searchSection(state: LiveState, pages: ChannelPages, actions: PhoneActions) {
    val channels = pages.search
    item {
        OutlinedTextField(value = state.query, onValueChange = actions.search,
            label = { Text(when { state.hasXtream -> "Rechercher une chaîne, un film, une série"; state.hasGuide -> "Rechercher une chaîne ou un programme"; else -> "Rechercher une chaîne" }) }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag("search-input"))
    }
    item {
        Text(
            if (state.hasXtream) "Recherche locale parmi ${state.channelCount} chaînes, ${state.movieCount} films et ${state.seriesCount} séries."
            else "Recherche locale parmi ${state.channelCount} chaînes importées.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (state.hasXtream) item { SectionTitle("Chaînes · ${state.searchCount}") }
    if (state.searchCount == 0) item { Text(if (state.hasXtream) "Aucune chaîne." else "Aucun résultat.", color = if (state.hasXtream) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) }
    items(channels.itemCount, key = channels.itemKey { "search-${it.id}" }) { index ->
        channels[index]?.let { channel -> ChannelCard(channel, state, false, actions.toggleFavorite, actions.guide.open) { actions.openChannel(channel) } }
    }
    searchProgrammeSection(state, actions.guide)
    searchVodSections(state, pages.searchMovies, pages.searchSeries, actions.vod)
}

@Composable
fun ChannelLogo(channel: Channel, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(52.dp).clip(shape).background(Color(0xFF232838)), contentAlignment = Alignment.Center) {
        if (channel.logoUrl != null) {
            SubcomposeAsyncImage(
                model = channel.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                error = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                loading = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        } else Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun FavoriteButton(favorite: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            Icons.Default.Star,
            contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
            tint = if (favorite) MaterialTheme.colorScheme.primary else Color(0xFF5A6070)
        )
    }
}

@Composable
private fun ChannelCard(channel: Channel, state: LiveState, selected: Boolean, toggleFavorite: (Channel) -> Unit, openGuide: (Channel) -> Unit, onClick: () -> Unit) {
    val nowNext = state.nowNext[channel.id]
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("channel-${channel.id}"),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF3B2534) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    (if (selected) "En lecture · " else "") + (state.categoryNames[channel.categoryId] ?: M3uParser.DEFAULT_GROUP),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
                // Only when a programme is known: no reserved space otherwise.
                if (nowNext != null) NowLine(nowNext, state.nowMs, Modifier.padding(top = 4.dp))
            }
            GuideButton(channel, Modifier.testTag("guide-${channel.id}"), openGuide)
            FavoriteButton(state.isFavorite(channel), Modifier.testTag("favorite-${channel.id}")) { toggleFavorite(channel) }
        }
    }
}

