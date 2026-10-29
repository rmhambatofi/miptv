package com.miptv

import android.content.Context
import androidx.core.net.toUri
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Error whose message is safe to show: it never contains the playlist location. */
class PlaylistException(message: String) : IOException(message)

/** HTTP status outside 2xx. The message carries the code only, never the location. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * `HttpURLConnection` with bounded timeouts and manual redirects, shared by playlists and the Xtream client.
 * Never logs the location, which may embed credentials. Throws [HttpStatusException] for a non-2xx status and
 * [PlaylistException] for a non-HTTP scheme or a broken redirect.
 */
object Http {
    const val USER_AGENT = "miptv/0.4 (Android)"
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val MAX_REDIRECTS = 5

    fun open(location: String): InputStream {
        var url = URL(location)
        repeat(MAX_REDIRECTS) {
            val connection = (url.openConnection() as? HttpURLConnection)
                ?: throw PlaylistException("Seules les adresses http:// et https:// sont acceptées.")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            val code = connection.responseCode
            if (code in 300..399) {
                val target = connection.getHeaderField("Location") ?: throw PlaylistException("Redirection invalide (HTTP $code).")
                connection.disconnect()
                url = URL(url, target)
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw HttpStatusException(code)
            }
            return connection.inputStream
        }
        throw PlaylistException("Trop de redirections.")
    }
}

/** Opens a playlist by location. Implementations must not log the location (it may embed credentials). */
interface PlaylistLoader { suspend fun open(location: String, kind: SourceKind): InputStream }

class AndroidPlaylistLoader(private val context: Context) : PlaylistLoader {
    override suspend fun open(location: String, kind: SourceKind): InputStream = withContext(Dispatchers.IO) {
        when (kind) {
            SourceKind.FILE -> context.contentResolver.openInputStream(location.toUri())
                ?: throw PlaylistException("Fichier inaccessible. Sélectionnez-le à nouveau.")
            SourceKind.URL -> Http.open(location)
            // An Xtream source is imported through XtreamClient, not read as a playlist.
            SourceKind.XTREAM -> throw PlaylistException("Cette source est un fournisseur Xtream, pas une playlist.")
        }
    }
}

/** Maps transport failures to French messages without echoing the location. */
fun describeImportError(error: Throwable): String = when (error) {
    is PlaylistException -> error.message ?: "Importation impossible."
    is UnknownHostException -> "Hôte introuvable. Vérifiez l'adresse et la connexion."
    is SocketTimeoutException -> "Délai dépassé. Le serveur ne répond pas."
    is SSLException -> "Connexion sécurisée impossible."
    is FileNotFoundException, is SecurityException -> "Fichier inaccessible. Sélectionnez-le à nouveau."
    is IllegalArgumentException, is java.net.MalformedURLException -> "Adresse invalide."
    is HttpStatusException -> "Le serveur a répondu HTTP ${error.code}."
    is IOException -> "Lecture de la playlist impossible."
    else -> "Importation impossible."
}

/**
 * In-memory implementation: sources and catalog live for the process only. Used by tests and as the
 * reference behaviour for the persistent implementation. Lists are filtered in memory and served as one page.
 */
