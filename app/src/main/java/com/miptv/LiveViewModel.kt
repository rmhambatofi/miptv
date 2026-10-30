package com.miptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Destination(val label: String) { HOME("Accueil"), TV("TV"), MOVIES("Films"), SERIES("Séries"), SEARCH("Recherche") }

data class ImportStatus(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

/**
 * What the single player opens: a live channel, a film or an episode. [streamUrl] may be an `xtream://` reference,
 * resolved with the source's credentials when playback starts, never before.
 */
data class PlayItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val streamUrl: String,
    val live: Boolean,
    val userAgent: String? = null,
    val referer: String? = null,
    /** Where to start, for a film or episode being resumed. */
    val resumeMs: Long = 0L
)

fun Channel.toPlayItem() = PlayItem(id, name, null, streamUrl, live = true, userAgent = userAgent, referer = referer)

/** The film sheet: the list entry at once, then the provider details (synopsis, duration, year). */
data class MovieSheet(val movie: Movie, val loading: Boolean = false, val error: String? = null)

/** The series sheet: seasons and episodes come from the cache or the provider; [season] is the one shown. */
data class SeriesSheet(val series: Series, val info: SeriesInfo? = null, val season: Int? = null, val loading: Boolean = false, val error: String? = null) {
    val episodes: List<Episode> get() = info?.episodes?.filter { it.season == season }.orEmpty()
    /** The episode after [current] in series order, across seasons. */
    fun next(current: Episode): Episode? = info?.episodes?.let { all -> all.getOrNull(all.indexOfFirst { it.id == current.id } + 1).takeIf { all.any { e -> e.id == current.id } } }
}

/** A film or episode with a resume position, for the home row. */
/** The guide of one channel for one local day; [focusMs] is the programme to scroll to (the one on air, or a search hit). */
/** [guideEndMs]: end of the channel's last known programme, null without any; days from there on are empty. */
data class GuideSheet(val channel: Channel, val dayStartMs: Long, val programmes: List<Programme> = emptyList(), val loading: Boolean = true, val focusMs: Long? = null, val guideEndMs: Long? = null)

data class ResumeItem(val item: PlayItem, val posterUrl: String?, val progress: PlaybackProgress, val movieId: String? = null, val seriesId: String? = null)

