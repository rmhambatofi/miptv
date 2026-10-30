package com.miptv

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvParserTest {
    private fun bytes(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    private fun parse(parser: XmltvParser, input: ByteArray = EpgFixtures.XMLTV.toByteArray()): Pair<XmltvParser.Result, List<Programme>> {
        val all = ArrayList<Programme>()
        val result = parser.parse(ByteArrayInputStream(input)) { all += it }
        return result to all
    }

    @Test fun channelsProgrammesOffsetsLanguagesAndEntities() {
        val (result, programmes) = parse(XmltvParser(sourceId = 4))
        assertEquals(listOf("tf1.fr", "f2.fr"), result.channels.map { it.id })
        assertEquals(listOf("TF1", "TF1 HD"), result.channels[0].names)
        assertEquals("http://img.example/tf1.png", result.channels[0].iconUrl)
        assertNull(result.channels[1].iconUrl)
        assertEquals(6, result.programmeCount)
        assertEquals(listOf("Journal de 20h", "Météo", "Film : Le Voyage", "Sans décalage", "Série", "Chaîne absente"), programmes.map { it.title })
        assertTrue(programmes.all { it.sourceId == 4L })

        val journal = programmes[0]
        assertEquals("tf1.fr", journal.channelKey)
        assertEquals(EpgFixtures.JOURNAL_START, journal.startMs)
        assertEquals(EpgFixtures.JOURNAL_STOP, journal.stopMs)
        assertEquals("Le journal, la météo & les sports.", journal.description)
        assertEquals("Information", journal.category)
        assertNull(journal.episode)

        // No stop: closed by the next programme of the channel.
        val meteo = programmes[1]
        assertEquals(EpgFixtures.JOURNAL_STOP, meteo.startMs)
        assertEquals(EpgFixtures.FILM_START, meteo.stopMs)
        assertNull(meteo.description)

        val film = programmes[2]
        assertEquals(EpgFixtures.FILM_STOP, film.stopMs)
        assertEquals("Deux amis <traversent> un pays.", film.description)
        assertEquals("Film", film.category)

        // Without offset the time is UTC; xmltv_ns is zero-based with optional totals.
        val utc = programmes[3]
        assertEquals(EpgFixtures.SANS_DECALAGE_START, utc.startMs)
        assertEquals(EpgFixtures.SANS_DECALAGE_STOP, utc.stopMs)
        assertEquals("S3 E5", utc.episode)

        // Minutes-only start with a colon offset; onscreen numbering kept verbatim.
        val serie = programmes[4]
        assertEquals(EpgFixtures.JOURNAL_START, serie.startMs)
        assertEquals(EpgFixtures.SERIE_STOP, serie.stopMs)
        assertEquals("S03E05", serie.episode)

        assertEquals("inconnue.fr", programmes[5].channelKey)
    }

    @Test fun windowKeepsOverlappingProgrammesOnly() {
        val from = 1_793_302_560_000L // 20:36 +0100: the news (ends 20:35) is out
        val to = 1_793_314_800_000L   // 30 October 00:00 +0100: the next morning is out
        val (result, programmes) = parse(XmltvParser(sourceId = 1, fromMs = from, toMs = to))
        assertEquals(listOf("Météo", "Film : Le Voyage", "Sans décalage", "Série"), programmes.map { it.title })
        assertEquals(4, result.programmeCount)
        assertEquals(2, result.channels.size)
    }

    @Test fun channelFilterAppliesToProgrammesAndChannels() {
        val (result, programmes) = parse(XmltvParser(sourceId = 1, channelFilter = { it == "tf1.fr" }))
        assertEquals(listOf("tf1.fr"), result.channels.map { it.id })
        assertEquals(3, programmes.size)
        assertTrue(programmes.all { it.channelKey == "tf1.fr" })
    }

    @Test fun programmesAreDeliveredInBatches() {
        val batches = ArrayList<List<Programme>>()
        val result = XmltvParser(sourceId = 1, batchSize = 4).parse(bytes(EpgFixtures.XMLTV)) { batches += it }
        assertEquals(listOf(4, 2), batches.map { it.size })
        assertEquals(6, result.programmeCount)
    }

    @Test fun gzipIsDetectedByItsMagicBytes() {
        val (_, plain) = parse(XmltvParser(sourceId = 2))
        val (result, zipped) = parse(XmltvParser(sourceId = 2), EpgFixtures.gzip(EpgFixtures.XMLTV))
        assertEquals(plain, zipped)
        assertEquals(2, result.channels.size)
    }

    @Test fun nonXmltvAndTruncatedDocumentsFailWithFrenchMessages() {
        val html = assertThrows(EpgException::class.java) { parse(XmltvParser(1), XtreamFixtures.HTML_LOGIN_PAGE.toByteArray()) }
        assertEquals(XmltvParser.UNEXPECTED, html.message)
        val empty = assertThrows(EpgException::class.java) { parse(XmltvParser(1), ByteArray(0)) }
        assertEquals(XmltvParser.UNEXPECTED, empty.message)
        val json = assertThrows(EpgException::class.java) { parse(XmltvParser(1), XtreamFixtures.LIVE_STREAMS.toByteArray()) }
        assertEquals(XmltvParser.UNEXPECTED, json.message)
        val truncated = assertThrows(EpgException::class.java) { parse(XmltvParser(1), EpgFixtures.XMLTV.substring(0, EpgFixtures.XMLTV.length / 2).toByteArray()) }
        assertEquals(XmltvParser.TRUNCATED, truncated.message)
        for (error in listOf(html, truncated, HttpStatusException(404), java.net.UnknownHostException("epg.example"), java.io.IOException("http://epg.example/guide.xml"), RuntimeException("x"))) {
            val message = describeEpgError(error)
            assertTrue(message, message.isNotBlank())
            assertFalse(message, message.contains("epg.example"))
        }
        assertEquals("Le serveur du guide a répondu HTTP 404.", describeEpgError(HttpStatusException(404)))
        assertEquals("Lecture du guide impossible.", describeEpgError(java.io.IOException("x")))
        assertEquals("Hôte introuvable. Vérifiez l'adresse et la connexion.", describeEpgError(java.net.UnknownHostException("x")))
    }

    @Test fun timeParsingToleratesPartialFieldsAndOffsets() {
        assertEquals(EpgFixtures.JOURNAL_START, XmltvParser.parseXmltvTime("20261029200000 +0100"))
        assertEquals(EpgFixtures.JOURNAL_START, XmltvParser.parseXmltvTime("20261029190000"))
        assertEquals(EpgFixtures.JOURNAL_START, XmltvParser.parseXmltvTime("20261029190000 Z"))
        assertEquals(EpgFixtures.JOURNAL_START, XmltvParser.parseXmltvTime(" 202610292000 +01:00 "))
        assertEquals(EpgFixtures.JOURNAL_START, XmltvParser.parseXmltvTime("20261029140000 -0500"))
        assertEquals(1_793_232_000_000L, XmltvParser.parseXmltvTime("20261029")) // midnight UTC
        assertEquals(EpgFixtures.JOURNAL_START - 19 * 3_600_000L, XmltvParser.parseXmltvTime("20261029000000 +9900")) // bad offset: UTC
        assertNull(XmltvParser.parseXmltvTime("2026102"))
        assertNull(XmltvParser.parseXmltvTime("20261332000000"))
        assertNull(XmltvParser.parseXmltvTime(null))
        assertNull(XmltvParser.parseXmltvTime("hier soir"))
    }

    @Test fun episodeNumbersAndBase64Helpers() {
        assertEquals("S1 E2", XmltvParser.formatXmltvNs("0.1."))
        assertEquals("S1 E2", XmltvParser.formatXmltvNs("0 . 1 / 12 . 0/2"))
        assertEquals("E3", XmltvParser.formatXmltvNs(".2."))
        assertEquals("S2", XmltvParser.formatXmltvNs("1.."))
        assertNull(XmltvParser.formatXmltvNs(".."))
        assertNull(XmltvParser.formatXmltvNs("abc"))
        assertEquals("Journal de 20h", XmltvParser.decodeBase64Text("Sm91cm5hbCBkZSAyMGg="))
        assertEquals("Le journal, la météo & les sports.", XmltvParser.decodeBase64Text("TGUgam91cm5hbCwgbGEgbcOpdMOpbyAmIGxlcyBzcG9ydHMu"))
        assertEquals("Nuit blanche", XmltvParser.decodeBase64Text("Nuit blanche"))
        assertEquals("Météo", XmltvParser.decodeBase64Text("Météo"))
        assertEquals("", XmltvParser.decodeBase64Text(null))
        assertEquals("", XmltvParser.decodeBase64Text("  "))
        // Four ASCII letters are valid base64 but decode to control bytes: kept in clear.
        assertEquals("News", XmltvParser.decodeBase64Text("News"))
    }

    @Test fun programmeProgressAndLiveness() {
        val programme = Programme("tf1.fr", 1_000L, 3_000L, "Journal")
        assertEquals(2_000L, programme.durationMs)
        assertFalse(programme.isLive(999L)); assertTrue(programme.isLive(1_000L)); assertTrue(programme.isLive(2_999L)); assertFalse(programme.isLive(3_000L))
        assertEquals(0f, programme.progress(500L), 0f); assertEquals(0.5f, programme.progress(2_000L), 0.001f); assertEquals(1f, programme.progress(9_000L), 0f)
        assertEquals(1f, Programme("x", 5L, 5L, "vide").progress(5L), 0f)
    }

    @Test fun aProviderSizedGuideStreamsThroughAWindowAndFilter() {
        val text = EpgFixtures.manyProgrammes(channels = 200, perChannel = 500) // 100 000 programmes
        val from = EpgFixtures.JOURNAL_START + 100 * 3_600_000L
        val to = from + 72 * 3_600_000L
        var batches = 0
        var largest = 0
        val favourites = (1..200 step 4).mapTo(HashSet()) { "c$it.test" }
        val started = System.nanoTime()
        val result = XmltvParser(sourceId = 9, fromMs = from, toMs = to, channelFilter = { it in favourites }).parse(bytes(text)) { batch ->
            batches++
            largest = maxOf(largest, batch.size)
            assertTrue(batch.all { it.channelKey in favourites && it.stopMs > from && it.startMs < to })
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("XMLTV de 100 000 programmes filtré en flux : ${result.programmeCount} gardés en $elapsedMs ms, $batches lots")
        assertEquals(50 * 72, result.programmeCount)
        assertEquals(50, result.channels.size)
        assertTrue(largest <= 500)
        assertTrue("$elapsedMs ms", elapsedMs < 30_000)
    }
}