class MemoryChannelRepository(
    private val loader: PlaylistLoader,
    private val xtream: XtreamClient? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ChannelRepository {
    private val mutex = Mutex()
    private val playlists = LinkedHashMap<Long, ParsedPlaylist>()
    private val favorites = LinkedHashSet<String>()
    private var nextId = 1L
    private val sourcesFlow = MutableStateFlow<List<Source>>(emptyList())
    private class Snapshot(val categories: List<Category>, val channels: List<Channel>, val favorites: Set<String>)
    private val snapshot = MutableStateFlow(Snapshot(emptyList(), emptyList(), emptySet()))

    // Xtream: clear passwords stay in memory here, which is why this implementation never ships as the app's store.
    private val accounts = HashMap<Long, XtreamAccount>()
    private class Vod(val movieCategories: List<Category>, val movies: List<Movie>, val seriesCategories: List<Category>, val series: List<Series>)
    private val vod = LinkedHashMap<Long, Vod>()
    private class VodSnapshot(val movieCategories: List<Category>, val movies: List<Movie>, val seriesCategories: List<Category>, val series: List<Series>, val favorites: Set<String>)
    private val vodSnapshot = MutableStateFlow(VodSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptySet()))
    private val detailedMovies = HashMap<String, Movie>()
    private val seriesInfos = HashMap<String, SeriesInfo>()
    private val progressFlow = MutableStateFlow<List<PlaybackProgress>>(emptyList())

    // Programme guide: programmes per source, the guide key of channels whose key is not their tvg-id, and a version
    // bumped on every change so the guide flows recompute.
    private val guides = HashMap<Long, List<Programme>>()
    private val guideKeys = HashMap<String, String>()
    private val guideVersion = MutableStateFlow(0)
    private val shortEpgFetched = HashMap<String, Long>()
    override val categories: Flow<List<Category>> = snapshot.map { it.categories }.distinctUntilChanged()
    override val favoriteIds: Flow<Set<String>> = snapshot.map { it.favorites }.distinctUntilChanged()
    override val sources: Flow<List<Source>> = sourcesFlow.asStateFlow()

    override fun channels(query: ChannelQuery): Flow<PagingData<Channel>> =
        snapshot.map { s -> s.channels.filter { query.matches(it, s.favorites) } }.distinctUntilChanged().map { PagingData.from(it, COMPLETE) }

    /** One page holding the whole list: nothing more to load at either end. */
    private val COMPLETE = LoadStates(LoadState.NotLoading(false), LoadState.NotLoading(true), LoadState.NotLoading(true))

    override fun count(query: ChannelQuery): Flow<Int> =
        snapshot.map { s -> s.channels.count { query.matches(it, s.favorites) } }.distinctUntilChanged()

    override suspend fun channel(id: String): Channel? = snapshot.value.channels.firstOrNull { it.id == id }

    override suspend fun addSource(name: String, location: String, kind: SourceKind): ImportResult = mutex.withLock {
        val source = Source(nextId, name, location, kind)
        val result = import(source)
        if (result is ImportResult.Success) {
            nextId++
            sourcesFlow.update { it + result.source }
            publish()
            if (result.source.hasGuide) importGuide(result.source.id)
            return ImportResult.Success(sourcesFlow.value.first { it.id == result.source.id })
        }
        result
    }

    override suspend fun checkXtreamAccount(account: XtreamAccount): Fetched<XtreamAccountInfo> =
        xtream?.check(account) ?: Fetched.Failure("Aucun fournisseur Xtream n'est configuré.")

    override suspend fun addXtreamSource(name: String, account: XtreamAccount): ImportResult = mutex.withLock {
        val server = normalizeXtreamServer(account.server)
        if (server.isEmpty() || account.username.isBlank() || account.password.isEmpty()) return ImportResult.Failure("Serveur, identifiant et mot de passe sont requis.")
        val normalized = account.copy(server = server, username = account.username.trim())
        val source = Source(nextId, name, server, SourceKind.XTREAM, username = normalized.username)
        val result = importXtream(source, normalized)
        if (result is ImportResult.Success) {
            nextId++
            accounts[source.id] = normalized
            sourcesFlow.update { it + result.source }
            publish()
        }
        result
    }

    override suspend fun refreshSource(id: Long): ImportResult = mutex.withLock {
        val source = sourcesFlow.value.firstOrNull { it.id == id } ?: return ImportResult.Failure("Source introuvable.")
        val result = if (source.kind == SourceKind.XTREAM) {
            accounts[id]?.let { importXtream(source, it) } ?: ImportResult.Failure(CREDENTIALS_TO_REENTER)
        } else import(source)
        val updated = when (result) {
            is ImportResult.Success -> result.source
            is ImportResult.Failure -> source.copy(lastError = result.message)
        }
        sourcesFlow.update { list -> list.map { if (it.id == id) updated else it } }
        publish()
        if (result is ImportResult.Success && updated.hasGuide) {
            importGuide(id)
            return ImportResult.Success(sourcesFlow.value.first { it.id == id })
        }
        result
    }

    /** Simulates a lost Keystore key: the password of [id] can no longer be read. */
    fun forgetPassword(id: Long) { accounts.remove(id) }

    override suspend fun removeSource(id: Long) = mutex.withLock {
        playlists.remove(id)
        vod.remove(id)
        accounts.remove(id)
        guides.remove(id)
        guideVersion.update { it + 1 }
        seriesInfos.keys.removeAll { XtreamIds.sourceOf(it) == id }
        detailedMovies.keys.removeAll { XtreamIds.sourceOf(it) == id }
        progressFlow.update { list -> list.filterNot { XtreamIds.sourceOf(it.itemId) == id } }
        sourcesFlow.update { list -> list.filterNot { it.id == id } }
        publish()
        // Like the Room implementation: favourites of removed items go, those of a refreshed source stay.
        val remaining = snapshot.value.channels.mapTo(HashSet()) { it.id }
        vodSnapshot.value.movies.mapTo(remaining) { it.id }
        vodSnapshot.value.series.mapTo(remaining) { it.id }
        if (favorites.retainAll(remaining)) publish()
    }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) = mutex.withLock {
        if (favorite) favorites += itemId else favorites -= itemId
        publish()
    }

    override val movieCategories: Flow<List<Category>> = vodSnapshot.map { it.movieCategories }.distinctUntilChanged()
    override val seriesCategories: Flow<List<Category>> = vodSnapshot.map { it.seriesCategories }.distinctUntilChanged()

    override fun movies(query: VodQuery): Flow<PagingData<Movie>> =
        vodSnapshot.map { s -> s.movies.filter { query.matches(it.id, it.title, it.categoryId, s.favorites) } }.distinctUntilChanged().map { PagingData.from(it, COMPLETE) }

    override fun series(query: VodQuery): Flow<PagingData<Series>> =
        vodSnapshot.map { s -> s.series.filter { query.matches(it.id, it.title, it.categoryId, s.favorites) } }.distinctUntilChanged().map { PagingData.from(it, COMPLETE) }

    override fun countMovies(query: VodQuery): Flow<Int> =
        vodSnapshot.map { s -> s.movies.count { query.matches(it.id, it.title, it.categoryId, s.favorites) } }.distinctUntilChanged()

    override fun countSeries(query: VodQuery): Flow<Int> =
        vodSnapshot.map { s -> s.series.count { query.matches(it.id, it.title, it.categoryId, s.favorites) } }.distinctUntilChanged()

    override fun recentMovies(limit: Int): Flow<List<Movie>> =
        vodSnapshot.map { s -> s.movies.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(limit) }.distinctUntilChanged()

    override fun recentSeries(limit: Int): Flow<List<Series>> =
        vodSnapshot.map { s -> s.series.filter { it.updatedAt != null }.sortedByDescending { it.updatedAt }.take(limit) }.distinctUntilChanged()

    override suspend fun movie(id: String): Movie? = detailedMovies[id] ?: vodSnapshot.value.movies.firstOrNull { it.id == id }
    override suspend fun series(id: String): Series? = seriesInfos[id]?.series ?: vodSnapshot.value.series.firstOrNull { it.id == id }
    override suspend fun episode(id: String): Episode? = seriesInfos.values.asSequence().flatMap { it.episodes }.firstOrNull { it.id == id }

    override suspend fun movieDetails(id: String): Fetched<Movie> {
        detailedMovies[id]?.let { return Fetched.Success(it) }
        val movie = movie(id) ?: return Fetched.Failure("Film introuvable.")
        val account = accounts[movie.sourceId] ?: return Fetched.Failure(CREDENTIALS_TO_REENTER)
        val client = xtream ?: return Fetched.Failure(CREDENTIALS_TO_REENTER)
        return try {
            Fetched.Success(client.movieDetails(account, movie).also { detailedMovies[id] = it })
        } catch (error: Exception) {
            Fetched.Failure(describeXtreamError(error))
        }
    }

    override suspend fun seriesInfo(id: String, refresh: Boolean): Fetched<SeriesInfo> {
        val cached = seriesInfos[id]
        if (cached != null && !refresh) return Fetched.Success(cached)
        val series = series(id) ?: return Fetched.Failure("Série introuvable.")
        val account = accounts[series.sourceId]
        val client = xtream
        if (account == null || client == null) return cached?.let { Fetched.Success(it) } ?: Fetched.Failure(CREDENTIALS_TO_REENTER)
        return try {
            Fetched.Success(client.seriesDetails(account, series).also { seriesInfos[id] = it })
        } catch (error: Exception) {
            cached?.let { Fetched.Success(it) } ?: Fetched.Failure(describeXtreamError(error))
        }
    }

    override suspend fun playbackUrl(streamUrl: String): String? {
        val ref = XtreamStreamRef.parse(streamUrl) ?: return streamUrl
        return accounts[ref.sourceId]?.let { ref.resolve(it) }
    }

    override val progress: Flow<List<PlaybackProgress>> = progressFlow.asStateFlow()
    override suspend fun progress(itemId: String): PlaybackProgress? = progressFlow.value.firstOrNull { it.itemId == itemId }
    override suspend fun saveProgress(itemId: String, positionMs: Long, durationMs: Long) {
        val entry = PlaybackProgress(itemId, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), System.currentTimeMillis())
        progressFlow.update { list -> listOf(entry) + list.filterNot { it.itemId == itemId } }
    }
    override suspend fun clearProgress(itemId: String) { progressFlow.update { list -> list.filterNot { it.itemId == itemId } } }

    private suspend fun import(source: Source): ImportResult = try {
        val parsed = loader.open(source.location, source.kind).use { M3uParser(source.id).parse(it) }
        if (parsed.channels.isEmpty()) ImportResult.Failure("Aucune chaîne trouvée dans cette playlist.")
        else {
            playlists[source.id] = parsed
            // The address the user set wins over the header's.
            ImportResult.Success(source.copy(lastRefreshed = clock(), channelCount = parsed.channels.size, lastError = null, epgUrl = source.epgUrl ?: parsed.epgUrl))
        }
    } catch (error: Exception) {
        ImportResult.Failure(describeImportError(error))
    }

    // --- Programme guide ---

    override suspend fun setEpgUrl(sourceId: Long, url: String?): ImportResult = mutex.withLock {
        val source = sourcesFlow.value.firstOrNull { it.id == sourceId } ?: return ImportResult.Failure("Source introuvable.")
        if (source.kind == SourceKind.XTREAM) return ImportResult.Failure("Le guide d'un fournisseur Xtream vient de son compte.")
        val clean = url?.trim()?.ifEmpty { null }
        if (clean != null && !clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return ImportResult.Failure("Seules les adresses http:// et https:// sont acceptées.")
        }
        if (clean == null) {
            guides.remove(sourceId)
            updateSource(sourceId) { it.copy(epgUrl = null, epgRefreshedAt = null, epgError = null, programmeCount = 0) }
            guideVersion.update { it + 1 }
            return ImportResult.Success(sourcesFlow.value.first { it.id == sourceId })
        }
        updateSource(sourceId) { it.copy(epgUrl = clean) }
        importGuide(sourceId)
    }

    override suspend fun refreshEpg(sourceId: Long): ImportResult = mutex.withLock { importGuide(sourceId) }

    private fun updateSource(id: Long, change: (Source) -> Source) = sourcesFlow.update { list -> list.map { if (it.id == id) change(it) else it } }

    /** Guide key of a channel: a matched guide channel, else its tvg-id. */
    private fun keyOf(channel: Channel): String? = guideKeys[channel.id] ?: channel.tvgId

    /** Same contract as the Room implementation: XMLTV of the playlist, or `xmltv.php` kept to the favourites. Callers hold [mutex]. */
    private suspend fun importGuide(sourceId: Long): ImportResult {
        val source = sourcesFlow.value.firstOrNull { it.id == sourceId } ?: return ImportResult.Failure("Source introuvable.")
        val now = clock()
        try {
            val channels = playlists[sourceId]?.channels.orEmpty()
            val location: String
            var filter: ((String) -> Boolean)? = null
            if (source.kind == SourceKind.XTREAM) {
                val account = accounts[sourceId] ?: throw EpgException(CREDENTIALS_TO_REENTER)
                val keys = channels.filter { it.id in favorites }.mapNotNullTo(HashSet()) { keyOf(it) }
                if (keys.isEmpty()) {
                    guides.remove(sourceId)
                    updateSource(sourceId) { it.copy(epgRefreshedAt = now, epgError = null, programmeCount = 0) }
                    guideVersion.update { it + 1 }
                    shortEpgFetched.clear()
                    return ImportResult.Success(sourcesFlow.value.first { it.id == sourceId })
                }
                location = account.xmltvUrl()
                filter = { it in keys }
            } else {
                location = source.epgUrl ?: return ImportResult.Failure("Aucune adresse de guide pour cette source.")
            }
            val programmes = ArrayList<Programme>()
            val parser = XmltvParser(sourceId, fromMs = now - EpgWindow.BEFORE_MS, toMs = now + EpgWindow.AFTER_MS, channelFilter = filter)
            val result = loader.open(location, SourceKind.URL).use { input -> parser.parse(input) { programmes += it } }
            if (source.kind != SourceKind.XTREAM) {
                val guideIds = result.channels.mapTo(HashSet()) { it.id }
                val byName = guideNameIndex(result.channels)
                for (channel in channels) {
                    val key = guideKey(channel.tvgId, channel.name, guideIds, byName)
                    if (key != null && key != channel.tvgId) guideKeys[channel.id] = key else guideKeys.remove(channel.id)
                }
            }
            guides[sourceId] = programmes
            purge(now)
            updateSource(sourceId) { it.copy(epgRefreshedAt = now, epgError = null, programmeCount = guides[sourceId]?.size ?: 0) }
            guideVersion.update { it + 1 }
            shortEpgFetched.clear()
            return ImportResult.Success(sourcesFlow.value.first { it.id == sourceId })
        } catch (error: Exception) {
            val message = describeEpgError(error)
            updateSource(sourceId) { it.copy(epgError = message) }
            return ImportResult.Failure(message)
        }
    }

    private fun purge(now: Long) {
        val limit = now - EpgWindow.PURGE_AFTER_MS
        for ((id, list) in guides.entries) guides[id] = list.filter { it.stopMs >= limit }
    }

    /** Programmes of a source by guide key, each list in start order. */
    private fun byKey(sourceId: Long): Map<String, List<Programme>> = guides[sourceId].orEmpty().groupBy { it.channelKey }.mapValues { (_, list) -> list.sortedBy { it.startMs } }

    override fun nowAndNext(nowMs: Long): Flow<Map<String, NowNext>> = combine(snapshot, guideVersion) { s, _ ->
        val indices = HashMap<Long, Map<String, List<Programme>>>()
        val out = HashMap<String, NowNext>()
        for (channel in s.channels) {
            val key = keyOf(channel) ?: continue
            val list = indices.getOrPut(channel.sourceId) { byKey(channel.sourceId) }[key] ?: continue
            val now = list.firstOrNull { it.isLive(nowMs) } ?: continue
            out[channel.id] = NowNext(now, list.firstOrNull { it.startMs >= now.stopMs })
        }
        out
    }

    override fun programmes(channelId: String, fromMs: Long, toMs: Long): Flow<List<Programme>> = combine(snapshot, guideVersion) { s, _ ->
        val channel = s.channels.firstOrNull { it.id == channelId } ?: return@combine emptyList()
        val key = keyOf(channel) ?: return@combine emptyList()
        byKey(channel.sourceId)[key].orEmpty().filter { it.stopMs > fromMs && it.startMs < toMs }
    }

    private fun hits(s: Snapshot, text: String, nowMs: Long): List<ProgrammeHit> {
        if (text.isBlank()) return emptyList()
        val needle = searchKey(text)
        val out = ArrayList<ProgrammeHit>()
        val indices = HashMap<Long, Map<String, List<Programme>>>()
        for (channel in s.channels) {
            val key = keyOf(channel) ?: continue
            for (programme in indices.getOrPut(channel.sourceId) { byKey(channel.sourceId) }[key].orEmpty()) {
                if (programme.stopMs > nowMs && searchKey(programme.title).contains(needle)) out += ProgrammeHit(programme, channel)
            }
        }
        return out.sortedWith(compareBy({ it.programme.startMs }, { it.channel.id }))
    }

    override fun searchProgrammes(text: String, nowMs: Long, limit: Int): Flow<List<ProgrammeHit>> = combine(snapshot, guideVersion) { s, _ -> hits(s, text, nowMs).take(limit) }

    override fun countProgrammes(text: String, nowMs: Long): Flow<Int> = combine(snapshot, guideVersion) { s, _ -> hits(s, text, nowMs).size }

    override suspend fun ensureShortEpg(channelId: String): Boolean = mutex.withLock {
        val channel = snapshot.value.channels.firstOrNull { it.id == channelId } ?: return false
        val ref = XtreamStreamRef.parse(channel.streamUrl) ?: return false
        val key = keyOf(channel) ?: "stream:${ref.streamId}".also { guideKeys[channelId] = it }
        val now = clock()
        val fetchedAt = shortEpgFetched[channelId]
        val running = guides[ref.sourceId].orEmpty().any { it.channelKey == key && it.stopMs > now }
        if (running && (fetchedAt == null || now - fetchedAt < EpgWindow.SHORT_EPG_TTL_MS)) return false
        val account = accounts[ref.sourceId] ?: return false
        val client = xtream ?: return false
        val fetched = try { client.shortEpg(account, ref.sourceId, ref.streamId, key, EpgWindow.SHORT_EPG_LIMIT) } catch (_: Exception) { return false }
        shortEpgFetched[channelId] = now
        if (fetched.isEmpty()) return false
        val starts = fetched.mapTo(HashSet()) { it.startMs }
        guides[ref.sourceId] = guides[ref.sourceId].orEmpty().filterNot { it.channelKey == key && it.startMs in starts } + fetched
        guideVersion.update { it + 1 }
        true
    }

    override suspend fun purgeProgrammes() = mutex.withLock {
        purge(clock())
        guideVersion.update { it + 1 }
    }

    private suspend fun importXtream(source: Source, account: XtreamAccount): ImportResult {
        val client = xtream ?: return ImportResult.Failure("Aucun fournisseur Xtream n'est configuré.")
        return try {
            val info = client.account(account)
            info.blocker()?.let { return ImportResult.Failure(it) }
            val live = client.liveCatalog(account, source.id, info.liveExtension)
            val catalog = Vod(client.movieCategories(account, source.id), client.movies(account, source.id), client.seriesCategories(account, source.id), client.series(account, source.id))
            if (live.channels.isEmpty() && catalog.movies.isEmpty() && catalog.series.isEmpty()) return ImportResult.Failure("Ce compte ne donne accès à aucun contenu.")
            playlists[source.id] = live
            vod[source.id] = catalog
            // The season/episode cache and film details of this source are refreshed with it.
            seriesInfos.keys.removeAll { XtreamIds.sourceOf(it) == source.id }
            detailedMovies.keys.removeAll { XtreamIds.sourceOf(it) == source.id }
            ImportResult.Success(source.copy(
                lastRefreshed = System.currentTimeMillis(), channelCount = live.channels.size, lastError = null,
                expiresAt = info.expiresAt, movieCount = catalog.movies.size, seriesCount = catalog.series.size
            ))
        } catch (error: Exception) {
            ImportResult.Failure(describeXtreamError(error))
        }
    }

    private fun publish() {
        val categories = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>()
        val movieCategories = ArrayList<Category>()
        val movies = ArrayList<Movie>()
        val seriesCategories = ArrayList<Category>()
        val series = ArrayList<Series>()
        for (id in sourcesFlow.value.map { it.id }) {
            playlists[id]?.let { parsed ->
                parsed.categories.forEach { categories.putIfAbsent(it.id, it) }
                channels += parsed.channels
            }
            vod[id]?.let { catalog ->
                movieCategories += catalog.movieCategories; movies += catalog.movies
                seriesCategories += catalog.seriesCategories; series += catalog.series
            }
        }
        val starred = favorites.toSet()
        snapshot.value = Snapshot(categories.values.toList(), channels, starred)
        vodSnapshot.value = VodSnapshot(movieCategories, movies, seriesCategories, series, starred)
    }
}
