package com.miptv

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            // The opening screen plays on a cold start only, not after a rotation or a process restore.
            var splash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            FullscreenEffect(state.fullscreen && !splash)
            MiptvTheme { if (splash) Splash { splash = false } else PhoneApp(state, model.actions(), model.feeds()) }
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
        background = Palette.Ink, surface = Palette.Panel, surfaceVariant = Palette.Raised,
        surfaceContainer = Palette.Panel, surfaceContainerHigh = Palette.Raised, surfaceContainerHighest = Palette.Raised,
        primary = Palette.Accent, onPrimary = Color(0xFF330010), primaryContainer = Palette.AccentSoft, onPrimaryContainer = Palette.Text,
        secondary = Palette.Link, secondaryContainer = Palette.AccentSoft, onSecondaryContainer = Palette.Text,
        onBackground = Palette.Text, onSurface = Palette.Text, onSurfaceVariant = Palette.Muted, outline = Palette.Dim, outlineVariant = Palette.Raised
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
    val nextEpisode: () -> Episode?,
    /** TV tab levels: a category's channels, back to the categories; the player minimized, expanded again, closed. */
    val openCategory: (String) -> Unit,
    val closeCategory: () -> Unit,
    val minimizePlayer: () -> Unit,
    val expandPlayer: () -> Unit,
    val closePlayer: () -> Unit
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
    nextEpisode = ::nextEpisode,
    openCategory = ::openCategory,
    closeCategory = ::closeCategory,
    minimizePlayer = ::minimizePlayer,
    expandPlayer = ::expandPlayer,
    closePlayer = ::closePlayer
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
        // Previous and next channel follow the list the channel was picked from.
        val loaded = pages.tv.itemSnapshotList.items
        val index = state.selected?.let { selected -> loaded.indexOfFirst { it.id == selected.id } } ?: -1
        val previous = loaded.getOrNull(index - 1).takeIf { index >= 0 }
        val next = loaded.getOrNull(index + 1).takeIf { index >= 0 }
        FullscreenPlayer(
            playback, onExit = { actions.fullscreen(false) }, onNext = if (nextEpisode != null) actions.vod.playNext else null, nowNext = state.selectedNowNext, nowMs = state.nowMs,
            onPreviousChannel = previous?.let { { actions.select(it) } }, onNextChannel = next?.let { { actions.select(it) } },
            onGuide = state.selected?.let { channel -> { actions.fullscreen(false); actions.guide.open(channel) } }
        )
        return
    }
    val guideOpen = !state.sourcesOpen && state.guideSheet != null
    val sheetOpen = !state.sourcesOpen && !guideOpen && (state.movieSheet != null || state.seriesSheet != null)
    val inTv = !state.sourcesOpen && !guideOpen && state.destination == Destination.TV
    BackHandler(state.sourcesOpen) { actions.openSources(false) }
    BackHandler(guideOpen) { actions.guide.close() }
    BackHandler(sheetOpen) { actions.vod.closeSheet() }
    // Back never stops the channel: the player gives way to the list it covered, the channels to the categories.
    val playerShown = inTv && state.playerOpen && state.selected != null
    BackHandler(playerShown) { actions.minimizePlayer() }
    // Turned sideways, the player of a channel, a film or an episode fills the screen above the tab bar: a touch on
    // the picture lowers the bar with the controls, the next one raises it again. Upright or away from a player,
    // the bar is always there; a new channel or episode brings it back with its controls.
    val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val vodShown = sheetOpen && state.vod != null && (state.destination == Destination.MOVIES || state.destination == Destination.SERIES)
    var barLowered by rememberSaveable { mutableStateOf(false) }
    val tapToLower = sideways && (playerShown || vodShown)
    LaunchedEffect(tapToLower, state.playItem?.id) { barLowered = false }
    BackHandler(inTv && !playerShown && state.channelsOpen) { actions.closeCategory() }
    BackHandler(!state.sourcesOpen && !sheetOpen && !guideOpen && !(inTv && (playerShown || state.channelsOpen)) && state.destination != Destination.HOME) {
        actions.navigate(Destination.HOME)
    }
    Scaffold(containerColor = Palette.Ink, bottomBar = {
        AnimatedVisibility(
            !(tapToLower && barLowered),
            enter = slideInVertically { it } + expandVertically(expandFrom = Alignment.Top),
            exit = slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Top)
        ) { MiptvNavBar(if (state.sourcesOpen) null else state.destination, actions.navigate) }
    }) { padding ->
        val guide = state.guideSheet
        val scrollState = key(state.destination, state.sourcesOpen, state.channelsOpen, state.playerOpen && state.selected != null, state.movieSheet?.movie?.id, state.seriesSheet?.series?.id, guide?.channel?.id, guide?.dayStartMs) { rememberLazyListState() }
        // Once the day's programmes arrive, show the one on air (or the searched one) first.
        LaunchedEffect(guideOpen, guide?.channel?.id, guide?.dayStartMs, guide?.loading) {
            if (!guideOpen || guide == null || guide.loading) return@LaunchedEffect
            val target = guide.focusMs ?: guide.programmes.firstOrNull { it.isLive(state.nowMs) }?.startMs ?: return@LaunchedEffect
            val index = guide.programmes.indexOfFirst { it.startMs == target }
            if (index > 0) scrollState.scrollToItem(GUIDE_HEADER_ITEMS + index)
        }
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when {
                state.sourcesOpen -> {
                    BackHeader("Sources", { actions.openSources(false) }, "close-sources")
                    ContentList(scrollState, padded = true) { sourcesSection(state, actions.sources) }
                }
                guideOpen -> ContentList(scrollState, padded = true) {
                    guideSection(state, guide!!, playback?.takeIf { state.selected != null }, actions.guide, actions.fullscreen, actions.toggleFavorite)
                }
                state.destination == Destination.HOME -> ContentList(scrollState, spacing = 22.dp) { homeSection(state, pages.home, actions) }
                state.destination == Destination.TV -> TvScreen(state, pages.tv, playback?.takeIf { state.selected != null }, actions, scrollState) { controls -> barLowered = !controls }
                state.destination == Destination.MOVIES -> MoviesScreen(state, pages.movies, playback, actions.vod, scrollState, sideways) { controls -> barLowered = !controls }
                state.destination == Destination.SERIES -> SeriesScreen(state, pages.series, playback, actions.vod, nextEpisode, scrollState, sideways) { controls -> barLowered = !controls }
                else -> {
                    AppHeader("Recherche", state.sourcesOpen, actions.openSources)
                    ContentList(scrollState, padded = true) { searchSection(state, pages, actions) }
                }
            }
        }
    }
}

