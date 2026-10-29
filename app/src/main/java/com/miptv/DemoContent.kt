package com.miptv

/** Fictional Movies/Series cards kept until a VOD provider (Xtream) exists. No playback. */
object DemoContent {
    val movies = listOf(Movie("m1", "Les lumières du Nord", synopsis = "Aventure · Fiche fictive"), Movie("m2", "L’océan intérieur", synopsis = "Documentaire · Fiche fictive"))
    val series = listOf(Series("s1", "Horizons", synopsis = "Exploration · Fiche fictive"), Series("s2", "La ville bleue", synopsis = "Drame · Fiche fictive"))
}
