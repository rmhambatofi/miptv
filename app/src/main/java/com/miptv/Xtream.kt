package com.miptv

import java.io.IOException
import java.io.Reader
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Provider-side failure with a message safe to show: it never contains the server, the user name or the password. */
class XtreamException(message: String) : IOException(message)

/**
 * Stable identifiers of Xtream content, prefixed by the source so two providers never collide and favourites of
 * channels, films and series share one table. Channels follow the plan: `xtream:{sourceId}:{stream_id}`.
 */
object XtreamIds {
    enum class Kind(val tag: String) { LIVE("live"), MOVIE("movie"), SERIES("series") }

    /** Pseudo provider category for streams with no `category_id`. */
    const val NO_CATEGORY = "none"

    fun channel(sourceId: Long, streamId: Long) = "xtream:$sourceId:$streamId"
    fun category(sourceId: Long, kind: Kind, providerId: String) = "xtream:$sourceId:category:${kind.tag}:$providerId"
    fun movie(sourceId: Long, streamId: Long) = "xtream:$sourceId:movie:$streamId"
    fun series(sourceId: Long, seriesId: Long) = "xtream:$sourceId:series:$seriesId"
    fun episode(sourceId: Long, episodeId: Long) = "xtream:$sourceId:episode:$episodeId"
}

/**
 * What a channel stores instead of a playable URL: `xtream://{sourceId}/live/{streamId}.{ext}`. The URL with the
 * credentials, `{server}/live/{user}/{password}/{streamId}.{ext}`, is built by [resolve] when playback starts and
 * is never persisted. Films use `/movie/`, episodes `/series/`.
 */
data class XtreamStreamRef(val sourceId: Long, val kind: Kind, val streamId: Long, val extension: String) {
    enum class Kind(val path: String) { LIVE("live"), MOVIE("movie"), EPISODE("series") }

    fun format(): String = "$SCHEME://$sourceId/${kind.path}/$streamId.$extension"

    fun resolve(account: XtreamAccount): String =
        "${account.server}/${kind.path}/${encodePathSegment(account.username)}/${encodePathSegment(account.password)}/$streamId.$extension"

    companion object {
        const val SCHEME = "xtream"
        private val PATTERN = Regex("""^xtream://(\d+)/(live|movie|series)/(\d+)\.([A-Za-z0-9]+)$""")

        fun parse(value: String): XtreamStreamRef? {
            val match = PATTERN.matchEntire(value.trim()) ?: return null
            val (sourceId, path, streamId, extension) = match.destructured
            val kind = Kind.entries.first { it.path == path }
            return XtreamStreamRef(sourceId.toLong(), kind, streamId.toLong(), extension)
        }

        fun isRef(value: String) = value.startsWith("$SCHEME://")
    }
}

fun Movie.streamRef() = XtreamStreamRef(sourceId, XtreamStreamRef.Kind.MOVIE, streamId, containerExtension)
fun Episode.streamRef() = XtreamStreamRef(sourceId, XtreamStreamRef.Kind.EPISODE, episodeId, containerExtension)

/**
 * `host:port`, with or without a scheme, a trailing slash or a pasted `player_api.php`/`get.php`/`/c` path, becomes
 * `http(s)://host:port`. Nothing is validated here: a bad address fails at connection time with "Adresse invalide".
 */
fun normalizeXtreamServer(input: String): String {
    var server = input.trim().substringBefore('?').substringBefore('#')
    if (server.isEmpty()) return server
    if (!server.contains("://")) server = "http://$server"
    server = server.trimEnd('/')
    for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php", "/c")) {
        if (server.endsWith(suffix, ignoreCase = true)) server = server.dropLast(suffix.length).trimEnd('/')
    }
    return server
}