data class LiveState(
    /** Imported categories; [categories] adds the pseudo-categories. */
    val imported: List<Category> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val sources: List<Source> = emptyList(),
    /** False until the stored sources have been read once; avoids flashing the "no source" screen. */
    val ready: Boolean = false,
    val categoryId: String = Category.ALL.id,
    val selected: Channel? = null,
    val destination: Destination = Destination.HOME,
    val query: String = "",
    /** Name filter applied inside the TV tab, on top of the selected category. */
    val filter: String = "",
    /** The TV tab shows the channels of [categoryId] rather than the list of categories. */
    val channelsOpen: Boolean = false,
    val sourcesOpen: Boolean = false,
    /** Only meaningful with something playing; cleared whenever playback is cleared. */
    val fullscreen: Boolean = false,
    val importStatus: ImportStatus = ImportStatus(),
    val channelCount: Int = 0,
    /** Size of the TV list ([channelQuery]) and of the search results ([searchQuery]). */
    val visibleCount: Int = 0,
    val searchCount: Int = 0,
    // Films and series (Sprint 3).
    val movieCategories: List<Category> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val movieCategoryId: String = Category.ALL.id,
    val movieFilter: String = "",
    val seriesCategoryId: String = Category.ALL.id,
    val seriesFilter: String = "",
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val visibleMovieCount: Int = 0,
    val visibleSeriesCount: Int = 0,
    val searchMovieCount: Int = 0,
    val searchSeriesCount: Int = 0,
    val movieSheet: MovieSheet? = null,
    val seriesSheet: SeriesSheet? = null,
    /** Film or episode playing; exclusive with [selected]. */
    val vod: PlayItem? = null,
    val progress: Map<String, PlaybackProgress> = emptyMap(),
    val resume: List<ResumeItem> = emptyList(),
    val recentMovies: List<Movie> = emptyList(),
    val recentSeries: List<Series> = emptyList(),
    /** Result of the connection test on the Xtream form. */
    val accountCheck: Fetched<XtreamAccountInfo>? = null,
    val checkingAccount: Boolean = false,
    // Programme guide (Sprint 4). [nowMs] moves once a minute while the screen is shown.
    val nowMs: Long = 0L,
    val nowNext: Map<String, NowNext> = emptyMap(),
    val favoriteChannels: List<Channel> = emptyList(),
    val guideSheet: GuideSheet? = null,
    val searchProgrammes: List<ProgrammeHit> = emptyList(),
    val searchProgrammeCount: Int = 0
) {
    val categories: List<Category> get() = listOf(Category.ALL, Category.FAVORITES) + imported
    val movieCategoryList: List<Category> get() = listOf(Category.ALL, Category.FAVORITES) + movieCategories
    val seriesCategoryList: List<Category> get() = listOf(Category.ALL, Category.FAVORITES) + seriesCategories
    val hasSources: Boolean get() = sources.isNotEmpty()
    val hasXtream: Boolean get() = sources.any { it.kind == SourceKind.XTREAM }
    val categoryNames: Map<String, String> by lazy { (categories + movieCategories + seriesCategories).associate { it.id to it.name } }
    val channelQuery: ChannelQuery get() = ChannelQuery(categoryId, filter.trim())
    val searchQuery: ChannelQuery get() = ChannelQuery(text = query.trim())
    val movieQuery: VodQuery get() = VodQuery(movieCategoryId, movieFilter.trim())
    val seriesQuery: VodQuery get() = VodQuery(seriesCategoryId, seriesFilter.trim())
    val searchVodQuery: VodQuery get() = VodQuery(text = query.trim())
    /** The one thing playing, if any. */
    val playItem: PlayItem? get() = selected?.toPlayItem() ?: vod
    /** At least one source can show programmes: the Recherche tab then has a « Programmes » group. */
    val hasGuide: Boolean get() = sources.any { it.hasGuide }
    /** Favourite channels with something on air, for the home row. */
    val onAirFavorites: List<Pair<Channel, NowNext>> get() = favoriteChannels.mapNotNull { channel -> nowNext[channel.id]?.let { channel to it } }
    /** The days the guide sheet offers: today and the three next ones, as local midnights. */
    val guideDays: List<Long> get() = guideDaysFrom(nowMs)
    /** What the selected channel airs, for the player header. */
    val selectedNowNext: NowNext? get() = selected?.let { nowNext[it.id] }
    fun isFavorite(channel: Channel): Boolean = channel.id in favoriteIds
    fun isFavorite(id: String): Boolean = id in favoriteIds
    /** Unfinished position of a film or episode, or null. */
    fun resumable(id: String): PlaybackProgress? = progress[id]?.takeIf { !it.finished && it.positionMs > 0 }
}

