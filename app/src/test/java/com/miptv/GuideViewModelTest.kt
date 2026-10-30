package com.miptv

import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The programme guide through the ViewModel: clock, « maintenant / ensuite », guide sheet, programme search, guide address. */
class GuideViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    @get:Rule val timeZone = ParisTimeZoneRule()
    private val loader = GuideLoader().apply {
        this[GuideFixtures.PLAYLIST_URL] = GuideFixtures.PLAYLIST
        this[GuideFixtures.GUIDE_URL] = GuideFixtures.GUIDE
        this[GuideFixtures.GUIDE2_URL] = GuideFixtures.GUIDE_V2
    }
    private val xtream = FakeXtreamClient()
    private val clock = TestClock()
    private val repository = MemoryChannelRepository(loader, xtream, clock)

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = LiveViewModel(repository, saved, clock).also { dispatcher.idle() }

    /** A model whose playlist, with its guide, is already imported. */
    private fun loaded(): LiveViewModel {
        runBlocking { repository.addSource("France", GuideFixtures.PLAYLIST_URL, SourceKind.URL) }
        return model()
    }

    private fun channel(name: String): Channel = runBlocking { repository.channels(ChannelQuery()).asSnapshot().first { it.name == name } }

    @Test fun nowAndNextFollowTheClockAndTheFavourites() {
        val vm = loaded()
        val state = vm.state.value
        assertEquals(GuideFixtures.NOW, state.nowMs)
        assertTrue(state.hasGuide)
        val tf1 = channel("TF1")
        assertEquals("Journal de 20h", state.nowNext[tf1.id]!!.now.title)
        assertEquals("Météo", state.nowNext[tf1.id]!!.next!!.title)
        assertEquals(4, state.nowNext.size)
        assertTrue(state.onAirFavorites.isEmpty())
        assertNull(state.selectedNowNext)

        vm.toggleFavorite(tf1)
        dispatcher.idle()
        assertEquals(listOf(tf1), vm.state.value.favoriteChannels)
        assertEquals(listOf("Journal de 20h"), vm.state.value.onAirFavorites.map { it.second.now.title })

        vm.navigate(Destination.TV)
        vm.select(tf1)
        dispatcher.idle()
        assertEquals("Journal de 20h", vm.state.value.selectedNowNext!!.now.title)

        // 20:36: the news is over, the weather is on, and only for a few minutes.
        clock.now = GuideFixtures.H20_35 + 60_000L
        vm.tick()
        dispatcher.idle()
        assertEquals("Météo", vm.state.value.nowNext[tf1.id]!!.now.title)
        assertEquals("Film : Le Voyage", vm.state.value.nowNext[tf1.id]!!.next!!.title)
        assertEquals("Météo", vm.state.value.onAirFavorites.single().second.now.title)
        assertEquals(guideDaysFrom(clock.now), vm.state.value.guideDays)
        assertEquals(4, vm.state.value.guideDays.size)
        assertEquals(dayStart(clock.now), vm.state.value.guideDays.first())
    }

    @Test fun guideSheetLoadsTheDayFocusesOnAirAndSwitchesDays() {
        val vm = loaded()
        val tf1 = channel("TF1")
        vm.openGuide(tf1)
        dispatcher.idle()
        val sheet = vm.state.value.guideSheet!!
        assertEquals(tf1, sheet.channel)
        assertEquals(dayStart(GuideFixtures.NOW), sheet.dayStartMs)
        assertFalse(sheet.loading)
        assertEquals(listOf("Journal de 20h", "Météo", "Film : Le Voyage", "Nuit blanche"), sheet.programmes.map { it.title })
        assertEquals(GuideFixtures.H19_30, sheet.focusMs)

        val tomorrow = vm.state.value.guideDays[1]
        vm.guideDay(tomorrow)
        dispatcher.idle()
        val next = vm.state.value.guideSheet!!
        assertEquals(tomorrow, next.dayStartMs)
        assertEquals(listOf("Nuit blanche"), next.programmes.map { it.title }) // crosses midnight
        assertNull(next.focusMs)
        vm.guideDay(tomorrow)
        assertEquals(next, vm.state.value.guideSheet)

        vm.closeGuide()
        assertNull(vm.state.value.guideSheet)
        // A channel without guide key shows an empty day, not a loading state.
        vm.openGuide(channel("Chaîne locale"))
        dispatcher.idle()
        assertTrue(vm.state.value.guideSheet!!.programmes.isEmpty())
        assertFalse(vm.state.value.guideSheet!!.loading)
        // Opening Sources closes the guide, like the other sheets.
        vm.openSources(true)
        assertNull(vm.state.value.guideSheet)
    }

    @Test fun programmeSearchOpensTheGuideOnTheHitAndPlaysFromIt() {
        val vm = loaded()
        vm.navigate(Destination.SEARCH)
        vm.search("islande")
        dispatcher.idle()
        assertEquals(1, vm.state.value.searchProgrammeCount)
        val hit = vm.state.value.searchProgrammes.single()
        assertEquals("Documentaire : voyage en Islande", hit.programme.title)
        assertEquals("France 2", hit.channel.name)
        vm.search("voyage")
        dispatcher.idle()
        assertEquals(3, vm.state.value.searchProgrammeCount)
        assertEquals(listOf("Voyage au bout du monde", "Film : Le Voyage", "Documentaire : voyage en Islande"), vm.state.value.searchProgrammes.map { it.programme.title })

        vm.openGuide(hit)
        dispatcher.idle()
        val sheet = vm.state.value.guideSheet!!
        assertEquals(hit.channel, sheet.channel)
        assertEquals(dayStart(GuideFixtures.H21_00), sheet.dayStartMs)
        assertEquals(GuideFixtures.H21_00, sheet.focusMs)
        assertEquals(listOf("Série", "Documentaire : voyage en Islande"), sheet.programmes.map { it.title })

        vm.playFromGuide(hit.channel)
        dispatcher.idle()
        assertNull(vm.state.value.guideSheet)
        assertEquals(Destination.TV, vm.state.value.destination)
        assertEquals(hit.channel.id, vm.state.value.selected?.id)
        assertEquals("Série", vm.state.value.selectedNowNext!!.now.title)
        // Search is kept for the Recherche tab.
        assertEquals("voyage", vm.state.value.query)
    }

    @Test fun guideAddressIsSavedRefreshedAndRemovedWithFrenchMessages() {
        val vm = loaded()
        vm.openSources(true)
        vm.setEpgUrl(1, GuideFixtures.GUIDE2_URL)
        var state = dispatcher.awaitImport(vm)
        assertEquals("1 programmes dans le guide de « France ».", state.importStatus.message)
        assertFalse(state.importStatus.error)
        assertEquals(GuideFixtures.GUIDE2_URL, state.sources.single().epgUrl)
        assertEquals(listOf("Nouveau"), state.nowNext.values.map { it.now.title })

        vm.setEpgUrl(1, "ftp://x")
        state = dispatcher.awaitImport(vm)
        assertEquals("Seules les adresses http:// et https:// sont acceptées.", state.importStatus.message)
        assertTrue(state.importStatus.error)

        vm.setEpgUrl(1, GuideFixtures.DOWN_URL)
        state = dispatcher.awaitImport(vm)
        assertTrue(state.importStatus.error)
        assertEquals("Hôte introuvable. Vérifiez l'adresse et la connexion.", state.sources.single().epgError)
        assertEquals(1, state.nowNext.size) // previous guide kept

        vm.refreshEpg(1)
        state = dispatcher.awaitImport(vm)
        assertTrue(state.importStatus.error)

        vm.setEpgUrl(1, " ")
        state = dispatcher.awaitImport(vm)
        assertEquals("Guide retiré de « France ».", state.importStatus.message)
        assertFalse(state.hasGuide)
        assertTrue(state.nowNext.isEmpty())
        assertEquals(0, state.searchProgrammeCount)
    }

    @Test fun xtreamChannelsFetchTheirShortEpgWhenSelectedOrOpened() {
        runBlocking { repository.addXtreamSource("Fournisseur", XtreamAccount("portal.example:8080", "u-test", "p-test")) }
        val vm = model()
        assertTrue(vm.state.value.hasGuide)
        assertTrue(vm.state.value.nowNext.isEmpty())
        vm.refreshEpg(1)
        var state = dispatcher.awaitImport(vm)
        assertEquals("Aucune chaîne favorite : le guide de « Fournisseur » suit vos favoris.", state.importStatus.message)

        val tf1 = runBlocking { repository.channel("xtream:1:101")!! }
        vm.navigate(Destination.TV)
        vm.select(tf1)
        dispatcher.idle()
        state = vm.state.value
        assertEquals("Journal de 20h", state.selectedNowNext!!.now.title)
        assertEquals(1, xtream.calls.count { it == "shortEpg:101" })
        vm.openGuide(tf1)
        dispatcher.idle()
        assertEquals(1, xtream.calls.count { it == "shortEpg:101" }) // cached
        assertEquals(4, vm.state.value.guideSheet!!.programmes.size)
        val f2 = runBlocking { repository.channel("xtream:1:102")!! }
        vm.openGuide(f2)
        dispatcher.idle()
        assertEquals(1, xtream.calls.count { it == "shortEpg:102" })
        assertEquals("Journal de 20h", vm.state.value.nowNext["xtream:1:102"]!!.now.title)
    }

    @Test fun guideFormattingInParisTime() {
        val paris = ZoneId.of("Europe/Paris")
        assertEquals("20:35", formatTime(GuideFixtures.H20_35, paris))
        assertEquals("19:30 – 20:35", formatSlot(Programme("tf1.fr", GuideFixtures.H19_30, GuideFixtures.H20_35, "Journal"), paris))
        assertEquals(GuideFixtures.DAY_START, dayStart(GuideFixtures.NOW, paris))
        assertEquals(GuideFixtures.NEXT_DAY, dayStart(GuideFixtures.H00_10_NEXT, paris))
        val days = guideDaysFrom(GuideFixtures.NOW, paris)
        assertEquals(GuideFixtures.DAY_START, days[0]); assertEquals(GuideFixtures.NEXT_DAY, days[1]); assertEquals(4, days.size)
        assertEquals("Aujourd'hui", dayLabel(days[0], GuideFixtures.NOW, paris))
        assertEquals("Demain", dayLabel(days[1], GuideFixtures.NOW, paris))
        assertEquals("sam. 31 oct.", dayLabel(days[2], GuideFixtures.NOW, paris))
        assertEquals("dim. 1 nov.", dayLabel(days[3], GuideFixtures.NOW, paris))
        val nowNext = NowNext(Programme("tf1.fr", GuideFixtures.H19_30, GuideFixtures.H20_35, "Journal"), Programme("tf1.fr", GuideFixtures.H20_35, GuideFixtures.H20_40, "Météo"))
        assertEquals("À suivre 20:35 · Météo", nextLabel(nowNext, paris))
        assertNull(nextLabel(nowNext.copy(next = null), paris))
        assertNotNull(nowNext.now)
    }
}
