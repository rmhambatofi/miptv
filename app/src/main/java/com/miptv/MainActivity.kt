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
    val removeSource: (Long) -> Unit
)

fun LiveViewModel.actions() = PhoneActions(
    ::navigate, ::category, ::select, ::search, ::filter, ::fullscreen, ::toggleFavorite, ::openChannel, ::openSources, ::addUrlSource, ::addFileSource, ::refreshSource, ::removeSource
)

/** Paged channel lists, collected by the screens that show them. */
class ChannelFeeds(val tv: Flow<PagingData<Channel>>, val search: Flow<PagingData<Channel>>, val home: Flow<PagingData<Channel>>)

fun LiveViewModel.feeds() = ChannelFeeds(channels, searchResults, homeChannels)

/** The collected lists, passed down to the lazy sections. */
class ChannelPages(val tv: LazyPagingItems<Channel>, val search: LazyPagingItems<Channel>, val home: LazyPagingItems<Channel>)

@Composable
fun PhoneApp(state: LiveState, actions: PhoneActions, feeds: ChannelFeeds) {
    val pages = ChannelPages(feeds.tv.collectAsLazyPagingItems(), feeds.search.collectAsLazyPagingItems(), feeds.home.collectAsLazyPagingItems())
    // Hoisted above both layouts: switching to fullscreen re-attaches the surface, never the player.
    val playback = rememberPlayback(state.selected)
    if (state.fullscreen && playback != null) {
        BackHandler { actions.fullscreen(false) }
        FullscreenPlayer(playback) { actions.fullscreen(false) }
        return
    }
    BackHandler(state.sourcesOpen) { actions.openSources(false) }
    BackHandler(!state.sourcesOpen && state.destination != Destination.HOME) { actions.navigate(Destination.HOME) }
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
        val scrollState = key(state.destination, state.sourcesOpen) { rememberLazyListState() }
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
            item { Text(if (state.sourcesOpen) "Sources" else state.destination.label, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
            when {
                state.sourcesOpen -> sourcesSection(state, actions.addUrlSource, actions.addFileSource, actions.refreshSource, actions.removeSource)
                state.destination == Destination.HOME -> homeSection(state, pages.home, actions)
                state.destination == Destination.TV -> tvSection(state, pages.tv, playback, actions)
                state.destination == Destination.MOVIES -> {
                    item { Text("Catalogue fictif · Les films arriveront avec un fournisseur Xtream.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(DemoContent.movies, key = { it.id }) { item -> CatalogCard(item.title, item.synopsis.orEmpty()) }
                }
                state.destination == Destination.SERIES -> {
                    item { Text("Catalogue fictif · Les séries arriveront avec un fournisseur Xtream.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(DemoContent.series, key = { it.id }) { item -> CatalogCard(item.title, item.synopsis.orEmpty()) }
                }
                else -> searchSection(state, pages.search, actions)
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
                if (state.hasSources) "${state.channelCount} chaînes dans ${state.imported.size} catégories, depuis ${state.sources.size} source(s)."
                else "Ajoutez votre playlist M3U pour retrouver vos chaînes ici."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { actions.navigate(Destination.TV) }) { Text("Explorer la TV") }
                OutlinedButton(onClick = { actions.openSources(true) }, modifier = Modifier.testTag("home-sources")) { Text("Sources") }
            }
        }
    }
    if (state.hasSources) {
        item { SectionTitle("À découvrir") }
        items(minOf(10, channels.itemCount), key = channels.itemKey { "home-${it.id}" }) { index ->
            channels[index]?.let { channel -> ChannelCard(channel, state, false, actions.toggleFavorite) { actions.openChannel(channel) } }
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
        } else Preview(playback, state.isFavorite(playback.channel), actions.fullscreen) { actions.toggleFavorite(playback.channel) }
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
        channels[index]?.let { channel -> ChannelCard(channel, state, state.selected?.id == channel.id, actions.toggleFavorite) { actions.select(channel) } }
    }
}

private fun LazyListScope.searchSection(state: LiveState, channels: LazyPagingItems<Channel>, actions: PhoneActions) {
    item {
        OutlinedTextField(value = state.query, onValueChange = actions.search,
            label = { Text("Rechercher une chaîne") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag("search-input"))
    }
    item { Text("Recherche locale parmi ${state.channelCount} chaînes importées.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (state.searchCount == 0) item { Text("Aucun résultat.") }
    items(channels.itemCount, key = channels.itemKey { "search-${it.id}" }) { index ->
        channels[index]?.let { channel -> ChannelCard(channel, state, false, actions.toggleFavorite) { actions.openChannel(channel) } }
    }
}

@Composable
private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

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
private fun ChannelCard(channel: Channel, state: LiveState, selected: Boolean, toggleFavorite: (Channel) -> Unit, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("channel-${channel.id}"),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF3B2534) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel)
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(
                    (if (selected) "En lecture · " else "") + (state.categoryNames[channel.categoryId] ?: M3uParser.DEFAULT_GROUP),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            FavoriteButton(state.isFavorite(channel), Modifier.testTag("favorite-${channel.id}")) { toggleFavorite(channel) }
        }
    }
}

@Composable
private fun CatalogCard(title: String, description: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