/** `player_api.php` address for [action] and its parameters. Credentials travel as query parameters: the protocol imposes it. */
fun XtreamAccount.apiUrl(action: String? = null, vararg params: Pair<String, String>): String {
    val url = StringBuilder(server).append("/player_api.php?username=").append(encodeQuery(username)).append("&password=").append(encodeQuery(password))
    if (action != null) url.append("&action=").append(action)
    for ((key, value) in params) url.append('&').append(key).append('=').append(encodeQuery(value))
    return url.toString()
}

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * Parsers for `player_api.php` responses, one reader in, domain objects out; no network. Tolerant of what panels
 * actually send: numbers as strings, `null` or missing fields, `category_id` absent, empty lists as `{}`, lists
 * keyed by id. Streams the response: the only list held is the result.
 */
class XtreamParser(private val sourceId: Long) {

    /** Account check (`player_api.php` without action). Throws [XtreamException] when the credentials are refused. */
    fun account(reader: Reader): XtreamAccountInfo = JsonStreamReader(reader).use { json ->
        if (json.peek() != JsonStreamReader.Token.BEGIN_OBJECT) throw XtreamException(UNEXPECTED)
        var hasUserInfo = false
        var auth: Boolean? = null
        var status: String? = null
        var expiresAt: Long? = null
        var trial = false
        var maxConnections: Int? = null
        var activeConnections: Int? = null
        var formats: List<String> = emptyList()
        var serverTime: Long? = null
        json.forEachField { field ->
            when (field) {
                "user_info" -> {
                    hasUserInfo = true
                    json.forEachField { f ->
                        when (f) {
                            "auth" -> auth = json.nextBooleanOrNull()
                            "status" -> status = json.nextStringOrNull()
                            "exp_date" -> expiresAt = json.nextLongOrNull()?.takeIf { it > 0 }?.times(1000)
                            "is_trial" -> trial = json.nextBooleanOrNull() == true
                            "max_connections" -> maxConnections = json.nextIntOrNull()
                            "active_cons" -> activeConnections = json.nextIntOrNull()
                            "allowed_output_formats" -> formats = json.nextStringList().map { it.lowercase() }
                            else -> json.skipValue()
                        }
                    }
                }
                "server_info" -> json.forEachField { f -> if (f == "timestamp_now") serverTime = json.nextLongOrNull()?.times(1000) else json.skipValue() }
                else -> json.skipValue()
            }
        }
        if (!hasUserInfo) throw XtreamException(UNEXPECTED)
        if (auth != true) throw XtreamException(REFUSED)
        XtreamAccountInfo(status?.ifBlank { null } ?: "Active", expiresAt, trial, maxConnections, activeConnections, formats, serverTime)
    }

    /** `get_live_categories`, `get_vod_categories` or `get_series_categories`. */
    fun categories(reader: Reader, kind: XtreamIds.Kind): List<Category> = JsonStreamReader(reader).use { json ->
        val out = LinkedHashMap<String, Category>()
        json.forEachElement {
            var id: String? = null
            var name: String? = null
            json.forEachField { f ->
                when (f) {
                    "category_id" -> id = json.nextStringOrNull()
                    "category_name" -> name = json.nextStringOrNull()
                    else -> json.skipValue()
                }
            }
            val providerId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachElement
            val categoryId = XtreamIds.category(sourceId, kind, providerId)
            out.putIfAbsent(categoryId, Category(categoryId, name?.trim()?.ifEmpty { null } ?: "Catégorie $providerId"))
        }
        out.values.toList()
    }

    /** `get_live_streams` as channels whose [Channel.streamUrl] is an [XtreamStreamRef] with [extension]. */
    fun liveChannels(reader: Reader, extension: String): List<Channel> = JsonStreamReader(reader).use { json ->
        val out = ArrayList<Channel>()
        val seen = HashSet<Long>()
        json.forEachElement {
            var streamId: Long? = null
            var name: String? = null
            var icon: String? = null
            var epg: String? = null
            var categoryId: String? = null
            var categoryIds: List<String> = emptyList()
            json.forEachField { f ->
                when (f) {
                    "stream_id" -> streamId = json.nextLongOrNull()
                    "name" -> name = json.nextStringOrNull()
                    "stream_icon" -> icon = json.nextStringOrNull()
                    "epg_channel_id" -> epg = json.nextStringOrNull()
                    "category_id" -> categoryId = json.nextStringOrNull()
                    "category_ids" -> categoryIds = json.nextStringList()
                    else -> json.skipValue()
                }
            }
            val id = streamId ?: return@forEachElement
            if (!seen.add(id)) return@forEachElement
            out += Channel(
                id = XtreamIds.channel(sourceId, id),
                name = name?.trim()?.ifEmpty { null } ?: "Chaîne $id",
                categoryId = categoryIdOf(categoryId, categoryIds, XtreamIds.Kind.LIVE),
                streamUrl = XtreamStreamRef(sourceId, XtreamStreamRef.Kind.LIVE, id, extension).format(),
                logoUrl = icon.urlOrNull(),
                tvgId = epg?.trim()?.ifEmpty { null },
                sourceId = sourceId
            )
        }
        out
    }

