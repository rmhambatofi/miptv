package com.miptv

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class PhoneNavigationTest {
    @get:Rule val compose = createComposeRule()

    private fun launch() {
        val model = LiveViewModel()
        compose.setContent {
            val state by model.state.collectAsState()
            MiptvTheme { PhoneApp(state, model::navigate, model::category, model::select, model::search, model::openChannel) }
        }
    }

    @Test fun bottomBarOpensAllFiveDestinations() {
        launch()
        compose.onNodeWithText("Votre cinéma de poche").assertIsDisplayed()
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("content").performScrollToNode(hasTestTag("category-strip"))
        compose.onNodeWithTag("category-animation").assertIsDisplayed()
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

    @Test fun emptyCategoryAndSearchFiltering() {
        launch()
        compose.onNodeWithTag("nav-TV").performClick()
        compose.onNodeWithTag("content").performScrollToNode(hasTestTag("category-strip"))
        compose.onNodeWithTag("category-empty").performScrollTo().performClick()
        compose.onNodeWithTag("empty-category").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav-SEARCH").performClick()
        compose.onNodeWithTag("search-input").performTextInput("SINTEL")
        compose.onNodeWithTag("channel-sintel").assertExists()
        compose.onNodeWithTag("channel-bbb").assertDoesNotExist()
    }
}
