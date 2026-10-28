package com.miptv

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import kotlinx.coroutines.flow.first
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

    private suspend fun ChannelRepository.loaded() = catalog.first { it.channels.isNotEmpty() || sources.first().isEmpty() }

    @Test fun importPersistsAndIsVisibleToANewRepositoryOnTheSameDatabase() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        val result = repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        assertTrue(result is ImportResult.Success)
        val reopened = RoomChannelRepository(database, loader)
        val catalog = reopened.catalog.first { it.channels.isNotEmpty() }
        assertEquals(listOf("Animation", "Cinéma"), catalog.categories.map { it.name })
        assertEquals(listOf("Big Buck Bunny", "Sintel"), catalog.channels.map { it.name })
        val source = reopened.sources.first().single()
        assertEquals(2, source.channelCount)
        assertNotNull(source.lastRefreshed)
    }

    @Test fun favoritesSurviveRefreshAndFailedRefreshKeepsChannels() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        val sintel = repository.catalog.first { it.channels.isNotEmpty() }.channels.first { it.tvgId == "sintel" }
        repository.setFavorite(sintel.id, true)
        assertEquals(listOf("Sintel"), repository.catalog.first { it.favorites.isNotEmpty() }.favorites.map { it.name })

        playlists["https://demo.example/list.m3u"] = playlists.getValue("https://demo.example/list.m3u").replace("Sintel", "Sintel HD").replace("Cinéma", "Films")
        val refreshed = repository.refreshSource(1)
        assertTrue(refreshed is ImportResult.Success)
        val catalog = repository.catalog.first { it.channels.any { c -> c.name == "Sintel HD" } }
        assertEquals(listOf("Sintel HD"), catalog.favorites.map { it.name })
        assertEquals(listOf("Animation", "Films"), catalog.categories.map { it.name })

        playlists["https://demo.example/list.m3u"] = "#EXTM3U\n"
        val failed = repository.refreshSource(1)
        assertTrue(failed is ImportResult.Failure)
        assertEquals(2, repository.catalog.first().channels.size)
        assertEquals("Aucune chaîne trouvée dans cette playlist.", repository.sources.first().single().lastError)
    }

    @Test fun removingASourceClearsItsChannelsCategoriesAndFavorites() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL)
        val channel = repository.catalog.first { it.channels.isNotEmpty() }.channels.first()
        repository.setFavorite(channel.id, true)
        repository.catalog.first { it.favorites.isNotEmpty() }
        repository.removeSource(1)
        val catalog = repository.catalog.first { it.channels.isEmpty() }
        assertTrue(catalog.categories.isEmpty())
        assertTrue(repository.sources.first().isEmpty())
        assertTrue(database.catalog().favoriteIds().first().isEmpty())
    }

    @Test fun failedAddDoesNotCreateASource() = runBlocking {
        val repository = RoomChannelRepository(database, loader)
        val result = repository.addSource("x", "https://nowhere.example/list.m3u", SourceKind.URL)
        assertTrue(result is ImportResult.Failure)
        assertTrue(repository.sources.first().isEmpty())
    }
}
