package com.miptv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** A server that accepts the connection and never answers: Media3 alone would keep loading. */
class PlayerTimeoutTest {
    @get:Rule val compose = createComposeRule()

    private val server = ServerSocket(0)
    private val held = CopyOnWriteArrayList<Socket>()
    private val acceptor = thread(isDaemon = true) {
        try { while (true) held += server.accept() } catch (_: Exception) { }
    }

    @After fun close() {
        server.close()
        held.forEach { it.close() }
        acceptor.join(1_000)
    }

    @Test fun silentStreamShowsTheErrorAfterTheLimitAndRetryLoadsAgain() {
        val channel = Channel("silent", "Flux muet", "g", "http://127.0.0.1:${server.localPort}/live.ts")
        compose.setContent {
            MiptvTheme { rememberPlayback(channel, loadingLimitMs = 3_000)?.let { Preview(it, favorite = false, onFullscreen = {}, onToggleFavorite = {}) } }
        }
        compose.onNodeWithTag("player-status").assertTextEquals("Chargement…")
        compose.onNodeWithTag("retry").assertDoesNotExist()

        compose.waitUntil(10_000) { compose.onAllNodesWithTag("retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-status").assertTextEquals(LOADING_TIMEOUT_MESSAGE)

        compose.onNodeWithTag("retry").performClick()
        compose.onNodeWithTag("retry").assertDoesNotExist()
        compose.onNodeWithTag("player-status").assertTextEquals("Chargement…")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-status").assertTextEquals(LOADING_TIMEOUT_MESSAGE)
    }
}
