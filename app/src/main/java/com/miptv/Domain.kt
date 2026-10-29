package com.miptv

import androidx.paging.PagingData
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.Flow

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
    /** Optional `#EXTVLCOPT` HTTP hints honoured by the player. */
    val userAgent: String? = null,
    val referer: String? = null
)

data class Movie(val id: String, val title: String, val description: String)
data class Series(val id: String, val title: String, val description: String)

/** What a channel list shows: a category or pseudo-category, narrowed by a name filter. */
data class ChannelQuery(val categoryId: String = Category.ALL.id, val text: String = "") {
    /** Category membership only; [favoriteIds] serves the favourites pseudo-category. */
    fun inCategory(channel: Channel, favoriteIds: Set<String>): Boolean = when (categoryId) {
        Category.ALL.id -> true
        Category.FAVORITES.id -> channel.id in favoriteIds
        else -> channel.categoryId == categoryId
    }
    fun matches(channel: Channel, favoriteIds: Set<String>): Boolean =
        inCategory(channel, favoriteIds) && searchKey(channel.name).contains(searchKey(text))
}

/** Lower-case, accent-free form used for name filtering, so "chaine" finds "CHAÎNE". Stored per channel. */
fun searchKey(text: String): String {
    val trimmed = text.trim()
    // Most names are ASCII: skip the Unicode decomposition, costly over tens of thousands of names.
    if (trimmed.all { it.code < 0x80 }) return trimmed.lowercase(Locale.ROOT)
    val decomposed = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
    val folded = StringBuilder(decomposed.length)
    for (c in decomposed) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) folded.append(c)
    return folded.toString().lowercase(Locale.ROOT)
}

enum class SourceKind { URL, FILE }

/** A configured playlist. [location] is an `http(s)` URL or a persisted `content://` URI; it is never logged. */
data class Source(
    val id: Long,
    val name: String,
    val location: String,
    val kind: SourceKind,
    val lastRefreshed: Long? = null,
    val channelCount: Int = 0,
    val lastError: String? = null
)

sealed interface ImportResult {
    data class Success(val source: Source) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * Channels are read through paged, per-query lists so a playlist of tens of thousands of entries never sits
 * in memory. Favourites are a separate id set: starring a channel does not reload the channel lists,
 * except the favourites pseudo-category.
 */
interface ChannelRepository {
    /** Imported categories, without the pseudo-categories. */
    val categories: Flow<List<Category>>
    val favoriteIds: Flow<Set<String>>
    val sources: Flow<List<Source>>
    fun channels(query: ChannelQuery): Flow<PagingData<Channel>>
    fun count(query: ChannelQuery): Flow<Int>
    suspend fun channel(id: String): Channel?
    suspend fun addSource(name: String, location: String, kind: SourceKind): ImportResult
    suspend fun refreshSource(id: Long): ImportResult
    suspend fun removeSource(id: Long)
    suspend fun setFavorite(channelId: String, favorite: Boolean)
}

/** Parsed playlist, before it is attached to a stored source. */
data class ParsedPlaylist(val categories: List<Category>, val channels: List<Channel>)

/** M3U import contract. [parse] on a string is kept for small inputs; [parseLines] streams. */
interface M3uAdapter {
    fun parse(playlist: String): ParsedPlaylist = parseLines(playlist.lineSequence())
    fun parseLines(lines: Sequence<String>): ParsedPlaylist
}

/** Extension point only: Xtream Codes stays outside Sprint 2. */
interface XtreamAdapter { fun adapt(categories: List<XtreamCategory>, streams: List<XtreamStream>): ParsedPlaylist }
data class XtreamCategory(val id: String, val name: String)
data class XtreamStream(val id: String, val name: String, val categoryId: String, val resolvedUrl: String)
