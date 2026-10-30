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

/**
 * A configured source. [location] is an `http(s)` URL, a persisted `content://` URI or, for [SourceKind.XTREAM], the
 * normalised portal address; it is never logged. The Xtream password is not part of the domain model: it is stored
 * encrypted and read back only to build a request or a playback URL.
 */
data class Source(
    val id: Long,
    val name: String,
    val location: String,
    val kind: SourceKind,
    val lastRefreshed: Long? = null,
    val channelCount: Int = 0,
    val lastError: String? = null,
    /** Xtream only. */
    val username: String? = null,
    /** Xtream only: account end date, epoch milliseconds; null when unlimited or unknown. */
    val expiresAt: Long? = null,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    /** XMLTV guide address of a playlist (from `url-tvg` or typed by the user); Xtream sources use `xmltv.php`. */
    val epgUrl: String? = null,
    val epgRefreshedAt: Long? = null,
    val epgError: String? = null,
    val programmeCount: Int = 0
) {
    /** Playlists need an address; a provider's guide is always reachable with the account. */
    val hasGuide: Boolean get() = kind == SourceKind.XTREAM || !epgUrl.isNullOrBlank()
}

/** What a film or series grid shows: a category or pseudo-category, narrowed by a title filter. */
data class VodQuery(val categoryId: String = Category.ALL.id, val text: String = "") {
    fun matches(id: String, title: String, categoryId: String, favoriteIds: Set<String>): Boolean {
        val inCategory = when (this.categoryId) {
            Category.ALL.id -> true
            Category.FAVORITES.id -> id in favoriteIds
            else -> categoryId == this.categoryId
        }
        return inCategory && searchKey(title).contains(searchKey(text))
    }
}

/** Where playback of a film or an episode stopped. [itemId] is the [Movie.id] or [Episode.id]. */
data class PlaybackProgress(val itemId: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    /** Past 95 % the item counts as watched: no "resume" offer. */
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 95 / 100
}

/** Outcome of a read that may need the provider: the value, or a French message. */
sealed interface Fetched<out T> {
    data class Success<T>(val value: T) : Fetched<T>
    data class Failure(val message: String) : Fetched<Nothing>
}

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
    /** Connection test before saving: the account as the provider describes it, or the French reason it cannot be used. */
    suspend fun checkXtreamAccount(account: XtreamAccount): Fetched<XtreamAccountInfo>
    /** Checks the account, imports live channels, films and series; the password is stored encrypted. */
    suspend fun addXtreamSource(name: String, account: XtreamAccount): ImportResult
    suspend fun refreshSource(id: Long): ImportResult
    suspend fun removeSource(id: Long)
    /** [itemId] is a channel, film or series id: favourites share one table. */
    suspend fun setFavorite(itemId: String, favorite: Boolean)

    // Films and series, from Xtream sources only. Lists are paged like channels; ids are prefixed per source.
    val movieCategories: Flow<List<Category>>
    val seriesCategories: Flow<List<Category>>
    fun movies(query: VodQuery): Flow<PagingData<Movie>>
    fun series(query: VodQuery): Flow<PagingData<Series>>
    fun countMovies(query: VodQuery): Flow<Int>
    fun countSeries(query: VodQuery): Flow<Int>
    /** Most recently added by the provider, for the home rows. */
    fun recentMovies(limit: Int): Flow<List<Movie>>
    fun recentSeries(limit: Int): Flow<List<Series>>
    suspend fun movie(id: String): Movie?
    suspend fun series(id: String): Series?
    suspend fun episode(id: String): Episode?
    /** The film with synopsis, duration and year: fetched from the provider once, then served from the base. */
    suspend fun movieDetails(id: String): Fetched<Movie>
    /** Seasons and episodes: fetched on the first opening, cached until the source is refreshed or [refresh] is set. */
    suspend fun seriesInfo(id: String, refresh: Boolean = false): Fetched<SeriesInfo>

    /**
     * The URL the player opens for a stored [Channel.streamUrl] or a [streamRef] of a film or episode: Xtream
     * references get the source's credentials here and nowhere else. Null when the source is gone or its
     * password can no longer be read. Plain URLs come back unchanged.
     */
    suspend fun playbackUrl(streamUrl: String): String?

    /** Resume positions, most recent first. */
    val progress: Flow<List<PlaybackProgress>>
    suspend fun progress(itemId: String): PlaybackProgress?
    suspend fun saveProgress(itemId: String, positionMs: Long, durationMs: Long)
    suspend fun clearProgress(itemId: String)

    // Programme guide. Programmes join channels through the guide key written on each channel at import
    // (`tvg-id`, `epg_channel_id`, or a guide channel matched by name for playlists).

    /** Stores the XMLTV address of a playlist source (null or blank clears it and its programmes) and imports the guide. */
    suspend fun setEpgUrl(sourceId: Long, url: String?): ImportResult
    /** Re-imports the guide of a source: its XMLTV file, or, for a provider, `xmltv.php` limited to the favourite channels. */
    suspend fun refreshEpg(sourceId: Long): ImportResult
    /** Channels with a programme on air at [nowMs], by channel id. Refreshed when the guide changes. */
    fun nowAndNext(nowMs: Long): Flow<Map<String, NowNext>>
    /** Programmes of one channel overlapping `[fromMs, toMs[`, in order. */
    fun programmes(channelId: String, fromMs: Long, toMs: Long): Flow<List<Programme>>
    /** Programmes still to come (or on air) whose title contains [text], soonest first, at most [limit]. */
    fun searchProgrammes(text: String, nowMs: Long, limit: Int = 200): Flow<List<ProgrammeHit>>
    fun countProgrammes(text: String, nowMs: Long): Flow<Int>
    /** Xtream channels: fetches the next programmes from the provider when the cached ones are stale. True when fetched. */
    suspend fun ensureShortEpg(channelId: String): Boolean
    /** Drops programmes finished for longer than [EpgWindow.PURGE_AFTER_MS]. */
    suspend fun purgeProgrammes()
}