    /** `get_vod_streams`. */
    fun movies(reader: Reader): List<Movie> = JsonStreamReader(reader).use { json ->
        val out = ArrayList<Movie>()
        val seen = HashSet<Long>()
        json.forEachElement {
            val m = MovieFields()
            json.forEachField { f -> if (!m.read(json, f)) json.skipValue() }
            val id = m.streamId ?: return@forEachElement
            if (seen.add(id)) out += m.toMovie(id)
        }
        out
    }

    /** `get_vod_info`: [movie] completed by the `info` and `movie_data` blocks. Missing blocks (unknown id) keep [movie]. */
    fun movieDetails(reader: Reader, movie: Movie): Movie = JsonStreamReader(reader).use { json ->
        if (json.peek() != JsonStreamReader.Token.BEGIN_OBJECT) throw XtreamException(UNEXPECTED)
        val m = MovieFields()
        json.forEachField { field ->
            when (field) {
                "info", "movie_data" -> json.forEachField { f -> if (!m.read(json, f)) json.skipValue() }
                else -> json.skipValue()
            }
        }
        movie.copy(
            title = m.title ?: movie.title,
            streamId = m.streamId ?: movie.streamId,
            categoryId = m.categoryId?.let { categoryIdOf(it, emptyList(), XtreamIds.Kind.MOVIE) } ?: movie.categoryId,
            posterUrl = m.poster.urlOrNull() ?: movie.posterUrl,
            rating = m.rating ?: movie.rating,
            year = m.resolvedYear ?: movie.year,
            synopsis = m.synopsis ?: movie.synopsis,
            durationSeconds = m.resolvedDuration ?: movie.durationSeconds,
            containerExtension = m.extension ?: movie.containerExtension,
            addedAt = m.added ?: movie.addedAt
        )
    }

    /** `get_series`. */
    fun series(reader: Reader): List<Series> = JsonStreamReader(reader).use { json ->
        val out = ArrayList<Series>()
        val seen = HashSet<Long>()
        json.forEachElement {
            val s = SeriesFields()
            json.forEachField { f -> if (!s.read(json, f)) json.skipValue() }
            val id = s.seriesId ?: return@forEachElement
            if (seen.add(id)) out += s.toSeries(id)
        }
        out
    }

    /**
     * `get_series_info`: [series] completed by `info`, plus seasons and episodes. `episodes` is an object keyed by
     * season number, or an array of arrays on some panels; `seasons` may be empty, in which case they are derived.
     */
    fun seriesDetails(reader: Reader, series: Series): SeriesInfo = JsonStreamReader(reader).use { json ->
        if (json.peek() != JsonStreamReader.Token.BEGIN_OBJECT) throw XtreamException(UNEXPECTED)
        val s = SeriesFields()
        val declared = ArrayList<SeasonFields>()
        val episodes = ArrayList<Episode>()
        json.forEachField { field ->
            when (field) {
                "info" -> json.forEachField { f -> if (!s.read(json, f)) json.skipValue() }
                "seasons" -> json.forEachElement {
                    val season = SeasonFields()
                    json.forEachField { f -> if (!season.read(json, f)) json.skipValue() }
                    if (season.number != null) declared += season
                }
                "episodes" -> when (json.peek()) {
                    JsonStreamReader.Token.BEGIN_OBJECT -> {
                        json.beginObject()
                        while (json.hasNext()) {
                            val seasonHint = json.nextName().trim().toIntOrNull()
                            readEpisodes(json, seasonHint, series, episodes)
                        }
                        json.endObject()
                    }
                    JsonStreamReader.Token.BEGIN_ARRAY -> {
                        json.beginArray()
                        while (json.hasNext()) readEpisodes(json, null, series, episodes)
                        json.endArray()
                    }
                    else -> json.skipValue()
                }
                else -> json.skipValue()
            }
        }
        val completed = series.copy(
            title = s.title ?: series.title,
            posterUrl = s.poster.urlOrNull() ?: series.posterUrl,
            rating = s.rating ?: series.rating,
            year = s.resolvedYear ?: series.year,
            synopsis = s.synopsis ?: series.synopsis,
            episodeRunTimeSeconds = s.runTime ?: series.episodeRunTimeSeconds,
            updatedAt = s.updated ?: series.updatedAt
        )
        episodes.sortWith(compareBy({ it.season }, { it.number }))
        SeriesInfo(completed, seasons(declared, episodes, series.id), episodes)
    }

