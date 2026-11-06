package fr.manitra.miptv

import java.util.Locale

/**
 * Every user-facing text of the app, one implementation per language ([FrenchTexts], [EnglishTexts], [SpanishTexts]).
 * A language is added by implementing this interface and listing it in [Language]: the compiler then reports any text
 * left untranslated. Read through [tr], which follows the language of the device (see [I18n]).
 */
interface Texts {
    val locale: Locale
    val datePattern: String
    val dayPattern: String

    val home: String
    val tv: String
    val movies: String
    val series: String
    val search: String
    val sources: String
    /** Title of the settings screen (sources, language), behind the gear. */
    val settings: String
    val channels: String
    val programmes: String
    val categories: String
    val category: String
    val allCategory: String
    val favorites: String
    val noCategory: String
    val untitled: String
    val unnamedChannel: String
    fun categoryFallback(id: String): String
    fun channelFallback(id: Long): String
    fun movieFallback(id: Long): String
    fun seriesFallback(id: Long): String
    fun seasonFallback(number: Int): String
    fun episodeFallback(number: Int): String
    val playlistDefaultName: String

    val tagline: String
    fun version(name: String): String
    fun byAuthor(author: String): String

    val back: String
    val close: String
    val clear: String
    val add: String
    val refresh: String
    val delete: String
    val show: String
    val hide: String
    val share: String
    val retry: String
    val loading: String

    val discover: String
    fun homeCatalogXtream(channels: Int, movies: Int, series: Int, sources: Int): String
    fun homeCatalogPlaylists(channels: Int, categories: Int, sources: Int): String
    val homeEmpty: String
    val exploreTv: String
    val filmVisual: String
    val seriesVisual: String
    val filmPoster: String
    val newBadge: String
    val newEpisodesBadge: String
    val liveBadge: String
    val nowBadge: String
    val play: String
    val pause: String
    val watch: String
    val watchNow: String
    val watchVerb: String
    val resume: String
    val restart: String
    val details: String
    val viewSeries: String
    val favoriteChannels: String
    val programmeGuide: String
    val addFavorite: String
    val removeFavorite: String
    val myList: String
    val inMyList: String

    val searchHintAll: String
    val searchHintGuide: String
    val searchHintChannels: String
    fun localSearchAll(channels: Int, movies: Int, series: Int): String
    fun localSearchChannels(channels: Int): String
    val noResult: String
    val noChannel: String
    val noMovie: String
    val noSeries: String
    val noProgramme: String

    val liveTv: String
    val noSourceConfigured: String
    val noSourceHint: String
    val addSource: String
    val coverView: String
    val listView: String
    val backToCategories: String
    val searchInCategory: String
    val changeLayout: String
    val filterChannels: String
    val filterMovies: String
    val filterSeries: String
    fun channelCount(count: Int): String
    val noChannelMatches: String
    val noFavoriteChannel: String
    val noChannelInCategory: String
    val nowPlaying: String
    val closePlayer: String
    val minimizePlayer: String
    val mute: String
    val unmute: String
    val fullscreen: String
    val exitFullscreen: String
    val previousChannel: String
    val nextChannel: String
    val nextEpisode: String

    val noMovieMatches: String
    val noMovieInList: String
    val noMovieInCategory: String
    val noSeriesMatches: String
    val noSeriesInList: String
    val noSeriesInCategory: String
    val loadingSheet: String
    val loadingSeasons: String
    val noSynopsis: String
    val noEpisode: String
    fun seasonCount(count: Int): String
    fun episodeCount(count: Int): String
    val watched: String
    val continueWatching: String
    fun timeLeft(duration: String): String
    val recentMovies: String
    val recentSeries: String
    val noXtreamProvider: String
    val noProviderMovies: String
    val noProviderSeries: String
    val addProvider: String

