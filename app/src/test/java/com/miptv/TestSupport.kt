package com.miptv

import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Playlists served by location; a missing location throws like an unreachable host. */
class FakeLoader(private val playlists: MutableMap<String, String> = mutableMapOf()) : PlaylistLoader {
    var opened = 0
    operator fun set(location: String, playlist: String) { playlists[location] = playlist }
    override suspend fun open(location: String, kind: SourceKind): InputStream {
        opened++
        return playlists[location]?.byteInputStream() ?: throw java.net.UnknownHostException("unreachable")
    }
}

const val DEMO_PLAYLIST = """#EXTM3U
#EXTINF:-1 tvg-id="bbb" tvg-logo="https://logo.example/bbb.png" group-title="Animation",Big Buck Bunny
https://download.blender.org/peach/trailer/trailer_480p.mov
#EXTINF:-1 tvg-id="sintel" group-title="Cinéma",Sintel
https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4
"""

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()

    /** Runs every coroutine queued on the main dispatcher (collectors, launches). */
    fun idle() = dispatcher.scheduler.advanceUntilIdle()

    /** Imports hop to Dispatchers.IO; poll until the queued continuation reports the import finished. */
    fun awaitImport(model: LiveViewModel): LiveState {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            dispatcher.scheduler.advanceUntilIdle()
            if (!model.state.value.importStatus.running) {
                dispatcher.scheduler.advanceUntilIdle()
                return model.state.value
            }
            if (System.currentTimeMillis() > deadline) throw AssertionError("L'importation n'a pas abouti dans le délai.")
            Thread.sleep(5)
        }
    }
}

