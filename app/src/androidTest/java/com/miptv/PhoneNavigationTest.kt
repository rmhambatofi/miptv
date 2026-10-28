package com.miptv

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun launch(withSource: Boolean = true): LiveViewModel {
        val repository = MemoryChannelRepository(loader)
        if (withSource) runBlocking { repository.addSource("Démo", "https://demo.example/list.m3u", SourceKind.URL) }
        val model = LiveViewModel(repository)
        compose.setContent {
            val state by model.state.collectAsState()
            MiptvTheme { PhoneApp(state, model.actions()) }
        }
        return model
    }

    /** Lazy items only exist once scrolled into composition. */
    private fun scrollTo(tag: String) = compose.onNodeWithTag("content").performScrollToNode(hasTestTag(tag))
    private fun chip(tag: String) {
        scrollTo("category-strip")
        compose.onNodeWithTag("category-strip").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun LiveViewModel.channelTag(tvgId: String) = "channel-" + state.value.catalog.channels.first { it.tvgId == tvgId }.id
    private fun LiveViewModel.categoryTag(tvgId: String) = "category-" + state.value.catalog.channels.first { it.tvgId == tvgId }.categoryId

    @Test fun bottomBarOpensAllFiveDestinations() {
        val model = launch()
        compose.onNodeWithText("Votre cinéma de poche").assertIsDisplayed()
        compose.onNodeWithTag("nav-TV").performClick()
        scrollTo("category-strip")
        compose.onNodeWithTag("category-strip").performScrollToNode(hasTestTag(model.categoryTag("bbb")))
        compose.onNodeWithTag(model.categoryTag("bbb")).assertIsDisplayed()
        compose.onNodeWithTag("nav-MOVIES").performClick()
        compose.onNodeWithText("Les lumières du Nord").assertIsDisplayed()
        compose.onNodeWithTag("nav-SERIES").performClick()
        compose.onNodeWithText("Horizons").assertIsDisplayed()
        compose.onNodeWithTag("nav-SEARCH").performClick()
        compose.onNodeWithTag("search-input").performTextInput("unknown")
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
        compose.onNodeWithTag(model.channelTag("sintel")).assertExists()
        compose.onNodeWithTag(model.channelTag("bbb")).assertDoesNotExist()
    }

    @Test fun sourcesScreenAddsUrlAndRejectsInvalidAddress() {
        launch(withSource = false)
        compose.onNodeWithTag("open-sources").performClick()
        compose.onNodeWithTag("no-sources").assertIsDisplayed()
        compose.onNodeWithTag("source-url").performTextInput("ftp://bad")
        compose.onNodeWithTag("add-url").performClick()
        compose.onNodeWithTag("import-status").assertTextContains("Adresse invalide", substring = true)
        compose.onNodeWithTag("source-url").performTextClearance()
        compose.onNodeWithTag("source-url").performTextInput("https://demo.example/list.m3u")
        compose.onNodeWithTag("add-url").performClick()
        // The sources collector and the import status land in separate frames; wait for the final message.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("import-status") and hasText("2 chaînes importées", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
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
        compose.onNodeWithTag("add-url").performClick()
        compose.waitUntil(5_000) { model.state.value.hasSources }
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("no-source").assertDoesNotExist()
        scrollTo("filter-input")
        compose.onNodeWithTag("filter-input").performTextInput("sin")
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
        val star = "favorite-" + model.state.value.catalog.channels.first { it.tvgId == "sintel" }.id
        scrollTo(star)
        compose.onNodeWithTag(star).performClick()
        compose.waitUntil(5_000) { model.state.value.catalog.favorites.size == 1 }
        chip("category-favorites")
        scrollTo(model.channelTag("sintel"))
        compose.onNodeWithTag(model.channelTag("sintel")).assertIsDisplayed()
        compose.onNodeWithTag(model.channelTag("bbb")).assertDoesNotExist()
    }
}
