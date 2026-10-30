package com.miptv

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HttpXtreamClient] against a local portal on a plain [ServerSocket] (the JDK's `com.sun.net.httpserver` is not on
 * the Android unit-test classpath): no network, recorded responses, credentials checked as query parameters.
 */
class XtreamClientTest {
    private lateinit var server: ServerSocket
    private lateinit var acceptor: Thread
    private val requests: MutableList<Map<String, String>> = Collections.synchronizedList(ArrayList())
    private val client = HttpXtreamClient()

    @Before fun start() {
        server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        acceptor = thread(isDaemon = true) {
            try { while (true) server.accept().use { serve(it) } } catch (_: Exception) { }
        }
    }

    @After fun stop() {
        server.close()
        acceptor.join(1_000)
    }

    private fun base(path: String = "") = "http://127.0.0.1:${server.localPort}$path"
    private fun account(path: String = "") = XtreamAccount(base(path), "u-test", "p-test")

    /** One HTTP/1.0-style exchange: read the request line and headers, answer, close. */
    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        while (true) { val header = input.readLine(); if (header.isNullOrEmpty()) break }
        val target = requestLine.split(' ').getOrElse(1) { "/" }
        val path = target.substringBefore('?')
        val rawQuery = target.substringAfter('?', "")
        val output = socket.getOutputStream()
        when (path) {
            "/player_api.php" -> handle(rawQuery, output)
            "/redirect/player_api.php" -> respond(output, 302, "", "Location: /player_api.php?$rawQuery")
            "/html/player_api.php" -> respond(output, 200, XtreamFixtures.HTML_LOGIN_PAGE)
            "/down/player_api.php" -> respond(output, 503, "")
            else -> respond(output, 404, "")
        }
    }

    private fun handle(rawQuery: String, output: OutputStream) {
        val params = rawQuery.split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        requests += params
        if (params["username"] != "u-test" || params["password"] != "p-test") return respond(output, 200, XtreamFixtures.ACCOUNT_REFUSED)
        val body = when (params["action"]) {
            null -> XtreamFixtures.ACCOUNT_ACTIVE
            "get_live_categories" -> XtreamFixtures.LIVE_CATEGORIES
            "get_live_streams" -> XtreamFixtures.LIVE_STREAMS
            "get_vod_categories" -> XtreamFixtures.KEYED_CATEGORIES
            "get_vod_streams" -> XtreamFixtures.VOD_STREAMS
            "get_vod_info" -> if (params["vod_id"] == "5001") XtreamFixtures.VOD_INFO else XtreamFixtures.VOD_INFO_UNKNOWN
            "get_series_categories" -> XtreamFixtures.EMPTY_OBJECT_LIST
            "get_series" -> XtreamFixtures.SERIES_LIST
            "get_series_info" -> if (params["series_id"] == "77") XtreamFixtures.SERIES_INFO else XtreamFixtures.SERIES_INFO_ARRAYS
            "get_short_epg" -> if (params["stream_id"] == "101") XtreamFixtures.SHORT_EPG else XtreamFixtures.SHORT_EPG_EMPTY
            else -> return respond(output, 404, "")
        }
        respond(output, 200, body)
    }

    private fun respond(output: OutputStream, code: Int, body: String, vararg headers: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 $code ${if (code == 200) "OK" else "Status"}\r\n")
            .append("Content-Type: application/json; charset=utf-8\r\n")
            .append("Content-Length: ${bytes.size}\r\n")
            .append("Connection: close\r\n")
        for (header in headers) head.append(header).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    @Test fun accountCheckSendsCredentialsAsQueryParametersOnly() = runBlocking {
        val info = client.account(account())
        assertEquals("Active", info.status)
        assertEquals(1, requests.size)
        assertEquals(mapOf("username" to "u-test", "password" to "p-test"), requests[0])
    }

    @Test fun refusedCredentialsSurfaceAsXtreamException() = runBlocking {
        val error = assertThrows(XtreamException::class.java) { runBlocking { client.account(XtreamAccount(base(), "u-test", "wrong")) } }
        assertEquals(XtreamParser.REFUSED, describeXtreamError(error))
        assertFalse(error.message!!.contains("wrong"))
    }

    @Test fun liveCatalogCombinesCategoriesAndStreams() = runBlocking {
        val catalog = client.liveCatalog(account(), sourceId = 5, extension = "ts")
        assertEquals(listOf("get_live_categories", "get_live_streams"), requests.map { it["action"] })
        assertEquals(listOf("Généralistes", "Sport", "Catégorie 9", M3uParser.DEFAULT_GROUP), catalog.categories.map { it.name })
        assertEquals(5, catalog.channels.size)
        assertEquals("xtream:5:101", catalog.channels[0].id)
        assertEquals("xtream://5/live/101.ts", catalog.channels[0].streamUrl)
        assertEquals(catalog.categories.last().id, catalog.channels[3].categoryId)
    }

    @Test fun vodAndSeriesEndpointsAreParsed() = runBlocking {
        assertEquals(listOf("Action", "Comédie"), client.movieCategories(account(), 5).map { it.name })
        val movies = client.movies(account(), 5)
        assertEquals(3, movies.size)
        val details = client.movieDetails(account(), movies[0])
        assertEquals("5001", requests.last()["vod_id"])
        assertEquals(7080, details.durationSeconds)
        assertEquals(movies[1], client.movieDetails(account(), movies[1]))
        assertTrue(client.seriesCategories(account(), 5).isEmpty())
        val series = client.series(account(), 5)
        assertEquals(2, series.size)
        val horizons = client.seriesDetails(account(), series[0])
        assertEquals("77", requests.last()["series_id"])
        assertEquals(listOf(1, 2), horizons.seasons.map { it.number })
        assertEquals(3, horizons.episodes.size)
        val ville = client.seriesDetails(account(), series[1])
        assertEquals(listOf(3, 4), ville.seasons.map { it.number })
        assertTrue(requests.all { it["username"] == "u-test" && it["password"] == "p-test" })
    }

    @Test fun shortEpgAsksForOneStreamWithALimit() = runBlocking {
        val programmes = client.shortEpg(account(), sourceId = 5, streamId = 101, channelKey = "tf1.fr", limit = 4)
        assertEquals(4, programmes.size)
        assertEquals("Journal de 20h", programmes[0].title)
        assertEquals("tf1.fr", programmes[0].channelKey)
        assertEquals(5L, programmes[0].sourceId)
        val request = requests.single()
        assertEquals("get_short_epg", request["action"])
        assertEquals("101", request["stream_id"])
        assertEquals("4", request["limit"])
        assertTrue(client.shortEpg(account(), 5, 102, "f2.fr").isEmpty())
    }

    @Test fun redirectsAreFollowedWithTheQueryKept() = runBlocking {
        val info = client.account(account("/redirect"))
        assertEquals("Active", info.status)
        assertEquals(mapOf("username" to "u-test", "password" to "p-test"), requests.single())
    }

    @Test fun htmlAndServerErrorsGetFrenchMessagesWithoutTheAccount() = runBlocking {
        val html = assertThrows(JsonSyntaxException::class.java) { runBlocking { client.account(account("/html")) } }
        assertEquals(XtreamParser.UNEXPECTED, describeXtreamError(html))
        val down = assertThrows(HttpStatusException::class.java) { runBlocking { client.movies(account("/down"), 5) } }
        assertEquals("Le serveur du fournisseur est en difficulté (HTTP 503).", describeXtreamError(down))
        val missing = assertThrows(HttpStatusException::class.java) { runBlocking { client.account(account("/nowhere")) } }
        assertEquals(404, missing.code)
        val unreachable = assertThrows(Exception::class.java) { runBlocking { client.account(XtreamAccount("http://127.0.0.1:1", "u-test", "p-test")) } }
        val message = describeXtreamError(unreachable)
        assertFalse(message, message.contains("127.0.0.1") || message.contains("u-test") || message.contains("p-test"))
        val scheme = assertThrows(PlaylistException::class.java) { runBlocking { client.account(XtreamAccount("ftp://portal.example", "u-test", "p-test")) } }
        assertEquals("Seules les adresses http:// et https:// sont acceptées.", describeXtreamError(scheme))
        assertNull(describeXtreamError(IllegalStateException()).takeIf { it.contains("u-test") })
    }
}