    val guideLimited: String
    val noProgrammeState: String
    val loadingGuide: String
    fun guideEndsAt(day: String, time: String): String
    val noGuideForSource: String
    val noProgrammeXtream: String
    val noProgrammeToday: String
    val onNow: String
    val today: String
    val tomorrow: String
    fun upNext(time: String, title: String): String
    val epgFieldLabel: String
    val epgPlaceholder: String
    val epgFieldHint: String
    val noGuide: String
    val guideNeverLoaded: String
    val favoritesGuideNeverLoaded: String
    fun guideStatus(favorites: Boolean, programmes: Int, refreshed: String): String
    val removeGuide: String
    val saveGuide: String
    val refreshGuide: String

    val sourcesIntro: String
    fun configuredSources(count: Int): String
    val noSourceYet: String

    /** Language picker of the settings. */
    val language: String
    fun systemLanguage(name: String): String

    /** Theme picker of the settings. */
    val theme: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val themeGlass: String
    val m3uPlaylist: String
    val xtreamProvider: String
    val newSource: String
    val nameOptional: String
    val playlistAddress: String
    val playlistPlaceholder: String
    val addAddress: String
    val m3uFile: String
    val server: String
    val serverPlaceholder: String
    val username: String
    val password: String
    val testConnection: String
    fun accountStatus(status: String, validity: String): String
    fun until(date: String): String
    val noExpiry: String
    fun connections(active: Int, max: Int): String
    fun formats(list: String): String
    val address: String
    val localFile: String
    fun accountValidUntil(date: String): String
    val accountWithoutExpiry: String
    fun sourceCountsXtream(channels: Int, movies: Int, series: Int): String
    fun refreshedOn(date: String): String
    val neverRefreshed: String

    val playerReadyHint: String
    val playbackEnded: String
    val waiting: String
    val liveBehindResuming: String
    val loadingTimeout: String
    val sourceCredentialsToReenter: String
    val streamConnectionFailed: String
    fun streamRefusedHttp(code: Int): String
    val streamRefused: String
    val streamNotFound: String
    val streamAccessDenied: String
    val streamFormatUnknown: String
    val streamCannotDecode: String
    val playbackUnavailable: String

    fun durationHours(hours: Int, minutes: String): String
    fun durationMinutes(minutes: Int): String
    fun resumeAt(duration: String): String

    val accountDisabled: String
    fun accountExpiredOn(date: String): String
    val accountExpired: String
    fun maxConnectionsReached(active: Int, max: Int): String
    val credentialsToReenter: String
    val secureStorageUnavailable: String
    val secureStorageUnavailableOnDevice: String
    val credentialsRequired: String
    val invalidAddressScheme: String
    val onlyHttp: String
    val importing: String
    fun importedXtream(channels: Int, movies: Int, series: Int, source: String): String
    fun importedChannels(channels: Int, source: String): String
    fun guideRemoved(source: String): String
    fun guideFollowsFavorites(source: String): String
    fun guideProgrammes(count: Int, source: String): String
    val sourceNotFound: String
    val movieNotFound: String
    val seriesNotFound: String
    val xtreamNeedsCredentials: String
    val xtreamGuideFromAccount: String
    val noGuideAddress: String
    val notXtreamSource: String
    val noChannelInPlaylist: String
    val accountNoContent: String
    val noXtreamConfigured: String

    fun invalidRedirect(code: Int): String
    val tooManyRedirects: String
    val fileInaccessible: String
    val xtreamNotPlaylist: String
    val importFailed: String
    val hostNotFound: String
    val timeout: String
    val secureConnectionFailed: String
    val invalidAddress: String
    fun serverHttp(code: Int): String
    val playlistReadFailed: String

    val guideNotXmltv: String
    val guideTruncated: String
    fun guideServerHttp(code: Int): String
    val guideReadFailed: String
    val guideImportFailed: String

    val credentialsRefused: String
    val notXtreamPortal: String
    fun credentialsRefusedHttp(code: Int): String
    val xtreamApiNotFound: String
    val tooManyRequests: String
    fun providerServerError(code: Int): String
    val catalogReadFailed: String
    val providerConnectionFailed: String
}
