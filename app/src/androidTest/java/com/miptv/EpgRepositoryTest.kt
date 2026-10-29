package com.miptv

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The programme guide in Room: same scenarios as the in-memory reference, plus the staging table and the purge. */
@RunWith(AndroidJUnit4::class)
class EpgRepositoryTest {
    private lateinit var database: MiptvDatabase
    private lateinit var repository: RoomChannelRepository
    private val loader = GuideLoader()
    private val xtream = FakeXtreamClient()
    private val clock = TestClock()
    private val cipher = AesGcmCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> { _ -> key } })

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MiptvDatabase::class.java).build()
        repository = RoomChannelRepository(database, loader, xtream, cipher, clock)
    }

    @After fun close() = database.close()

    private fun count(sql: String): Int = database.query(sql, null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun playlistGuideIsImportedAndJoined() = runBlocking {
        EpgScenarios.playlistGuideIsImportedAndJoined(repository, loader)
        assertEquals(10, count("SELECT COUNT(*) FROM programmes WHERE sourceId = 1"))
        assertEquals(0, count("SELECT COUNT(*) FROM programmes WHERE sourceId < 0"))
        // Keys: tvg-id kept, name match for France 2 and Arte, bare tvg-id for M6, none for the local channel.
        assertEquals(1, count("SELECT COUNT(*) FROM channels WHERE name = 'France 2' AND epgKey = 'f2.fr'"))
        assertEquals(1, count("SELECT COUNT(*) FROM channels WHERE name = 'Arte' AND epgKey = 'arte.fr' AND tvgId = 'old.id'"))
        assertEquals(1, count("SELECT COUNT(*) FROM channels WHERE name = 'M6' AND epgKey = 'm6.fr'"))
        assertEquals(1, count("SELECT COUNT(*) FROM channels WHERE name = 'Chaîne locale' AND epgKey IS NULL"))
    }

    @Test fun guideErrorsKeepThePreviousProgrammes() = runBlocking {
        EpgScenarios.guideErrorsKeepThePreviousProgrammes(repository, loader)
        assertEquals(0, count("SELECT COUNT(*) FROM programmes"))
    }

    @Test fun refreshingThePlaylistReimportsTheGuide() = runBlocking { EpgScenarios.refreshingThePlaylistReimportsTheGuide(repository, loader) }

    @Test fun xtreamGuideFollowsFavouritesAndShortEpgFillsTheRest() = runBlocking {
        EpgScenarios.xtreamGuideFollowsFavouritesAndShortEpgFillsTheRest(repository, loader, xtream)
        assertEquals(0, count("SELECT COUNT(*) FROM programmes"))
    }

    @Test fun purgeDropsFinishedProgrammes() = runBlocking {
        EpgScenarios.purgeDropsFinishedProgrammes(repository, loader, clock)
        assertEquals(1, count("SELECT COUNT(*) FROM programmes"))
    }
}