/** Parsed playlist, before it is attached to a stored source. [epgUrl] is the `url-tvg` of the `#EXTM3U` header, when present. */
data class ParsedPlaylist(val categories: List<Category>, val channels: List<Channel>, val epgUrl: String? = null)

/**
 * One entry of a programme guide. [channelKey] is the guide's channel id (`tvg-id` of an M3U channel, `epg_channel_id`
 * of an Xtream channel, `channel` of an XMLTV `<programme>`), scoped by the [sourceId] that imported it. Times are epoch
 * milliseconds; the guide's own offsets are applied at parse time.
 */
data class Programme(
    val channelKey: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    /** « S1 E2 » when the guide numbers the episode. */
    val episode: String? = null,
    val sourceId: Long = 0L
) {
    val durationMs: Long get() = stopMs - startMs
    fun isLive(nowMs: Long): Boolean = nowMs >= startMs && nowMs < stopMs
    /** Elapsed share at [nowMs], 0 before the start and 1 after the end. */
    fun progress(nowMs: Long): Float = when {
        durationMs <= 0 -> if (nowMs >= stopMs) 1f else 0f
        else -> ((nowMs - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
    }
}

/** A `<channel>` of an XMLTV guide: its id, its display names (the first is the main one) and its icon. */
data class EpgChannel(val id: String, val names: List<String>, val iconUrl: String? = null)

/** What a channel airs now and right after; [next] is only known while something is on. */
data class NowNext(val now: Programme, val next: Programme? = null)

/** A programme found by the search, with the channel it airs on. */
data class ProgrammeHit(val programme: Programme, val channel: Channel)

/** Programme guide retention: programmes overlapping this window around the import time are kept. */
object EpgWindow {
    const val BEFORE_MS = 6 * 3_600_000L
    const val AFTER_MS = 3 * 24 * 3_600_000L
    /** Programmes finished for longer than this are purged. */
    const val PURGE_AFTER_MS = 12 * 3_600_000L
    /** Xtream `get_short_epg` answers are refreshed after this delay. */
    const val SHORT_EPG_TTL_MS = 30 * 60_000L
    const val SHORT_EPG_LIMIT = 12
    /** The daily refresh reloads guides older than this; a little under a day, so a late run still reloads. */
    const val REFRESH_AFTER_MS = 20 * 3_600_000L
    /** At launch, guides older than this are reloaded (the daily work may have been held back by the system). */
    const val STALE_AT_LAUNCH_MS = 24 * 3_600_000L
}

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
    /** `get_short_epg`: the next [limit] programmes of a live stream, keyed by [channelKey] (its `epg_channel_id`). */
    suspend fun shortEpg(account: XtreamAccount, sourceId: Long, streamId: Long, channelKey: String, limit: Int = 10): List<Programme>
}
