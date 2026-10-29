package com.miptv

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Xtream through the interface: the provider form, Films and Séries sheets, the VOD player and the home resume row. No network. */
class XtreamPhoneTest {
    @get:Rule val compose = createComposeRule()
    private val xtream = FakeXtreamClient()
    private val loader = object : PlaylistLoader {
        override suspend fun open(location: String, kind: SourceKind): java.io.InputStream = throw java.net.UnknownHostException()
    }
    private val repository = MemoryChannelRepository(loader, xtream)

    private fun launch(withProvider: Boolean): LiveViewModel {
        if (withProvider) runBlocking { repository.addXtreamSource("Fournisseur", XtreamAccount("portal.example:8080", "u-test", "p-test")) }
        val model = LiveViewModel(repository)
        compose.setContent {
            val state by model.state.collectAsState()
            MiptvTheme { PhoneApp(state, model.actions(), model.feeds()) }
        }
        return model
    }

    private fun hideKeyboard() { Espresso.closeSoftKeyboard(); compose.waitForIdle() }
    private fun scrollTo(tag: String) = compose.onNodeWithTag("content").performScrollToNode(hasTestTag(tag))

    @Test fun providerFormTestsThenAddsTheAccount() {
        val model = launch(withProvider = false)
        compose.onNodeWithTag("nav-MOVIES").performClick()
        compose.onNodeWithTag("no-provider").assertIsDisplayed()
        compose.onNodeWithTag("add-provider").performClick()
        compose.onNodeWithTag("form-XTREAM").performClick()
        compose.onNodeWithTag("xtream-server").performTextInput("portal.example:8080")
        compose.onNodeWithTag("xtream-username").performTextInput("u-test")
        compose.onNodeWithTag("xtream-password").performTextInput("wrong")
        hideKeyboard()
        scrollTo("check-xtream")
        compose.onNodeWithTag("check-xtream").performClick()
        compose.waitUntil(5_000) { model.state.value.accountCheck != null }
        scrollTo("account-check")
        compose.onNodeWithTag("account-check").assertTextContains(XtreamParser.REFUSED)
        scrollTo("xtream-password")
        compose.onNodeWithTag("xtream-password").performTextReplacement("p-test")
        hideKeyboard()
        scrollTo("check-xtream")
        compose.onNodeWithTag("check-xtream").performClick()
        compose.waitUntil(5_000) { model.state.value.accountCheck is Fetched.Success }
        scrollTo("account-check")
        compose.onNodeWithTag("account-check").assertTextContains("Compte active", substring = true)
        compose.onNodeWithTag("add-xtream").performClick()
        compose.waitUntil(5_000) { model.state.value.hasXtream && !model.state.value.importStatus.running }
        compose.waitForIdle()
        scrollTo("import-status")
        compose.onNodeWithTag("import-status").assertTextContains("5 chaînes, 3 films et 2 séries", substring = true)
        scrollTo("source-expiry-1")
        compose.onNodeWithTag("source-expiry-1").assertTextContains("01/01/2027", substring = true)
        // The password never reaches the state.
        compose.onNodeWithText("p-test", substring = true).assertDoesNotExist()
    }

    @Test fun filmsGridOpensASheetThatPlays() {
        val model = launch(withProvider = true)
        compose.onNodeWithTag("nav-MOVIES").performClick()
        scrollTo("movie-xtream:1:movie:5001")
        compose.onNodeWithTag("movie-xtream:1:movie:5001").performClick()
        compose.waitUntil(5_000) { model.state.value.movieSheet?.loading == false }
        compose.onNodeWithTag("sheet-title").assertTextContains("Le Voyage (2019)")
        scrollTo("movie-synopsis")
        compose.onNodeWithTag("movie-synopsis").assertTextContains("Deux amis traversent un pays.")
        compose.onNodeWithTag("movie-meta").assertTextContains("1 h 58", substring = true)
        scrollTo("play-movie")
        compose.onNodeWithTag("play-movie").performClick()
        compose.waitUntil(5_000) { model.state.value.vod != null }
        scrollTo("vod-player")
        compose.onNodeWithTag("player-status").assertExists()
        scrollTo("enter-fullscreen")
        compose.onNodeWithTag("enter-fullscreen").performClick()
        compose.onNodeWithTag("fullscreen-player").assertIsDisplayed()
        compose.onNodeWithTag("exit-fullscreen").performClick()
        compose.onNodeWithTag("fullscreen-player").assertDoesNotExist()
        scrollTo("close-player")
        compose.onNodeWithTag("close-player").performClick()
        compose.onNodeWithTag("vod-player").assertDoesNotExist()
        compose.onNodeWithTag("close-sheet").performClick()
        scrollTo("movie-category-strip")
        compose.onNodeWithTag("movie-category-strip").assertIsDisplayed()
    }

    @Test fun seriesSheetSwitchesSeasonsPlaysAndOffersTheNextEpisode() {
        val model = launch(withProvider = true)
        compose.onNodeWithTag("nav-SERIES").performClick()
        scrollTo("series-xtream:1:series:77")
        compose.onNodeWithTag("series-xtream:1:series:77").performClick()
        compose.waitUntil(5_000) { model.state.value.seriesSheet?.info != null }
        scrollTo("season-strip")
        compose.onNodeWithTag("season-2").performClick()
        scrollTo("episode-xtream:1:episode:9003")
        compose.onNodeWithTag("episode-xtream:1:episode:9003").assertIsDisplayed()
        compose.onNodeWithTag("episode-xtream:1:episode:9001").assertDoesNotExist()
        compose.onNodeWithTag("season-1").performClick()
        scrollTo("episode-xtream:1:episode:9001")
        compose.onNodeWithTag("episode-xtream:1:episode:9001").performClick()
        compose.waitUntil(5_000) { model.state.value.vod?.id == "xtream:1:episode:9001" }
        scrollTo("next-episode")
        compose.onNodeWithTag("next-episode").performClick()
        compose.waitUntil(5_000) { model.state.value.vod?.id == "xtream:1:episode:9002" }
        scrollTo("vod-player")
        compose.onNodeWithText("S01E02 · Épisode 2").assertIsDisplayed()
    }

    @Test fun homeOffersToResumeAnUnfinishedFilm() {
        runBlocking {
            repository.addXtreamSource("Fournisseur", XtreamAccount("portal.example:8080", "u-test", "p-test"))
            repository.saveProgress("xtream:1:movie:5001", 2_520_000, 7_080_000)
        }
        val model = launch(withProvider = false)
        compose.waitUntil(5_000) { model.state.value.resume.isNotEmpty() }
        scrollTo("resume-row")
        compose.onNodeWithTag("resume-xtream:1:movie:5001").assertTextContains("Reprendre à 42 min", substring = true)
        compose.onNodeWithTag("resume-xtream:1:movie:5001").performClick()
        compose.waitUntil(5_000) { model.state.value.vod?.resumeMs == 2_520_000L }
        compose.onNodeWithTag("sheet-title").assertTextContains("Le Voyage (2019)")
        scrollTo("resume-movie")
        compose.onNodeWithTag("resume-movie").assertTextContains("Reprendre à 42 min")
    }
}
