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

    private fun LiveViewModel.channelTag(tvgId: String) = "channel-" + state.value.catalog.channels.first { it.tvgId == tvgId }.id
    private fun LiveViewModel.categoryTag(tvgId: String) = "category-" + state.value.catalog.channels.first { it.tvgId == tvgId }.categoryId

    @Test fun bottomBarOpensAllFiveDestinations() {
        val model = launch()
        compose.onNodeWithText("Votre cinéma de poche").assertIsDisplayed()
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("content").performScrollToNode(hasTestTag("category-strip"))
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
        compose.onNodeWithTag("content").performScrollToNode(hasTestTag("category-strip"))
        compose.onNodeWithTag(model.categoryTag("sintel")).performScrollTo().performClick()
        compose.onNodeWithTag(model.channelTag("sintel")).performScrollTo().assertIsDisplayed()
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
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("no-sources").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("import-status").assertTextContains("2 chaînes importées", substring = true)
        compose.onNodeWithText("Démo", substring = true).assertDoesNotExist()
        compose.onNodeWithText("demo.example", substring = true).assertExists()
    }
}