    private fun readEpisodes(json: JsonStreamReader, seasonHint: Int?, series: Series, out: MutableList<Episode>) {
        var index = 0
        json.forEachElement {
            index++
            var id: Long? = null
            var number: Int? = null
            var title: String? = null
            var extension: String? = null
            var season: Int? = null
            var durationSeconds: Int? = null
            var durationText: String? = null
            var image: String? = null
            var plot: String? = null
            json.forEachField { f ->
                when (f) {
                    "id" -> id = json.nextLongOrNull()
                    "episode_num" -> number = json.nextIntOrNull()
                    "title" -> title = json.nextStringOrNull()
                    "container_extension" -> extension = json.nextStringOrNull()
                    "season" -> season = json.nextIntOrNull()
                    "info" -> json.forEachField { g ->
                        when (g) {
                            "duration_secs" -> durationSeconds = json.nextIntOrNull()
                            "duration" -> durationText = json.nextStringOrNull()
                            "movie_image" -> image = json.nextStringOrNull()
                            "plot" -> plot = json.nextStringOrNull()
                            "season" -> { val value = json.nextIntOrNull(); if (season == null) season = value }
                            else -> json.skipValue()
                        }
                    }
                    else -> json.skipValue()
                }
            }
            val episodeId = id ?: return@forEachElement
            val seasonNumber = season ?: seasonHint ?: 1
            val episodeNumber = number ?: index
            out += Episode(
                id = XtreamIds.episode(sourceId, episodeId),
                seriesId = series.id,
                season = seasonNumber,
                number = episodeNumber,
                title = title?.trim()?.ifEmpty { null } ?: "Épisode $episodeNumber",
                sourceId = sourceId,
                episodeId = episodeId,
                containerExtension = extension.extensionOr("mp4"),
                durationSeconds = durationSeconds?.takeIf { it > 0 } ?: parseDuration(durationText),
                thumbnailUrl = image.urlOrNull(),
                synopsis = plot?.trim()?.ifEmpty { null }
            )
        }
    }

    /** Seasons that have episodes, with the declared metadata when present; the declared list alone when no episode came. */
    private fun seasons(declared: List<SeasonFields>, episodes: List<Episode>, seriesId: String): List<Season> {
        val byNumber = declared.associateBy { it.number!! }
        val counts = episodes.groupingBy { it.season }.eachCount()
        val numbers = if (counts.isEmpty()) byNumber.keys else counts.keys
        return numbers.sorted().map { number ->
            val meta = byNumber[number]
            Season(
                seriesId = seriesId,
                number = number,
                name = meta?.name?.trim()?.ifEmpty { null } ?: "Saison $number",
                episodeCount = counts[number] ?: meta?.episodeCount ?: 0,
                posterUrl = meta?.cover.urlOrNull()
            )
        }
    }

    private class MovieFields {
        var streamId: Long? = null
        var title: String? = null
        var poster: String? = null
        var rating: Double? = null
        var year: Int? = null
        var releaseDate: String? = null
        var synopsis: String? = null
        var duration: Int? = null
        var durationText: String? = null
        var runTimeMinutes: Int? = null
        var extension: String? = null
        var categoryId: String? = null
        var categoryIds: List<String> = emptyList()
        var added: Long? = null

