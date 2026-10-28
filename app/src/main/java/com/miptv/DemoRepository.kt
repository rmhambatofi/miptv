package com.miptv

/** Public Blender demonstration films, not live television or commercial channels. */
class DemoRepository : ChannelRepository {
    override val catalog = Catalog(
        listOf(Category("animation", "Animation"), Category("cinema", "Cinéma"), Category("empty", "Découverte")),
        listOf(
            Channel("bbb", "Big Buck Bunny", "animation", "https://download.blender.org/peach/trailer/trailer_480p.mov"),
            Channel("sintel", "Sintel", "cinema", "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4")
        )
    )
    companion object {
        val movies = listOf(Movie("m1", "Les lumières du Nord", "Aventure · Fiche fictive"), Movie("m2", "L’océan intérieur", "Documentaire · Fiche fictive"))
        val series = listOf(Series("s1", "Horizons", "Exploration · Fiche fictive"), Series("s2", "La ville bleue", "Drame · Fiche fictive"))
    }
}
