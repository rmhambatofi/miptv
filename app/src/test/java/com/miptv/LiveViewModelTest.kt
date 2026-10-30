package com.miptv

import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiveViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    private val loader = FakeLoader(mutableMapOf("https://demo.example/list.m3u" to DEMO_PLAYLIST))

    private val repository = MemoryChannelRepository(loader)

    private fun loadedModel(saved: SavedStateHandle = SavedStateHandle()): LiveViewModel = runBlocking {
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        LiveViewModel(repository, saved).also { dispatcher.idle() }
    }

    /** Lists as the screens page them, for the model's current queries. */
    private fun list(query: ChannelQuery) = runBlocking { repository.channels(query).asSnapshot() }
    private val LiveViewModel.visible get() = list(state.value.channelQuery)
    private val LiveViewModel.results get() = list(state.value.searchQuery)
    private val LiveViewModel.all get() = list(ChannelQuery())
    private fun LiveViewModel.channel(tvgId: String) = all.first { it.tvgId == tvgId }
    private fun LiveViewModel.categoryOf(tvgId: String) = channel(tvgId).categoryId
    /** Opening re-reads the channel from the repository, asynchronously. */
    private fun LiveViewModel.open(channel: Channel) { openChannel(channel); dispatcher.idle() }

    @Test fun catalogIsReadyAndShowsAllChannelsByDefault() {
        val vm = loadedModel()
        assertTrue(vm.state.value.ready)
        assertEquals(Category.ALL.id, vm.state.value.categoryId)
        assertEquals(2, vm.visible.size)
        assertEquals(2, vm.state.value.visibleCount)
        assertEquals(listOf("Toutes", "Favoris", "Animation", "Cinéma"), vm.state.value.categories.map { it.name })
    }

    @Test fun categoryChangeStopsPlaybackAndHandlesEmptySelection() {
        val vm = loadedModel()
        vm.select(vm.visible.first())
        assertNotNull(vm.state.value.selected)
        vm.category(vm.categoryOf("sintel"))
        assertNull(vm.state.value.selected)
        assertEquals(listOf("Sintel"), vm.visible.map { it.name })
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
        vm.open(vm.all.last())
        assertEquals(Destination.TV, vm.state.value.destination)
        assertNotNull(vm.state.value.selected)
        vm.navigate(Destination.MOVIES)
        assertNull(vm.state.value.selected)
    }

    @Test fun tvTabGoesFromCategoriesToChannelsToPlayerAndBack() {
        val saved = SavedStateHandle()
        val vm = loadedModel(saved)
        vm.openCategory(vm.categoryOf("sintel"))
        assertEquals(Destination.TV, vm.state.value.destination)
        assertTrue(vm.state.value.channelsOpen)
        assertEquals(true, saved.get<Boolean>("channelsOpen"))
        vm.select(vm.channel("sintel"))
        vm.closePlayer()
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.channelsOpen)
        vm.select(vm.channel("sintel"))
        vm.closeCategory()
        assertNull(vm.state.value.selected)
        assertFalse(vm.state.value.channelsOpen)
        vm.openCategory("missing")
        assertFalse(vm.state.value.channelsOpen)
        // A channel opened from elsewhere plays with its category open behind it; the tab bar starts over.
        vm.navigate(Destination.SEARCH)
        vm.open(vm.channel("bbb"))
        assertTrue(vm.state.value.channelsOpen)
        assertEquals("bbb", vm.state.value.selected?.tvgId)
        vm.navigate(Destination.TV)
        assertFalse(vm.state.value.channelsOpen)
        assertEquals(false, saved.get<Boolean>("channelsOpen"))
    }

    @Test fun searchIsTrimmedCaseInsensitiveAndHandlesNoMatch() {
        val vm = loadedModel()
        vm.search("  SINTEL ")
        assertEquals(listOf("sintel"), vm.results.map { it.tvgId })
        vm.search("no such film")
        assertTrue(vm.results.isEmpty())
        vm.search("")
        assertEquals(2, vm.results.size)
    }

    @Test fun openingSearchResultKeepsCurrentCategoryWhenItContainsTheChannel() {
        val vm = loadedModel()
        val channel = vm.channel("sintel")
        vm.open(channel)
        assertEquals(Category.ALL.id, vm.state.value.categoryId)
        assertEquals(channel, vm.state.value.selected)
        vm.category(vm.categoryOf("bbb"))
        vm.open(channel)
        assertEquals(channel.categoryId, vm.state.value.categoryId)
        assertEquals(channel, vm.state.value.selected)
    }

    @Test fun forgedChannelIsRejected() {
        val vm = loadedModel()
        vm.open(vm.all.first().copy(streamUrl = "https://example.invalid/video"))
        assertNull(vm.state.value.selected)
        assertEquals(Destination.HOME, vm.state.value.destination)
    }

    @Test fun savedNavigationCategoryAndQueryAreRestoredWithoutAutoplay() = runTest {
        val handle = SavedStateHandle()
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        val vm = LiveViewModel(repository, handle)
        dispatcher.idle()
        vm.open(vm.all.last())
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
        val vm = LiveViewModel(repository)
        dispatcher.idle()
        assertTrue(vm.state.value.ready)
        assertFalse(vm.state.value.hasSources)
        vm.category("missing")
        assertTrue(vm.visible.isEmpty())
        assertTrue(vm.results.isEmpty())
    }

    @Test fun addingUrlSourceImportsAndReportsCount() {
        val vm = LiveViewModel(repository)
        dispatcher.idle()
        vm.addUrlSource("", "https://demo.example/list.m3u")
        val state = dispatcher.awaitImport(vm)
        assertFalse(state.importStatus.running)
        assertFalse(state.importStatus.error)
        assertEquals("2 chaînes importées depuis « demo.example ».", state.importStatus.message)
        assertEquals(listOf("demo.example"), state.sources.map { it.name })
        assertEquals(2, state.channelCount)
        assertNotNull(state.sources.single().lastRefreshed)
    }

    @Test fun invalidUrlAndUnreachableHostAreReportedWithoutAddingASource() {
        val vm = LiveViewModel(repository)
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
        assertEquals(2, vm.all.size)
        assertEquals("Aucune chaîne trouvée dans cette playlist.", vm.state.value.sources.single().lastError)
        vm.removeSource(id)
        dispatcher.idle()
        assertTrue(vm.state.value.sources.isEmpty())
        assertTrue(vm.all.isEmpty())
        assertTrue(vm.state.value.ready)
    }

    @Test fun refreshDropsSelectionOfVanishedChannelAndFallsBackToAllCategory() {
        val vm = loadedModel()
        val id = vm.state.value.sources.single().id
        vm.open(vm.channel("sintel"))
        vm.category(vm.categoryOf("sintel"))
        vm.select(vm.channel("sintel"))
        loader["https://demo.example/list.m3u"] = "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbb\" group-title=\"Animation\",Big Buck Bunny\nhttps://a/1\n"
        vm.refreshSource(id)
        dispatcher.awaitImport(vm)
        assertNull(vm.state.value.selected)
        assertEquals(Category.ALL.id, vm.state.value.categoryId)
        assertEquals(1, vm.all.size)
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
        assertEquals(listOf("Big Buck Bunny"), vm.visible.map { it.name })
        vm.category(vm.categoryOf("sintel"))
        assertTrue(vm.visible.isEmpty())
        vm.filter("")
        assertEquals(listOf("Sintel"), vm.visible.map { it.name })
        vm.filter("x")
        assertEquals("x", LiveViewModel(MemoryChannelRepository(loader), handle).state.value.filter)
        assertEquals("Cinéma", vm.state.value.categoryNames[vm.categoryOf("sintel")])
    }

    @Test fun fullscreenRequiresASelectionAndEndsWithIt() {
        val vm = loadedModel()
        vm.fullscreen(true)
        assertFalse(vm.state.value.fullscreen)
        vm.open(vm.channel("bbb"))
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
        vm.open(vm.channel("sintel"))
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
        assertTrue(vm.visible.isEmpty())
        vm.toggleFavorite(vm.channel("sintel"))
        dispatcher.idle()
        assertEquals(listOf("Sintel"), vm.visible.map { it.name })
        assertTrue(vm.state.value.isFavorite(vm.channel("sintel")))
        vm.select(vm.channel("sintel"))
        vm.toggleFavorite(vm.channel("bbb"))
        dispatcher.idle()
        assertEquals(2, vm.visible.size)
        assertEquals("sintel", vm.state.value.selected?.tvgId)
        loader["https://demo.example/list.m3u"] = DEMO_PLAYLIST.replace("Sintel", "Sintel HD")
        vm.refreshSource(vm.state.value.sources.single().id)
        dispatcher.awaitImport(vm)
        assertEquals(listOf("Big Buck Bunny", "Sintel HD"), vm.visible.map { it.name })
        assertEquals("Sintel HD", vm.state.value.selected?.name)
        vm.toggleFavorite(vm.channel("sintel"))
        dispatcher.idle()
        assertEquals(listOf("Big Buck Bunny"), vm.visible.map { it.name })
        // Un-starring the playing channel hides it from the list but does not interrupt playback.
        assertEquals("Sintel HD", vm.state.value.selected?.name)
        assertFalse(vm.state.value.isFavorite(vm.state.value.selected!!))
    }

    @Test fun filterAndSearchIgnoreCaseAndAccentsAndKeepCountsInStep() {
        loader["https://demo.example/accents.m3u"] = "#EXTM3U\n#EXTINF:-1 group-title=\"Infos\",CHAÎNE INFO\nhttps://a/1\n#EXTINF:-1 group-title=\"Infos\",Télé Matin\nhttps://a/2\n"
        runBlocking { repository.addSource("Accents", "https://demo.example/accents.m3u", SourceKind.URL) }
        val vm = LiveViewModel(repository).also { dispatcher.idle() }
        vm.filter("chaîne")
        dispatcher.idle()
        assertEquals(listOf("CHAÎNE INFO"), vm.visible.map { it.name })
        vm.filter("chaine")
        dispatcher.idle()
        assertEquals(listOf("CHAÎNE INFO"), vm.visible.map { it.name })
        assertEquals(1, vm.state.value.visibleCount)
        vm.search("TÉLÉ")
        dispatcher.idle()
        assertEquals(listOf("Télé Matin"), vm.results.map { it.name })
        assertEquals(1, vm.state.value.searchCount)
        assertEquals(2, vm.state.value.channelCount)
    }
}
