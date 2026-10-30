package com.miptv

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveOverlapsTest {
    private val h = 3_600_000L
    private val m = 60_000L
    private fun p(start: Long, stop: Long, title: String, key: String = "bein1.fr") = Programme(key, start, stop, title)

    @Test fun aScheduleListedTwiceOneHourApartKeepsTheEarlierCopy() {
        // As seen on a provider's beIN Sports 1: every programme again one hour later.
        val first = listOf(p(3 * h, 5 * h + 45 * m, "Portland / Memphis"), p(5 * h + 45 * m, 6 * h, "Ça se passe"), p(6 * h, 8 * h, "Man City / Brighton"))
        val late = first.map { it.copy(startMs = it.startMs + h, stopMs = it.stopMs + h) }
        assertEquals(first, resolveOverlaps((first + late).shuffled(java.util.Random(7))))
    }

    @Test fun aShortOverrunCutsThePreviousProgramme() {
        val out = resolveOverlaps(listOf(p(20 * h, 20 * h + 35 * m, "Avant"), p(20 * h + 30 * m, 21 * h + 30 * m, "Capital")))
        assertEquals(listOf(p(20 * h, 20 * h + 30 * m, "Avant"), p(20 * h + 30 * m, 21 * h + 30 * m, "Capital")), out)
    }

    @Test fun anOverlapOfHalfTheProgrammeOrMoreDropsIt() {
        val film = p(20 * h, 22 * h, "Film")
        assertEquals(listOf(film), resolveOverlaps(listOf(film, p(21 * h, 23 * h, "Magazine"))))
        assertEquals(listOf(film), resolveOverlaps(listOf(film, p(21 * h, 21 * h + 30 * m, "Journal"))))
    }

    @Test fun theSameTitleOrTheSameStartIsADuplicateHoweverShortTheOverlap() {
        val match = p(20 * h, 22 * h, "Liverpool / Chelsea")
        assertEquals(listOf(match), resolveOverlaps(listOf(match, p(21 * h + 50 * m, 23 * h + 50 * m, "LIVERPOOL / CHELSEA"))))
        val short = p(20 * h, 20 * h + 5 * m, "Flash")
        assertEquals(listOf(short), resolveOverlaps(listOf(short, p(20 * h, 22 * h, "Film"))))
    }

    @Test fun channelsAreResolvedApartAndCleanGuidesAreUntouched() {
        val tf1 = listOf(p(20 * h, 21 * h, "Journal", "tf1.fr"), p(21 * h, 23 * h, "Film", "tf1.fr"))
        val f2 = listOf(p(20 * h + 30 * m, 22 * h, "Série", "f2.fr"))
        assertEquals(tf1 + f2, resolveOverlaps(listOf(tf1[1], f2[0], tf1[0])))
        assertEquals(emptyList<Programme>(), resolveOverlaps(emptyList()))
    }
}