        /** Reads one field of a list entry, of `info` or of `movie_data`; false when the field is unknown. */
        fun read(json: JsonStreamReader, field: String): Boolean {
            when (field) {
                "stream_id" -> streamId = json.nextLongOrNull()
                "name" -> title = json.nextStringOrNull()?.trim()?.ifEmpty { null } ?: title
                "title", "o_name" -> { val value = json.nextStringOrNull()?.trim()?.ifEmpty { null }; if (title == null) title = value }
                "stream_icon", "movie_image" -> poster = json.nextStringOrNull()?.trim()?.ifEmpty { null } ?: poster
                "cover_big", "cover" -> { val value = json.nextStringOrNull()?.trim()?.ifEmpty { null }; if (poster == null) poster = value }
                "rating" -> rating = parseRating(json.nextStringOrNull()) ?: rating
                "year" -> year = parseYear(json.nextStringOrNull()) ?: year
                "release_date", "releasedate", "releaseDate" -> releaseDate = json.nextStringOrNull() ?: releaseDate
                "plot" -> synopsis = json.nextStringOrNull()?.trim()?.ifEmpty { null } ?: synopsis
                "description" -> { val value = json.nextStringOrNull()?.trim()?.ifEmpty { null }; if (synopsis == null) synopsis = value }
                "duration_secs" -> duration = json.nextIntOrNull()?.takeIf { it > 0 } ?: duration
                "duration" -> durationText = json.nextStringOrNull() ?: durationText
                "episode_run_time" -> runTimeMinutes = json.nextIntOrNull()?.takeIf { it > 0 } ?: runTimeMinutes
                "container_extension" -> extension = json.nextStringOrNull() ?: extension
                "category_id" -> categoryId = json.nextStringOrNull() ?: categoryId
                "category_ids" -> categoryIds = json.nextStringList()
                "added" -> added = json.nextLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: added
                else -> return false
            }
            return true
        }

        val resolvedYear: Int? get() = year ?: parseYear(releaseDate) ?: title?.let { parseYearInTitle(it) }
        val resolvedDuration: Int? get() = duration ?: parseDuration(durationText) ?: runTimeMinutes?.times(60)
    }

    private fun MovieFields.toMovie(id: Long) = Movie(
        id = XtreamIds.movie(sourceId, id),
        title = title ?: "Film $id",
        sourceId = sourceId,
        streamId = id,
        categoryId = categoryIdOf(categoryId, categoryIds, XtreamIds.Kind.MOVIE),
        posterUrl = poster.urlOrNull(),
        rating = rating,
        year = resolvedYear,
        synopsis = synopsis,
        durationSeconds = resolvedDuration,
        containerExtension = extension.extensionOr("mp4"),
        addedAt = added
    )

    private class SeriesFields {
        var seriesId: Long? = null
        var title: String? = null
        var poster: String? = null
        var rating: Double? = null
        var year: Int? = null
        var releaseDate: String? = null
        var synopsis: String? = null
        var runTime: Int? = null
        var categoryId: String? = null
        var categoryIds: List<String> = emptyList()
        var updated: Long? = null

        fun read(json: JsonStreamReader, field: String): Boolean {
            when (field) {
                "series_id" -> seriesId = json.nextLongOrNull()
                "name", "title" -> title = json.nextStringOrNull()?.trim()?.ifEmpty { null } ?: title
                "cover" -> poster = json.nextStringOrNull()?.trim()?.ifEmpty { null } ?: poster
                "cover_big", "movie_image" -> { val value = json.nextStringOrNull()?.trim()?.ifEmpty { null }; if (poster == null) poster = value }
                "rating" -> rating = parseRating(json.nextStringOrNull()) ?: rating
                "year" -> year = parseYear(json.nextStringOrNull()) ?: year
                "release_date", "releasedate", "releaseDate" -> releaseDate = json.nextStringOrNull() ?: releaseDate
                "plot" -> synopsis = json.nextStringOrNull()?.trim()?.ifEmpty { null } ?: synopsis
                "description" -> { val value = json.nextStringOrNull()?.trim()?.ifEmpty { null }; if (synopsis == null) synopsis = value }
                "episode_run_time" -> runTime = json.nextIntOrNull()?.takeIf { it > 0 }?.times(60) ?: runTime
                "category_id" -> categoryId = json.nextStringOrNull() ?: categoryId
                "category_ids" -> categoryIds = json.nextStringList()
                "last_modified" -> updated = json.nextLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: updated
                else -> return false
            }
            return true
        }

