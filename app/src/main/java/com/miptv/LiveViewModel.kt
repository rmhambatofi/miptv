package com.miptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Destination(val label: String) { HOME("Accueil"), TV("TV"), MOVIES("Films"), SERIES("Séries"), SEARCH("Recherche") }
data class LiveState(
    val catalog: Catalog,
    val categoryId: String,
    val selected: Channel? = null,
    val destination: Destination = Destination.HOME,
    val query: String = ""
) {
    val visibleChannels get() = catalog.channelsIn(categoryId)
    val searchChannels get() = catalog.channels.filter { it.name.contains(query.trim(), ignoreCase = true) }
}
class LiveViewModel(
    repository: ChannelRepository = DemoRepository(),
    private val saved: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    private val catalog = repository.catalog
    private val initialCategory = saved.get<String>("category")?.takeIf { id -> catalog.categories.any { it.id == id } }
        ?: catalog.categories.firstOrNull()?.id.orEmpty()
    private val mutable = MutableStateFlow(LiveState(
        catalog = catalog,
        categoryId = initialCategory,
        destination = Destination.entries.firstOrNull { it.name == saved.get<String>("destination") } ?: Destination.HOME,
        query = saved.get<String>("query").orEmpty()
    ))
    val state = mutable.asStateFlow()
    fun navigate(destination: Destination) {
        saved["destination"] = destination.name
        mutable.value = mutable.value.copy(destination = destination, selected = null)
    }
    fun category(id: String) {
        if (catalog.categories.none { it.id == id } || id == mutable.value.categoryId) return
        saved["category"] = id
        mutable.value = mutable.value.copy(categoryId = id, selected = null)
    }
    fun select(channel: Channel) {
        if (channel in mutable.value.visibleChannels) mutable.value = mutable.value.copy(selected = channel)
    }
    fun openChannel(channel: Channel) {
        if (channel !in catalog.channels) return
        navigate(Destination.TV)
        category(channel.categoryId)
        select(channel)
    }
    fun search(query: String) {
        saved["query"] = query
        mutable.value = mutable.value.copy(query = query)
    }
}
