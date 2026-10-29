package com.miptv

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens a database built from the exported schema files (`app/schemas`), as a Sprint 2 install left it.
 * Each future version adds a `runMigrationsAndValidate` step here, from a v1 database filled the same way.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MiptvDatabase::class.java)

    private val loader = object : PlaylistLoader {
        override suspend fun open(location: String, kind: SourceKind): InputStream = throw java.net.UnknownHostException()
    }

    @Test fun filledVersion1DatabaseOpensWithTheCurrentEntities() = runBlocking {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO sources (id, name, location, kind, lastRefreshed, channelCount, lastError) VALUES (1, 'Démo', 'https://demo.example/list.m3u', 'URL', 1730000000000, 2, NULL)")
            execSQL("INSERT INTO categories (id, name, position) VALUES ('animation', 'Animation', 0), ('cinema', 'Cinéma', 1)")
            execSQL("INSERT INTO channels (id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, position, userAgent, referer) VALUES ('bbb', 'Big Buck Bunny', 'animation', 'https://a/1', NULL, 'bbb', 1, 0, 'VLC/3.0', NULL)")
            execSQL("INSERT INTO channels (id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, position, userAgent, referer) VALUES ('sintel', 'Sintel', 'cinema', 'https://a/2', 'https://a/sintel.png', 'sintel', 1, 1, NULL, 'https://a/')")
            execSQL("INSERT INTO favorites (channelId, addedAt) VALUES ('sintel', 1730000000000)")
            close()
        }

        // Room checks the identity hash written by the helper: a schema file out of step with the entities fails here.
        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), MiptvDatabase::class.java, DB).build()
        try {
            val repository = RoomChannelRepository(database, loader)
            val catalog = repository.catalog.first { it.channels.isNotEmpty() }
            assertEquals(listOf("Animation", "Cinéma"), catalog.categories.map { it.name })
            assertEquals(listOf("Big Buck Bunny", "Sintel"), catalog.channels.map { it.name })
            assertEquals(listOf("sintel"), catalog.favorites.map { it.id })
            val bbb = catalog.channels.first()
            assertEquals("VLC/3.0", bbb.userAgent)
            assertNull(bbb.logoUrl)
            assertEquals("https://a/", catalog.channels.last().referer)
            val source = repository.sources.first().single()
            assertEquals(SourceKind.URL, source.kind)
            assertEquals(2, source.channelCount)
            assertNull(source.lastError)
        } finally {
            database.close()
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