        val resolvedYear: Int? get() = year ?: parseYear(releaseDate) ?: title?.let { parseYearInTitle(it) }
    }

    private fun SeriesFields.toSeries(id: Long) = Series(
        id = XtreamIds.series(sourceId, id),
        title = title ?: "Série $id",
        sourceId = sourceId,
        seriesId = id,
        categoryId = categoryIdOf(categoryId, categoryIds, XtreamIds.Kind.SERIES),
        posterUrl = poster.urlOrNull(),
        rating = rating,
        year = resolvedYear,
        synopsis = synopsis,
        episodeRunTimeSeconds = runTime,
        updatedAt = updated
    )

    private class SeasonFields {
        var number: Int? = null
        var name: String? = null
        var episodeCount: Int? = null
        var cover: String? = null

        fun read(json: JsonStreamReader, field: String): Boolean {
            when (field) {
                "season_number" -> number = json.nextIntOrNull()
                "name" -> name = json.nextStringOrNull()
                "episode_count" -> episodeCount = json.nextIntOrNull()
                "cover" -> cover = json.nextStringOrNull()?.trim()?.ifEmpty { null } ?: cover
                "cover_big" -> { val value = json.nextStringOrNull()?.trim()?.ifEmpty { null }; if (cover == null) cover = value }
                else -> return false
            }
            return true
        }
    }

    /** `category_id`, else the first of `category_ids`, else the pseudo-category for uncategorised content. */
    private fun categoryIdOf(categoryId: String?, categoryIds: List<String>, kind: XtreamIds.Kind): String {
        val providerId = categoryId?.trim()?.ifEmpty { null } ?: categoryIds.firstOrNull()?.trim()?.ifEmpty { null } ?: XtreamIds.NO_CATEGORY
        return XtreamIds.category(sourceId, kind, providerId)
    }

    companion object {
        const val REFUSED = "Identifiants refusés. Vérifiez l'identifiant et le mot de passe."
        const val UNEXPECTED = "Réponse inattendue : ce serveur ne semble pas être un portail Xtream."

        fun parseRating(text: String?): Double? = text?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10.0 }

        private val YEAR = Regex("""(?<!\d)(1[89]\d\d|20\d\d|21\d\d)(?!\d)""")
        private val YEAR_IN_TITLE = Regex("""\((1[89]\d\d|20\d\d|21\d\d)\)""")

        fun parseYear(text: String?): Int? = text?.let { YEAR.find(it)?.value?.toInt() }
        fun parseYearInTitle(title: String): Int? = YEAR_IN_TITLE.find(title)?.groupValues?.get(1)?.toInt()

        /** `"01:45:00"`, `"45:00"`, or a plain number of minutes (`episode_run_time`); null when unusable or zero. */
        fun parseDuration(text: String?): Int? {
            val value = text?.trim()?.ifEmpty { null } ?: return null
            val parts = value.split(':')
            val seconds = when {
                parts.size == 1 -> value.toIntOrNull()?.times(60)
                parts.size in 2..3 && parts.all { it.toIntOrNull() != null } -> parts.map { it.toInt() }.fold(0) { acc, part -> acc * 60 + part }
                else -> null
            }
            return seconds?.takeIf { it > 0 }
        }

        private fun String?.urlOrNull(): String? = this?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

        private fun String?.extensionOr(default: String): String = this?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } } ?: default
    }
}

/**
 * Channels whose category is absent from [categories] (streams without `category_id`, or pointing to a deleted
 * category) are moved to one "Sans catégorie" category appended to the list, as the M3U import does for groups.
 */
