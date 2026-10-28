package com.miptv

data class Category(val id: String, val name: String) {
    companion object {
        /** Pseudo-categories shown before the imported groups. */
        val ALL = Category("all", "Toutes")
        val FAVORITES = Category("favorites", "Favoris")
    }
}

data class Channel(
    val id: String,
    val name: String,
    val categoryId: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val sourceId: Long = 0L,
    val favorite: Boolean = false,
    /** Optional `#EXTVLCOPT` HTTP hints honoured by the player. */
    val userAgent: String? = null,
    val referer: String? = null
)

data class Movie(val id: String, val title: String, val description: String)
data class Series(val id: String, val title: String, val description: String)

data class Catalog(val categories: List<Category>, val channels: List<Channel>) {
    val favorites: List<Channel> by lazy { channels.filter { it.favorite } }
    private val byCategory: Map<String, List<Channel>> by lazy { channels.groupBy { it.categoryId } }
    fun channelsIn(categoryId: String): List<Channel> = when (categoryId) {
        Category.ALL.id -> channels
        Category.FAVORITES.id -> favorites
        else -> byCategory[categoryId].orEmpty()
    }
    companion object { val EMPTY = Catalog(emptyList(), emptyList()) }
}

interface ChannelRepository { val catalog: Catalog }

/** Parsed playlist, before it is attached to a stored source. */
data class ParsedPlaylist(val categories: List<Category>, val channels: List<Channel>)

/** M3U import contract. [parse] on a string is kept for small inputs; [parseLines] streams. */
interface M3uAdapter {
    fun parse(playlist: String): ParsedPlaylist = parseLines(playlist.lineSequence())
    fun parseLines(lines: Sequence<String>): ParsedPlaylist
}

/** Extension point only: Xtream Codes stays outside Sprint 2. */
interface XtreamAdapter { fun adapt(categories: List<XtreamCategory>, streams: List<XtreamStream>): Catalog }
data class XtreamCategory(val id: String, val name: String)
data class XtreamStream(val id: String, val name: String, val categoryId: String, val resolvedUrl: String)
