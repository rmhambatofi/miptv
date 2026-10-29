package com.miptv

import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class XtreamViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val xtream = FakeXtreamClient()
    private val repository = MemoryChannelRepository(FakeLoader(mutableMapOf("https://demo.example/list.m3u" to DEMO_PLAYLIST)), xtream)

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = LiveViewModel(repository, saved).also { dispatcher.idle() }

    /** A model with one Xtream source already imported. */
    private fun loaded(): LiveViewModel {
        runBlocking { repository.addXtreamSource("Fournisseur", XtreamAccount("portal.example:8080", "u-test", "p-test")) }
        return model()
    }

    private fun LiveViewModel.movie(streamId: Long) = runBlocking { repository.movie("xtream:1:movie:$streamId")!! }
    private fun LiveViewModel.series(seriesId: Long) = runBlocking { repository.series("xtream:1:series:$seriesId")!! }
    // Lists are read from the repository for the model's queries: the model's cachedIn flows need the test dispatcher to run.
    private val LiveViewModel.visibleMovies get() = runBlocking { repository.movies(state.value.movieQuery).asSnapshot() }
    private val LiveViewModel.visibleSeries get() = runBlocking { repository.series(state.value.seriesQuery).asSnapshot() }
    private val LiveViewModel.foundMovies get() = runBlocking { repository.movies(state.value.searchVodQuery).asSnapshot() }
    private val LiveViewModel.foundSeries get() = runBlocking { repository.series(state.value.searchVodQuery).asSnapshot() }

    @Test fun addingAnXtreamSourceReportsChannelsFilmsAndSeries() {
        val vm = model()
        assertFalse(vm.state.value.hasXtream)
        vm.addXtreamSource("", "portal.example:8080", "u-test", "p-test")
        val state = dispatcher.awaitImport(vm)
        assertEquals("5 chaînes, 3 films et 2 séries importés depuis « portal.example:8080 ».", state.importStatus.message)
        assertFalse(state.importStatus.error)
        assertTrue(state.hasXtream)
        assertEquals(Triple(5, 3, 2), Triple(state.channelCount, state.movieCount, state.seriesCount))
        assertEquals(listOf("Action", "Comédie"), state.movieCategories.map { it.name })
        assertEquals(listOf("Le Voyage (2019)"), state.recentMovies.map { it.title })
        assertEquals(listOf("Horizons"), state.recentSeries.map { it.title })
        assertTrue(runBlocking { repository.channels(ChannelQuery()).asSnapshot() }.all { XtreamStreamRef.isRef(it.streamUrl) })
        // The password went to the client and nowhere else.
        assertEquals("p-test", xtream.accounts.first().password)
    }

    @Test fun incompleteOrRefusedXtreamFormsAreReportedInFrench() {
        val vm = model()
        vm.addXtreamSource("Nom", "portal.example", "", "p")
        dispatcher.idle()
        assertEquals("Serveur, identifiant et mot de passe sont requis.", vm.state.value.importStatus.message)
        assertTrue(vm.state.value.importStatus.error)
        vm.addXtreamSource("Nom", "portal.example", "u-test", "wrong")
        val state = dispatcher.awaitImport(vm)
        assertEquals(XtreamParser.REFUSED, state.importStatus.message)
        assertTrue(state.importStatus.error)
        assertTrue(state.sources.isEmpty())
    }

    @Test fun connectionTestDescribesTheAccountWithoutSavingAnything() {
        val vm = model()
        vm.checkXtreamAccount("portal.example:8080", "u-test", "p-test")
        assertTrue(vm.state.value.checkingAccount)
        dispatcher.idle()
        val check = vm.state.value.accountCheck as Fetched.Success
        assertEquals("Active", check.value.status)
        assertEquals("Compte active jusqu'au 01/01/2027 · 1/2 connexion(s) · formats m3u8, ts, rtmp", describeAccount(check.value))
        assertTrue(vm.state.value.sources.isEmpty())
        vm.checkXtreamAccount("portal.example:8080", "u-test", "wrong")
        dispatcher.idle()
        assertEquals(Fetched.Failure(XtreamParser.REFUSED), vm.state.value.accountCheck)
        vm.clearAccountCheck()
        assertNull(vm.state.value.accountCheck)
        xtream.accountJson = XtreamFixtures.ACCOUNT_EXPIRED
        vm.checkXtreamAccount("portal.example:8080", "u-test", "p-test")
        dispatcher.idle()
        assertEquals(Fetched.Failure("Compte expiré le 01/01/2025."), vm.state.value.accountCheck)
        vm.openSources(false)
        assertNull(vm.state.value.accountCheck)
    }

    @Test fun movieCategoriesAndFilterNarrowTheGridAndAreRestored() {
        val handle = SavedStateHandle()
        runBlocking { repository.addXtreamSource("Fournisseur", XtreamAccount("portal.example:8080", "u-test", "p-test")) }
        val vm = LiveViewModel(repository, handle).also { dispatcher.idle() }
        assertEquals(3, vm.state.value.visibleMovieCount)
        assertEquals(listOf("Toutes", "Favoris", "Action", "Comédie"), vm.state.value.movieCategoryList.map { it.name })
        vm.movieCategory("xtream:1:category:movie:3")
        dispatcher.idle()
        assertEquals(listOf("Le Voyage (2019)"), vm.visibleMovies.map { it.title })
        assertEquals(1, vm.state.value.visibleMovieCount)
        vm.movieCategory("unknown")
        assertEquals("xtream:1:category:movie:3", vm.state.value.movieCategoryId)
        vm.movieCategory(Category.ALL.id)
        vm.movieFilter("  NUIT ")
        dispatcher.idle()
        assertEquals(listOf("Nuit blanche"), vm.visibleMovies.map { it.title })
        vm.seriesFilter("ville")
        vm.seriesCategory(Category.FAVORITES.id)
        dispatcher.idle()
        assertEquals(0, vm.state.value.visibleSeriesCount)
        vm.toggleFavorite("xtream:1:series:78")
        dispatcher.idle()
        assertEquals(listOf("La ville bleue"), vm.visibleSeries.map { it.title })
        assertTrue(vm.state.value.isFavorite("xtream:1:series:78"))
        val restored = LiveViewModel(repository, handle).also { dispatcher.idle() }
        assertEquals("NUIT", restored.state.value.movieFilter.trim())
        assertEquals(Category.FAVORITES.id, restored.state.value.seriesCategoryId)
        assertEquals("ville", restored.state.value.seriesFilter)
    }

    @Test fun openingAFilmCompletesTheSheetAndPlaysWithResume() {
        val vm = loaded()
        vm.navigate(Destination.HOME)
        val movie = vm.movie(5001)
        vm.openMovie(movie)
        assertEquals(Destination.MOVIES, vm.state.value.destination)
        assertTrue(vm.state.value.movieSheet!!.loading)
        dispatcher.idle()
        val sheet = vm.state.value.movieSheet!!
        assertFalse(sheet.loading)
        assertEquals("Deux amis traversent un pays.", sheet.movie.synopsis)
        assertEquals(7080, sheet.movie.durationSeconds)
        assertEquals("2019 · 1 h 58 · ★ 7,6", sheet.movie.movieSubtitle())
        assertNull(vm.state.value.resumable(movie.id))
        vm.playMovie(sheet.movie)
        val item = vm.state.value.vod!!
        assertEquals(movie.id, item.id)
        assertEquals("xtream://1/movie/5001.mkv", item.streamUrl)
        assertFalse(item.live)
        assertEquals(0L, item.resumeMs)
        assertEquals(item, vm.state.value.playItem)
        assertEquals("http://portal.example:8080/movie/u-test/p-test/5001.mkv", runBlocking { vm.resolveStream(item.streamUrl) })
        // The player reports a position: the sheet offers to resume there, from the start on request.
        vm.saveProgress(movie.id, 2_520_000, 7_080_000)
        dispatcher.idle()
        assertEquals("Reprendre à 42 min", resumeLabel(vm.state.value.resumable(movie.id)!!.positionMs))
        vm.playMovie(sheet.movie)
        assertEquals(2_520_000L, vm.state.value.vod!!.resumeMs)
        vm.playMovie(sheet.movie, fromStart = true)
        assertEquals(0L, vm.state.value.vod!!.resumeMs)
        assertEquals(listOf(movie.id), vm.state.value.resume.map { it.item.id })
        // Past 95 % the film counts as watched: no more resume.
        vm.saveProgress(movie.id, 7_000_000, 7_080_000)
        dispatcher.idle()
        assertNull(vm.state.value.resumable(movie.id))
        assertTrue(vm.state.value.resume.isEmpty())
        vm.closeSheet()
        assertNull(vm.state.value.movieSheet)
        assertNull(vm.state.value.vod)
    }

    @Test fun seriesSheetSelectsSeasonsPlaysEpisodesAndChainsToTheNext() {
        val vm = loaded()
        vm.navigate(Destination.SEARCH)
        vm.openSeries(vm.series(77))
        assertEquals(Destination.SERIES, vm.state.value.destination)
        dispatcher.idle()
        val sheet = vm.state.value.seriesSheet!!
        assertEquals(listOf(1, 2), sheet.info!!.seasons.map { it.number })
        assertEquals(1, sheet.season)
        assertEquals(listOf("Horizons - S01E01 - Départ", "Épisode 2"), sheet.episodes.map { it.title })
        vm.selectSeason(2)
        assertEquals(listOf("Retour"), vm.state.value.seriesSheet!!.episodes.map { it.title })
        vm.selectSeason(9)
        assertEquals(2, vm.state.value.seriesSheet!!.season)
        val first = sheet.info.episodes[0]
        vm.playEpisode(first)
        val item = vm.state.value.vod!!
        assertEquals("S01E01 · Horizons - S01E01 - Départ", item.title)
        assertEquals("Horizons", item.subtitle)
        assertEquals("xtream://1/series/9001.mkv", item.streamUrl)
        assertEquals(1, vm.state.value.seriesSheet!!.season) // follows the episode played
        assertEquals("Épisode 2", vm.nextEpisode()!!.title)
        vm.playNextEpisode()
        assertEquals("xtream:1:episode:9002", vm.state.value.vod!!.id)
        vm.playNextEpisode()
        assertEquals("xtream:1:episode:9003", vm.state.value.vod!!.id)
        assertEquals(2, vm.state.value.seriesSheet!!.season)
        assertNull(vm.nextEpisode())
        vm.playNextEpisode()
        assertEquals("xtream:1:episode:9003", vm.state.value.vod!!.id)
        // Resume of an episode from the home row re-opens the series at its season.
        vm.saveProgress("xtream:1:episode:9002", 600_000, 2_850_000)
        dispatcher.idle()
        vm.navigate(Destination.HOME)
        assertNull(vm.state.value.vod)
        val resume = vm.state.value.resume.single()
        assertEquals("S01E02 · Épisode 2", resume.item.title)
        vm.resume(resume)
        dispatcher.idle()
        assertEquals(Destination.SERIES, vm.state.value.destination)
        assertEquals(1, vm.state.value.seriesSheet!!.season)
        assertEquals(600_000L, vm.state.value.vod!!.resumeMs)
        vm.stopVod()
        assertNull(vm.state.value.vod)
        assertNotNull(vm.state.value.seriesSheet)
    }

    @Test fun searchGroupsChannelsFilmsAndSeries() {
        val vm = loaded()
        vm.search("nuit")
        dispatcher.idle()
        assertEquals(Triple(0, 1, 0), Triple(vm.state.value.searchCount, vm.state.value.searchMovieCount, vm.state.value.searchSeriesCount))
        assertEquals(listOf("Nuit blanche"), vm.foundMovies.map { it.title })
        vm.search("horizons")
        dispatcher.idle()
        assertEquals(listOf("Horizons"), vm.foundSeries.map { it.title })
        vm.search("tf1")
        dispatcher.idle()
        assertEquals(Triple(1, 0, 0), Triple(vm.state.value.searchCount, vm.state.value.searchMovieCount, vm.state.value.searchSeriesCount))
    }

    @Test fun onePlayerOnlyAndSheetsCloseWithTheirSource() {
        val vm = loaded()
        vm.openMovie(vm.movie(5001))
        dispatcher.idle()
        vm.playMovie(vm.movie(5001))
        vm.fullscreen(true)
        assertTrue(vm.state.value.fullscreen)
        // Selecting a channel stops the film; switching tab stops everything.
        val channel = runBlocking { repository.channels(ChannelQuery()).asSnapshot().first() }
        vm.navigate(Destination.TV)
        assertNull(vm.state.value.vod)
        assertFalse(vm.state.value.fullscreen)
        vm.select(channel)
        assertEquals(channel.toPlayItem(), vm.state.value.playItem)
        vm.navigate(Destination.MOVIES)
        assertNull(vm.state.value.playItem)
        vm.openMovie(vm.movie(5002))
        dispatcher.idle()
        vm.playMovie(vm.movie(5002))
        vm.removeSource(1)
        dispatcher.idle()
        assertNull(vm.state.value.movieSheet)
        assertNull(vm.state.value.vod)
        assertFalse(vm.state.value.hasXtream)
        assertEquals(0, vm.state.value.movieCount)
    }
}
