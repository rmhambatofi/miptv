package com.miptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labels shown on sheets, rows and the Sources form. */
class FormatTest {
    @Test fun durationsAndResumeLabels() {
        assertEquals("59 min", formatDuration(59 * 60))
        assertEquals("1 h 00", formatDuration(3600))
        assertEquals("1 h 58", formatDuration(7080))
        assertEquals("0 min", formatDuration(30))
        assertEquals("Reprendre à 42 min", resumeLabel(2_520_000))
        assertEquals("Reprendre à 1 h 05", resumeLabel(3_900_000))
        assertEquals("Reprendre à 1 min", resumeLabel(5_000)) // never "0 min"
    }

    @Test fun movieSubtitleUsesWhatIsKnown() {
        val movie = Movie("m", "Film", year = 2019, durationSeconds = 7080, rating = 7.65)
        assertEquals("2019 · 1 h 58 · ★ 7,7", movie.movieSubtitle())
        assertEquals("2019", movie.copy(durationSeconds = null, rating = null).movieSubtitle())
        assertNull(Movie("m", "Film").movieSubtitle())
        assertEquals("Film", Movie("m", "Film").toPlayItem(1).let { assertNull(it.subtitle); it.title })
    }

    @Test fun episodeTitlesArePaddedAndNextFollowsSeriesOrder() {
        val series = Series("s", "Horizons")
        val e1 = Episode("e1", "s", 1, 9, "Neuf")
        val e2 = Episode("e2", "s", 1, 10, "Dix")
        val e3 = Episode("e3", "s", 2, 1, "Un")
        assertEquals("S01E09 · Neuf", e1.toPlayItem(series).title)
        assertEquals("Horizons", e1.toPlayItem(series).subtitle)
        assertEquals("S02E01 · Un", e3.toPlayItem(series, 500).let { assertEquals(500L, it.resumeMs); it.title })
        val sheet = SeriesSheet(series, SeriesInfo(series, emptyList(), listOf(e1, e2, e3)), season = 1)
        assertEquals(listOf("Neuf", "Dix"), sheet.episodes.map { it.title })
        assertEquals(e2, sheet.next(e1))
        assertEquals(e3, sheet.next(e2))
        assertNull(sheet.next(e3))
        assertNull(sheet.next(Episode("other", "s", 1, 1, "Ailleurs")))
        assertTrue(SeriesSheet(series).episodes.isEmpty())
    }

    @Test fun progressFinishesPastNinetyFivePercent() {
        assertFalse(PlaybackProgress("x", 94_000, 100_000, 0).finished)
        assertTrue(PlaybackProgress("x", 95_000, 100_000, 0).finished)
        assertFalse(PlaybackProgress("x", 5_000, 0, 0).finished)
    }

    @Test fun vodQueryMatchesCategoryFavouritesAndAccentFreeTitles() {
        val favorites = setOf("fav")
        assertTrue(VodQuery().matches("a", "Été indien", "c1", favorites))
        assertTrue(VodQuery(text = "ete").matches("a", "Été indien", "c1", favorites))
        assertFalse(VodQuery(text = "hiver").matches("a", "Été indien", "c1", favorites))
        assertTrue(VodQuery("c1").matches("a", "x", "c1", favorites))
        assertFalse(VodQuery("c2").matches("a", "x", "c1", favorites))
        assertTrue(VodQuery(Category.FAVORITES.id).matches("fav", "x", "c1", favorites))
        assertFalse(VodQuery(Category.FAVORITES.id).matches("a", "x", "c1", favorites))
    }

    @Test fun accountDescriptionsForTheConnectionTest() {
        val active = XtreamAccountInfo("Active", 1_798_761_600_000L, maxConnections = 2, activeConnections = 1, allowedOutputFormats = listOf("m3u8", "ts"))
        assertEquals("Compte active jusqu'au 01/01/2027 · 1/2 connexion(s) · formats m3u8, ts", describeAccount(active))
        assertEquals("Compte active sans date d'expiration", describeAccount(XtreamAccountInfo("Active", null)))
        val saturated = XtreamAccountInfo("Active", null, maxConnections = 1, activeConnections = 1)
        assertEquals("Compte active sans date d'expiration · 1/1 connexion(s) · Nombre maximal de connexions atteint (1/1).", describeAccount(saturated))
    }
}