/** The scrolling body of every screen. [padded] lists get side margins; others lay out edge to edge. */
@Composable
fun ContentList(listState: LazyListState, padded: Boolean = false, spacing: Dp = 16.dp, content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().imePadding().testTag("content"),
        contentPadding = if (padded) PaddingValues(16.dp) else PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

private val Gutter = Modifier.padding(horizontal = 16.dp)

private fun LazyListScope.homeSection(state: LiveState, channels: LazyPagingItems<Channel>, actions: PhoneActions) {
    item(key = "hero") { HomeHero(state, actions) }
    homeVodRows(state, actions.vod)
    favoriteChannelsRow(state, actions)
    if (state.hasSources) {
        // Ten rows at most: one block, so the rows sit together rather than at the sections' spacing.
        item(key = "discover") {
            Column {
                Text("À découvrir", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Gutter.padding(bottom = 6.dp))
                for (index in 0 until minOf(10, channels.itemCount)) {
                    channels[index]?.let { channel ->
                        key(channel.id) { ChannelRow(channel, state, false, actions.toggleFavorite, actions.guide.open, Modifier.padding(horizontal = 12.dp)) { actions.openChannel(channel) } }
                    }
                }
            }
        }
    }
}

/** One page of the home carousel. */
private class HeroSlide(val key: String, val title: String, val meta: String?, val synopsis: String?, val imageUrl: String?, val play: (() -> Unit)?, val open: () -> Unit)

/**
 * Accueil's hero: the newest films (or series) in a carousel that turns on its own, under the brand bar.
 * Without a provider catalogue it welcomes and leads to the TV tab or the Sources.
 */
@Composable
private fun HomeHero(state: LiveState, actions: PhoneActions) {
    val slides = remember(state.recentMovies, state.recentSeries, state.categoryNames) {
        state.recentMovies.take(6).map { movie ->
            HeroSlide(movie.id, movie.title, listOfNotNull(state.categoryNames[movie.categoryId]?.let { splitZone(it).second }, movie.year?.toString(), movie.durationSeconds?.let(::formatDuration)).joinToString(" · ").ifEmpty { null },
                movie.synopsis, movie.posterUrl, play = { actions.vod.openMovie(movie); actions.vod.playMovie(movie, false) }, open = { actions.vod.openMovie(movie) })
        }.ifEmpty {
            state.recentSeries.take(6).map { series ->
                HeroSlide(series.id, series.title, listOfNotNull(state.categoryNames[series.categoryId]?.let { splitZone(it).second }, series.year?.toString()).joinToString(" · ").ifEmpty { null },
                    series.synopsis, series.posterUrl, play = null, open = { actions.vod.openSeries(series) })
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(500.dp).testTag("home-hero")) {
        if (slides.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF412036), Color(0xFF192844)))))
            HeroShade()
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Votre cinéma de poche", fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 34.sp)
                Text(
                    when {
                        state.hasXtream -> "${state.channelCount} chaînes, ${state.movieCount} films et ${state.seriesCount} séries, depuis ${state.sources.size} source(s)."
                        state.hasSources -> "${state.channelCount} chaînes dans ${state.imported.size} catégories, depuis ${state.sources.size} source(s)."
                        else -> "Ajoutez votre playlist M3U ou votre fournisseur Xtream pour retrouver vos chaînes, films et séries ici."
                    },
                    fontSize = 14.sp, color = Palette.Muted, textAlign = TextAlign.Center, lineHeight = 20.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    PillButton("Explorer la TV", { actions.navigate(Destination.TV) }, icon = MiptvIcons.Tv)
                    PillButton("Sources", { actions.openSources(true) }, Modifier.testTag("home-sources"), primary = false)
                }
            }
        } else {
            val pager = rememberPagerState { slides.size }
            LaunchedEffect(pager, slides.size) {
                while (slides.size > 1) {
                    kotlinx.coroutines.delay(6_000)
                    if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { slides[it].key }) { page ->
                val slide = slides[page]
                Box(Modifier.fillMaxSize()) {
                    Backdrop(slide.imageUrl, "Visuel du film")
                    HeroShade()
                    Column(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("NOUVEAUTÉ", color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Text(slide.title, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 37.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = slide.open))
                        slide.meta?.let { Text(it, fontSize = 13.sp, color = Palette.Muted) }
                        slide.synopsis?.let { Text(it, fontSize = 14.sp, color = Palette.Muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (slide.play != null) PillButton("Lecture", slide.play, Modifier.testTag("hero-play"), icon = MiptvIcons.Play)
                            PillButton(if (slide.play != null) "Détails" else "Voir la série", slide.open, Modifier.testTag("hero-details"), primary = slide.play == null)
                        }
                    }
                }
            }
            PagerDots(slides.size, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
        }
        // The brand bar sits over the picture.
        Box(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp)) {
            Text("miptv", color = Palette.Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart))
            Text("Accueil", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
            IconButton(onClick = { actions.openSources(!state.sourcesOpen) }, modifier = Modifier.align(Alignment.CenterEnd).testTag("open-sources")) {
                Icon(MiptvIcons.Settings, contentDescription = "Sources")
            }
        }
    }
}

