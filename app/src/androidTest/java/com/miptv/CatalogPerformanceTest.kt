package com.miptv

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Timings on a 50 000-channel playlist (500 groups of 100), logged under the tag `miptv-perf`. Too slow for every
 * CI run, so it only runs on demand:
 * `adb shell am instrument -w -e perf true -e class com.miptv.CatalogPerformanceTest com.miptv.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class CatalogPerformanceTest {
    private val playlist: String = buildString {
        append("#EXTM3U\n")
        for (n in 0 until 50_000) {
            append("#EXTINF:-1 tvg-id=\"ch$n\" tvg-logo=\"http://logo.example/$n.png\" group-title=\"Groupe ${n / 100}\",Chaîne $n\n")
            append("http://stream.example/live/$n.ts\n")
        }
    }
    private val loader = object : PlaylistLoader {
        override suspend fun open(location: String, kind: SourceKind): InputStream = playlist.byteInputStream()
    }

    @Before fun onDemand() = assumeTrue(InstrumentationRegistry.getArguments().getString("perf") == "true")

    private fun log(label: String, value: Long, unit: String = "ms") = Log.i("miptv-perf", "$label: $value $unit")
    private fun heapMb(): Long { Runtime.getRuntime().gc(); return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576 }

    @Test fun fiftyThousandChannels() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("perf.db")
        val database = Room.databaseBuilder(context, MiptvDatabase::class.java, "perf.db").build()
        try {
            measureTimeMillis { M3uParser(99).parse(playlist.byteInputStream()) }.let { log("parse", it) }
            val repository = RoomChannelRepository(database, loader)
            measureTimeMillis { assertTrue(repository.addSource("Perf", "http://perf/list.m3u", SourceKind.URL) is ImportResult.Success) }.let { log("import", it) }

            val heapBefore = heapMb()
            val model = withContext(Dispatchers.Main) { LiveViewModel(repository) }
            measureTimeMillis {
                model.state.first { it.ready && it.channelCount == 50_000 && it.imported.size == 500 }
                // The first load covers three pages, a few screens of cards.
                assertEquals(180, model.channels.asSnapshot().size)
            }.let { log("first screen right after import (counts + first page)", it) }
            log("heap delta", heapMb() - heapBefore, "MB")

            val target = repository.list(ChannelQuery(model.state.value.imported[250].id)).first()
            measureTimeMillis {
                withContext(Dispatchers.Main) { model.toggleFavorite(target) }
                model.state.first { target.id in it.favoriteIds }
            }.let { log("favorite toggle", it) }

            measureTimeMillis {
                withContext(Dispatchers.Main) { model.navigate(Destination.TV); model.filter("Chaîne 4999") }
                model.state.first { it.visibleCount == 11 }
                assertEquals(11, model.channels.asSnapshot().size)
            }.let { log("filter (count + list)", it) }

            measureTimeMillis {
                withContext(Dispatchers.Main) { model.filter(""); model.category(target.categoryId) }
                model.state.first { it.categoryId == target.categoryId && it.visibleCount == 100 }
                assertEquals(100, model.channels.asSnapshot().size)
            }.let { log("category (count + first page)", it) }

            measureTimeMillis {
                withContext(Dispatchers.Main) { model.search("chaine 1234") }
                model.state.first { it.searchCount == 11 }
            }.let { log("search count", it) }

            // Data cost of scrolling: paging-testing's scroll simulation is itself too slow to time the database.
            val source = ChannelPagingSource(database, ChannelQuery())
            var key: ChannelKey? = null
            measureTimeMillis {
                repeat(84) {
                    val params: PagingSource.LoadParams<ChannelKey> = key?.let { PagingSource.LoadParams.Append(it, 60, false) } ?: PagingSource.LoadParams.Refresh(null, 60, false)
                    key = (source.load(params) as PagingSource.LoadResult.Page).nextKey
                }
            }.let { log("84 pages, down to channel 5000", it) }
        } finally {
            database.close()
            context.deleteDatabase("perf.db")
        }
    }
}
