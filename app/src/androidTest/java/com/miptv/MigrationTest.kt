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
 * Builds a database from the exported schema files (`app/schemas`), as a Sprint 2 install left it, then
 * migrates it step by step (v1 → v2 → v3 → v4). Each future version adds its `runMigrationsAndValidate` step here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MiptvDatabase::class.java)

    private val loader = object : PlaylistLoader {
        override suspend fun open(location: String, kind: SourceKind): InputStream = throw java.net.UnknownHostException()
    }

    @Test fun filledVersion1DatabaseMigratesAndOpensWithTheCurrentEntities() = runBlocking {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO sources (id, name, location, kind, lastRefreshed, channelCount, lastError) VALUES (1, 'Démo', 'https://demo.example/list.m3u', 'URL', 1730000000000, 2, NULL)")
            execSQL("INSERT INTO categories (id, name, position) VALUES ('animation', 'Animation', 0), ('cinema', 'Cinéma', 1)")
            execSQL("INSERT INTO channels (id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, position, userAgent, referer) VALUES ('bbb', 'Big Buck Bunny', 'animation', 'https://a/1', NULL, 'bbb', 1, 0, 'VLC/3.0', NULL)")
            execSQL("INSERT INTO channels (id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, position, userAgent, referer) VALUES ('sintel', 'Sintel', 'cinema', 'https://a/2', 'https://a/sintel.png', 'sintel', 1, 1, NULL, 'https://a/')")
            execSQL("INSERT INTO channels (id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, position, userAgent, referer) VALUES ('tele', 'TÉLÉ Matin', 'cinema', 'https://a/3', NULL, NULL, 1, 2, NULL, NULL)")
            execSQL("INSERT INTO favorites (channelId, addedAt) VALUES ('sintel', 1730000000000)")
            close()
        }

        // Validates columns, defaults and indices against 2.json; the search key is filled for existing rows.
        helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).apply {
            query("SELECT searchName FROM channels ORDER BY position").use { cursor ->
                val keys = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
                assertEquals(listOf("big buck bunny", "sintel", "tele matin"), keys)
            }
            close()
        }

        // v3 adds the Xtream columns (null or zero for a playlist source) and the VOD tables, empty.
        helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3).apply {
            query("SELECT username, encryptedPassword, expiresAt, movieCount, seriesCount FROM sources").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0)); assertTrue(cursor.isNull(1)); assertTrue(cursor.isNull(2))
                assertEquals(0, cursor.getInt(3)); assertEquals(0, cursor.getInt(4))
            }
            for (table in listOf("vod_categories", "movies", "series", "seasons", "episodes", "progress")) {
                query("SELECT COUNT(*) FROM $table").use { cursor -> cursor.moveToFirst(); assertEquals(table, 0, cursor.getInt(0)) }
            }
            close()
        }

        // v4 adds the guide columns, seeds the channel guide key from tvgId and creates the empty programmes table.
        helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4).apply {
            query("SELECT epgUrl, epgRefreshedAt, epgError, programmeCount FROM sources").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0)); assertTrue(cursor.isNull(1)); assertTrue(cursor.isNull(2)); assertEquals(0, cursor.getInt(3))
            }
            query("SELECT epgKey FROM channels ORDER BY position").use { cursor ->
                val keys = ArrayList<String?>()
                while (cursor.moveToNext()) keys += if (cursor.isNull(0)) null else cursor.getString(0)
                assertEquals(listOf("bbb", "sintel", null), keys)
            }
            query("SELECT COUNT(*) FROM programmes").use { cursor -> cursor.moveToFirst(); assertEquals(0, cursor.getInt(0)) }
            close()
        }

        // Room checks the identity hash: a schema file out of step with the entities fails here.
        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), MiptvDatabase::class.java, DB)
            .addMigrations(*MIGRATIONS).build()
        try {
            val repository = RoomChannelRepository(database, loader)
            assertEquals(listOf("Animation", "Cinéma"), repository.categories.first().map { it.name })
            val channels = repository.list()
            assertEquals(listOf("Big Buck Bunny", "Sintel", "TÉLÉ Matin"), channels.map { it.name })
            assertEquals(setOf("sintel"), repository.favoriteIds.first())
            assertEquals(listOf("Sintel"), repository.list(ChannelQuery(Category.FAVORITES.id)).map { it.name })
            assertEquals(listOf("TÉLÉ Matin"), repository.list(ChannelQuery("cinema", "télé")).map { it.name })
            val bbb = channels.first()
            assertEquals("VLC/3.0", bbb.userAgent)
            assertNull(bbb.logoUrl)
            assertEquals("https://a/", channels[1].referer)
            val source = repository.sources.first().single()
            assertEquals(SourceKind.URL, source.kind)
            assertEquals(2, source.channelCount)
            assertNull(source.lastError)
            assertNull(source.username)
            assertEquals(0, source.movieCount)
            assertTrue(repository.movieCategories.first().isEmpty())
            assertTrue(repository.progress.first().isEmpty())
            assertEquals(0, repository.countMovies(VodQuery()).first())
            assertTrue(repository.nowAndNext(System.currentTimeMillis()).first().isEmpty())
            assertFalse(source.hasGuide)
        } finally {
            database.close()
        }
    }

    private companion object { const val DB = "migration-test.db" }
}