/** Darkens the top for the brand bar and fades the bottom into the ground. */
@Composable
fun BoxScope.HeroShade() {
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Palette.Ink.copy(alpha = 0.55f), 0.25f to Color.Transparent, 0.45f to Color.Transparent, 1f to Palette.Ink)))
}

/** Full-bleed picture behind a hero, or a labelled tinted ground when there is none. */
@Composable
fun BoxScope.Backdrop(url: String?, placeholder: String) {
    Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color(0xFF412036), Color(0xFF192844)))), contentAlignment = Alignment.Center) {
        if (url != null) SubcomposeAsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            error = { HeroPlaceholder(placeholder) }, loading = { })
        else HeroPlaceholder(placeholder)
    }
}

@Composable
private fun HeroPlaceholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text.uppercase(), fontSize = 12.sp, letterSpacing = 2.sp, color = Palette.Text.copy(alpha = 0.35f))
    }
}

/** Accueil: the favourite channels as logo tiles with what they air. Tap plays; a long press opens the guide. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.favoriteChannelsRow(state: LiveState, actions: PhoneActions) {
    val favorites = state.favoriteChannels
    if (favorites.isEmpty()) return
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Gutter.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Chaînes favorites", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Toutes", color = Palette.Link, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { actions.openCategory(Category.FAVORITES.id) }.padding(8.dp).testTag("favorites-all"))
            }
            LazyRow(modifier = Modifier.testTag("now-row"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(favorites, key = { it.id }) { channel ->
                    val nowNext = state.nowNext[channel.id]
                    Column(
                        Modifier.width(84.dp).clip(RoundedCornerShape(14.dp))
                            .combinedClickable(onClickLabel = "Lire", onLongClickLabel = "Guide des programmes", onLongClick = { actions.guide.open(channel) }) { actions.openChannel(channel) }
                            .testTag("now-${channel.id}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ChannelLogo(channel, size = 72.dp, corner = 14.dp)
                        Text(nowNext?.now?.title ?: splitZone(channel.name).second, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Results grouped as channels, films and series; without an Xtream source only channels are searched. */
