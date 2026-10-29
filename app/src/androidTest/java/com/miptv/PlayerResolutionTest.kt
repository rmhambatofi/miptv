package com.miptv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The VOD player resolves its `xtream://` reference when it starts and again on every retry; a lost credential is a clear message, not a crash. */
class PlayerResolutionTest {
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

    @Test fun missingCredentialsShowTheMessageAndRetryResolvesAgain() {
        val item = PlayItem("xtream:1:movie:5", "Le Voyage", "2019", "xtream://1/movie/5.mkv", live = false, resumeMs = 60_000)
        val attempts = AtomicInteger()
        val resolved = CopyOnWriteArrayList<String>()
        var closed = false
        compose.setContent {
            MiptvTheme {
                val playback = rememberPlayback(item, resolve = { ref ->
                    resolved += ref
                    // First attempt: the key is gone. Second: a server that accepts and never answers.
                    if (attempts.getAndIncrement() == 0) null else "http://127.0.0.1:${server.localPort}/movie/u/p/5.mkv"
                }, onProgress = null, loadingLimitMs = 3_000)
                playback?.let { VodPlayer(it, onFullscreen = {}, onClose = { closed = true }, onNext = null) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-status").assertTextEquals(CREDENTIALS_MESSAGE)
        compose.onNodeWithText("Le Voyage").assertIsDisplayed()
        assertEquals(listOf("xtream://1/movie/5.mkv"), resolved.toList())

        compose.onNodeWithTag("retry").performClick()
        compose.onNodeWithTag("retry").assertDoesNotExist()
        compose.onNodeWithTag("player-status").assertTextEquals("Chargement…")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-status").assertTextEquals(LOADING_TIMEOUT_MESSAGE)
        assertEquals(2, resolved.size)
        assertTrue(held.isNotEmpty()) // the resolved URL, not the reference, reached the server

        compose.onNodeWithTag("next-episode").assertDoesNotExist()
        compose.onNodeWithTag("close-player").performClick()
        assertTrue(closed)
    }
}
