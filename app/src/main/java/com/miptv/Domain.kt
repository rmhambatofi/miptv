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

/**
 * A film of an Xtream provider. [id] is `xtream:{sourceId}:movie:{streamId}`. No playable URL is kept: it would embed
 * the credentials. [streamRef] builds it when playback starts.
 */
data class Movie(
    val id: String,
    val title: String,
    val sourceId: Long = 0L,
    /** Provider `stream_id`. */
    val streamId: Long = 0L,
    val categoryId: String = "",
    val posterUrl: String? = null,
    /** Out of 10, when the provider gives one. */
    val rating: Double? = null,
    val year: Int? = null,
    val synopsis: String? = null,
    val durationSeconds: Int? = null,
    val containerExtension: String = "mp4",
    /** When the provider added it, epoch milliseconds; drives the "recent" rows. */
    val addedAt: Long? = null
)

/** A series of an Xtream provider. [id] is `xtream:{sourceId}:series:{seriesId}`. Seasons and episodes come with [SeriesInfo]. */
data class Series(
    val id: String,
    val title: String,
    val sourceId: Long = 0L,
    /** Provider `series_id`. */
    val seriesId: Long = 0L,
    val categoryId: String = "",
    val posterUrl: String? = null,
    val rating: Double? = null,
    val year: Int? = null,
    val synopsis: String? = null,
    /** Typical episode length, when the provider gives one. */
    val episodeRunTimeSeconds: Int? = null,
    /** Provider `last_modified`, epoch milliseconds. */
    val updatedAt: Long? = null
)

data class Season(val seriesId: String, val number: Int, val name: String, val episodeCount: Int, val posterUrl: String? = null)

/** [id] is `xtream:{sourceId}:episode:{episodeId}`; [episodeId] is the provider id used in the playback URL. */
data class Episode(
    val id: String,
    val seriesId: String,
    val season: Int,
    val number: Int,
    val title: String,
    val sourceId: Long = 0L,
    val episodeId: Long = 0L,
    val containerExtension: String = "mp4",
    val durationSeconds: Int? = null,
    val thumbnailUrl: String? = null,
    val synopsis: String? = null
)

/** A series with its seasons and every episode, as `get_series_info` returns them. */
data class SeriesInfo(val series: Series, val seasons: List<Season>, val episodes: List<Episode>)

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

enum class SourceKind { URL, FILE, XTREAM }

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

/** Portal and credentials of an Xtream Codes account. Never logged, never placed in a message or an exception. */
data class XtreamAccount(val server: String, val username: String, val password: String)

/** `user_info` of `player_api.php`, once the credentials are accepted. Times are epoch milliseconds. */
data class XtreamAccountInfo(
    /** Provider status: `Active`, `Expired`, `Banned`, `Disabled`… */
    val status: String,
    /** Null when the account has no end date. */
    val expiresAt: Long?,
    val isTrial: Boolean = false,
    val maxConnections: Int? = null,
    val activeConnections: Int? = null,
    /** `allowed_output_formats`, typically `m3u8`, `ts`, `rtmp`. */
    val allowedOutputFormats: List<String> = emptyList(),
    /** `server_info.timestamp_now`, when present. */
    val serverTime: Long? = null
) {
    /** Container of the live streams: `ts` unless the account only allows HLS. */
    val liveExtension: String
        get() = when {
            allowedOutputFormats.isEmpty() || "ts" in allowedOutputFormats -> "ts"
            "m3u8" in allowedOutputFormats -> "m3u8"
            else -> allowedOutputFormats.first()
        }

    val connectionsExhausted: Boolean
        get() = maxConnections != null && maxConnections > 0 && activeConnections != null && activeConnections >= maxConnections

    /** French reason the account cannot be used, or null when it can. Refused credentials never get here: they throw. */
    fun blocker(nowMs: Long = System.currentTimeMillis()): String? = when {
        status.equals("Banned", ignoreCase = true) || status.equals("Disabled", ignoreCase = true) -> "Compte désactivé par le fournisseur."
        status.equals("Expired", ignoreCase = true) || (expiresAt != null && expiresAt <= nowMs) ->
            if (expiresAt != null) "Compte expiré le ${frenchDate(expiresAt)}." else "Compte expiré."
        else -> null
    }

    /** French warning when every connection the account allows is already in use. */
    fun connectionsMessage(): String? =
        if (connectionsExhausted) "Nombre maximal de connexions atteint ($activeConnections/$maxConnections)." else null
}

fun frenchDate(epochMs: Long): String = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(java.util.Date(epochMs))

/**
 * Xtream Codes provider access over `player_api.php`. Implementations never log the account or echo it in an error:
 * [describeXtreamError] turns failures into French messages. Ids are prefixed by [sourceId] (see `XtreamIds`).
 */
interface XtreamClient {
    /** Checks the credentials; throws [XtreamException] when they are refused. */
    suspend fun account(account: XtreamAccount): XtreamAccountInfo
    /** Live categories and channels, ready for the `channels` table; [extension] comes from [XtreamAccountInfo.liveExtension]. */
    suspend fun liveCatalog(account: XtreamAccount, sourceId: Long, extension: String): ParsedPlaylist
    suspend fun movieCategories(account: XtreamAccount, sourceId: Long): List<Category>
    suspend fun movies(account: XtreamAccount, sourceId: Long): List<Movie>
    /** `get_vod_info`: [movie] completed with synopsis, duration, year and rating. */
    suspend fun movieDetails(account: XtreamAccount, movie: Movie): Movie
    suspend fun seriesCategories(account: XtreamAccount, sourceId: Long): List<Category>
    suspend fun series(account: XtreamAccount, sourceId: Long): List<Series>
    /** `get_series_info`: [series] completed, with its seasons and episodes. */
    suspend fun seriesDetails(account: XtreamAccount, series: Series): SeriesInfo
}
