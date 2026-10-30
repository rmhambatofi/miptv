package com.miptv

import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.io.InputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Xtream sources in Room: encrypted password, VOD tables, caches, favourites and resume positions. No network. */
@RunWith(AndroidJUnit4::class)
class XtreamRepositoryTest {
    private lateinit var database: MiptvDatabase
    private lateinit var repository: RoomChannelRepository
    private val xtream = FakeXtreamClient()
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var keyLost = false
    private val cipher = AesGcmCipher { if (keyLost) null else key }
    private val loader = object : PlaylistLoader {
        override suspend fun open(location: String, kind: SourceKind): InputStream = throw java.net.UnknownHostException()
    }
    private val account = XtreamAccount("portal.example:8080/", " u-test ", "p-test")

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiptvDatabase::class.java).build()
        repository = RoomChannelRepository(database, loader, xtream, cipher, TickingClock())
    }

    @After fun close() = database.close()

    private suspend fun add(): Source = (repository.addXtreamSource("Fournisseur", account) as ImportResult.Success).source

    private fun count(table: String): Int = database.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun importStoresTheAccountAndTheWholeCatalogue() = runBlocking {
        val source = add()
        assertEquals(SourceKind.XTREAM, source.kind)
        assertEquals("http://portal.example:8080", source.location)
        assertEquals("u-test", source.username)
        assertEquals(1_798_761_600_000L, source.expiresAt)
        assertEquals(listOf(5, 3, 2), listOf(source.channelCount, source.movieCount, source.seriesCount))
        assertEquals(source, repository.sources.first().single())
        assertTrue(xtream.accounts.all { it.server == "http://portal.example:8080" && it.username == "u-test" })
        val channels = repository.list()
        assertEquals(listOf("xtream:1:101", "xtream:1:102", "xtream:1:103", "xtream:1:104", "xtream:1:107"), channels.map { it.id })
        assertEquals("xtream://1/live/101.ts", channels[0].streamUrl)
        // "Catégorie 9" has no channel: the shared category table only keeps categories in use, as for playlists.
        assertEquals(listOf("Généralistes", "Sport", M3uParser.DEFAULT_GROUP), repository.categories.first().map { it.name })
        assertEquals(listOf("TF1 HD"), repository.list(ChannelQuery(text = "tf1")).map { it.name })
        assertEquals(listOf("Le Voyage (2019)", "Nuit blanche", "Sans conteneur"), repository.movies(VodQuery()).asSnapshot().map { it.title })
        assertEquals(listOf("Action", "Comédie"), repository.movieCategories.first().map { it.name })
        assertEquals(1, repository.countMovies(VodQuery("xtream:1:category:movie:3")).first())
        assertEquals(listOf("Nuit blanche"), repository.movies(VodQuery(text = "NUIT")).asSnapshot().map { it.title })
        assertEquals(listOf("Horizons", "La ville bleue"), repository.series(VodQuery()).asSnapshot().map { it.title })
        assertEquals(2, repository.countSeries(VodQuery()).first())
        assertTrue(repository.seriesCategories.first().isEmpty())
        assertEquals(listOf("Le Voyage (2019)"), repository.recentMovies(10).first().map { it.title })
        assertEquals(listOf("Horizons"), repository.recentSeries(10).first().map { it.title })
        assertEquals("mkv", repository.movie("xtream:1:movie:5001")!!.containerExtension)
        assertEquals(2019, repository.series("xtream:1:series:77")!!.year)
    }

    @Test fun passwordRestsEncryptedAndOnlyBuildsPlaybackUrls() = runBlocking {
        add()
        val stored = database.query("SELECT encryptedPassword FROM sources", null).use { it.moveToFirst(); it.getString(0) }
        assertTrue(stored, stored.startsWith(AesGcmCipher.PREFIX))
        assertFalse(stored.contains("p-test"))
        assertEquals("http://portal.example:8080/live/u-test/p-test/101.ts", repository.playbackUrl("xtream://1/live/101.ts"))
        val movie = repository.movie("xtream:1:movie:5001")!!
        assertEquals("http://portal.example:8080/movie/u-test/p-test/5001.mkv", repository.playbackUrl(movie.streamRef().format()))
        assertEquals("https://a/1.m3u8", repository.playbackUrl("https://a/1.m3u8"))
        assertNull(repository.playbackUrl("xtream://9/live/1.ts"))
        // A new repository on the same base decrypts with the same key.
        assertEquals("http://portal.example:8080/live/u-test/p-test/102.ts", RoomChannelRepository(database, loader, xtream, cipher).playbackUrl("xtream://1/live/102.ts"))
    }

    @Test fun lostKeyAsksForTheCredentialsAgainAndKeepsTheCatalogue() = runBlocking {
        add()
        keyLost = true
        assertEquals(ImportResult.Failure(CREDENTIALS_TO_REENTER), repository.refreshSource(1))
        assertEquals(CREDENTIALS_TO_REENTER, repository.sources.first().single().lastError)
        assertEquals(5, repository.list().size)
        assertEquals(3, count("movies"))
        assertNull(repository.playbackUrl("xtream://1/live/101.ts"))
        assertEquals(Fetched.Failure(CREDENTIALS_TO_REENTER), repository.movieDetails("xtream:1:movie:5001"))
        assertEquals(Fetched.Failure(CREDENTIALS_TO_REENTER), repository.seriesInfo("xtream:1:series:77"))
        keyLost = false
        assertTrue(repository.refreshSource(1) is ImportResult.Success)
        assertNull(repository.sources.first().single().lastError)
    }

    @Test fun refusedOrExpiredAccountsFailWithoutTouchingTheCatalogue() = runBlocking {
        add()
        xtream.validPassword = "changed"
        assertEquals(ImportResult.Failure(XtreamParser.REFUSED), repository.refreshSource(1))
        assertEquals(XtreamParser.REFUSED, repository.sources.first().single().lastError)
        assertEquals(5, repository.list().size)
        xtream.validPassword = "p-test"
        xtream.accountJson = XtreamFixtures.ACCOUNT_EXPIRED
        assertEquals(ImportResult.Failure("Compte expiré le 01/01/2025."), repository.refreshSource(1))
        assertEquals(ImportResult.Failure("Compte expiré le 01/01/2025."), repository.addXtreamSource("Autre", account))
        assertEquals(1, repository.sources.first().size)
        assertTrue(repository.addSource("x", "http://portal.example", SourceKind.XTREAM) is ImportResult.Failure)
        assertEquals(ImportResult.Failure("Serveur, identifiant et mot de passe sont requis."), repository.addXtreamSource("x", XtreamAccount("portal.example", "", "p")))
    }

    @Test fun seriesAndFilmDetailsAreFetchedOnceThenCachedUntilRefresh() = runBlocking {
        add()
        xtream.calls.clear()
        val info = (repository.seriesInfo("xtream:1:series:77") as Fetched.Success).value
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals(3, info.episodes.size)
        assertEquals("Une expédition au long cours.", info.series.synopsis)
        val cached = (repository.seriesInfo("xtream:1:series:77") as Fetched.Success).value
        assertEquals(info, cached)
        assertEquals(listOf("seriesDetails"), xtream.calls)
        assertEquals("Une expédition au long cours.", repository.series("xtream:1:series:77")!!.synopsis)
        assertEquals("Horizons - S01E01 - Départ", repository.episode("xtream:1:episode:9001")!!.title)
        assertEquals(2, count("seasons")); assertEquals(3, count("episodes"))
        // Provider down: the cache answers; an unknown series fails with the French message.
        xtream.failure = IOException("down")
        assertTrue(repository.seriesInfo("xtream:1:series:77") is Fetched.Success)
        assertEquals(Fetched.Failure("Lecture du catalogue impossible."), repository.seriesInfo("xtream:1:series:78"))
        assertEquals(Fetched.Failure("Lecture du catalogue impossible."), repository.movieDetails("xtream:1:movie:5001"))
        xtream.failure = null
        val movie = (repository.movieDetails("xtream:1:movie:5001") as Fetched.Success).value
        assertEquals(7080, movie.durationSeconds)
        assertEquals("Deux amis traversent un pays.", repository.movie("xtream:1:movie:5001")!!.synopsis)
        repository.movieDetails("xtream:1:movie:5001")
        assertEquals(1, xtream.calls.count { it == "movieDetails" })
        assertEquals(Fetched.Failure("Film introuvable."), repository.movieDetails("xtream:1:movie:1"))
        // A refresh drops the cache: next opening fetches again, and the film details are reloaded too.
        assertTrue(repository.refreshSource(1) is ImportResult.Success)
        assertEquals(0, count("seasons")); assertEquals(0, count("episodes"))
        assertNull(repository.movie("xtream:1:movie:5001")!!.durationSeconds)
        repository.seriesInfo("xtream:1:series:77")
        assertEquals(2, xtream.calls.count { it == "seriesDetails" })
        assertEquals(3, count("episodes"))
        repository.seriesInfo("xtream:1:series:77", refresh = true)
        assertEquals(3, xtream.calls.count { it == "seriesDetails" })
    }

    @Test fun favouritesAndProgressCoverFilmsSurviveRefreshAndGoWithTheSource() = runBlocking {
        add()
        repository.setFavorite("xtream:1:movie:5001", true)
        repository.setFavorite("xtream:1:series:77", true)
        repository.setFavorite("xtream:1:101", true)
        assertEquals(setOf("xtream:1:movie:5001", "xtream:1:series:77", "xtream:1:101"), repository.favoriteIds.first())
        assertEquals(listOf("Le Voyage (2019)"), repository.movies(VodQuery(Category.FAVORITES.id)).asSnapshot().map { it.title })
        assertEquals(1, repository.countMovies(VodQuery(Category.FAVORITES.id)).first())
        assertEquals(1, repository.countSeries(VodQuery(Category.FAVORITES.id, "hori")).first())
        assertEquals(listOf("TF1 HD"), repository.list(ChannelQuery(Category.FAVORITES.id)).map { it.name })
        repository.saveProgress("xtream:1:movie:5001", 60_000, 7_080_000)
        repository.saveProgress("xtream:1:episode:9001", 10_000, 2_700_000)
        assertEquals(listOf("xtream:1:episode:9001", "xtream:1:movie:5001"), repository.progress.first().map { it.itemId })
        assertEquals(60_000L, repository.progress("xtream:1:movie:5001")!!.positionMs)
        assertFalse(repository.progress("xtream:1:movie:5001")!!.finished)
        repository.saveProgress("xtream:1:movie:5001", 6_900_000, 7_080_000)
        assertTrue(repository.progress("xtream:1:movie:5001")!!.finished)
        assertTrue(repository.refreshSource(1) is ImportResult.Success)
        assertEquals(3, repository.favoriteIds.first().size)
        assertEquals(2, repository.progress.first().size)
        repository.clearProgress("xtream:1:episode:9001")
        assertEquals(listOf("xtream:1:movie:5001"), repository.progress.first().map { it.itemId })
        repository.removeSource(1)
        assertTrue(repository.favoriteIds.first().isEmpty())
        assertTrue(repository.progress.first().isEmpty())
        assertTrue(repository.list().isEmpty())
        assertTrue(repository.movieCategories.first().isEmpty())
        for (table in listOf("sources", "channels", "categories", "movies", "series", "vod_categories", "seasons", "episodes", "progress", "favorites")) {
            assertEquals(table, 0, count(table))
        }
    }
}
