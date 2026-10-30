package com.miptv

import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Programme guide behaviour every [ChannelRepository] must show, run against the in-memory implementation on the
 * JVM and the Room one on a device. Times come from [GuideFixtures]; the clock is frozen at 20:30.
 */
object EpgScenarios {
    private suspend fun ChannelRepository.channelNamed(name: String): Channel = channels(ChannelQuery()).asSnapshot().first { it.name == name }

    /** Adds the playlist with its `url-tvg` and checks the join through tvg-id, display name and bare key. */
    suspend fun playlistGuideIsImportedAndJoined(repository: ChannelRepository, loader: GuideLoader): Source {
        loader[GuideFixtures.PLAYLIST_URL] = GuideFixtures.PLAYLIST
        loader[GuideFixtures.GUIDE_URL] = GuideFixtures.GUIDE
        val source = (repository.addSource("France", GuideFixtures.PLAYLIST_URL, SourceKind.URL) as ImportResult.Success).source
        assertEquals(GuideFixtures.GUIDE_URL, source.epgUrl)
        assertTrue(source.hasGuide)
        assertEquals(9, source.programmeCount) // the orphan of "unknown.fr" is dropped
        assertEquals(GuideFixtures.NOW, source.epgRefreshedAt)
        assertNull(source.epgError)
        assertEquals(source, repository.sources.first().single())
        assertEquals(1, loader.opened[GuideFixtures.GUIDE_URL])

        val tf1 = repository.channelNamed("TF1")
        val f2 = repository.channelNamed("France 2")
        val arte = repository.channelNamed("Arte")
        val m6 = repository.channelNamed("M6")
        val locale = repository.channelNamed("Chaîne locale")
        val now = repository.nowAndNext(GuideFixtures.NOW).first()
        assertEquals(setOf(tf1.id, f2.id, arte.id, m6.id), now.keys)
        assertEquals("Journal de 20h", now[tf1.id]!!.now.title)
        assertEquals(GuideFixtures.H19_30, now[tf1.id]!!.now.startMs)
        assertEquals("Le journal.", now[tf1.id]!!.now.description)
        assertEquals("Information", now[tf1.id]!!.now.category)
        assertEquals("Météo", now[tf1.id]!!.next!!.title)
        assertEquals("Série", now[f2.id]!!.now.title)
        assertEquals("S3 E5", now[f2.id]!!.now.episode)
        assertEquals("Documentaire : voyage en Islande", now[f2.id]!!.next!!.title)
        assertEquals("Voyage au bout du monde", now[arte.id]!!.now.title)
        assertNull(now[arte.id]!!.next)
        assertEquals("Capital", now[m6.id]!!.now.title) // starts exactly now; "Avant" has just ended
        assertNull(now[m6.id]!!.next)
        assertNull(now[locale.id])

        assertEquals(listOf("Série", "Documentaire : voyage en Islande"), repository.programmes(f2.id, GuideFixtures.DAY_START, GuideFixtures.NEXT_DAY).first().map { it.title })
        assertEquals(listOf("Journal de 20h", "Météo", "Film : Le Voyage", "Nuit blanche"), repository.programmes(tf1.id, GuideFixtures.DAY_START, GuideFixtures.NEXT_DAY).first().map { it.title })
        assertEquals(listOf("Nuit blanche"), repository.programmes(tf1.id, GuideFixtures.NEXT_DAY, GuideFixtures.NEXT_DAY + 86_400_000L).first().map { it.title })
        assertTrue(repository.programmes(locale.id, GuideFixtures.DAY_START, GuideFixtures.NEXT_DAY).first().isEmpty())

        val hits = repository.searchProgrammes("VOYAGE", GuideFixtures.NOW).first()
        assertEquals(listOf("Voyage au bout du monde", "Film : Le Voyage", "Documentaire : voyage en Islande"), hits.map { it.programme.title })
        assertEquals(listOf(arte.id, tf1.id, f2.id), hits.map { it.channel.id })
        assertEquals(3, repository.countProgrammes("voyage", GuideFixtures.NOW).first())
        assertEquals(1, repository.searchProgrammes("voyage", GuideFixtures.NOW, limit = 1).first().size)
        // Finished programmes and the orphan are not searched; a blank text finds nothing.
        assertEquals(0, repository.countProgrammes("avant", GuideFixtures.NOW).first())
        assertEquals(0, repository.countProgrammes("orphelin", GuideFixtures.NOW).first())
        assertEquals(0, repository.countProgrammes("  ", GuideFixtures.NOW).first())
        assertTrue(repository.searchProgrammes("", GuideFixtures.NOW).first().isEmpty())
        return source
    }