class LiveViewModel(
    private val repository: ChannelRepository,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {
    private val mutable = MutableStateFlow(LiveState(
        nowMs = clock(),
        categoryId = saved.get<String>("category") ?: Category.ALL.id,
        destination = Destination.entries.firstOrNull { it.name == saved.get<String>("destination") } ?: Destination.HOME,
        query = saved.get<String>("query").orEmpty(),
        filter = saved.get<String>("filter").orEmpty(),
        sourcesOpen = saved.get<Boolean>("sourcesOpen") ?: false,
        channelsOpen = saved.get<Boolean>("channelsOpen") ?: false,
        movieCategoryId = saved.get<String>("movieCategory") ?: Category.ALL.id,
        movieFilter = saved.get<String>("movieFilter").orEmpty(),
        seriesCategoryId = saved.get<String>("seriesCategory") ?: Category.ALL.id,
        seriesFilter = saved.get<String>("seriesFilter").orEmpty()
    ))
    val state = mutable.asStateFlow()

    /** Paged lists for the TV tab, the search tab and the home preview; they follow the state's queries. */
    val channels: Flow<PagingData<Channel>> = paged { it.channelQuery }
    val searchResults: Flow<PagingData<Channel>> = paged { it.searchQuery }
    val homeChannels: Flow<PagingData<Channel>> = repository.channels(ChannelQuery()).cachedIn(viewModelScope)
    val movies: Flow<PagingData<Movie>> = pagedMovies { it.movieQuery }
    val series: Flow<PagingData<Series>> = pagedSeries { it.seriesQuery }
    val searchMovies: Flow<PagingData<Movie>> = pagedMovies { it.searchVodQuery }
    val searchSeries: Flow<PagingData<Series>> = pagedSeries { it.searchVodQuery }

    init {
        viewModelScope.launch { repository.categories.collect { categories -> mutable.update { it.withCategories(categories) } } }
        viewModelScope.launch { repository.movieCategories.collect { categories -> mutable.update { it.withMovieCategories(categories) } } }
        viewModelScope.launch { repository.seriesCategories.collect { categories -> mutable.update { it.withSeriesCategories(categories) } } }
        viewModelScope.launch {
            repository.favoriteIds.collect { ids ->
                // Favourite channels are few: read them once per change for the « En ce moment » row (film and series ids yield null).
                val channels = ids.mapNotNull { repository.channel(it) }
                mutable.update { it.copy(favoriteIds = ids, favoriteChannels = channels) }
            }
        }
        viewModelScope.launch { repository.purgeProgrammes() }
        viewModelScope.launch {
            mutable.map { it.nowMs }.distinctUntilChanged().flatMapLatest { repository.nowAndNext(it) }
                .collect { map -> mutable.update { it.copy(nowNext = map) } }
        }
        viewModelScope.launch {
            mutable.map { it.query.trim() to it.nowMs }.distinctUntilChanged().flatMapLatest { (text, now) -> repository.searchProgrammes(text, now) }
                .collect { hits -> mutable.update { it.copy(searchProgrammes = hits) } }
        }
        viewModelScope.launch {
            mutable.map { it.query.trim() to it.nowMs }.distinctUntilChanged().flatMapLatest { (text, now) -> repository.countProgrammes(text, now) }
                .collect { n -> mutable.update { it.copy(searchProgrammeCount = n) } }
        }
        viewModelScope.launch {
            mutable.map { it.guideSheet?.let { sheet -> sheet.channel.id to sheet.dayStartMs } }.distinctUntilChanged()
                .flatMapLatest { key -> if (key == null) flowOf(null) else repository.programmes(key.first, key.second, key.second + DAY_MS).map { key to it } }
                .collect { loaded ->
                    if (loaded == null) return@collect
                    mutable.update { state ->
                        val sheet = state.guideSheet
                        if (sheet == null || sheet.channel.id != loaded.first.first || sheet.dayStartMs != loaded.first.second) state
                        else state.copy(guideSheet = sheet.copy(programmes = loaded.second, loading = false))
                    }
                }
        }
        viewModelScope.launch {
            mutable.map { it.guideSheet?.channel?.id }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else repository.guideEnd(id).map { id to it } }
                .collect { end ->
                    if (end == null) return@collect
                    mutable.update { state ->
                        val sheet = state.guideSheet
                        if (sheet == null || sheet.channel.id != end.first) state else state.copy(guideSheet = sheet.copy(guideEndMs = end.second))
                    }
                }
        }
        viewModelScope.launch {
            repository.sources.collect { sources ->
                mutable.update { it.copy(sources = sources, ready = true) }
                // Every import or removal touches the sources: re-read what plays or is open, which may be gone or renamed.
                revalidateSelection()
                revalidateSheets()
            }
        }
        viewModelScope.launch { repository.recentMovies(RECENT).collect { list -> mutable.update { it.copy(recentMovies = list) } } }
        viewModelScope.launch { repository.recentSeries(RECENT).collect { list -> mutable.update { it.copy(recentSeries = list) } } }
        viewModelScope.launch {
            repository.progress.collect { list ->
                val resume = list.asSequence().filter { !it.finished && it.positionMs > 0 }.take(RECENT).toList().mapNotNull { resumeItem(it) }
                mutable.update { it.copy(progress = list.associateBy { p -> p.itemId }, resume = resume) }
            }
        }
        counted({ ChannelQuery() }) { state, n -> state.copy(channelCount = n) }
        counted({ it.channelQuery }) { state, n -> state.copy(visibleCount = n) }
        counted({ it.searchQuery }) { state, n -> state.copy(searchCount = n) }
        countedVod({ VodQuery() }, repository::countMovies) { state, n -> state.copy(movieCount = n) }
        countedVod({ VodQuery() }, repository::countSeries) { state, n -> state.copy(seriesCount = n) }
        countedVod({ it.movieQuery }, repository::countMovies) { state, n -> state.copy(visibleMovieCount = n) }
        countedVod({ it.seriesQuery }, repository::countSeries) { state, n -> state.copy(visibleSeriesCount = n) }
        countedVod({ it.searchVodQuery }, repository::countMovies) { state, n -> state.copy(searchMovieCount = n) }
        countedVod({ it.searchVodQuery }, repository::countSeries) { state, n -> state.copy(searchSeriesCount = n) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun paged(query: (LiveState) -> ChannelQuery): Flow<PagingData<Channel>> =
        mutable.map(query).distinctUntilChanged().flatMapLatest { repository.channels(it) }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun pagedMovies(query: (LiveState) -> VodQuery): Flow<PagingData<Movie>> =
        mutable.map(query).distinctUntilChanged().flatMapLatest { repository.movies(it) }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun pagedSeries(query: (LiveState) -> VodQuery): Flow<PagingData<Series>> =
        mutable.map(query).distinctUntilChanged().flatMapLatest { repository.series(it) }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun counted(query: (LiveState) -> ChannelQuery, apply: (LiveState, Int) -> LiveState) {
        viewModelScope.launch {
            mutable.map(query).distinctUntilChanged().flatMapLatest { repository.count(it) }.distinctUntilChanged()
                .collect { n -> mutable.update { apply(it, n) } }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun countedVod(query: (LiveState) -> VodQuery, count: (VodQuery) -> Flow<Int>, apply: (LiveState, Int) -> LiveState) {
        viewModelScope.launch {
            mutable.map(query).distinctUntilChanged().flatMapLatest { count(it) }.distinctUntilChanged()
                .collect { n -> mutable.update { apply(it, n) } }
        }
    }

    private fun LiveState.withCategories(imported: List<Category>): LiveState {
        // No SavedStateHandle write here: collectors may run off the main thread, and an unknown saved category is re-validated on restore.
        val known = categoryId == Category.ALL.id || categoryId == Category.FAVORITES.id || imported.any { it.id == categoryId }
        return copy(imported = imported, categoryId = if (known) categoryId else Category.ALL.id)
    }

    private fun LiveState.withMovieCategories(list: List<Category>): LiveState {
        val known = movieCategoryId == Category.ALL.id || movieCategoryId == Category.FAVORITES.id || list.any { it.id == movieCategoryId }
        return copy(movieCategories = list, movieCategoryId = if (known) movieCategoryId else Category.ALL.id)
    }

    private fun LiveState.withSeriesCategories(list: List<Category>): LiveState {
        val known = seriesCategoryId == Category.ALL.id || seriesCategoryId == Category.FAVORITES.id || list.any { it.id == seriesCategoryId }
        return copy(seriesCategories = list, seriesCategoryId = if (known) seriesCategoryId else Category.ALL.id)
    }

    private suspend fun revalidateSelection() {
        val current = mutable.value.selected ?: return
        val fresh = repository.channel(current.id)
        mutable.update { state ->
            if (state.selected?.id != current.id) state
            else state.copy(selected = fresh, fullscreen = state.fullscreen && fresh != null)
        }
    }

    /** An open film or series that vanished with its source closes; a playing one stops. */
    private suspend fun revalidateSheets() {
        val state = mutable.value
        val movieGone = state.movieSheet != null && repository.movie(state.movieSheet.movie.id) == null
        val seriesGone = state.seriesSheet != null && repository.series(state.seriesSheet.series.id) == null
        val vodGone = state.vod != null && XtreamIds.sourceOf(state.vod.id)?.let { id -> state.sources.none { it.id == id } } == true
        if (movieGone || seriesGone || vodGone) mutable.update {
            it.copy(
                movieSheet = if (movieGone) null else it.movieSheet,
                seriesSheet = if (seriesGone) null else it.seriesSheet,
                vod = if (vodGone || movieGone || seriesGone) null else it.vod,
                fullscreen = it.fullscreen && !(vodGone || movieGone || seriesGone)
            )
        }
    }

    private suspend fun resumeItem(progress: PlaybackProgress): ResumeItem? {
        repository.movie(progress.itemId)?.let { movie ->
            return ResumeItem(movie.toPlayItem(progress.positionMs), movie.posterUrl, progress, movieId = movie.id)
        }
        val episode = repository.episode(progress.itemId) ?: return null
        val series = repository.series(episode.seriesId) ?: return null
        return ResumeItem(episode.toPlayItem(series, progress.positionMs), episode.thumbnailUrl ?: series.posterUrl, progress, seriesId = series.id)
    }

    fun navigate(destination: Destination) {
        saved["destination"] = destination.name
        // One player: changing tab stops whatever plays, as the Sprint 2 TV tab did.
        // The TV tab starts again from its list of categories.
        saved["channelsOpen"] = false
        mutable.update { it.copy(destination = destination, selected = null, vod = null, fullscreen = false, channelsOpen = false) }
        openSources(false)
    }

    fun category(id: String) {
        val state = mutable.value
        if (state.categories.none { it.id == id } || id == state.categoryId) return
        saved["category"] = id
        mutable.update { it.copy(categoryId = id, selected = null, fullscreen = false) }
    }

    /** From the list of categories (or « Toutes » on Accueil): the channels of [id], in the TV tab. */
    fun openCategory(id: String) {
        if (mutable.value.categories.none { it.id == id }) return
        if (mutable.value.destination != Destination.TV || mutable.value.sourcesOpen) navigate(Destination.TV)
        category(id)
        saved["channelsOpen"] = true
        mutable.update { it.copy(channelsOpen = true) }
    }

    /** Back from the channels of a category to the list of categories; playback stops. */
    fun closeCategory() {
        saved["channelsOpen"] = false
        mutable.update { it.copy(channelsOpen = false, selected = null, fullscreen = false) }
    }

    /** Back from the player to the channels of its category. */
    fun closePlayer() {
        mutable.update { it.copy(selected = null, fullscreen = false) }
    }

    fun select(channel: Channel) {
        mutable.update { state -> if (state.channelQuery.matches(channel, state.favoriteIds)) state.copy(selected = channel, vod = null) else state }
        viewModelScope.launch { repository.ensureShortEpg(channel.id) }
    }

    /** From the home or search lists: re-read the channel, so a stale or forged one is ignored. */
    fun openChannel(channel: Channel) {
        viewModelScope.launch {
            val real = repository.channel(channel.id)?.takeIf { it.streamUrl == channel.streamUrl } ?: return@launch
            navigate(Destination.TV)
            val state = mutable.value
            if (!ChannelQuery(state.categoryId).inCategory(real, state.favoriteIds)) category(real.categoryId)
            // Back from the player then leads to the channels of its category.
            saved["channelsOpen"] = true
            mutable.update { it.copy(channelsOpen = true) }
            select(real)
        }
    }

    fun search(query: String) {
        saved["query"] = query
        mutable.update { it.copy(query = query) }
    }

    fun filter(text: String) {
        saved["filter"] = text
        mutable.update { it.copy(filter = text) }
    }

    fun toggleFavorite(channel: Channel) = toggleFavorite(channel.id)

    /** Channels, films and series share the favourites table. A provider's channel added reloads its source's guide. */
    fun toggleFavorite(id: String) {
        val favorite = mutable.value.isFavorite(id)
        viewModelScope.launch {
            repository.setFavorite(id, !favorite)
            if (favorite) return@launch
            val channel = repository.channel(id) ?: return@launch
            val xtream = mutable.value.sources.any { it.id == channel.sourceId && it.kind == SourceKind.XTREAM }
            if (xtream && !channel.tvgId.isNullOrBlank()) reloadGuideSoon(channel.sourceId)
        }
    }

    /** Guide reloads waiting for more favourites, by source; removed once the download starts. */
    private val guideReloads = HashMap<Long, Job>()

    /**
     * The provider's guide follows its favourites: reload it [GUIDE_RELOAD_DELAY_MS] after the last star, so several
     * stars in a row cost one download. The download itself is never cancelled (it would leave an error on the source);
     * its outcome shows on the source card.
     */
    private fun reloadGuideSoon(sourceId: Long) {
        guideReloads.remove(sourceId)?.cancel()
        guideReloads[sourceId] = viewModelScope.launch {
            delay(GUIDE_RELOAD_DELAY_MS)
            guideReloads.remove(sourceId)
            withContext(NonCancellable) { repository.refreshEpg(sourceId) }
        }
    }

    fun fullscreen(on: Boolean) {
        mutable.update { it.copy(fullscreen = on && it.playItem != null) }
    }

    fun openSources(open: Boolean) {
        saved["sourcesOpen"] = open
        mutable.update {
            if (open) it.copy(sourcesOpen = true, selected = null, vod = null, fullscreen = false, guideSheet = null)
            else it.copy(sourcesOpen = false, importStatus = ImportStatus(), accountCheck = null, checkingAccount = false)
        }
    }

    // --- programme guide ---

    /** Moves the shared clock; the screen calls it once a minute so progress bars and « maintenant » follow. */
    fun tick() {
        mutable.update { it.copy(nowMs = clock()) }
    }

    /** Opens the guide of a channel on today, focused on what is on air; Xtream channels fetch their short EPG. */
    fun openGuide(channel: Channel) {
        val now = clock()
        mutable.update { it.copy(nowMs = now, guideSheet = GuideSheet(channel, dayStart(now), focusMs = it.nowNext[channel.id]?.now?.startMs, guideEndMs = it.guideEndOf(channel))) }
        viewModelScope.launch { repository.ensureShortEpg(channel.id) }
    }

    /** From a search hit: the guide of the channel on the programme's day, scrolled to it. */
    fun openGuide(hit: ProgrammeHit) {
        mutable.update { it.copy(nowMs = clock(), guideSheet = GuideSheet(hit.channel, dayStart(hit.programme.startMs), focusMs = hit.programme.startMs, guideEndMs = it.guideEndOf(hit.channel))) }
    }

    /** Reopening the sheet of the same channel keeps its known end: the flow does not emit again for an unchanged channel. */
    private fun LiveState.guideEndOf(channel: Channel): Long? = guideSheet?.takeIf { it.channel.id == channel.id }?.guideEndMs

    fun guideDay(dayStartMs: Long) {
        mutable.update { state ->
            val sheet = state.guideSheet ?: return@update state
            if (sheet.dayStartMs == dayStartMs) state else state.copy(guideSheet = sheet.copy(dayStartMs = dayStartMs, programmes = emptyList(), loading = true, focusMs = null))
        }
    }

    fun closeGuide() {
        mutable.update { it.copy(guideSheet = null) }
    }

    /** « Lire » in the guide: the TV tab with the channel playing; the guide closes. */
    fun playFromGuide(channel: Channel) {
        closeGuide()
        openChannel(channel)
    }

    fun setEpgUrl(sourceId: Long, url: String?) = import(::guideMessage) { repository.setEpgUrl(sourceId, url) }

    fun refreshEpg(sourceId: Long) = import(::guideMessage) { repository.refreshEpg(sourceId) }

    private fun guideMessage(source: Source): String = when {
        !source.hasGuide -> "Guide retiré de « ${source.name} »."
        source.kind == SourceKind.XTREAM && source.programmeCount == 0 -> "Aucune chaîne favorite : le guide de « ${source.name} » suit vos favoris."
        else -> "${source.programmeCount} programmes dans le guide de « ${source.name} »."
    }

    // --- films and series ---

    fun movieCategory(id: String) {
        val state = mutable.value
        if (state.movieCategoryList.none { it.id == id } || id == state.movieCategoryId) return
        saved["movieCategory"] = id
        mutable.update { it.copy(movieCategoryId = id) }
    }

    fun movieFilter(text: String) {
        saved["movieFilter"] = text
        mutable.update { it.copy(movieFilter = text) }
    }

    fun seriesCategory(id: String) {
        val state = mutable.value
        if (state.seriesCategoryList.none { it.id == id } || id == state.seriesCategoryId) return
        saved["seriesCategory"] = id
        mutable.update { it.copy(seriesCategoryId = id) }
    }

    fun seriesFilter(text: String) {
        saved["seriesFilter"] = text
        mutable.update { it.copy(seriesFilter = text) }
    }

    /** Opens the film sheet (switching to the Films tab) and completes it from the provider, once. */
    fun openMovie(movie: Movie) {
        if (mutable.value.destination != Destination.MOVIES || mutable.value.sourcesOpen) navigate(Destination.MOVIES)
        mutable.update { it.copy(movieSheet = MovieSheet(movie, loading = true), seriesSheet = null) }
        viewModelScope.launch {
            val sheet = when (val result = repository.movieDetails(movie.id)) {
                is Fetched.Success -> MovieSheet(result.value)
                is Fetched.Failure -> MovieSheet(movie, error = result.message)
            }
            mutable.update { if (it.movieSheet?.movie?.id == movie.id) it.copy(movieSheet = sheet) else it }
        }
    }

    /** Opens the series sheet (switching to the Séries tab); seasons and episodes come from the cache or the provider. */
    fun openSeries(series: Series) {
        if (mutable.value.destination != Destination.SERIES || mutable.value.sourcesOpen) navigate(Destination.SERIES)
        mutable.update { it.copy(seriesSheet = SeriesSheet(series, loading = true), movieSheet = null) }
        viewModelScope.launch {
            val sheet = when (val result = repository.seriesInfo(series.id)) {
                is Fetched.Success -> SeriesSheet(result.value.series, result.value, result.value.seasons.firstOrNull()?.number)
                is Fetched.Failure -> SeriesSheet(series, error = result.message)
            }
            mutable.update { state ->
                val current = state.seriesSheet ?: return@update state
                if (current.series.id != series.id) state
                // Keep the season the user picked meanwhile (a refresh), else the first one.
                else state.copy(seriesSheet = sheet.copy(season = current.season?.takeIf { s -> sheet.info?.seasons?.any { it.number == s } == true } ?: sheet.season))
            }
        }
    }

    /** Back from a sheet: the film or episode playing inside it stops. */
    fun closeSheet() {
        mutable.update { it.copy(movieSheet = null, seriesSheet = null, vod = null, fullscreen = false) }
    }

    fun selectSeason(number: Int) {
        mutable.update { state ->
            val sheet = state.seriesSheet ?: return@update state
            if (sheet.info?.seasons?.none { it.number == number } != false) state else state.copy(seriesSheet = sheet.copy(season = number))
        }
    }

    /** Plays the open film, from its resume position unless [fromStart]. */
    fun playMovie(movie: Movie, fromStart: Boolean = false) {
        val resume = if (fromStart) 0L else mutable.value.resumable(movie.id)?.positionMs ?: 0L
        mutable.update { it.copy(vod = movie.toPlayItem(resume), selected = null) }
    }

    fun playEpisode(episode: Episode, fromStart: Boolean = false) {
        val state = mutable.value
        val series = state.seriesSheet?.series?.takeIf { it.id == episode.seriesId } ?: return
        val resume = if (fromStart) 0L else state.resumable(episode.id)?.positionMs ?: 0L
        mutable.update { it.copy(vod = episode.toPlayItem(series, resume), selected = null, seriesSheet = it.seriesSheet?.copy(season = episode.season)) }
    }

    /** The episode that follows the one playing, or null at the end of the series. */
    fun nextEpisode(): Episode? {
        val state = mutable.value
        val playing = state.vod ?: return null
        val sheet = state.seriesSheet ?: return null
        val current = sheet.info?.episodes?.firstOrNull { it.id == playing.id } ?: return null
        return sheet.next(current)
    }

    fun playNextEpisode() {
        nextEpisode()?.let { playEpisode(it, fromStart = true) }
    }

    fun stopVod() {
        mutable.update { it.copy(vod = null, fullscreen = false) }
    }

    /** From the home "Reprendre" row: opens the sheet it belongs to, then plays from the saved position. */
    fun resume(item: ResumeItem) {
        viewModelScope.launch {
            when {
                item.movieId != null -> {
                    val movie = repository.movie(item.movieId) ?: return@launch
                    openMovie(movie)
                    playMovie(movie)
                }
                item.seriesId != null -> {
                    val series = repository.series(item.seriesId) ?: return@launch
                    val info = (repository.seriesInfo(series.id) as? Fetched.Success)?.value ?: return@launch
                    val episode = info.episodes.firstOrNull { it.id == item.item.id } ?: return@launch
                    openSeries(series)
                    mutable.update { it.copy(seriesSheet = SeriesSheet(info.series, info, episode.season)) }
                    playEpisode(episode)
                }
            }
        }
    }

    /** Called by the player every few seconds and when it closes. */
    fun saveProgress(itemId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        viewModelScope.launch { repository.saveProgress(itemId, positionMs, durationMs) }
    }

    /** The URL the player opens; Xtream references get their credentials here, at the last moment. */
    suspend fun resolveStream(streamUrl: String): String? = repository.playbackUrl(streamUrl)

    // --- sources ---

    fun addUrlSource(name: String, url: String) {
        val location = url.trim()
        if (!location.startsWith("http://", ignoreCase = true) && !location.startsWith("https://", ignoreCase = true)) {
            mutable.update { it.copy(importStatus = ImportStatus(message = "Adresse invalide : elle doit commencer par http:// ou https://.", error = true)) }
            return
        }
        val label = name.trim().ifEmpty { location.substringAfter("://").substringBefore('/').substringAfter('@').ifEmpty { "Playlist" } }
        import { repository.addSource(label, location, SourceKind.URL) }
    }

    fun addFileSource(name: String, uri: String) {
        if (uri.isBlank()) return
        import { repository.addSource(name.trim().ifEmpty { "Fichier M3U" }, uri, SourceKind.FILE) }
    }

    /** The password only passes through here: it is neither kept in the state nor in the saved handle. */
    fun addXtreamSource(name: String, server: String, username: String, password: String) {
        val normalized = normalizeXtreamServer(server)
        if (normalized.isEmpty() || username.isBlank() || password.isEmpty()) {
            mutable.update { it.copy(importStatus = ImportStatus(message = "Serveur, identifiant et mot de passe sont requis.", error = true)) }
            return
        }
        val label = name.trim().ifEmpty { normalized.substringAfter("://").substringBefore('/').ifEmpty { "Xtream" } }
        import { repository.addXtreamSource(label, XtreamAccount(normalized, username.trim(), password)) }
    }

    /** Connection test from the form; the result is shown until the form changes or closes. */
    fun checkXtreamAccount(server: String, username: String, password: String) {
        if (mutable.value.checkingAccount) return
        mutable.update { it.copy(checkingAccount = true, accountCheck = null) }
        viewModelScope.launch {
            val result = repository.checkXtreamAccount(XtreamAccount(server, username, password))
            mutable.update { it.copy(checkingAccount = false, accountCheck = result) }
        }
    }

    fun clearAccountCheck() {
        mutable.update { if (it.accountCheck == null) it else it.copy(accountCheck = null) }
    }

    fun refreshSource(id: Long) = import { repository.refreshSource(id) }

    fun removeSource(id: Long) {
        viewModelScope.launch { repository.removeSource(id) }
    }

    private fun importedMessage(source: Source): String = when (source.kind) {
        SourceKind.XTREAM -> "${source.channelCount} chaînes, ${source.movieCount} films et ${source.seriesCount} séries importés depuis « ${source.name} »."
        else -> "${source.channelCount} chaînes importées depuis « ${source.name} »."
    }

    private fun import(message: (Source) -> String = ::importedMessage, block: suspend () -> ImportResult) {
        if (mutable.value.importStatus.running) return
        mutable.update { it.copy(importStatus = ImportStatus(running = true, message = "Importation en cours…"), accountCheck = null) }
        viewModelScope.launch {
            val status = when (val result = block()) {
                is ImportResult.Success -> ImportStatus(message = message(result.source))
                is ImportResult.Failure -> ImportStatus(message = result.message, error = true)
            }
            mutable.update { it.copy(importStatus = status) }
        }
    }

    private companion object {
        const val RECENT = 10
        const val DAY_MS = 24 * 3_600_000L
        const val GUIDE_RELOAD_DELAY_MS = 3_000L
    }
}

// --- programme guide formatting (local time zone) ---

/** Local midnight of the day containing [epochMs]. */
fun dayStart(epochMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
    java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

/** Today and the three following days, as local midnights: the retention window of the guide. */
fun guideDaysFrom(nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Long> {
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return (0L..3L).map { today.plusDays(it).atStartOfDay(zone).toInstant().toEpochMilli() }
}

/** « Aujourd'hui », « Demain », then « jeu. 31 oct. ». */
fun dayLabel(dayStartMs: Long, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val day = java.time.Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (java.time.temporal.ChronoUnit.DAYS.between(today, day)) {
        0L -> "Aujourd'hui"
        1L -> "Demain"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.FRANCE))
    }
}

/** « 20:35 ». */
fun formatTime(epochMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

/** « 20:00 – 20:35 ». */
fun formatSlot(programme: Programme, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String = "${formatTime(programme.startMs, zone)} – ${formatTime(programme.stopMs, zone)}"

/** « À suivre 20:35 · Météo », or null. */
fun nextLabel(nowNext: NowNext, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? = nowNext.next?.let { "À suivre ${formatTime(it.startMs, zone)} · ${it.title}" }

fun Movie.toPlayItem(resumeMs: Long = 0L) = PlayItem(id, title, movieSubtitle(), streamRef().format(), live = false, resumeMs = resumeMs)

fun Episode.toPlayItem(series: Series, resumeMs: Long = 0L) =
    PlayItem(id, "S${season.pad()}E${number.pad()} · $title", series.title, streamRef().format(), live = false, resumeMs = resumeMs)

private fun Int.pad() = toString().padStart(2, '0')

/** "2019 · 1 h 58 · 7,6" with whatever is known. */
fun Movie.movieSubtitle(): String? = listOfNotNull(year?.toString(), durationSeconds?.let(::formatDuration), rating?.let { "★ ${formatRating(it)}" })
    .joinToString(" · ").ifEmpty { null }

fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}" else "$minutes min"
}

fun formatRating(rating: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", rating)

/** "Reprendre à 42 min" / "Reprendre à 1 h 05". */
fun resumeLabel(positionMs: Long): String = "Reprendre à ${formatDuration((positionMs / 1000).toInt().coerceAtLeast(60))}"
