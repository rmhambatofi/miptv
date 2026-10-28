package com.miptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

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
            MiptvTheme { PhoneApp(state, model.actions()) }
        }
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
    val openChannel: (Channel) -> Unit,
    val openSources: (Boolean) -> Unit,
    val addUrlSource: (String, String) -> Unit,
    val addFileSource: (String, String) -> Unit,
    val refreshSource: (Long) -> Unit,
    val removeSource: (Long) -> Unit
)

fun LiveViewModel.actions() = PhoneActions(::navigate, ::category, ::select, ::search, ::openChannel, ::openSources, ::addUrlSource, ::addFileSource, ::refreshSource, ::removeSource)

@Composable
fun PhoneApp(state: LiveState, actions: PhoneActions) {
    val navigate = actions.navigate
    val category = actions.category
    val select = actions.select
    val search = actions.search
    val openChannel = actions.openChannel
    BackHandler(state.sourcesOpen) { actions.openSources(false) }
    BackHandler(!state.sourcesOpen && state.destination != Destination.HOME) { navigate(Destination.HOME) }
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
                    selected = state.destination == destination,
                    onClick = { navigate(destination) },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(destination.label, maxLines = 1) },
                    modifier = Modifier.testTag("nav-${destination.name}")
                )
            }
        }
    }) { padding ->
        val scrollState = key(state.destination) { rememberLazyListState() }
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("content"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("miptv", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { actions.openSources(!state.sourcesOpen) }, modifier = Modifier.testTag("open-sources")) {
                        Icon(Icons.Default.Settings, contentDescription = "Sources", tint = if (state.sourcesOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text(if (state.sourcesOpen) "Sources" else state.destination.label, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
            if (state.sourcesOpen) sourcesSection(state, actions.addUrlSource, actions.addFileSource, actions.refreshSource, actions.removeSource)
            else when (state.destination) {
                Destination.HOME -> {
                    item {
                        Column(Modifier.fillMaxWidth().background(
                            Brush.linearGradient(listOf(Color(0xFF412036), Color(0xFF192844))), RoundedCornerShape(24.dp)
                        ).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Votre cinéma de poche", style = MaterialTheme.typography.headlineMedium)
                            Text("Découvrez une expérience mobile, avec des films de démonstration ouverts et aucun compte à configurer.")
                            Button(onClick = { navigate(Destination.TV) }) { Text("Explorer la TV") }
                        }
                    }
                    item { SectionTitle("À découvrir") }
                    items(state.catalog.channels, key = { "home-${it.id}" }) { channel -> ChannelCard(channel, false) { openChannel(channel) } }
                    item { Text("Mode démonstration · Aucun fournisseur connecté", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Destination.TV -> {
                    item { Text("Vidéos de démonstration à la demande. Aucune diffusion TV en direct.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item {
                        val channel = state.selected
                        if (channel == null) {
                            Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                                    Text("Votre aperçu", style = MaterialTheme.typography.titleLarge)
                                    Text("Touchez une chaîne ci-dessous pour lancer la démo.")
                                }
                            }
                        } else key(channel.id) { Preview(channel) }
                    }
                    item { SectionTitle("Catégories") }
                    item {
                        LazyRow(modifier = Modifier.testTag("category-strip"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.categories, key = { it.id }) { item ->
                                FilterChip(selected = state.categoryId == item.id, onClick = { category(item.id) }, label = { Text(item.name) }, modifier = Modifier.testTag("category-${item.id}"))
                            }
                        }
                    }
                    item { SectionTitle("Chaînes · ${state.visibleChannels.size}") }
                    if (state.visibleChannels.isEmpty()) item { Text("Aucune chaîne dans cette catégorie.", modifier = Modifier.testTag("empty-category")) }
                    items(state.visibleChannels, key = { it.id }) { channel -> ChannelCard(channel, state.selected == channel) { select(channel) } }
                }
                Destination.MOVIES -> {
                    item { Text("Catalogue fictif · Lecture disponible uniquement dans TV.") }
                    items(DemoContent.movies, key = { it.id }) { item -> CatalogCard(item.title, item.description) }
                }
                Destination.SERIES -> {
                    item { Text("Catalogue fictif · Lecture disponible uniquement dans TV.") }
                    items(DemoContent.series, key = { it.id }) { item -> CatalogCard(item.title, item.description) }
                }
                Destination.SEARCH -> {
                    item {
                        OutlinedTextField(value = state.query, onValueChange = search,
                            label = { Text("Rechercher une démo") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().testTag("search-input"))
                    }
                    item { Text("Recherche locale parmi les chaînes de démonstration.") }
                    if (state.searchChannels.isEmpty()) item { Text("Aucun résultat.") }
                    items(state.searchChannels, key = { it.id }) { channel -> ChannelCard(channel, false) { openChannel(channel) } }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

@Composable
private fun ChannelCard(channel: Channel, selected: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("channel-${channel.id}"),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF3B2534) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(channel.name, style = MaterialTheme.typography.titleMedium)
                Text(if (selected) "Sélectionnée · Vidéo de démo" else "Vidéo de démo · Toucher pour regarder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
