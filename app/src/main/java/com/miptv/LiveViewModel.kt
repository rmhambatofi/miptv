package com.miptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Destination(val label: String) { HOME("Accueil"), TV("TV"), MOVIES("Films"), SERIES("Séries"), SEARCH("Recherche") }

data class ImportStatus(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

data class LiveState(
    /** Imported categories; [categories] adds the pseudo-categories. */
    val imported: List<Category> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val sources: List<Source> = emptyList(),
    /** False until the stored sources have been read once; avoids flashing the "no source" screen. */
    val ready: Boolean = false,
    val categoryId: String = Category.ALL.id,
    val selected: Channel? = null,
    val destination: Destination = Destination.HOME,
    val query: String = "",
    /** Name filter applied inside the TV tab, on top of the selected category. */
    val filter: String = "",
    val sourcesOpen: Boolean = false,
    /** Only meaningful with a selection; cleared whenever the selection is cleared. */
    val fullscreen: Boolean = false,
    val importStatus: ImportStatus = ImportStatus(),
    val channelCount: Int = 0,
    /** Size of the TV list ([channelQuery]) and of the search results ([searchQuery]). */
    val visibleCount: Int = 0,
    val searchCount: Int = 0
) {
    val categories: List<Category> get() = listOf(Category.ALL, Category.FAVORITES) + imported
    val hasSources: Boolean get() = sources.isNotEmpty()
    val categoryNames: Map<String, String> by lazy { categories.associate { it.id to it.name } }
    val channelQuery: ChannelQuery get() = ChannelQuery(categoryId, filter.trim())
    val searchQuery: ChannelQuery get() = ChannelQuery(text = query.trim())
    fun isFavorite(channel: Channel): Boolean = channel.id in favoriteIds
}

class LiveViewModel(
    private val repository: ChannelRepository,
    private val saved: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    private val mutable = MutableStateFlow(LiveState(
        categoryId = saved.get<String>("category") ?: Category.ALL.id,
        destination = Destination.entries.firstOrNull { it.name == saved.get<String>("destination") } ?: Destination.HOME,
        query = saved.get<String>("query").orEmpty(),
        filter = saved.get<String>("filter").orEmpty(),
        sourcesOpen = saved.get<Boolean>("sourcesOpen") ?: false
    ))
    val state = mutable.asStateFlow()

    /** Paged lists for the TV tab, the search tab and the home preview; they follow the state's queries. */
    val channels: Flow<PagingData<Channel>> = paged { it.channelQuery }
    val searchResults: Flow<PagingData<Channel>> = paged { it.searchQuery }
    val homeChannels: Flow<PagingData<Channel>> = repository.channels(ChannelQuery()).cachedIn(viewModelScope)

    init {
        viewModelScope.launch { repository.categories.collect { categories -> mutable.update { it.withCategories(categories) } } }
        viewModelScope.launch { repository.favoriteIds.collect { ids -> mutable.update { it.copy(favoriteIds = ids) } } }
        viewModelScope.launch {
            repository.sources.collect { sources ->
                mutable.update { it.copy(sources = sources, ready = true) }
                // Every import or removal touches the sources: re-read the playing channel, which may be gone or renamed.
                revalidateSelection()
            }
        }
        counted({ ChannelQuery() }) { state, n -> state.copy(channelCount = n) }
        counted({ it.channelQuery }) { state, n -> state.copy(visibleCount = n) }
        counted({ it.searchQuery }) { state, n -> state.copy(searchCount = n) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun paged(query: (LiveState) -> ChannelQuery): Flow<PagingData<Channel>> =
        mutable.map(query).distinctUntilChanged().flatMapLatest { repository.channels(it) }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun counted(query: (LiveState) -> ChannelQuery, apply: (LiveState, Int) -> LiveState) {
        viewModelScope.launch {
            mutable.map(query).distinctUntilChanged().flatMapLatest { repository.count(it) }.distinctUntilChanged()
                .collect { n -> mutable.update { apply(it, n) } }
        }
    }

    private fun LiveState.withCategories(imported: List<Category>): LiveState {
        // No SavedStateHandle write here: collectors may run off the main thread, and an unknown saved category is re-validated on restore.
        val known = categoryId == Category.ALL.id || categoryId == Category.FAVORITES.id || imported.any { it.id == categoryId }
        return copy(imported = imported, categoryId = if (known) categoryId else Category.ALL.id)
    }

    private suspend fun revalidateSelection() {
        val current = mutable.value.selected ?: return
        val fresh = repository.channel(current.id)
        mutable.update { state ->
            if (state.selected?.id != current.id) state
            else state.copy(selected = fresh, fullscreen = state.fullscreen && fresh != null)
        }
    }

    fun navigate(destination: Destination) {
        saved["destination"] = destination.name
        mutable.update { it.copy(destination = destination, selected = null, fullscreen = false) }
        openSources(false)
    }

    fun category(id: String) {
        val state = mutable.value
        if (state.categories.none { it.id == id } || id == state.categoryId) return
        saved["category"] = id
        mutable.update { it.copy(categoryId = id, selected = null, fullscreen = false) }
    }

    fun select(channel: Channel) {
        mutable.update { state -> if (state.channelQuery.matches(channel, state.favoriteIds)) state.copy(selected = channel) else state }
    }

    /** From the home or search lists: re-read the channel, so a stale or forged one is ignored. */
    fun openChannel(channel: Channel) {
        viewModelScope.launch {
            val real = repository.channel(channel.id)?.takeIf { it.streamUrl == channel.streamUrl } ?: return@launch
            navigate(Destination.TV)
            val state = mutable.value
            if (!ChannelQuery(state.categoryId).inCategory(real, state.favoriteIds)) category(real.categoryId)
            select(real)
        }
    }

    fun search(query: String) {
        saved["query"] = query
        mutable.update { it.copy(query = query) }
    }

    fun filter(text: String) {
        saved["filter"] = text
        mutable.update { it.copy(filter = text) }
    }

    fun toggleFavorite(channel: Channel) {
        val favorite = mutable.value.isFavorite(channel)
        viewModelScope.launch { repository.setFavorite(channel.id, !favorite) }
    }

    fun fullscreen(on: Boolean) {
        mutable.update { it.copy(fullscreen = on && it.selected != null) }
    }

    fun openSources(open: Boolean) {
        saved["sourcesOpen"] = open
        mutable.update { if (open) it.copy(sourcesOpen = true, selected = null, fullscreen = false) else it.copy(sourcesOpen = false, importStatus = ImportStatus()) }
    }

    fun addUrlSource(name: String, url: String) {
        val location = url.trim()
        if (!location.startsWith("http://", ignoreCase = true) && !location.startsWith("https://", ignoreCase = true)) {
            mutable.update { it.copy(importStatus = ImportStatus(message = "Adresse invalide : elle doit commencer par http:// ou https://.", error = true)) }
            return
        }
        val label = name.trim().ifEmpty { location.substringAfter("://").substringBefore('/').substringAfter('@').ifEmpty { "Playlist" } }
        import { repository.addSource(label, location, SourceKind.URL) }
    }

    fun addFileSource(name: String, uri: String) {
        if (uri.isBlank()) return
        import { repository.addSource(name.trim().ifEmpty { "Fichier M3U" }, uri, SourceKind.FILE) }
    }

    /** The password only passes through here: it is neither kept in the state nor in the saved handle. */
    fun addXtreamSource(name: String, server: String, username: String, password: String) {
        val normalized = normalizeXtreamServer(server)
        if (normalized.isEmpty() || username.isBlank() || password.isEmpty()) {
            mutable.update { it.copy(importStatus = ImportStatus(message = "Serveur, identifiant et mot de passe sont requis.", error = true)) }
            return
        }
        val label = name.trim().ifEmpty { normalized.substringAfter("://").substringBefore('/').ifEmpty { "Xtream" } }
        import { repository.addXtreamSource(label, XtreamAccount(normalized, username.trim(), password)) }
    }

    fun refreshSource(id: Long) = import { repository.refreshSource(id) }

    fun removeSource(id: Long) {
        viewModelScope.launch { repository.removeSource(id) }
    }

    private fun importedMessage(source: Source): String = when (source.kind) {
        SourceKind.XTREAM -> "${source.channelCount} chaînes, ${source.movieCount} films et ${source.seriesCount} séries importés depuis « ${source.name} »."
        else -> "${source.channelCount} chaînes importées depuis « ${source.name} »."
    }

    private fun import(block: suspend () -> ImportResult) {
        if (mutable.value.importStatus.running) return
        mutable.update { it.copy(importStatus = ImportStatus(running = true, message = "Importation en cours…")) }
        viewModelScope.launch {
            val status = when (val result = block()) {
                is ImportResult.Success -> ImportStatus(message = importedMessage(result.source))
                is ImportResult.Failure -> ImportStatus(message = result.message, error = true)
            }
            mutable.update { it.copy(importStatus = status) }
        }
    }
}
