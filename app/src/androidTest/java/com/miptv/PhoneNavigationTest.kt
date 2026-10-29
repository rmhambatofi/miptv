package com.miptv

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class PhoneNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val playlist = """#EXTM3U
#EXTINF:-1 tvg-id="bbb" group-title="Animation",Big Buck Bunny
https://download.blender.org/peach/trailer/trailer_480p.mov
#EXTINF:-1 tvg-id="sintel" group-title="Cinéma",Sintel
https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4
"""

    private val loader = object : PlaylistLoader {
        override suspend fun open(location: String, kind: SourceKind): InputStream =
            if (location == "https://demo.example/list.m3u") playlist.byteInputStream() else throw java.net.UnknownHostException()
    }

    private val repository = MemoryChannelRepository(loader)

    private fun launch(withSource: Boolean = true): LiveViewModel {
        if (withSource) runBlocking { repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL) }
        val model = LiveViewModel(repository)
        compose.setContent {
            val state by model.state.collectAsState()
            MiptvTheme { PhoneApp(state, model.actions(), model.feeds()) }
        }
        return model
    }

    /** On API 33+ the soft keyboard stays up after text input, hides buttons and shrinks the lazy viewport. */
    private fun hideKeyboard() {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    /** Lazy items only exist once scrolled into composition. */
    private fun scrollTo(tag: String) = compose.onNodeWithTag("content").performScrollToNode(hasTestTag(tag))
    private fun chip(tag: String) {
        scrollTo("category-strip")
        compose.onNodeWithTag("category-strip").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun channel(tvgId: String) = runBlocking { repository.list().first { it.tvgId == tvgId } }
    private fun LiveViewModel.channelTag(tvgId: String) = "channel-" + channel(tvgId).id
    private fun LiveViewModel.categoryTag(tvgId: String) = "category-" + channel(tvgId).categoryId

    @Test fun bottomBarOpensAllFiveDestinations() {
        val model = launch()
        compose.onNodeWithText("Votre cinéma de poche").assertIsDisplayed()
        compose.onNodeWithTag("nav-TV").performClick()
        scrollTo("category-strip")
        compose.onNodeWithTag("category-strip").performScrollToNode(hasTestTag(model.categoryTag("bbb")))
        compose.onNodeWithTag(model.categoryTag("bbb")).assertIsDisplayed()
        // Without an Xtream source, Films and Séries lead to the provider form.
        compose.onNodeWithTag("nav-MOVIES").performClick()
        compose.onNodeWithTag("no-provider").assertIsDisplayed()
        compose.onNodeWithTag("nav-SERIES").performClick()
        compose.onNodeWithTag("no-provider").assertIsDisplayed()
        compose.onNodeWithTag("nav-SEARCH").performClick()
        compose.onNodeWithTag("search-input").performTextInput("unknown")
        hideKeyboard()
        compose.onNodeWithText("Aucun résultat.").assertIsDisplayed()
        compose.onNodeWithTag("nav-HOME").performClick()
        compose.onNodeWithText("Votre cinéma de poche").assertIsDisplayed()
    }

    @Test fun categoryFilterAndSearchFiltering() {
        val model = launch()
        compose.onNodeWithTag("nav-TV").performClick()
        chip(model.categoryTag("sintel"))
        scrollTo(model.channelTag("sintel"))
        compose.onNodeWithTag(model.channelTag("sintel")).assertIsDisplayed()
        compose.onNodeWithTag(model.channelTag("bbb")).assertDoesNotExist()
        compose.onNodeWithTag("nav-SEARCH").performClick()
        compose.onNodeWithTag("search-input").performTextInput("SINTEL")
        hideKeyboard()
        compose.onNodeWithTag(model.channelTag("sintel")).assertExists()
        compose.onNodeWithTag(model.channelTag("bbb")).assertDoesNotExist()
    }

    @Test fun sourcesScreenAddsUrlAndRejectsInvalidAddress() {
        val model = launch(withSource = false)
        compose.onNodeWithTag("open-sources").performClick()
        scrollTo("no-sources")
        compose.onNodeWithTag("no-sources").assertIsDisplayed()
        scrollTo("source-url")
        compose.onNodeWithTag("source-url").performTextInput("ftp://bad")
        hideKeyboard()
        compose.onNodeWithTag("add-url").performClick()
        compose.onNodeWithTag("import-status").assertTextContains("Adresse invalide", substring = true)
        // Replace in one action: clearing then typing is not reliable on every API level.
        compose.onNodeWithTag("source-url").performTextReplacement("https://demo.example/list.m3u")
        hideKeyboard()
        compose.onNodeWithTag("source-url").assertTextContains("https://demo.example/list.m3u")
        scrollTo("add-url")
        compose.onNodeWithTag("add-url").performClick()
        compose.waitUntil(5_000) { model.state.value.sources.isNotEmpty() && !model.state.value.importStatus.running }
        compose.waitForIdle()
        scrollTo("import-status")
        compose.onNodeWithTag("import-status").assertTextContains("2 chaînes importées", substring = true)
        compose.onNodeWithTag("no-sources").assertDoesNotExist()
        scrollTo("source-1")
        compose.onNodeWithTag("source-1").assertIsDisplayed()
        compose.onNodeWithTag("remove-1").assertExists()
    }

    @Test fun noSourceStateLeadsToSourcesScreenAndFilterNarrowsChannels() {
        val model = launch(withSource = false)
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("no-source").assertIsDisplayed()
        compose.onNodeWithTag("add-source").performClick()
        compose.onNodeWithTag("source-url").assertIsDisplayed()
        compose.onNodeWithTag("source-url").performTextInput("https://demo.example/list.m3u")
        hideKeyboard()
        scrollTo("add-url")
        compose.onNodeWithTag("add-url").performClick()
        compose.waitUntil(5_000) { model.state.value.hasSources && !model.state.value.importStatus.running }
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("no-source").assertDoesNotExist()
        scrollTo("filter-input")
        compose.onNodeWithTag("filter-input").performTextInput("sin")
        hideKeyboard()
        scrollTo(model.channelTag("sintel"))
        compose.onNodeWithTag(model.channelTag("sintel")).assertIsDisplayed()
        compose.onNodeWithTag(model.channelTag("bbb")).assertDoesNotExist()
    }

    @Test fun selectingAChannelShowsPreviewAndFullscreenToggles() {
        val model = launch()
        compose.onNodeWithTag("nav-TV").performClick()
        scrollTo(model.channelTag("bbb"))
        compose.onNodeWithTag(model.channelTag("bbb")).performClick()
        scrollTo("player-status")
        compose.onNodeWithTag("player-status").assertExists()
        scrollTo("enter-fullscreen")
        compose.onNodeWithTag("enter-fullscreen").performClick()
        compose.onNodeWithTag("fullscreen-player").assertIsDisplayed()
        compose.onNodeWithTag("exit-fullscreen").performClick()
        compose.onNodeWithTag("fullscreen-player").assertDoesNotExist()
        compose.onNodeWithTag("nav-HOME").performClick()
        compose.onNodeWithText("Votre cinéma de poche").assertIsDisplayed()
    }

    @Test fun favoriteStarFeedsTheFavoritesCategory() {
        val model = launch()
        compose.onNodeWithTag("nav-TV").performClick()
        chip("category-favorites")
        scrollTo("empty-category")
        compose.onNodeWithTag("empty-category").assertIsDisplayed()
        chip("category-all")
        val star = "favorite-" + channel("sintel").id
        scrollTo(star)
        compose.onNodeWithTag(star).performClick()
        compose.waitUntil(5_000) { model.state.value.favoriteIds.size == 1 }
        chip("category-favorites")
        scrollTo(model.channelTag("sintel"))
        compose.onNodeWithTag(model.channelTag("sintel")).assertIsDisplayed()
        compose.onNodeWithTag(model.channelTag("bbb")).assertDoesNotExist()
    }
}
