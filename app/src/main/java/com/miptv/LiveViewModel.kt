package com.miptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Destination(val label: String) { HOME("Accueil"), TV("TV"), MOVIES("Films"), SERIES("Séries"), SEARCH("Recherche") }

data class ImportStatus(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

data class LiveState(
    val catalog: Catalog = Catalog.EMPTY,
    val sources: List<Source> = emptyList(),
    /** False until the stored catalog has been read once; avoids flashing the "no source" screen. */
    val ready: Boolean = false,
    val categoryId: String = Category.ALL.id,
    val selected: Channel? = null,
    val destination: Destination = Destination.HOME,
    val query: String = "",
    /** Name filter applied inside the TV tab, on top of the selected category. */
    val filter: String = "",
    val sourcesOpen: Boolean = false,
    val importStatus: ImportStatus = ImportStatus()
) {
    val categories: List<Category> get() = listOf(Category.ALL) + catalog.categories
    val hasSources: Boolean get() = sources.isNotEmpty()
    val categoryNames: Map<String, String> by lazy { categories.associate { it.id to it.name } }
    val visibleChannels: List<Channel> by lazy {
        val needle = filter.trim()
        val inCategory = catalog.channelsIn(categoryId)
        if (needle.isEmpty()) inCategory else inCategory.filter { it.name.contains(needle, ignoreCase = true) }
    }
    val searchChannels: List<Channel> by lazy {
        val needle = query.trim()
        if (needle.isEmpty()) catalog.channels else catalog.channels.filter { it.name.contains(needle, ignoreCase = true) }
    }
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

    init {
        viewModelScope.launch { repository.catalog.collect { catalog -> mutable.update { it.withCatalog(catalog) } } }
        viewModelScope.launch { repository.sources.collect { sources -> mutable.update { it.copy(sources = sources) } } }
    }

    private fun LiveState.withCatalog(catalog: Catalog): LiveState {
        // No SavedStateHandle write here: collectors may run off the main thread, and an unknown saved category is re-validated on restore.
        val categoryId = if (categories(catalog).any { it.id == categoryId }) categoryId else Category.ALL.id
        val selected = selected?.let { current -> catalog.channels.firstOrNull { it.id == current.id } }
        return copy(catalog = catalog, categoryId = categoryId, selected = selected, ready = true)
    }

    private fun categories(catalog: Catalog) = listOf(Category.ALL) + catalog.categories

    fun navigate(destination: Destination) {
        saved["destination"] = destination.name
        mutable.update { it.copy(destination = destination, selected = null) }
        openSources(false)
    }

    fun category(id: String) {
        val state = mutable.value
        if (state.categories.none { it.id == id } || id == state.categoryId) return
        saved["category"] = id
        mutable.update { it.copy(categoryId = id, selected = null) }
    }

    fun select(channel: Channel) {
        mutable.update { state -> if (channel in state.visibleChannels) state.copy(selected = channel) else state }
    }

    fun openChannel(channel: Channel) {
        val real = mutable.value.catalog.channels.firstOrNull { it.id == channel.id && it.streamUrl == channel.streamUrl } ?: return
        navigate(Destination.TV)
        if (mutable.value.catalog.channelsIn(mutable.value.categoryId).none { it.id == real.id }) category(real.categoryId)
        select(real)
    }

    fun search(query: String) {
        saved["query"] = query
        mutable.update { it.copy(query = query) }
    }

    fun filter(text: String) {
        saved["filter"] = text
        mutable.update { it.copy(filter = text) }
    }

    fun openSources(open: Boolean) {
        saved["sourcesOpen"] = open
        mutable.update { if (open) it.copy(sourcesOpen = true, selected = null) else it.copy(sourcesOpen = false, importStatus = ImportStatus()) }
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

    fun refreshSource(id: Long) = import { repository.refreshSource(id) }

    fun removeSource(id: Long) {
        viewModelScope.launch { repository.removeSource(id) }
    }

    private fun import(block: suspend () -> ImportResult) {
        if (mutable.value.importStatus.running) return
        mutable.update { it.copy(importStatus = ImportStatus(running = true, message = "Importation en cours…")) }
        viewModelScope.launch {
            val status = when (val result = block()) {
                is ImportResult.Success -> ImportStatus(message = "${result.source.channelCount} chaînes importées depuis « ${result.source.name} ».")
                is ImportResult.Failure -> ImportStatus(message = result.message, error = true)
            }
            mutable.update { it.copy(importStatus = status) }
        }
    }
}