fun attachToCategories(categories: List<Category>, channels: List<Channel>, sourceId: Long): ParsedPlaylist {
    val known = categories.mapTo(HashSet()) { it.id }
    val fallbackId = XtreamIds.category(sourceId, XtreamIds.Kind.LIVE, XtreamIds.NO_CATEGORY)
    var orphans = false
    val attached = channels.map { channel ->
        if (channel.categoryId in known) channel else { orphans = true; channel.copy(categoryId = fallbackId) }
    }
    val all = if (orphans && fallbackId !in known) categories + Category(fallbackId, M3uParser.DEFAULT_GROUP) else categories
    return ParsedPlaylist(all, attached)
}

/** [XtreamClient] over [Http], on `Dispatchers.IO`. The account only ever appears in the request URL. */
class HttpXtreamClient : XtreamClient {
    override suspend fun account(account: XtreamAccount): XtreamAccountInfo =
        fetch(account.apiUrl()) { XtreamParser(0L).account(it) }

    override suspend fun liveCatalog(account: XtreamAccount, sourceId: Long, extension: String): ParsedPlaylist {
        val parser = XtreamParser(sourceId)
        val categories = fetch(account.apiUrl("get_live_categories")) { parser.categories(it, XtreamIds.Kind.LIVE) }
        val channels = fetch(account.apiUrl("get_live_streams")) { parser.liveChannels(it, extension) }
        return attachToCategories(categories, channels, sourceId)
    }

    override suspend fun movieCategories(account: XtreamAccount, sourceId: Long): List<Category> =
        fetch(account.apiUrl("get_vod_categories")) { XtreamParser(sourceId).categories(it, XtreamIds.Kind.MOVIE) }

    override suspend fun movies(account: XtreamAccount, sourceId: Long): List<Movie> =
        fetch(account.apiUrl("get_vod_streams")) { XtreamParser(sourceId).movies(it) }

    override suspend fun movieDetails(account: XtreamAccount, movie: Movie): Movie =
        fetch(account.apiUrl("get_vod_info", "vod_id" to movie.streamId.toString())) { XtreamParser(movie.sourceId).movieDetails(it, movie) }

    override suspend fun seriesCategories(account: XtreamAccount, sourceId: Long): List<Category> =
        fetch(account.apiUrl("get_series_categories")) { XtreamParser(sourceId).categories(it, XtreamIds.Kind.SERIES) }

    override suspend fun series(account: XtreamAccount, sourceId: Long): List<Series> =
        fetch(account.apiUrl("get_series")) { XtreamParser(sourceId).series(it) }

    override suspend fun seriesDetails(account: XtreamAccount, series: Series): SeriesInfo =
        fetch(account.apiUrl("get_series_info", "series_id" to series.seriesId.toString())) { XtreamParser(series.sourceId).seriesDetails(it, series) }

    private suspend fun <T> fetch(url: String, parse: (Reader) -> T): T = withContext(Dispatchers.IO) {
        Http.open(url).bufferedReader(Charsets.UTF_8).use(parse)
    }
}

/** French message for a failed provider call. Never echoes the server, the user name or the password. */
fun describeXtreamError(error: Throwable): String = when (error) {
    is XtreamException -> error.message ?: XtreamParser.UNEXPECTED
    is JsonSyntaxException -> XtreamParser.UNEXPECTED
    is HttpStatusException -> when (error.code) {
        401, 403 -> "Identifiants refusés par le serveur (HTTP ${error.code})."
        404 -> "API Xtream introuvable à cette adresse (HTTP 404). Vérifiez le serveur et le port."
        429 -> "Trop de requêtes : le serveur demande d'attendre avant de réessayer."
        in 500..599 -> "Le serveur du fournisseur est en difficulté (HTTP ${error.code})."
        else -> "Le serveur a répondu HTTP ${error.code}."
    }
    is PlaylistException, is UnknownHostException, is SocketTimeoutException, is SSLException,
    is IllegalArgumentException, is MalformedURLException -> describeImportError(error)
    is IOException -> "Lecture du catalogue impossible."
    else -> "Connexion au fournisseur impossible."
}
