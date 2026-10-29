package com.miptv

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomChannelRepositoryTest {
    private lateinit var database: MiptvDatabase
    private val playlists = mutableMapOf("https://demo.example/list.m3u" to """#EXTM3U
#EXTINF:-1 tvg-id="bbb" group-title="Animation",Big Buck Bunny
https://a/1
#EXTINF:-1 tvg-id="sintel" group-title="Cinéma",Sintel
https://a/2
""")
    private val loader = object : PlaylistLoader {
        override suspend fun open(location: String, kind: SourceKind): InputStream =
            playlists[location]?.byteInputStream() ?: throw java.net.UnknownHostException()
    }

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiptvDatabase::class.java).build()
    }

    @After fun close() = database.close()

    @Test fun importPersistsAndIsVisibleToANewRepositoryOnTheSameDatabase() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        val result = repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        assertTrue(result is ImportResult.Success)
        val reopened = RoomChannelRepository(database, loader)
        assertEquals(listOf("Animation", "Cinéma"), reopened.categories.first().map { it.name })
        assertEquals(listOf("Big Buck Bunny", "Sintel"), reopened.list().map { it.name })
        assertEquals(2, reopened.count(ChannelQuery()).first())
        val source = reopened.sources.first().single()
        assertEquals(2, source.channelCount)
        assertNotNull(source.lastRefreshed)
    }

    @Test fun favoritesSurviveRefreshAndFailedRefreshKeepsChannels() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        val favorites = ChannelQuery(Category.FAVORITES.id)
        val sintel = repository.list().first { it.tvgId == "sintel" }
        repository.setFavorite(sintel.id, true)
        assertEquals(listOf("Sintel"), repository.list(favorites).map { it.name })

        playlists["https://demo.example/list.m3u"] = playlists.getValue("https://demo.example/list.m3u").replace("Sintel", "Sintel HD").replace("Cinéma", "Films")
        val refreshed = repository.refreshSource(1)
        assertTrue(refreshed is ImportResult.Success)
        assertEquals(listOf("Sintel HD"), repository.list(favorites).map { it.name })
        assertEquals(setOf(sintel.id), repository.favoriteIds.first())
        assertEquals(listOf("Animation", "Films"), repository.categories.first().map { it.name })

        playlists["https://demo.example/list.m3u"] = "#EXTM3U\n"
        val failed = repository.refreshSource(1)
        assertTrue(failed is ImportResult.Failure)
        assertEquals(2, repository.list().size)
        assertEquals("Aucune chaîne trouvée dans cette playlist.", repository.sources.first().single().lastError)
    }

    @Test fun removingASourceClearsItsChannelsCategoriesAndFavorites() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        val channel = repository.list().first()
        repository.setFavorite(channel.id, true)
        repository.removeSource(1)
        assertTrue(repository.list().isEmpty())
        assertTrue(repository.categories.first().isEmpty())
        assertTrue(repository.sources.first().isEmpty())
        assertTrue(repository.favoriteIds.first().isEmpty())
        assertNull(repository.channel(channel.id))
    }

    @Test fun failedAddDoesNotCreateASource() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        val result = repository.addSource("x", "https://nowhere.example/list.m3u", SourceKind.URL)
        assertTrue(result is ImportResult.Failure)
        assertTrue(repository.sources.first().isEmpty())
    }

    @Test fun sqlFilterFoldsCaseAndAccentsAndTreatsWildcardsLiterally() = runBlocking {
        playlists["https://demo.example/names.m3u"] = """#EXTM3U
#EXTINF:-1 group-title="Infos",CHAÎNE INFO
https://a/3
#EXTINF:-1 group-title="Infos",Télé 100%
https://a/4
#EXTINF:-1 group-title="Infos",Télé_Matin
https://a/5
"""
        val repository = RoomChannelRepository(database, loader)
        repository.addSource("Noms", "https://demo.example/names.m3u", SourceKind.URL)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        fun names(text: String, category: String = Category.ALL.id) = runBlocking { repository.list(ChannelQuery(category, text)).map { it.name } }
        assertEquals(listOf("CHAÎNE INFO"), names("chaîne"))
        assertEquals(listOf("CHAÎNE INFO"), names("  Chaine "))
        assertEquals(listOf("Télé 100%", "Télé_Matin"), names("TELE"))
        assertEquals(listOf("Télé 100%"), names("%"))
        assertEquals(listOf("Télé_Matin"), names("_"))
        assertEquals(emptyList<String>(), names("\\"))
        val infos = repository.categories.first().first { it.name == "Infos" }.id
        assertEquals(listOf("Télé 100%", "Télé_Matin"), names("télé", infos))
        assertEquals(2, repository.count(ChannelQuery(infos, "télé")).first())
        assertEquals(emptyList<String>(), names("sintel", infos))
        // Order: by source, then playlist position.
        assertEquals(listOf("CHAÎNE INFO", "Télé 100%", "Télé_Matin", "Big Buck Bunny", "Sintel"), names(""))
        val sintel = repository.list().first { it.tvgId == "sintel" }
        repository.setFavorite(sintel.id, true)
        assertEquals(listOf("Sintel"), names("SIN", Category.FAVORITES.id))
        assertEquals(emptyList<String>(), names("tele", Category.FAVORITES.id))
    }

    @Test fun starringAChannelInvalidatesOnlyTheFavouritesList() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        val dao = database.catalog()
        val all = ChannelPagingSource(database, ChannelQuery())
        val category = ChannelPagingSource(database, ChannelQuery(repository.categories.first().first().id))
        val favorites = ChannelPagingSource(database, ChannelQuery(Category.FAVORITES.id))
        val params = PagingSource.LoadParams.Refresh<ChannelKey>(null, 60, false)
        listOf(all, category, favorites).forEach { it.load(params) }
        val counts = mutableListOf<Int>()
        val counting = launch { dao.countAll("%").toList(counts) }
        delay(300)
        repository.setFavorite(repository.list().first().id, true)
        // Room's invalidation tracker runs asynchronously: the favourites source going invalid proves the wait is long enough.
        val deadline = System.currentTimeMillis() + 5_000
        while (!favorites.invalid && System.currentTimeMillis() < deadline) delay(20)
        delay(300)
        counting.cancel()
        assertTrue(favorites.invalid)
        assertFalse(all.invalid)
        assertFalse(category.invalid)
        assertEquals(listOf(2), counts)
    }

    @Test fun keysetPagesCoverEveryChannelOnceInOrderAndRefreshFromTheMiddle() = runBlocking {
        fun playlist(prefix: String, count: Int) = buildString {
            append("#EXTM3U\n")
            for (n in 0 until count) append("#EXTINF:-1 tvg-id=\"$prefix$n\" group-title=\"G${n % 3}\",$prefix $n\nhttps://a/$prefix$n\n")
        }
        playlists["https://demo.example/a.m3u"] = playlist("a", 130)
        playlists["https://demo.example/b.m3u"] = playlist("b", 70)
        val repository = RoomChannelRepository(database, loader)
        repository.addSource("A", "https://demo.example/a.m3u", SourceKind.URL)
        repository.addSource("B", "https://demo.example/b.m3u", SourceKind.URL)
        val expected = (0 until 130).map { "a $it" } + (0 until 70).map { "b $it" }
        assertEquals(expected, repository.list().map { it.name })
        val g1 = repository.categories.first().first { it.name == "G1" }.id
        assertEquals(expected.filter { it.substringAfter(' ').toInt() % 3 == 1 }, repository.list(ChannelQuery(g1)).map { it.name })
        assertEquals(listOf("a 7") + (70..79).map { "a $it" }, repository.list(ChannelQuery(text = "A 7")).map { it.name })

        // After an invalidation the list reloads from the channel on screen; prepends bring back the earlier ones.
        val source = ChannelPagingSource(database, ChannelQuery())
        val from = source.load(PagingSource.LoadParams.Refresh(ChannelKey(1, 100, ChannelKey.Direction.FROM), 60, false)) as PagingSource.LoadResult.Page
        assertEquals(expected.subList(100, 160), from.data.map { it.name })
        val before = source.load(PagingSource.LoadParams.Prepend(from.prevKey!!, 60, false)) as PagingSource.LoadResult.Page
        assertEquals(expected.subList(40, 100), before.data.map { it.name })
        val start = source.load(PagingSource.LoadParams.Prepend(before.prevKey!!, 60, false)) as PagingSource.LoadResult.Page
        assertEquals(expected.subList(0, 40), start.data.map { it.name })
        assertNull(start.prevKey)
        val tail = source.load(PagingSource.LoadParams.Append(from.nextKey!!, 60, false)) as PagingSource.LoadResult.Page
        assertEquals(expected.subList(160, 200), tail.data.map { it.name })
        assertNull(tail.nextKey)
    }
}
