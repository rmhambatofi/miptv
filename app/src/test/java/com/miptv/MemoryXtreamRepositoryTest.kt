package com.miptv

import androidx.paging.testing.asSnapshot
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory reference behaviour for Xtream sources; the Room implementation is checked the same way on a device. */
class MemoryXtreamRepositoryTest {
    private val xtream = FakeXtreamClient()
    private val repository = MemoryChannelRepository(FakeLoader(), xtream)
    private val account = XtreamAccount("portal.example:8080/", " u-test ", "p-test")

    private fun add(): Source = runBlocking { (repository.addXtreamSource("Fournisseur", account) as ImportResult.Success).source }

    @Test fun xtreamSourceBringsChannelsFilmsAndSeries() = runBlocking {
        val source = add()
        assertEquals(SourceKind.XTREAM, source.kind)
        assertEquals("http://portal.example:8080", source.location)
        assertEquals("u-test", source.username)
        assertEquals(1_798_761_600_000L, source.expiresAt)
        assertEquals(Triple(5, 3, 2), Triple(source.channelCount, source.movieCount, source.seriesCount))
        assertTrue(xtream.accounts.all { it.server == "http://portal.example:8080" && it.username == "u-test" })
        val channels = repository.channels(ChannelQuery()).asSnapshot()
        assertEquals(listOf("xtream:1:101", "xtream:1:102", "xtream:1:103", "xtream:1:104", "xtream:1:107"), channels.map { it.id })
        assertEquals("xtream://1/live/101.ts", channels[0].streamUrl)
        assertEquals(listOf("Généralistes", "Sport", "Catégorie 9", M3uParser.DEFAULT_GROUP), repository.categories.first().map { it.name })
        assertEquals(3, repository.movies(VodQuery()).asSnapshot().size)
        assertEquals(listOf("Action", "Comédie"), repository.movieCategories.first().map { it.name })
        assertEquals(1, repository.countMovies(VodQuery("xtream:1:category:movie:3")).first())
        assertEquals(listOf("Nuit blanche"), repository.movies(VodQuery(text = "NUIT")).asSnapshot().map { it.title })
        assertEquals(listOf("Horizons", "La ville bleue"), repository.series(VodQuery()).asSnapshot().map { it.title })
        assertEquals(listOf("Le Voyage (2019)"), repository.recentMovies(10).first().map { it.title })
        assertEquals(listOf("Horizons"), repository.recentSeries(10).first().map { it.title })
        assertEquals("http://portal.example:8080/live/u-test/p-test/101.ts", repository.playbackUrl(channels[0].streamUrl))
        assertEquals("http://portal.example:8080/movie/u-test/p-test/5001.mkv", repository.playbackUrl(repository.movie("xtream:1:movie:5001")!!.streamRef().format()))
        assertEquals("https://a/1.m3u8", repository.playbackUrl("https://a/1.m3u8"))
        assertNull(repository.playbackUrl("xtream://9/live/1.ts"))
    }

    @Test fun refusedExpiredOrLostCredentialsFailAndKeepTheCatalogue() = runBlocking {
        add()
        xtream.validPassword = "changed"
        assertEquals(ImportResult.Failure(XtreamParser.REFUSED), repository.refreshSource(1))
        assertEquals(XtreamParser.REFUSED, repository.sources.first().single().lastError)
        assertEquals(5, repository.channels(ChannelQuery()).asSnapshot().size)
        xtream.validPassword = "p-test"
        xtream.accountJson = XtreamFixtures.ACCOUNT_EXPIRED
        assertEquals(ImportResult.Failure("Compte expiré le 01/01/2025."), repository.addXtreamSource("Autre", account))
        assertEquals(1, repository.sources.first().size)
        xtream.accountJson = XtreamFixtures.ACCOUNT_ACTIVE
        repository.forgetPassword(1)
        assertEquals(ImportResult.Failure(CREDENTIALS_TO_REENTER), repository.refreshSource(1))
        assertNull(repository.playbackUrl("xtream://1/live/101.ts"))
        assertEquals(Fetched.Failure(CREDENTIALS_TO_REENTER), repository.movieDetails("xtream:1:movie:5001"))
        assertEquals(ImportResult.Failure("Serveur, identifiant et mot de passe sont requis."), repository.addXtreamSource("x", XtreamAccount("", "u", "p")))
        assertTrue(repository.addSource("x", "http://portal.example", SourceKind.XTREAM) is ImportResult.Failure)
    }

