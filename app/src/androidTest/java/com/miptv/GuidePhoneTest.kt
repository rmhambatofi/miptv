package com.miptv

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.paging.testing.asSnapshot
import androidx.test.espresso.Espresso
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The programme guide through the interface: channel cards, the guide sheet, the home row, the search group and the source card. No network. */
class GuidePhoneTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeZone = ParisTimeZoneRule()
    private val loader = GuideLoader().apply {
        this[GuideFixtures.PLAYLIST_URL] = GuideFixtures.PLAYLIST
        this[GuideFixtures.GUIDE_URL] = GuideFixtures.GUIDE
        this[GuideFixtures.GUIDE2_URL] = GuideFixtures.GUIDE_V2
    }
    private val clock = TestClock()
    private val repository = MemoryChannelRepository(loader, FakeXtreamClient(), clock)

    private fun launch(): LiveViewModel {
        runBlocking { repository.addSource("France", GuideFixtures.PLAYLIST_URL, SourceKind.URL) }
        val model = LiveViewModel(repository, clock = clock)
        compose.setContent {
            val state by model.state.collectAsState()
            MiptvTheme { PhoneApp(state, model.actions(), model.feeds()) }
        }
        return model
    }

    private fun channel(name: String): Channel = runBlocking { repository.channels(ChannelQuery()).asSnapshot().first { it.name == name } }
    private fun scrollTo(tag: String) = compose.onNodeWithTag("content").performScrollToNode(hasTestTag(tag))

    @Test fun channelCardsShowWhatIsOnAndTheGuideSheetFollowsTheDays() {
        val model = launch()
        val tf1 = channel("TF1")
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("category-all").performClick()
        scrollTo("channel-${tf1.id}")
        compose.onNodeWithTag("channel-${tf1.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Journal de 20h")))
        compose.onNodeWithTag("channel-${tf1.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("À suivre 20:35 · Météo")))
        val locale = channel("Chaîne locale")
        scrollTo("channel-${locale.id}")
        compose.onNodeWithTag("channel-${locale.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasTestTag("now-line")).not())

        scrollTo("guide-${tf1.id}")
        compose.onNodeWithTag("guide-${tf1.id}").performClick()
        compose.waitUntil(5_000) { model.state.value.guideSheet?.loading == false }
        compose.onNodeWithTag("guide-title").assertTextContains("TF1")
        val today = model.state.value.guideDays[0]
        val tomorrow = model.state.value.guideDays[1]
        compose.onNodeWithTag("guide-day-$today").assertIsSelected()
        scrollTo("programme-${GuideFixtures.H19_30}")
        compose.onNodeWithTag("programme-${GuideFixtures.H19_30}").assertTextContains("En ce moment · Information", substring = true)
        compose.onNodeWithTag("programme-${GuideFixtures.H19_30}").performClick() // unfolds the description
        compose.onNodeWithTag("programme-description", useUnmergedTree = true).assertTextContains("Le journal.")
        scrollTo("guide-days")
        compose.onNodeWithTag("guide-day-$tomorrow").performClick()
        compose.waitUntil(5_000) { model.state.value.guideSheet?.let { !it.loading && it.dayStartMs == tomorrow } == true }
        scrollTo("programme-${GuideFixtures.H22_40}")
        compose.onNodeWithTag("programme-${GuideFixtures.H22_40}").assertTextContains("Nuit blanche", substring = true)
        // The guide stops at 00:10 the next day: the day after is faded and says so.
        val emptyDay = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Sans programme")
        scrollTo("guide-days")
        compose.onNodeWithTag("guide-day-$tomorrow").assert(emptyDay.not())
        val after = model.state.value.guideDays[2]
        compose.onNodeWithTag("guide-days").performScrollToNode(hasTestTag("guide-day-$after"))
        compose.onNodeWithTag("guide-day-$after").assert(emptyDay).performClick()
        compose.waitUntil(5_000) { model.state.value.guideSheet?.let { !it.loading && it.dayStartMs == after } == true }
        scrollTo("guide-empty")
        compose.onNodeWithTag("guide-empty").assertTextContains("Pas encore de programmes pour ce jour : le guide connu s'arrête demain à 00:10.")
        scrollTo("guide-play")
        compose.onNodeWithTag("guide-play").performClick()
        compose.waitUntil(5_000) { model.state.value.selected?.id == tf1.id }
        assertEquals(Destination.TV, model.state.value.destination)
        compose.onNodeWithTag("guide-sheet").assertDoesNotExist()
        compose.onNodeWithTag("preview-now", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Journal de 20h")))
    }

    @Test fun aProviderChannelOutsideTheFavouritesSaysItsGuideIsLimited() {
        val model = launch()
        runBlocking { repository.addXtreamSource("Fournisseur", XtreamAccount("http://portal.example:8080", "u-test", "p-test")) }
        val tf1 = runBlocking { repository.channel("xtream:2:101")!! } // source 1 is the playlist
        compose.runOnIdle { model.openGuide(tf1) }
        compose.waitUntil(5_000) { model.state.value.guideSheet?.loading == false }
        scrollTo("guide-limited")
        compose.onNodeWithTag("guide-limited", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Guide limité aux prochaines heures", substring = true)))
        compose.onNodeWithTag("guide-limited-favorite").performClick()
        compose.waitUntil(5_000) { model.state.value.isFavorite(tf1) }
        compose.onNodeWithTag("guide-limited").assertDoesNotExist()
        // A playlist channel never shows the note.
        compose.runOnIdle { model.openGuide(channel("TF1")) }
        compose.waitUntil(5_000) { model.state.value.guideSheet?.channel?.name == "TF1" && model.state.value.guideSheet?.loading == false }
        compose.onNodeWithTag("guide-limited").assertDoesNotExist()
    }

    @Test fun favouritesGetAHomeRow() {
        val model = launch()
        val tf1 = channel("TF1")
        compose.onNodeWithTag("now-row").assertDoesNotExist()
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("category-all").performClick()
        scrollTo("favorite-${tf1.id}")
        compose.onNodeWithTag("favorite-${tf1.id}").performClick()
        compose.waitUntil(5_000) { model.state.value.onAirFavorites.size == 1 }
        compose.onNodeWithTag("nav-HOME").performClick()
        scrollTo("now-row")
        compose.onNodeWithTag("now-${tf1.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("Journal de 20h")))
        // A long press on a favourite opens its guide; a tap plays it. Through the accessibility action: an injected
        // long-press gesture took the CI emulator offline at this step, three runs in five.
        compose.onNodeWithTag("now-${tf1.id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { model.state.value.guideSheet != null }
        compose.onNodeWithTag("guide-title").assertTextContains("TF1")
    }

    @Test fun programmeSearchOpensTheGuideOnTheHit() {
        val model = launch()
        compose.onNodeWithTag("nav-SEARCH").performClick()
        compose.onNodeWithTag("search-input").performTextInput("islande")
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { model.state.value.searchProgrammeCount == 1 }
        val f2 = channel("France 2")
        scrollTo("hit-${f2.id}-${GuideFixtures.H21_00}")
        compose.onNodeWithTag("hit-${f2.id}-${GuideFixtures.H21_00}").performClick()
        compose.waitUntil(5_000) { model.state.value.guideSheet?.loading == false }
        compose.onNodeWithTag("guide-title").assertTextContains("France 2")
        scrollTo("programme-${GuideFixtures.H21_00}")
        compose.onNodeWithTag("programme-${GuideFixtures.H21_00}").assertTextContains("Documentaire : voyage en Islande", substring = true)
        compose.onNodeWithTag("close-guide").performClick()
        compose.onNodeWithTag("guide-sheet").assertDoesNotExist()
        compose.onNodeWithTag("search-input").assertTextContains("islande", substring = true)
    }

    @Test fun sourceCardEditsTheGuideAddress() {
        val model = launch()
        compose.onNodeWithTag("open-sources").performClick()
        scrollTo("epg-url-1")
        compose.onNodeWithTag("epg-url-1").assertTextContains(GuideFixtures.GUIDE_URL, substring = true)
        compose.onNodeWithTag("epg-status-1").assertTextContains("9 programmes", substring = true)
        compose.onNodeWithTag("epg-url-1").performTextReplacement(GuideFixtures.GUIDE2_URL)
        Espresso.closeSoftKeyboard()
        scrollTo("save-epg-1")
        compose.onNodeWithTag("save-epg-1").performClick()
        compose.waitUntil(5_000) { model.state.value.sources.single().programmeCount == 1 && !model.state.value.importStatus.running }
        compose.waitForIdle()
        scrollTo("epg-status-1")
        compose.onNodeWithTag("epg-status-1").assertTextContains("1 programmes", substring = true)
        compose.onNodeWithTag("save-epg-1").assertDoesNotExist()
        scrollTo("import-status")
        compose.onNodeWithTag("import-status").assertTextContains("1 programmes dans le guide de « France ».")
        scrollTo("refresh-epg-1")
        compose.onNodeWithTag("refresh-epg-1").assertIsDisplayed()
    }
}