    /** A bad address, an unreachable one, then a working one; clearing the address drops the programmes. */
    suspend fun guideErrorsKeepThePreviousProgrammes(repository: ChannelRepository, loader: GuideLoader) {
        playlistGuideIsImportedAndJoined(repository, loader)
        assertEquals(ImportResult.Failure("Seules les adresses http:// et https:// sont acceptées."), repository.setEpgUrl(1, "ftp://epg.example/g.xml"))
        assertEquals(GuideFixtures.GUIDE_URL, repository.sources.first().single().epgUrl)

        val down = repository.setEpgUrl(1, GuideFixtures.DOWN_URL)
        assertEquals(ImportResult.Failure("Hôte introuvable. Vérifiez l'adresse et la connexion."), down)
        var source = repository.sources.first().single()
        assertEquals(GuideFixtures.DOWN_URL, source.epgUrl)
        assertEquals("Hôte introuvable. Vérifiez l'adresse et la connexion.", source.epgError)
        assertEquals(9, source.programmeCount)
        assertEquals(GuideFixtures.NOW, source.epgRefreshedAt)
        assertEquals(4, repository.nowAndNext(GuideFixtures.NOW).first().size)

        loader[GuideFixtures.DOWN_URL] = XtreamFixtures.HTML_LOGIN_PAGE
        assertEquals(ImportResult.Failure(XmltvParser.UNEXPECTED), repository.refreshEpg(1))
        assertEquals(4, repository.nowAndNext(GuideFixtures.NOW).first().size)

        loader[GuideFixtures.DOWN_URL] = GuideFixtures.GUIDE_V2
        val fixed = repository.refreshEpg(1)
        assertTrue(fixed.toString(), fixed is ImportResult.Success)
        source = repository.sources.first().single()
        assertNull(source.epgError)
        assertEquals(1, source.programmeCount)
        val now = repository.nowAndNext(GuideFixtures.NOW).first()
        assertEquals(listOf("Nouveau"), now.values.map { it.now.title })
        assertEquals(repository.channelNamed("TF1").id, now.keys.single())

        assertTrue(repository.setEpgUrl(1, "  ") is ImportResult.Success)
        source = repository.sources.first().single()
        assertNull(source.epgUrl); assertNull(source.epgRefreshedAt); assertEquals(0, source.programmeCount)
        assertFalse(source.hasGuide)
        assertTrue(repository.nowAndNext(GuideFixtures.NOW).first().isEmpty())
        assertEquals(ImportResult.Failure("Aucune adresse de guide pour cette source."), repository.refreshEpg(1))
        assertEquals(ImportResult.Failure("Source introuvable."), repository.refreshEpg(9))
    }

    /** Refreshing the playlist re-imports the guide at the address the user chose; a playlist without header has no guide. */
    suspend fun refreshingThePlaylistReimportsTheGuide(repository: ChannelRepository, loader: GuideLoader) {
        playlistGuideIsImportedAndJoined(repository, loader)
        loader[GuideFixtures.GUIDE2_URL] = GuideFixtures.GUIDE_V2
        assertTrue(repository.setEpgUrl(1, GuideFixtures.GUIDE2_URL) is ImportResult.Success)
        val refreshed = (repository.refreshSource(1) as ImportResult.Success).source
        assertEquals(GuideFixtures.GUIDE2_URL, refreshed.epgUrl)
        assertEquals(1, refreshed.programmeCount)
        assertEquals(2, loader.opened[GuideFixtures.GUIDE2_URL])
        assertEquals(1, loader.opened[GuideFixtures.GUIDE_URL])
        // The channels were replaced: the keys matched by name are computed again.
        assertEquals(listOf("Nouveau"), repository.nowAndNext(GuideFixtures.NOW).first().values.map { it.now.title })

        loader["http://list.example/bis.m3u"] = GuideFixtures.PLAYLIST_WITHOUT_GUIDE
        val bare = (repository.addSource("Bis", "http://list.example/bis.m3u", SourceKind.URL) as ImportResult.Success).source
        assertNull(bare.epgUrl); assertFalse(bare.hasGuide); assertEquals(0, bare.programmeCount); assertNull(bare.epgRefreshedAt)
        assertEquals(setOf(GuideFixtures.PLAYLIST_URL, GuideFixtures.GUIDE_URL, GuideFixtures.GUIDE2_URL, "http://list.example/bis.m3u"), loader.opened.keys)
        // "TF1 bis" shares tf1.fr but belongs to a source without guide: no programme for it.
        assertNull(repository.nowAndNext(GuideFixtures.NOW).first()[repository.channelNamed("TF1 bis").id])
    }