private fun LazyListScope.searchSection(state: LiveState, pages: ChannelPages, actions: PhoneActions) {
    val channels = pages.search
    item {
        TextField(value = state.query, onValueChange = actions.search,
            placeholder = { Text(when { state.hasXtream -> "Rechercher une chaîne, un film, une série"; state.hasGuide -> "Rechercher une chaîne ou un programme"; else -> "Rechercher une chaîne" }) },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(MiptvIcons.Search, contentDescription = null) },
            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { actions.search("") }) { Icon(MiptvIcons.Close, contentDescription = "Effacer") } },
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedContainerColor = Palette.Panel, unfocusedContainerColor = Palette.Panel),
            modifier = Modifier.fillMaxWidth().testTag("search-input"))
    }
    item {
        Text(
            if (state.hasXtream) "Recherche locale parmi ${state.channelCount} chaînes, ${state.movieCount} films et ${state.seriesCount} séries."
            else "Recherche locale parmi ${state.channelCount} chaînes importées.",
            fontSize = 13.sp, color = Palette.Muted
        )
    }
    if (state.hasXtream) item { SectionTitle("Chaînes · ${state.searchCount}") }
    if (state.searchCount == 0) item { Text(if (state.hasXtream) "Aucune chaîne." else "Aucun résultat.", color = if (state.hasXtream) Palette.Muted else Palette.Text) }
    items(channels.itemCount, key = channels.itemKey { "search-${it.id}" }) { index ->
        channels[index]?.let { channel -> ChannelRow(channel, state, false, actions.toggleFavorite, actions.guide.open) { actions.openChannel(channel) } }
    }
    searchProgrammeSection(state, actions.guide)
    searchVodSections(state, pages.searchMovies, pages.searchSeries, actions.vod)
}

/** Channel logo on a raised tile; without a picture, the short name of the channel. */
@Composable
fun ChannelLogo(channel: Channel, modifier: Modifier = Modifier, size: Dp = 52.dp, corner: Dp = 12.dp, highlight: Boolean = false) {
    Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(Palette.Raised), contentAlignment = Alignment.Center) {
        val short = @Composable {
            // Smaller tiles get a smaller type, so a word is not cut in two.
            val type = (size.value / 5f).coerceIn(9f, 13f)
            Text(splitZone(channel.name).second.split(' ').take(2).joinToString(" ").uppercase(), color = if (highlight) Palette.Accent else Palette.Text,
                fontSize = type.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (type + 2).sp,
                modifier = Modifier.padding(4.dp))
        }
        if (channel.logoUrl != null) {
            SubcomposeAsyncImage(
                model = channel.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size / 10),
                error = { short() }, loading = { }
            )
        } else short()
    }
}

/** Bookmark toggle shared by channels, films and series. */
@Composable
fun FavoriteButton(favorite: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            if (favorite) MiptvIcons.BookmarkFilled else MiptvIcons.Bookmark,
            contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
            tint = if (favorite) Palette.Accent else Palette.Dim
        )
    }
}
