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
    const val USER_AGENT = "miptv/0.3 (Android)"
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
class MemoryChannelRepository(private val loader: PlaylistLoader) : ChannelRepository {
    private val mutex = Mutex()
    private val playlists = LinkedHashMap<Long, ParsedPlaylist>()
    private val favorites = LinkedHashSet<String>()
    private var nextId = 1L
    private val sourcesFlow = MutableStateFlow<List<Source>>(emptyList())
    private class Snapshot(val categories: List<Category>, val channels: List<Channel>, val favorites: Set<String>)
    private val snapshot = MutableStateFlow(Snapshot(emptyList(), emptyList(), emptySet()))
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
        }
        result
    }

    override suspend fun refreshSource(id: Long): ImportResult = mutex.withLock {
        val source = sourcesFlow.value.firstOrNull { it.id == id } ?: return ImportResult.Failure("Source introuvable.")
        val result = import(source)
        val updated = when (result) {
            is ImportResult.Success -> result.source
            is ImportResult.Failure -> source.copy(lastError = result.message)
        }
        sourcesFlow.update { list -> list.map { if (it.id == id) updated else it } }
        publish()
        result
    }

    override suspend fun removeSource(id: Long) = mutex.withLock {
        playlists.remove(id)
        sourcesFlow.update { list -> list.filterNot { it.id == id } }
        publish()
        // Like the Room implementation: favourites of removed channels go, those of a refreshed source stay.
        val remaining = snapshot.value.channels.mapTo(HashSet()) { it.id }
        if (favorites.retainAll(remaining)) publish()
    }

    override suspend fun setFavorite(channelId: String, favorite: Boolean) = mutex.withLock {
        if (favorite) favorites += channelId else favorites -= channelId
        publish()
    }

    private suspend fun import(source: Source): ImportResult = try {
        val parsed = loader.open(source.location, source.kind).use { M3uParser(source.id).parse(it) }
        if (parsed.channels.isEmpty()) ImportResult.Failure("Aucune chaîne trouvée dans cette playlist.")
        else {
            playlists[source.id] = parsed
            ImportResult.Success(source.copy(lastRefreshed = System.currentTimeMillis(), channelCount = parsed.channels.size, lastError = null))
        }
    } catch (error: Exception) {
        ImportResult.Failure(describeImportError(error))
    }

    private fun publish() {
        val categories = LinkedHashMap<String, Category>()
        val channels = ArrayList<Channel>()
        for (id in sourcesFlow.value.map { it.id }) {
            val parsed = playlists[id] ?: continue
            parsed.categories.forEach { categories.putIfAbsent(it.id, it) }
            channels += parsed.channels
        }
        snapshot.value = Snapshot(categories.values.toList(), channels, favorites.toSet())
    }
}
