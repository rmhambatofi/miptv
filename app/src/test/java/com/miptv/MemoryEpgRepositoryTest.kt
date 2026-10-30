package com.miptv

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The in-memory programme guide, reference for the Room implementation ([EpgScenarios]). */
class MemoryEpgRepositoryTest {
    private val loader = GuideLoader()
    private val xtream = FakeXtreamClient()
    private val clock = TestClock()
    private val repository = MemoryChannelRepository(loader, xtream, clock)

    @Test fun playlistGuideIsImportedAndJoined() = runBlocking { EpgScenarios.playlistGuideIsImportedAndJoined(repository, loader); Unit }
    @Test fun guideErrorsKeepThePreviousProgrammes() = runBlocking { EpgScenarios.guideErrorsKeepThePreviousProgrammes(repository, loader) }
    @Test fun refreshingThePlaylistReimportsTheGuide() = runBlocking { EpgScenarios.refreshingThePlaylistReimportsTheGuide(repository, loader) }
    @Test fun xtreamGuideFollowsFavouritesAndShortEpgFillsTheRest() = runBlocking { EpgScenarios.xtreamGuideFollowsFavouritesAndShortEpgFillsTheRest(repository, loader, xtream) }
    @Test fun overlappingGuideKeepsOneTimeline() = runBlocking { EpgScenarios.overlappingGuideKeepsOneTimeline(repository, loader) }
    @Test fun overlappingShortEpgKeepsOneTimeline() = runBlocking { EpgScenarios.overlappingShortEpgKeepsOneTimeline(repository, xtream) }
    @Test fun staleGuidesAreReloaded() = runBlocking { EpgScenarios.staleGuidesAreReloaded(repository, loader, clock) }
    @Test fun purgeDropsFinishedProgrammes() = runBlocking { EpgScenarios.purgeDropsFinishedProgrammes(repository, loader, clock) }
}