    @Test fun detailsAreCachedUntilTheSourceIsRefreshed() = runBlocking {
        add()
        xtream.calls.clear()
        val info = (repository.seriesInfo("xtream:1:series:77") as Fetched.Success).value
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals(3, info.episodes.size)
        repository.seriesInfo("xtream:1:series:77")
        assertEquals(listOf("seriesDetails"), xtream.calls)
        assertEquals("Horizons - S01E01 - Départ", repository.episode("xtream:1:episode:9001")!!.title)
        xtream.failure = IOException("down")
        assertTrue(repository.seriesInfo("xtream:1:series:77") is Fetched.Success)
        assertEquals(Fetched.Failure("Lecture du catalogue impossible."), repository.seriesInfo("xtream:1:series:78"))
        xtream.failure = null
        val movie = (repository.movieDetails("xtream:1:movie:5001") as Fetched.Success).value
        assertEquals(7080, movie.durationSeconds)
        assertEquals(7080, repository.movie("xtream:1:movie:5001")!!.durationSeconds)
        repository.movieDetails("xtream:1:movie:5001")
        assertEquals(1, xtream.calls.count { it == "movieDetails" })
        assertTrue(repository.refreshSource(1) is ImportResult.Success)
        repository.seriesInfo("xtream:1:series:77")
        assertEquals(2, xtream.calls.count { it == "seriesDetails" })
    }

    @Test fun favouritesAndProgressCoverFilmsAndGoWithTheSource() = runBlocking {
        add()
        repository.setFavorite("xtream:1:movie:5001", true)
        repository.setFavorite("xtream:1:101", true)
        assertEquals(setOf("xtream:1:movie:5001", "xtream:1:101"), repository.favoriteIds.first())
        assertEquals(listOf("Le Voyage (2019)"), repository.movies(VodQuery(Category.FAVORITES.id)).asSnapshot().map { it.title })
        assertEquals(1, repository.countMovies(VodQuery(Category.FAVORITES.id)).first())
        repository.saveProgress("xtream:1:movie:5001", 60_000, 7_080_000)
        repository.saveProgress("xtream:1:episode:9001", 10_000, 2_700_000)
        assertEquals(listOf("xtream:1:episode:9001", "xtream:1:movie:5001"), repository.progress.first().map { it.itemId })
        assertFalse(repository.progress("xtream:1:movie:5001")!!.finished)
        repository.saveProgress("xtream:1:movie:5001", 6_900_000, 7_080_000)
        assertTrue(repository.progress("xtream:1:movie:5001")!!.finished)
        assertTrue(repository.refreshSource(1) is ImportResult.Success)
        assertEquals(2, repository.favoriteIds.first().size)
        assertEquals(2, repository.progress.first().size)
        repository.clearProgress("xtream:1:episode:9001")
        assertEquals(1, repository.progress.first().size)
        repository.removeSource(1)
        assertTrue(repository.favoriteIds.first().isEmpty())
        assertTrue(repository.progress.first().isEmpty())
        assertTrue(repository.movies(VodQuery()).asSnapshot().isEmpty())
        assertTrue(repository.channels(ChannelQuery()).asSnapshot().isEmpty())
        assertTrue(repository.movieCategories.first().isEmpty())
        assertNull(repository.episode("xtream:1:episode:9001"))
    }
}
