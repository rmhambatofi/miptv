package com.miptv

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The screen does not lock while a channel, film or episode plays; paused or closed, it may again. */
class KeepScreenOnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenStaysOnOnlyWhilePlaying() {
        val item = PlayItem("xtream:1:movie:5", "Le Voyage", "2019", "xtream://1/movie/5.mkv", live = false, resumeMs = 0)
        var shown by mutableStateOf<PlayItem?>(item)
        lateinit var view: View
        var playback: Playback? = null
        compose.setContent {
            view = LocalView.current
            // No stream is resolved, so only the test drives the playing flag.
            playback = rememberPlayback(shown, resolve = { null }, onProgress = null)
        }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }

        compose.runOnIdle { playback!!.playing = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn) }

        compose.runOnIdle { playback!!.playing = false }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }

        compose.runOnIdle { playback!!.playing = true }
        compose.runOnIdle { assertTrue(view.keepScreenOn) }
        compose.runOnIdle { shown = null }
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }
}
