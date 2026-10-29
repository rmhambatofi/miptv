package com.miptv

import java.io.StringReader
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamParserTest {
    private val parser = XtreamParser(sourceId = 3)
    private val account = XtreamAccount("http://portal.example:8080", "u-test", "p-test")
    private fun read(text: String) = StringReader(text)

    // --- account ---

    @Test fun activeAccountWithStringNumbers() {
        val info = parser.account(read(XtreamFixtures.ACCOUNT_ACTIVE))
        assertEquals("Active", info.status)
        assertEquals(1_798_761_600_000L, info.expiresAt)
        assertFalse(info.isTrial)
        assertEquals(2, info.maxConnections)
        assertEquals(1, info.activeConnections)
        assertEquals(listOf("m3u8", "ts", "rtmp"), info.allowedOutputFormats)
        assertEquals(1_761_734_400_000L, info.serverTime)
        assertEquals("ts", info.liveExtension)
        assertNull(info.blocker(nowMs = 1_761_734_400_000L))
        assertNull(info.connectionsMessage())
    }

    @Test fun unlimitedHlsOnlyAccount() {
        val info = parser.account(read(XtreamFixtures.ACCOUNT_UNLIMITED))
        assertNull(info.expiresAt)
        assertEquals("m3u8", info.liveExtension)
        assertNull(info.blocker())
        assertEquals("ts", XtreamAccountInfo("Active", null).liveExtension)
        assertEquals("rtmp", XtreamAccountInfo("Active", null, allowedOutputFormats = listOf("rtmp")).liveExtension)
    }

    @Test fun refusedCredentialsThrowWithoutEchoingThem() {
        val error = assertThrows(XtreamException::class.java) { parser.account(read(XtreamFixtures.ACCOUNT_REFUSED)) }
        assertEquals(XtreamParser.REFUSED, error.message)
        assertThrows(XtreamException::class.java) { parser.account(read("""{"user_info":[]}""")) }
        assertEquals(XtreamParser.UNEXPECTED, assertThrows(XtreamException::class.java) { parser.account(read("""{"server_info":{}}""")) }.message)
        assertEquals(XtreamParser.UNEXPECTED, assertThrows(XtreamException::class.java) { parser.account(read("[]")) }.message)
        assertThrows(JsonSyntaxException::class.java) { parser.account(read(XtreamFixtures.HTML_LOGIN_PAGE)) }
    }

    @Test fun expiredBannedAndSaturatedAccountsAreDescribedInFrench() {
        val expired = parser.account(read(XtreamFixtures.ACCOUNT_EXPIRED))
        assertEquals("Compte expiré le 01/01/2025.", expired.blocker(nowMs = 1_761_734_400_000L))
        // An end date in the past counts as expired even when the status says otherwise.
        val stale = XtreamAccountInfo("Active", expiresAt = 1_735_732_800_000L)
        assertEquals("Compte expiré le 01/01/2025.", stale.blocker(nowMs = 1_735_732_800_001L))
        assertNull(stale.blocker(nowMs = 1_735_732_799_999L))
        assertEquals("Compte expiré.", XtreamAccountInfo("Expired", null).blocker())
        assertEquals("Compte désactivé par le fournisseur.", parser.account(read(XtreamFixtures.ACCOUNT_BANNED)).blocker(nowMs = 0L))
        assertEquals("Compte désactivé par le fournisseur.", XtreamAccountInfo("Disabled", null).blocker())
        val saturated = parser.account(read(XtreamFixtures.ACCOUNT_SATURATED))
        assertTrue(saturated.connectionsExhausted)
        assertEquals("Nombre maximal de connexions atteint (2/2).", saturated.connectionsMessage())
        assertNull(saturated.blocker(nowMs = 0L))
    }

    // --- categories and live streams ---

    @Test fun categoriesGetPrefixedIdsAndFallbackNames() {
        val categories = parser.categories(read(XtreamFixtures.LIVE_CATEGORIES), XtreamIds.Kind.LIVE)
        assertEquals(listOf("xtream:3:category:live:1", "xtream:3:category:live:7", "xtream:3:category:live:9"), categories.map { it.id })
        assertEquals(listOf("Généralistes", "Sport", "Catégorie 9"), categories.map { it.name })
        assertTrue(parser.categories(read(XtreamFixtures.EMPTY_OBJECT_LIST), XtreamIds.Kind.LIVE).isEmpty())
        assertTrue(parser.categories(read("[]"), XtreamIds.Kind.LIVE).isEmpty())
        val keyed = parser.categories(read(XtreamFixtures.KEYED_CATEGORIES), XtreamIds.Kind.MOVIE)
        assertEquals(listOf("xtream:3:category:movie:3", "xtream:3:category:movie:5"), keyed.map { it.id })
    }

    @Test fun liveStreamsBecomeChannelsWithStreamRefsInsteadOfUrls() {
        val channels = parser.liveChannels(read(XtreamFixtures.LIVE_STREAMS), extension = "ts")
        assertEquals(listOf("xtream:3:101", "xtream:3:102", "xtream:3:103", "xtream:3:104", "xtream:3:107"), channels.map { it.id })
        val tf1 = channels[0]
        assertEquals("TF1 HD", tf1.name)
        assertEquals("xtream:3:category:live:1", tf1.categoryId)
        assertEquals("xtream://3/live/101.ts", tf1.streamUrl)
        assertEquals("http://img.example/tf1.png", tf1.logoUrl)
        assertEquals("tf1.fr", tf1.tvgId)
        assertEquals(3L, tf1.sourceId)
        // Null category_id falls back to category_ids; empty logo and epg become null.
        assertEquals("xtream:3:category:live:7", channels[1].categoryId)
        assertNull(channels[1].logoUrl)
        assertNull(channels[1].tvgId)
        assertEquals("Orpheline", channels[2].name)
        assertEquals("xtream:3:category:live:none", channels[3].categoryId)
        assertEquals("Chaîne 107", channels[4].name)
        assertNull(channels[4].logoUrl) // not http(s)
        assertTrue(channels.all { XtreamStreamRef.isRef(it.streamUrl) && !it.streamUrl.contains("u-test") && !it.streamUrl.contains("p-test") })
        assertTrue(parser.liveChannels(read(XtreamFixtures.EMPTY_OBJECT_LIST), "ts").isEmpty())
    }

    @Test fun orphanChannelsJoinOneUncategorisedCategory() {
        val categories = parser.categories(read(XtreamFixtures.LIVE_CATEGORIES), XtreamIds.Kind.LIVE)
        val channels = parser.liveChannels(read(XtreamFixtures.LIVE_STREAMS), "m3u8")
        val catalog = attachToCategories(categories, channels, sourceId = 3)
        assertEquals(listOf("Généralistes", "Sport", "Catégorie 9", M3uParser.DEFAULT_GROUP), catalog.categories.map { it.name })
        val fallback = catalog.categories.last().id
        assertEquals(fallback, catalog.channels[2].categoryId) // category 42 does not exist
        assertEquals(fallback, catalog.channels[3].categoryId)
        assertEquals("xtream:3:category:live:1", catalog.channels[0].categoryId)
        assertEquals("xtream://3/live/101.m3u8", catalog.channels[0].streamUrl)
        // Nothing orphaned: no category added.
        val clean = attachToCategories(categories, channels.take(2), 3)
        assertEquals(3, clean.categories.size)
    }

    // --- films ---

    @Test fun moviesTolerateMissingAndMistypedFields() {
        val movies = parser.movies(read(XtreamFixtures.VOD_STREAMS))
        assertEquals(listOf("xtream:3:movie:5001", "xtream:3:movie:5002", "xtream:3:movie:5003"), movies.map { it.id })
        val voyage = movies[0]
        assertEquals("Le Voyage (2019)", voyage.title)
        assertEquals(5001L, voyage.streamId)
        assertEquals("xtream:3:category:movie:3", voyage.categoryId)
        assertEquals("http://img.example/voyage.jpg", voyage.posterUrl)
        assertEquals(7.5, voyage.rating!!, 0.0)
        assertEquals(2019, voyage.year) // from the title
        assertEquals("mkv", voyage.containerExtension)
        assertEquals(1_699_999_000_000L, voyage.addedAt)
        assertNull(voyage.synopsis)
        val nuit = movies[1]
        assertNull(nuit.posterUrl)
        assertNull(nuit.rating) // "N/A"
        assertEquals(2021, nuit.year)
        assertEquals("mp4", nuit.containerExtension) // empty -> default
        assertEquals("xtream:3:category:movie:none", nuit.categoryId)
        assertEquals("Une nuit sans sommeil.", nuit.synopsis)
        assertNull(nuit.addedAt)
        val third = movies[2]
        assertNull(third.rating) // 0
        assertEquals("xtream:3:category:movie:5", third.categoryId)
        assertNull(third.year)
    }

    @Test fun movieDetailsCompleteTheListEntry() {
        val entry = parser.movies(read(XtreamFixtures.VOD_STREAMS))[0]
        val movie = parser.movieDetails(read(XtreamFixtures.VOD_INFO), entry)
        assertEquals(entry.id, movie.id)
        assertEquals("Le Voyage (2019)", movie.title) // movie_data.name wins over info.name
        assertEquals("Deux amis traversent un pays.", movie.synopsis)
        assertEquals(7080, movie.durationSeconds)
        assertEquals(2019, movie.year)
        assertEquals(7.6, movie.rating!!, 0.0)
        assertEquals("http://img.example/voyage.jpg", movie.posterUrl)
        assertEquals("mkv", movie.containerExtension)
        assertEquals("xtream:3:category:movie:3", movie.categoryId)
        // Unknown id: the entry is kept untouched.
        assertEquals(entry, parser.movieDetails(read(XtreamFixtures.VOD_INFO_UNKNOWN), entry))
        // Duration fallbacks: text, then run time in minutes.
        val textOnly = """{"info":{"duration":"01:30:00","release_date":"2018-02-03"},"movie_data":{"stream_id":5001}}"""
        val fromText = parser.movieDetails(read(textOnly), entry.copy(year = null))
        assertEquals(5400, fromText.durationSeconds)
        assertEquals(2018, fromText.year)
        val minutesOnly = """{"info":{"episode_run_time":"95"},"movie_data":{"stream_id":5001}}"""
        assertEquals(5700, parser.movieDetails(read(minutesOnly), entry).durationSeconds)
        assertThrows(XtreamException::class.java) { parser.movieDetails(read("[]"), entry) }
    }

    @Test fun thirtyThousandFilmsStreamThrough() {
        val count = 30_000
        val text = XtreamFixtures.manyMovies(count)
        val started = System.nanoTime()
        val movies = parser.movies(read(text))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(count, movies.size)
        assertEquals("xtream:3:movie:100001", movies[0].id)
        assertEquals("xtream:3:movie:${100_000 + count}", movies.last().id)
        assertEquals(2011, movies[0].year)
        assertEquals("xtream:3:category:movie:1", movies[0].categoryId)
        assertEquals(movies.size, movies.mapTo(HashSet()) { it.id }.size)
        assertTrue("Parsing de $count films en $elapsedMs ms", elapsedMs < 10_000)
    }

    // --- series ---

    @Test fun seriesListTolerateMissingFields() {
        val series = parser.series(read(XtreamFixtures.SERIES_LIST))
        assertEquals(listOf("xtream:3:series:77", "xtream:3:series:78"), series.map { it.id })
        val horizons = series[0]
        assertEquals("Horizons", horizons.title)
        assertEquals(77L, horizons.seriesId)
        assertEquals("xtream:3:category:series:12", horizons.categoryId)
        assertEquals("http://img.example/horizons.jpg", horizons.posterUrl)
        assertEquals(8.0, horizons.rating!!, 0.0)
        assertEquals(2019, horizons.year)
        assertEquals("Une expédition.", horizons.synopsis)
        assertEquals(2700, horizons.episodeRunTimeSeconds)
        assertEquals(1_700_000_500_000L, horizons.updatedAt)
        val ville = series[1]
        assertNull(ville.posterUrl); assertNull(ville.rating); assertNull(ville.year); assertNull(ville.synopsis)
        assertNull(ville.episodeRunTimeSeconds); assertNull(ville.updatedAt)
        assertEquals("xtream:3:category:series:none", ville.categoryId)
    }

    @Test fun seriesDetailsBuildSeasonsAndEpisodes() {
        val entry = parser.series(read(XtreamFixtures.SERIES_LIST))[0]
        val info = parser.seriesDetails(read(XtreamFixtures.SERIES_INFO), entry)
        assertEquals("http://img.example/horizons-big.jpg", info.series.posterUrl)
        assertEquals("Une expédition au long cours.", info.series.synopsis)
        assertEquals(8.2, info.series.rating!!, 0.0)
        assertEquals(entry.id, info.series.id)
        // Season 0 is declared without episodes and dropped; season 2 has no name.
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals(listOf("Saison 1", "Saison 2"), info.seasons.map { it.name })
        assertEquals(listOf(2, 1), info.seasons.map { it.episodeCount })
        assertEquals("http://img.example/h-s1.jpg", info.seasons[0].posterUrl)
        assertTrue(info.seasons.all { it.seriesId == entry.id })
        assertEquals(3, info.episodes.size)
        val first = info.episodes[0]
        assertEquals("xtream:3:episode:9001", first.id)
        assertEquals(entry.id, first.seriesId)
        assertEquals(1, first.season); assertEquals(1, first.number)
        assertEquals("Horizons - S01E01 - Départ", first.title)
        assertEquals(9001L, first.episodeId)
        assertEquals("mkv", first.containerExtension)
        assertEquals(2700, first.durationSeconds)
        assertEquals("http://img.example/h-101.jpg", first.thumbnailUrl)
        assertEquals("Le départ.", first.synopsis)
        assertEquals(3L, first.sourceId)
        val second = info.episodes[1]
        assertEquals("Épisode 2", second.title)
        assertEquals(2850, second.durationSeconds) // "00:47:30" since duration_secs is 0
        assertNull(second.thumbnailUrl)
        val third = info.episodes[2]
        assertEquals(2, third.season)
        assertEquals("mp4", third.containerExtension)
        assertNull(third.durationSeconds)
    }

    @Test fun seriesDetailsAcceptArraysOfArraysAndDeriveSeasons() {
        val entry = Series("xtream:3:series:78", "La ville bleue", sourceId = 3, seriesId = 78)
        val info = parser.seriesDetails(read(XtreamFixtures.SERIES_INFO_ARRAYS), entry)
        assertEquals(listOf(3, 4), info.seasons.map { it.number })
        assertEquals(listOf("Saison 3", "Saison 4"), info.seasons.map { it.name })
        assertEquals(listOf(2, 1), info.seasons.map { it.episodeCount })
        assertEquals(listOf(9101L, 9102L, 9103L), info.episodes.map { it.episodeId })
        assertEquals("La ville bleue", info.series.title)
        // Nothing at all: the entry survives, no seasons.
        val empty = parser.seriesDetails(read("""{"seasons":[],"info":[],"episodes":[]}"""), entry)
        assertEquals(entry, empty.series)
        assertTrue(empty.seasons.isEmpty() && empty.episodes.isEmpty())
    }

    // --- URLs ---

    @Test fun serverAddressesAreNormalised() {
        assertEquals("http://portal.example:8080", normalizeXtreamServer("portal.example:8080"))
        assertEquals("http://portal.example:8080", normalizeXtreamServer("  http://portal.example:8080/  "))
        assertEquals("https://portal.example", normalizeXtreamServer("https://portal.example/player_api.php?username=a&password=b"))
        assertEquals("http://portal.example:8080", normalizeXtreamServer("http://portal.example:8080/get.php"))
        assertEquals("http://portal.example", normalizeXtreamServer("http://portal.example/c/"))
        assertEquals("http://portal.example/sub", normalizeXtreamServer("portal.example/sub/"))
        assertEquals("", normalizeXtreamServer("   "))
    }

    @Test fun playbackUrlsAreBuiltAtTheLastMomentFromRefs() {
        val live = XtreamStreamRef.parse("xtream://3/live/101.ts")!!
        assertEquals(XtreamStreamRef(3, XtreamStreamRef.Kind.LIVE, 101, "ts"), live)
        assertEquals("http://portal.example:8080/live/u-test/p-test/101.ts", live.resolve(account))
        assertEquals("xtream://3/live/101.ts", live.format())
        val movie = Movie("xtream:3:movie:5001", "Le Voyage", sourceId = 3, streamId = 5001, containerExtension = "mkv")
        assertEquals("http://portal.example:8080/movie/u-test/p-test/5001.mkv", movie.streamRef().resolve(account))
        assertEquals("xtream://3/movie/5001.mkv", movie.streamRef().format())
        val episode = Episode("xtream:3:episode:9001", movie.id, 1, 1, "Départ", sourceId = 3, episodeId = 9001, containerExtension = "mp4")
        assertEquals("http://portal.example:8080/series/u-test/p-test/9001.mp4", episode.streamRef().resolve(account))
        assertEquals(XtreamStreamRef(3, XtreamStreamRef.Kind.EPISODE, 9001, "mp4"), XtreamStreamRef.parse(episode.streamRef().format()))
        assertNull(XtreamStreamRef.parse("http://portal.example/live/u/p/1.ts"))
        assertNull(XtreamStreamRef.parse("xtream://3/other/1.ts"))
        assertFalse(XtreamStreamRef.isRef("https://x"))
    }

    @Test fun credentialsAreUrlEncoded() {
        val odd = XtreamAccount("http://portal.example", "jean dupont@x", "p&w=/é")
        assertEquals("http://portal.example/player_api.php?username=jean+dupont%40x&password=p%26w%3D%2F%C3%A9&action=get_vod_info&vod_id=5", odd.apiUrl("get_vod_info", "vod_id" to "5"))
        assertEquals("http://portal.example/player_api.php?username=jean+dupont%40x&password=p%26w%3D%2F%C3%A9", odd.apiUrl())
        assertEquals("http://portal.example/live/jean%20dupont%40x/p%26w%3D%2F%C3%A9/1.ts", XtreamStreamRef(1, XtreamStreamRef.Kind.LIVE, 1, "ts").resolve(odd))
    }

    // --- errors ---

    @Test fun errorMessagesNeverContainTheAccount() {
        val secrets = listOf("portal.example", "u-test", "p-test")
        val errors = listOf(
            XtreamException(XtreamParser.REFUSED),
            JsonSyntaxException("Caractère inattendu."),
            HttpStatusException(401), HttpStatusException(403), HttpStatusException(404), HttpStatusException(429), HttpStatusException(503), HttpStatusException(418),
            UnknownHostException("portal.example"),
            SocketTimeoutException("connect timed out to portal.example"),
            MalformedURLException("no protocol: portal.example/player_api.php?username=u-test&password=p-test"),
            IllegalArgumentException("http://portal.example/u-test/p-test"),
            PlaylistException("Trop de redirections."),
            java.io.IOException("unexpected end of stream on http://portal.example/u-test/p-test"),
            RuntimeException("boom u-test")
        )
        for (error in errors) {
            val message = describeXtreamError(error)
            assertTrue(message, message.isNotBlank())
            for (secret in secrets) assertFalse("$message contient $secret", message.contains(secret))
        }
        assertEquals(XtreamParser.REFUSED, describeXtreamError(XtreamException(XtreamParser.REFUSED)))
        assertEquals(XtreamParser.UNEXPECTED, describeXtreamError(JsonSyntaxException("x")))
        assertEquals("Identifiants refusés par le serveur (HTTP 403).", describeXtreamError(HttpStatusException(403)))
        assertEquals("API Xtream introuvable à cette adresse (HTTP 404). Vérifiez le serveur et le port.", describeXtreamError(HttpStatusException(404)))
        assertEquals("Le serveur a répondu HTTP 418.", describeXtreamError(HttpStatusException(418)))
        assertEquals("Hôte introuvable. Vérifiez l'adresse et la connexion.", describeXtreamError(UnknownHostException("portal.example")))
        assertEquals("Adresse invalide.", describeXtreamError(MalformedURLException("no protocol")))
        assertEquals("Lecture du catalogue impossible.", describeXtreamError(java.io.IOException("x")))
        // Playlists keep their own wording for the same HTTP failure.
        assertEquals("Le serveur a répondu HTTP 404.", describeImportError(HttpStatusException(404)))
    }

    @Test fun helpersParseRatingsYearsAndDurations() {
        assertEquals(7.5, XtreamParser.parseRating("7.5")!!, 0.0)
        assertEquals(7.5, XtreamParser.parseRating(" 7,5 ")!!, 0.0)
        assertNull(XtreamParser.parseRating("N/A")); assertNull(XtreamParser.parseRating("0")); assertNull(XtreamParser.parseRating("75")); assertNull(XtreamParser.parseRating(null))
        assertEquals(2019, XtreamParser.parseYear("2019-06-14")); assertEquals(1999, XtreamParser.parseYear("1999")); assertNull(XtreamParser.parseYear("12345")); assertNull(XtreamParser.parseYear(""))
        assertEquals(2019, XtreamParser.parseYearInTitle("Le Voyage (2019)")); assertNull(XtreamParser.parseYearInTitle("2019 Odyssée"))
        assertEquals(7080, XtreamParser.parseDuration("01:58:00")); assertEquals(2850, XtreamParser.parseDuration("47:30")); assertEquals(5400, XtreamParser.parseDuration("90"))
        assertNull(XtreamParser.parseDuration("0")); assertNull(XtreamParser.parseDuration("")); assertNull(XtreamParser.parseDuration("1h30")); assertNull(XtreamParser.parseDuration(null))
    }
}