    /** Provider guide: nothing downloaded without favourites, short EPG on demand, `xmltv.php` kept to the favourites. */
    suspend fun xtreamGuideFollowsFavouritesAndShortEpgFillsTheRest(repository: ChannelRepository, loader: GuideLoader, xtream: FakeXtreamClient) {
        val account = XtreamAccount("http://portal.example:8080", "u-test", "p-test")
        val source = (repository.addXtreamSource("Fournisseur", account) as ImportResult.Success).source
        assertTrue(source.hasGuide)
        assertNull(source.epgUrl)
        val xmltv = account.xmltvUrl()
        assertNull(loader.opened[xmltv])
        val empty = (repository.refreshEpg(source.id) as ImportResult.Success).source
        assertEquals(0, empty.programmeCount); assertNull(empty.epgError); assertEquals(GuideFixtures.NOW, empty.epgRefreshedAt)
        assertNull(loader.opened[xmltv])

        assertTrue(repository.ensureShortEpg("xtream:1:101"))
        var now = repository.nowAndNext(GuideFixtures.NOW).first()
        assertEquals("Journal de 20h", now["xtream:1:101"]!!.now.title)
        assertEquals("Météo", now["xtream:1:101"]!!.next!!.title)
        assertFalse(repository.ensureShortEpg("xtream:1:101"))
        assertEquals(1, xtream.calls.count { it == "shortEpg:101" })
        assertEquals(listOf("Journal de 20h", "Météo", "Film : Le Voyage", "Nuit blanche"), repository.programmes("xtream:1:101", GuideFixtures.DAY_START, GuideFixtures.NEXT_DAY).first().map { it.title })
        // No epg_channel_id: the stream id serves as key.
        assertTrue(repository.ensureShortEpg("xtream:1:102"))
        assertEquals("Journal de 20h", repository.nowAndNext(GuideFixtures.NOW).first()["xtream:1:102"]!!.now.title)
        assertFalse(repository.ensureShortEpg("s1-unknown"))
        assertEquals(2, repository.countProgrammes("journal", GuideFixtures.NOW).first())

        repository.setFavorite("xtream:1:101", true)
        loader[xmltv] = GuideFixtures.GUIDE
        val withFavourite = (repository.refreshEpg(source.id) as ImportResult.Success).source
        assertEquals(4, withFavourite.programmeCount)
        assertEquals(1, loader.opened[xmltv])
        now = repository.nowAndNext(GuideFixtures.NOW).first()
        assertEquals(setOf("xtream:1:101"), now.keys)
        assertEquals(GuideFixtures.H19_30, now["xtream:1:101"]!!.now.startMs)
        assertFalse(repository.ensureShortEpg("xtream:1:101"))
        assertEquals(1, xtream.calls.count { it == "shortEpg:101" })

        // The provider's guide fails: the favourites' programmes stay, the error is on the source.
        loader[xmltv] = "<html>maintenance</html>"
        assertEquals(ImportResult.Failure(XmltvParser.UNEXPECTED), repository.refreshEpg(source.id))
        assertEquals(XmltvParser.UNEXPECTED, repository.sources.first().single().epgError)
        assertEquals(4, repository.programmes("xtream:1:101", GuideFixtures.DAY_START, GuideFixtures.NEXT_DAY).first().size)
        // A source refresh brings the guide along.
        loader[xmltv] = GuideFixtures.GUIDE_V2
        val refreshed = (repository.refreshSource(source.id) as ImportResult.Success).source
        assertEquals(1, refreshed.programmeCount); assertNull(refreshed.epgError)

        repository.removeSource(source.id)
        assertTrue(repository.nowAndNext(GuideFixtures.NOW).first().isEmpty())
        assertEquals(0, repository.countProgrammes("nouveau", GuideFixtures.NOW).first())
    }

    /** Programmes finished for more than twelve hours go; the running and coming ones stay. */
    suspend fun purgeDropsFinishedProgrammes(repository: ChannelRepository, loader: GuideLoader, clock: TestClock) {
        playlistGuideIsImportedAndJoined(repository, loader)
        val tf1 = repository.channelNamed("TF1")
        clock.now = GuideFixtures.NOON_NEXT
        repository.purgeProgrammes()
        assertEquals(listOf("Nuit blanche"), repository.programmes(tf1.id, GuideFixtures.DAY_START, GuideFixtures.NEXT_DAY + 86_400_000L).first().map { it.title })
        assertTrue(repository.programmes(repository.channelNamed("France 2").id, GuideFixtures.DAY_START, GuideFixtures.NEXT_DAY).first().isEmpty())
        assertTrue(repository.nowAndNext(clock.now).first().isEmpty())
    }
}
