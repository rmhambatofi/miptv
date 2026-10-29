package com.miptv

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiveViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    private val loader = FakeLoader(mutableMapOf("https://demo.example/list.m3u" to DEMO_PLAYLIST))

    private fun loadedModel(saved: SavedStateHandle = SavedStateHandle()): LiveViewModel = runBlocking {
        val repository = MemoryChannelRepository(loader)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        LiveViewModel(repository, saved).also { dispatcher.idle() }
    }

    private val LiveViewModel.channels get() = state.value.catalog.channels
    private fun LiveViewModel.channel(tvgId: String) = channels.first { it.tvgId == tvgId }
    private fun LiveViewModel.categoryOf(tvgId: String) = channel(tvgId).categoryId

    @Test fun catalogIsReadyAndShowsAllChannelsByDefault() {
        val vm = loadedModel()
        assertTrue(vm.state.value.ready)
        assertEquals(Category.ALL.id, vm.state.value.categoryId)
        assertEquals(2, vm.state.value.visibleChannels.size)
        assertEquals(listOf("Toutes", "Favoris", "Animation", "Cinéma"), vm.state.value.categories.map { it.name })
    }

    @Test fun categoryChangeStopsPlaybackAndHandlesEmptySelection() {
        val vm = loadedModel()
        vm.select(vm.state.value.visibleChannels.first())
        assertNotNull(vm.state.value.selected)
        vm.category(vm.categoryOf("sintel"))
        assertNull(vm.state.value.selected)
        assertEquals(listOf("Sintel"), vm.state.value.visibleChannels.map { it.name })
    }

    @Test fun invalidSelectionAndCategoryAreIgnored() {
        val vm = loadedModel()
        vm.category(vm.categoryOf("sintel"))
        vm.select(vm.channel("bbb"))
        vm.category("missing")
        assertNull(vm.state.value.selected)
        assertEquals(vm.categoryOf("sintel"), vm.state.value.categoryId)
    }

    @Test fun leavingTvClearsPlayback() {
        val vm = loadedModel()
        vm.openChannel(vm.channels.last())
        assertEquals(Destination.TV, vm.state.value.destination)
        assertNotNull(vm.state.value.selected)
        vm.navigate(Destination.MOVIES)
        assertNull(vm.state.value.selected)
    }

    @Test fun searchIsTrimmedCaseInsensitiveAndHandlesNoMatch() {
        val vm = loadedModel()
        vm.search("  SINTEL ")
        assertEquals(listOf("sintel"), vm.state.value.searchChannels.map { it.tvgId })
        vm.search("no such film")
        assertTrue(vm.state.value.searchChannels.isEmpty())
        vm.search("")
        assertEquals(2, vm.state.value.searchChannels.size)
    }

    @Test fun openingSearchResultKeepsCurrentCategoryWhenItContainsTheChannel() {
        val vm = loadedModel()
        val channel = vm.channel("sintel")
        vm.openChannel(channel)
        assertEquals(Category.ALL.id, vm.state.value.categoryId)
        assertEquals(channel, vm.state.value.selected)
        vm.category(vm.categoryOf("bbb"))
        vm.openChannel(channel)
        assertEquals(channel.categoryId, vm.state.value.categoryId)
        assertEquals(channel, vm.state.value.selected)
    }

    @Test fun forgedChannelIsRejected() {
        val vm = loadedModel()
        vm.openChannel(vm.channels.first().copy(streamUrl = "https://example.invalid/video"))
        assertNull(vm.state.value.selected)
        assertEquals(Destination.HOME, vm.state.value.destination)
    }

    @Test fun savedNavigationCategoryAndQueryAreRestoredWithoutAutoplay() = runTest {
        val handle = SavedStateHandle()
        val repository = MemoryChannelRepository(loader)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        val vm = LiveViewModel(repository, handle)
        dispatcher.idle()
        vm.openChannel(vm.channels.last())
        vm.category(vm.categoryOf("sintel"))
        vm.search("Sintel")
        vm.openSources(true)
        val restored = LiveViewModel(repository, handle)
        dispatcher.idle()
        assertEquals(Destination.TV, restored.state.value.destination)
        assertEquals(vm.categoryOf("sintel"), restored.state.value.categoryId)
        assertEquals("Sintel", restored.state.value.query)
        assertTrue(restored.state.value.sourcesOpen)
        assertNull(restored.state.value.selected)
    }

    @Test fun emptyRepositoryIsReadyWithoutSources() {
        val vm = LiveViewModel(MemoryChannelRepository(loader))
        dispatcher.idle()
        assertTrue(vm.state.value.ready)
        assertFalse(vm.state.value.hasSources)
        vm.category("missing")
        assertTrue(vm.state.value.visibleChannels.isEmpty())
        assertTrue(vm.state.value.searchChannels.isEmpty())
    }

    @Test fun addingUrlSourceImportsAndReportsCount() {
        val vm = LiveViewModel(MemoryChannelRepository(loader))
        dispatcher.idle()
        vm.addUrlSource("", "https://demo.example/list.m3u")
        val state = dispatcher.awaitImport(vm)
        assertFalse(state.importStatus.running)
        assertFalse(state.importStatus.error)
        assertEquals("2 chaînes importées depuis « demo.example ».", state.importStatus.message)
        assertEquals(listOf("demo.example"), state.sources.map { it.name })
        assertEquals(2, state.catalog.channels.size)
        assertNotNull(state.sources.single().lastRefreshed)
    }

    @Test fun invalidUrlAndUnreachableHostAreReportedWithoutAddingASource() {
        val vm = LiveViewModel(MemoryChannelRepository(loader))
        dispatcher.idle()
        vm.addUrlSource("x", "ftp://demo.example/list.m3u")
        dispatcher.idle()
        assertTrue(vm.state.value.importStatus.error)
        assertEquals(0, loader.opened)
        vm.addUrlSource("x", "https://nowhere.example/list.m3u")
        assertTrue(dispatcher.awaitImport(vm).importStatus.error)
        assertEquals("Hôte introuvable. Vérifiez l'adresse et la connexion.", vm.state.value.importStatus.message)
        assertTrue(vm.state.value.sources.isEmpty())
    }

    @Test fun refreshKeepsPreviousChannelsOnFailureAndRemoveClearsCatalog() {
        val vm = loadedModel()
        val id = vm.state.value.sources.single().id
        loader["https://demo.example/list.m3u"] = "#EXTM3U\n# vide\n"
        vm.refreshSource(id)
        assertTrue(dispatcher.awaitImport(vm).importStatus.error)
        assertEquals(2, vm.channels.size)
        assertEquals("Aucune chaîne trouvée dans cette playlist.", vm.state.value.sources.single().lastError)
        vm.removeSource(id)
        dispatcher.idle()
        assertTrue(vm.state.value.sources.isEmpty())
        assertTrue(vm.channels.isEmpty())
        assertTrue(vm.state.value.ready)
    }

    @Test fun refreshDropsSelectionOfVanishedChannelAndFallsBackToAllCategory() {
        val vm = loadedModel()
        val id = vm.state.value.sources.single().id
        vm.openChannel(vm.channel("sintel"))
        vm.category(vm.categoryOf("sintel"))
        vm.select(vm.channel("sintel"))
        loader["https://demo.example/list.m3u"] = "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbb\" group-title=\"Animation\",Big Buck Bunny\nhttps://a/1\n"
        vm.refreshSource(id)
        dispatcher.awaitImport(vm)
        assertNull(vm.state.value.selected)
        assertEquals(Category.ALL.id, vm.state.value.categoryId)
        assertEquals(1, vm.channels.size)
    }

    @Test fun navigatingClosesSourcesScreen() {
        val vm = loadedModel()
        vm.openSources(true)
        vm.navigate(Destination.TV)
        assertFalse(vm.state.value.sourcesOpen)
    }

    @Test fun filterNarrowsTheSelectedCategoryAndIsRestored() {
        val handle = SavedStateHandle()
        val vm = loadedModel(handle)
        vm.filter("  bunny ")
        assertEquals(listOf("Big Buck Bunny"), vm.state.value.visibleChannels.map { it.name })
        vm.category(vm.categoryOf("sintel"))
        assertTrue(vm.state.value.visibleChannels.isEmpty())
        vm.filter("")
        assertEquals(listOf("Sintel"), vm.state.value.visibleChannels.map { it.name })
        vm.filter("x")
        assertEquals("x", LiveViewModel(MemoryChannelRepository(loader), handle).state.value.filter)
        assertEquals("Cinéma", vm.state.value.categoryNames[vm.categoryOf("sintel")])
    }

    @Test fun fullscreenRequiresASelectionAndEndsWithIt() {
        val vm = loadedModel()
        vm.fullscreen(true)
        assertFalse(vm.state.value.fullscreen)
        vm.openChannel(vm.channel("bbb"))
        vm.fullscreen(true)
        assertTrue(vm.state.value.fullscreen)
        vm.fullscreen(false)
        assertFalse(vm.state.value.fullscreen)
        vm.fullscreen(true)
        vm.category(vm.categoryOf("sintel"))
        assertFalse(vm.state.value.fullscreen)
        vm.select(vm.channel("sintel"))
        vm.fullscreen(true)
        vm.navigate(Destination.HOME)
        assertFalse(vm.state.value.fullscreen)
        assertNull(vm.state.value.selected)
    }

    @Test fun fullscreenEndsWhenTheChannelVanishesOnRefresh() {
        val vm = loadedModel()
        vm.openChannel(vm.channel("sintel"))
        vm.fullscreen(true)
        loader["https://demo.example/list.m3u"] = "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbb\" group-title=\"Animation\",Big Buck Bunny\nhttps://a/1\n"
        vm.refreshSource(vm.state.value.sources.single().id)
        dispatcher.awaitImport(vm)
        assertFalse(vm.state.value.fullscreen)
        assertNull(vm.state.value.selected)
    }

    @Test fun favoritesToggleSurviveRefreshAndFeedTheFavoritesCategory() {
        val vm = loadedModel()
        vm.category(Category.FAVORITES.id)
        assertTrue(vm.state.value.visibleChannels.isEmpty())
        vm.toggleFavorite(vm.channel("sintel"))
        dispatcher.idle()
        assertEquals(listOf("Sintel"), vm.state.value.visibleChannels.map { it.name })
        assertTrue(vm.channel("sintel").favorite)
        vm.select(vm.channel("sintel"))
        vm.toggleFavorite(vm.channel("bbb"))
        dispatcher.idle()
        assertEquals(2, vm.state.value.visibleChannels.size)
        assertEquals("sintel", vm.state.value.selected?.tvgId)
        loader["https://demo.example/list.m3u"] = DEMO_PLAYLIST.replace("Sintel", "Sintel HD")
        vm.refreshSource(vm.state.value.sources.single().id)
        dispatcher.awaitImport(vm)
        assertEquals(listOf("Big Buck Bunny", "Sintel HD"), vm.state.value.visibleChannels.map { it.name })
        assertEquals("Sintel HD", vm.state.value.selected?.name)
        vm.toggleFavorite(vm.channel("sintel"))
        dispatcher.idle()
        assertEquals(listOf("Big Buck Bunny"), vm.state.value.visibleChannels.map { it.name })
        // Un-starring the playing channel hides it from the list but does not interrupt playback.
        assertEquals("Sintel HD", vm.state.value.selected?.name)
        assertFalse(vm.state.value.selected!!.favorite)
    }
}
