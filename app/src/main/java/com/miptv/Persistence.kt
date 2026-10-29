package com.miptv

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.map
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.InvalidationTracker
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val location: String,
    val kind: String,
    val lastRefreshed: Long?,
    val channelCount: Int,
    val lastError: String?,
    // v3, Xtream sources only. The password is a [SecretCipher] output, never the clear text.
    val username: String? = null,
    val encryptedPassword: String? = null,
    val expiresAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val movieCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val seriesCount: Int = 0,
    // v4 (Sprint 4), programme guide.
    val epgUrl: String? = null,
    val epgRefreshedAt: Long? = null,
    val epgError: String? = null,
    @ColumnInfo(defaultValue = "0") val programmeCount: Int = 0
) {
    fun toSource() = Source(
        id, name, location, SourceKind.valueOf(kind), lastRefreshed, channelCount, lastError, username, expiresAt, movieCount, seriesCount,
        epgUrl, epgRefreshedAt, epgError, programmeCount
    )
    companion object {
        /** [encryptedPassword] is kept from the stored row: the domain [Source] never carries it. */
        fun from(source: Source, encryptedPassword: String? = null) = SourceEntity(
            source.id, source.name, source.location, source.kind.name, source.lastRefreshed, source.channelCount, source.lastError,
            source.username, encryptedPassword, source.expiresAt, source.movieCount, source.seriesCount,
            source.epgUrl, source.epgRefreshedAt, source.epgError, source.programmeCount
        )
    }
}

/** Rows paged by [KeysetPagingSource]: list order is source, then position within the source. */
interface Ordered {
    val sourceId: Long
    val position: Int
}

@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val id: String, val name: String, val position: Int)

/** Indices follow the list order (source, then playlist position), globally and within a category. */
@Entity(tableName = "channels", indices = [Index("sourceId", "position"), Index("categoryId", "sourceId", "position"), Index("sourceId", "epgKey")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String,
    val streamUrl: String,
    val logoUrl: String?,
    val tvgId: String?,
    override val sourceId: Long,
    override val position: Int,
    val userAgent: String?,
    val referer: String?,
    /** [searchKey] of [name], matched with LIKE: SQLite only folds ASCII case. */
    @ColumnInfo(defaultValue = "") val searchName: String,
    /** v4: key of the channel in its source's guide (`programmes.channelKey`): [tvgId], or a guide channel matched by name. */
    val epgKey: String? = null
) : Ordered {
    fun toChannel() = Channel(id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, userAgent, referer)
    companion object {
        fun from(channel: Channel, position: Int) = ChannelEntity(
            channel.id, channel.name, channel.categoryId, channel.streamUrl, channel.logoUrl, channel.tvgId, channel.sourceId, position, channel.userAgent, channel.referer,
            searchKey(channel.name), channel.tvgId
        )
    }
}

/** Favourites of channels, films and series: [channelId] keeps its v1 name but holds any prefixed item id. */
@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val channelId: String, @ColumnInfo(name = "addedAt") val addedAt: Long)

// v3 (Sprint 3, lot 2): Xtream films and series, their categories, the season/episode cache and resume positions.

/** Film and series categories, apart from the TV ones; [kind] is `movie` or `series`. */
@Entity(tableName = "vod_categories", indices = [Index("sourceId")])
data class VodCategoryEntity(@PrimaryKey val id: String, val name: String, val kind: String, val sourceId: Long, val position: Int) {
    companion object {
        const val MOVIE = "movie"
        const val SERIES = "series"
    }
}

@Entity(tableName = "movies", indices = [Index("sourceId", "position"), Index("categoryId", "sourceId", "position"), Index("addedAt")])
data class MovieEntity(
    @PrimaryKey val id: String,
    val title: String,
    override val sourceId: Long,
    val streamId: Long,
    val categoryId: String,
    val posterUrl: String?,
    val rating: Double?,
    val year: Int?,
    val synopsis: String?,
    val durationSeconds: Int?,
    val containerExtension: String,
    val addedAt: Long?,
    override val position: Int,
    val searchTitle: String,
    /** When `get_vod_info` was last merged in; null until the film is opened. */
    val detailsLoadedAt: Long?
) : Ordered {
    fun toMovie() = Movie(id, title, sourceId, streamId, categoryId, posterUrl, rating, year, synopsis, durationSeconds, containerExtension, addedAt)
    companion object {
        fun from(movie: Movie, position: Int, detailsLoadedAt: Long? = null) = MovieEntity(
            movie.id, movie.title, movie.sourceId, movie.streamId, movie.categoryId, movie.posterUrl, movie.rating, movie.year, movie.synopsis,
            movie.durationSeconds, movie.containerExtension, movie.addedAt, position, searchKey(movie.title), detailsLoadedAt
        )
    }
}

@Entity(tableName = "series", indices = [Index("sourceId", "position"), Index("categoryId", "sourceId", "position"), Index("updatedAt")])
data class SeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    override val sourceId: Long,
    val seriesId: Long,
    val categoryId: String,
    val posterUrl: String?,
    val rating: Double?,
    val year: Int?,
    val synopsis: String?,
    val episodeRunTimeSeconds: Int?,
    val updatedAt: Long?,
    override val position: Int,
    val searchTitle: String,
    /** When seasons and episodes were cached; null until the series is opened, reset by a source refresh. */
    val detailsLoadedAt: Long?
) : Ordered {
    fun toSeries() = Series(id, title, sourceId, seriesId, categoryId, posterUrl, rating, year, synopsis, episodeRunTimeSeconds, updatedAt)
    companion object {
        fun from(series: Series, position: Int, detailsLoadedAt: Long? = null) = SeriesEntity(
            series.id, series.title, series.sourceId, series.seriesId, series.categoryId, series.posterUrl, series.rating, series.year, series.synopsis,
            series.episodeRunTimeSeconds, series.updatedAt, position, searchKey(series.title), detailsLoadedAt
        )
    }
}

@Entity(tableName = "seasons", primaryKeys = ["seriesId", "number"])
data class SeasonEntity(val seriesId: String, val number: Int, val name: String, val episodeCount: Int, val posterUrl: String?) {
    fun toSeason() = Season(seriesId, number, name, episodeCount, posterUrl)
    companion object { fun from(season: Season) = SeasonEntity(season.seriesId, season.number, season.name, season.episodeCount, season.posterUrl) }
}

@Entity(tableName = "episodes", indices = [Index("seriesId"), Index("sourceId")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val season: Int,
    val number: Int,
    val title: String,
    val sourceId: Long,
    val episodeId: Long,
    val containerExtension: String,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val synopsis: String?
) {
    fun toEpisode() = Episode(id, seriesId, season, number, title, sourceId, episodeId, containerExtension, durationSeconds, thumbnailUrl, synopsis)
    companion object {
        fun from(e: Episode) = EpisodeEntity(e.id, e.seriesId, e.season, e.number, e.title, e.sourceId, e.episodeId, e.containerExtension, e.durationSeconds, e.thumbnailUrl, e.synopsis)
    }
}

/** Resume position of a film or an episode. Kept across refreshes; removed with the source. */
@Entity(tableName = "progress")
data class ProgressEntity(@PrimaryKey val itemId: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    fun toProgress() = PlaybackProgress(itemId, positionMs, durationMs, updatedAt)
}

/**
 * v4 (Sprint 4): programme guide. One row per programme of a source's guide, keyed by the guide's channel id; channels
 * join through `channels.epgKey`. While a guide is being imported its rows sit under `-sourceId` and replace the
 * previous ones in one transaction at the end, so a failed download leaves the old guide in place.
 */
@Entity(tableName = "programmes", primaryKeys = ["sourceId", "channelKey", "startMs"], indices = [Index("stopMs"), Index("startMs")])
data class ProgrammeEntity(
    val sourceId: Long,
    val channelKey: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val episode: String?,
    /** [searchKey] of [title]. */
    val searchTitle: String
) {
    fun toProgramme() = Programme(channelKey, startMs, stopMs, title, description, category, episode, sourceId)
    companion object {
        fun from(programme: Programme, sourceId: Long = programme.sourceId) = ProgrammeEntity(
            sourceId, programme.channelKey, programme.startMs, programme.stopMs, programme.title, programme.description, programme.category, programme.episode, searchKey(programme.title)
        )
    }
}

/** A programme with the id of a channel that airs it (several channels may share one guide key). */
data class ChannelProgrammeRow(val channelId: String, @Embedded val programme: ProgrammeEntity)

/** What the guide matcher needs of a channel. */
data class ChannelKeyRow(val id: String, val name: String, val tvgId: String?, val epgKey: String?)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM sources ORDER BY id") fun sources(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM sources WHERE id = :id") suspend fun source(id: Long): SourceEntity?
    @Query("SELECT MAX(id) FROM sources") suspend fun maxSourceId(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSource(source: SourceEntity)
    @Update suspend fun updateSource(source: SourceEntity)
    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: Long)

    @Query("SELECT * FROM categories ORDER BY position") fun categories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories") suspend fun categoriesOnce(): List<CategoryEntity>
    @Query("SELECT MAX(position) FROM categories") suspend fun maxCategoryPosition(): Int?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCategories(categories: List<CategoryEntity>)
    @Query("DELETE FROM categories WHERE id NOT IN (SELECT DISTINCT categoryId FROM channels)") suspend fun pruneCategories()

    /** One page of a [ChannelPagingSource] query; the paging source observes the tables itself. */
    @RawQuery suspend fun page(query: SupportSQLiteQuery): List<ChannelEntity>

    // Counts without a filter skip LIKE, which would read every row. Only the favourites counts read the favorites table.
    @Query("SELECT COUNT(*) FROM channels") fun countAll(): Flow<Int>
    @Query("SELECT COUNT(*) FROM channels WHERE searchName LIKE :pattern ESCAPE '\\'") fun countAll(pattern: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId") fun countInCategory(categoryId: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM channels WHERE categoryId = :categoryId AND searchName LIKE :pattern ESCAPE '\\'") fun countInCategory(categoryId: String, pattern: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM channels WHERE id IN (SELECT channelId FROM favorites)") fun countFavorites(): Flow<Int>
    @Query("SELECT COUNT(*) FROM channels WHERE id IN (SELECT channelId FROM favorites) AND searchName LIKE :pattern ESCAPE '\\'") fun countFavorites(pattern: String): Flow<Int>
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: String): ChannelEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChannels(channels: List<ChannelEntity>)
    @Query("DELETE FROM channels WHERE sourceId = :sourceId") suspend fun deleteChannels(sourceId: Long)

    @Query("SELECT channelId FROM favorites") fun favoriteIds(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFavorite(favorite: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE channelId = :channelId") suspend fun deleteFavorite(channelId: String)
    @Query("DELETE FROM favorites WHERE channelId NOT IN (SELECT id FROM channels) AND channelId NOT IN (SELECT id FROM movies) AND channelId NOT IN (SELECT id FROM series)")
    suspend fun pruneFavorites()

    // Films and series. Pages and counts are raw keyset queries shared with channels; see KeysetPagingSource.
    @Query("SELECT * FROM vod_categories WHERE kind = :kind ORDER BY position") fun vodCategories(kind: String): Flow<List<VodCategoryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVodCategories(categories: List<VodCategoryEntity>)
    @Query("DELETE FROM vod_categories WHERE sourceId = :sourceId") suspend fun deleteVodCategories(sourceId: Long)
    @RawQuery suspend fun moviePage(query: SupportSQLiteQuery): List<MovieEntity>
    @RawQuery suspend fun seriesPage(query: SupportSQLiteQuery): List<SeriesEntity>
    @RawQuery(observedEntities = [MovieEntity::class, FavoriteEntity::class]) fun countMovies(query: SupportSQLiteQuery): Flow<Int>
    @RawQuery(observedEntities = [SeriesEntity::class, FavoriteEntity::class]) fun countSeries(query: SupportSQLiteQuery): Flow<Int>
    @Query("SELECT * FROM movies WHERE id = :id") suspend fun movie(id: String): MovieEntity?
    @Query("SELECT * FROM series WHERE id = :id") suspend fun series(id: String): SeriesEntity?
    @Query("SELECT * FROM movies WHERE addedAt IS NOT NULL ORDER BY addedAt DESC LIMIT :limit") fun recentMovies(limit: Int): Flow<List<MovieEntity>>
    @Query("SELECT * FROM series WHERE updatedAt IS NOT NULL ORDER BY updatedAt DESC LIMIT :limit") fun recentSeries(limit: Int): Flow<List<SeriesEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMovies(movies: List<MovieEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSeries(series: List<SeriesEntity>)
    @Update suspend fun updateMovie(movie: MovieEntity)
    @Update suspend fun updateSeries(series: SeriesEntity)
    @Query("DELETE FROM movies WHERE sourceId = :sourceId") suspend fun deleteMovies(sourceId: Long)
    @Query("DELETE FROM series WHERE sourceId = :sourceId") suspend fun deleteSeries(sourceId: Long)

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY number") suspend fun seasons(seriesId: String): List<SeasonEntity>
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY season, number") suspend fun episodes(seriesId: String): List<EpisodeEntity>
    @Query("SELECT * FROM episodes WHERE id = :id") suspend fun episode(id: String): EpisodeEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSeasons(seasons: List<SeasonEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpisodes(episodes: List<EpisodeEntity>)
    @Query("DELETE FROM seasons WHERE seriesId = :seriesId") suspend fun deleteSeasons(seriesId: String)
    @Query("DELETE FROM episodes WHERE seriesId = :seriesId") suspend fun deleteEpisodes(seriesId: String)
    @Query("DELETE FROM seasons WHERE seriesId IN (SELECT id FROM series WHERE sourceId = :sourceId)") suspend fun deleteSeasonsOfSource(sourceId: Long)
    @Query("DELETE FROM episodes WHERE sourceId = :sourceId") suspend fun deleteEpisodesOfSource(sourceId: Long)

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 100") fun progress(): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE itemId = :itemId") suspend fun progressOf(itemId: String): ProgressEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProgress(progress: ProgressEntity)
    @Query("DELETE FROM progress WHERE itemId = :itemId") suspend fun deleteProgress(itemId: String)
    /** Item ids carry the source: `xtream:{sourceId}:…`. */
    @Query("DELETE FROM progress WHERE itemId LIKE 'xtream:' || :sourceId || ':%'") suspend fun deleteProgressOfSource(sourceId: Long)

    // Programme guide (v4).
    /** Blocking on purpose: called from the XMLTV parser's sink, on the import thread, one short transaction per batch. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertProgrammesNow(rows: List<ProgrammeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProgrammes(rows: List<ProgrammeEntity>)
    @Query("DELETE FROM programmes WHERE sourceId = :sourceId") suspend fun deleteProgrammes(sourceId: Long)
    @Query("UPDATE programmes SET sourceId = :to WHERE sourceId = :from") suspend fun moveProgrammes(from: Long, to: Long)
    /** Programmes of guide channels no channel of the source designates: unreachable by any join, so dropped. */
    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND channelKey NOT IN (SELECT epgKey FROM channels WHERE sourceId = :sourceId AND epgKey IS NOT NULL)")
    suspend fun deleteOrphanProgrammes(sourceId: Long)
    @Query("DELETE FROM programmes WHERE stopMs < :beforeMs") suspend fun purgeProgrammes(beforeMs: Long)
    @Query("SELECT COUNT(*) FROM programmes WHERE sourceId = :sourceId") suspend fun countProgrammes(sourceId: Long): Int
    @Query("SELECT id, name, tvgId, epgKey FROM channels WHERE sourceId = :sourceId") suspend fun channelKeys(sourceId: Long): List<ChannelKeyRow>
    @Query("SELECT DISTINCT epgKey FROM channels WHERE sourceId = :sourceId AND epgKey IS NOT NULL AND id IN (SELECT channelId FROM favorites)")
    suspend fun favoriteEpgKeys(sourceId: Long): List<String>
    @Query("UPDATE channels SET epgKey = :key WHERE id = :id") suspend fun setEpgKey(id: String, key: String?)
    @Query("SELECT * FROM channels WHERE id IN (:ids)") suspend fun channelsByIds(ids: List<String>): List<ChannelEntity>

    @Query("SELECT c.id AS channelId, p.* FROM programmes p JOIN channels c ON c.sourceId = p.sourceId AND c.epgKey = p.channelKey WHERE p.startMs <= :nowMs AND p.stopMs > :nowMs")
    fun onAir(nowMs: Long): Flow<List<ChannelProgrammeRow>>
    /** For each channel with a programme on air, the first programme starting at or after its end. */
    @Query(
        "SELECT c.id AS channelId, n.* FROM programmes p " +
            "JOIN channels c ON c.sourceId = p.sourceId AND c.epgKey = p.channelKey " +
            "JOIN programmes n ON n.sourceId = p.sourceId AND n.channelKey = p.channelKey " +
            "AND n.startMs = (SELECT MIN(q.startMs) FROM programmes q WHERE q.sourceId = p.sourceId AND q.channelKey = p.channelKey AND q.startMs >= p.stopMs) " +
            "WHERE p.startMs <= :nowMs AND p.stopMs > :nowMs"
    )
    fun upNext(nowMs: Long): Flow<List<ChannelProgrammeRow>>
    @Query("SELECT p.* FROM programmes p JOIN channels c ON c.sourceId = p.sourceId AND c.epgKey = p.channelKey WHERE c.id = :channelId AND p.stopMs > :fromMs AND p.startMs < :toMs ORDER BY p.startMs")
    fun programmesOf(channelId: String, fromMs: Long, toMs: Long): Flow<List<ProgrammeEntity>>
    @Query("SELECT c.id AS channelId, p.* FROM programmes p JOIN channels c ON c.sourceId = p.sourceId AND c.epgKey = p.channelKey WHERE p.stopMs > :nowMs AND p.searchTitle LIKE :pattern ESCAPE '\\' ORDER BY p.startMs, c.id LIMIT :limit")
    fun searchProgrammes(pattern: String, nowMs: Long, limit: Int): Flow<List<ChannelProgrammeRow>>
    @Query("SELECT COUNT(*) FROM programmes p JOIN channels c ON c.sourceId = p.sourceId AND c.epgKey = p.channelKey WHERE p.stopMs > :nowMs AND p.searchTitle LIKE :pattern ESCAPE '\\'")
    fun countProgrammes(pattern: String, nowMs: Long): Flow<Int>
    /** A guide key with a programme ending after [afterMs], for the short-EPG cache check. */
    @Query("SELECT MAX(stopMs) FROM programmes WHERE sourceId = :sourceId AND channelKey = :channelKey AND stopMs > :afterMs") suspend fun lastStop(sourceId: Long, channelKey: String, afterMs: Long): Long?

    /** Swaps the staged guide (`-sourceId`) in for the current one. */
    @Transaction
    suspend fun commitProgrammes(sourceId: Long) {
        deleteProgrammes(sourceId)
        moveProgrammes(-sourceId, sourceId)
    }

    /** Replaces one source's films, series and VOD categories. Favourites of items that are still there survive. */
    @Transaction
    suspend fun replaceVod(sourceId: Long, catalog: VodCatalog) {
        deleteVodCategories(sourceId)
        deleteSeasonsOfSource(sourceId)
        deleteEpisodesOfSource(sourceId)
        deleteMovies(sourceId)
        deleteSeries(sourceId)
        insertVodCategories(
            catalog.movieCategories.mapIndexed { i, c -> VodCategoryEntity(c.id, c.name, VodCategoryEntity.MOVIE, sourceId, i) } +
                catalog.seriesCategories.mapIndexed { i, c -> VodCategoryEntity(c.id, c.name, VodCategoryEntity.SERIES, sourceId, i) }
        )
        catalog.movies.chunked(500).forEachIndexed { chunk, movies -> insertMovies(movies.mapIndexed { i, m -> MovieEntity.from(m, chunk * 500 + i) }) }
        catalog.series.chunked(500).forEachIndexed { chunk, series -> insertSeries(series.mapIndexed { i, s -> SeriesEntity.from(s, chunk * 500 + i) }) }
        pruneFavorites()
    }

    /** Removes everything a source owns. */
    @Transaction
    suspend fun deleteSourceContent(sourceId: Long) {
        deleteChannels(sourceId)
        deleteProgrammes(sourceId)
        deleteProgrammes(-sourceId)
        deleteSeasonsOfSource(sourceId)
        deleteEpisodesOfSource(sourceId)
        deleteMovies(sourceId)
        deleteSeries(sourceId)
        deleteVodCategories(sourceId)
        deleteProgressOfSource(sourceId)
        deleteSource(sourceId)
        pruneCategories()
        pruneFavorites()
    }

    /** Replaces one source's channels, merging its groups into the shared category table. */
    @Transaction
    suspend fun replaceChannels(sourceId: Long, parsed: ParsedPlaylist) {
        deleteChannels(sourceId)
        var position = maxCategoryPosition() ?: -1
        insertCategories(parsed.categories.map { CategoryEntity(it.id, it.name, ++position) })
        parsed.channels.chunked(500).forEachIndexed { chunk, channels ->
            insertChannels(channels.mapIndexed { index, channel -> ChannelEntity.from(channel, chunk * 500 + index) })
        }
        pruneCategories()
    }
}

/** Films, series and their categories of one Xtream source, as imported together. */
class VodCatalog(val movieCategories: List<Category>, val movies: List<Movie>, val seriesCategories: List<Category>, val series: List<Series>) {
    companion object { val EMPTY = VodCatalog(emptyList(), emptyList(), emptyList(), emptyList()) }
}

@Database(
    entities = [
        SourceEntity::class, CategoryEntity::class, ChannelEntity::class, FavoriteEntity::class,
        VodCategoryEntity::class, MovieEntity::class, SeriesEntity::class, SeasonEntity::class, EpisodeEntity::class, ProgressEntity::class,
        ProgrammeEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class MiptvDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
}

/** v2 (Sprint 3): searchable name column and list-order indices. The search key is computed in Kotlin. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE channels ADD COLUMN searchName TEXT NOT NULL DEFAULT ''")
        db.execSQL("DROP INDEX IF EXISTS index_channels_sourceId")
        db.execSQL("DROP INDEX IF EXISTS index_channels_categoryId")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_sourceId_position ON channels (sourceId, position)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_channels_categoryId_sourceId_position ON channels (categoryId, sourceId, position)")
        val update = db.compileStatement("UPDATE channels SET searchName = ? WHERE id = ?")
        db.query("SELECT id, name FROM channels").use { cursor ->
            while (cursor.moveToNext()) {
                update.bindString(1, searchKey(cursor.getString(1)))
                update.bindString(2, cursor.getString(0))
                update.executeUpdateDelete()
            }
        }
    }
}

/**
 * v3 (Sprint 3, lot 2): Xtream account columns on `sources`, VOD tables, season/episode cache and resume positions.
 * The statements are those Room exports in `3.json`, so `MigrationTest` validates them against the entities.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN username TEXT")
        db.execSQL("ALTER TABLE sources ADD COLUMN encryptedPassword TEXT")
        db.execSQL("ALTER TABLE sources ADD COLUMN expiresAt INTEGER")
        db.execSQL("ALTER TABLE sources ADD COLUMN movieCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN seriesCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `vod_categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vod_categories_sourceId` ON `vod_categories` (`sourceId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `movies` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `streamId` INTEGER NOT NULL, `categoryId` TEXT NOT NULL, `posterUrl` TEXT, `rating` REAL, `year` INTEGER, `synopsis` TEXT, `durationSeconds` INTEGER, `containerExtension` TEXT NOT NULL, `addedAt` INTEGER, `position` INTEGER NOT NULL, `searchTitle` TEXT NOT NULL, `detailsLoadedAt` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sourceId_position` ON `movies` (`sourceId`, `position`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_sourceId_position` ON `movies` (`categoryId`, `sourceId`, `position`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_addedAt` ON `movies` (`addedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `series` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `seriesId` INTEGER NOT NULL, `categoryId` TEXT NOT NULL, `posterUrl` TEXT, `rating` REAL, `year` INTEGER, `synopsis` TEXT, `episodeRunTimeSeconds` INTEGER, `updatedAt` INTEGER, `position` INTEGER NOT NULL, `searchTitle` TEXT NOT NULL, `detailsLoadedAt` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_sourceId_position` ON `series` (`sourceId`, `position`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_categoryId_sourceId_position` ON `series` (`categoryId`, `sourceId`, `position`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_updatedAt` ON `series` (`updatedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `seasons` (`seriesId` TEXT NOT NULL, `number` INTEGER NOT NULL, `name` TEXT NOT NULL, `episodeCount` INTEGER NOT NULL, `posterUrl` TEXT, PRIMARY KEY(`seriesId`, `number`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `episodes` (`id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `season` INTEGER NOT NULL, `number` INTEGER NOT NULL, `title` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `episodeId` INTEGER NOT NULL, `containerExtension` TEXT NOT NULL, `durationSeconds` INTEGER, `thumbnailUrl` TEXT, `synopsis` TEXT, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_seriesId` ON `episodes` (`seriesId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_sourceId` ON `episodes` (`sourceId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `progress` (`itemId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
    }
}

/**
 * v4 (Sprint 4, lot 2): guide columns on `sources`, guide key on `channels` (seeded from `tvgId`) and the `programmes`
 * table. Statements as exported in `4.json`.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN epgUrl TEXT")
        db.execSQL("ALTER TABLE sources ADD COLUMN epgRefreshedAt INTEGER")
        db.execSQL("ALTER TABLE sources ADD COLUMN epgError TEXT")
        db.execSQL("ALTER TABLE sources ADD COLUMN programmeCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE channels ADD COLUMN epgKey TEXT")
        db.execSQL("UPDATE channels SET epgKey = tvgId WHERE tvgId IS NOT NULL AND tvgId != ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId_epgKey` ON `channels` (`sourceId`, `epgKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `programmes` (`sourceId` INTEGER NOT NULL, `channelKey` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `stopMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `category` TEXT, `episode` TEXT, `searchTitle` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `channelKey`, `startMs`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_programmes_stopMs` ON `programmes` (`stopMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_programmes_startMs` ON `programmes` (`startMs`)")
    }
}

/** Every migration, in order, for `Room.databaseBuilder`. Declared after the migrations: top-level values initialise in file order. */
val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

/** LIKE pattern matching [text] anywhere in a [searchKey]; `%`, `_` and the escape character are literal. */
fun likePattern(text: String): String =
    "%" + searchKey(text).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** Page size: a few screens of channel cards. */
private val PAGING = PagingConfig(pageSize = 60, enablePlaceholders = false)

/** A place in list order: channels sort by source, then playlist position. */
data class ChannelKey(val sourceId: Long, val position: Int, val direction: Direction) {
    /** [FROM] includes the keyed channel (refresh), [AFTER] and [BEFORE] exclude it (append, prepend). */
    enum class Direction { FROM, AFTER, BEFORE }
}

/**
 * Keyset paging over a table of [Ordered] rows (channels, films, series): each page continues from the last (or
 * first) row read, through the `(sourceId, position)` indices, so a page costs the same at any depth and no page
 * counts the whole query, unlike Room's LIMIT/OFFSET source. It is invalidated by changes to [table], and to the
 * favourites for that list only. [filter] is the category and text of the query; [searchColumn] holds the [searchKey].
 */
class KeysetPagingSource<E : Ordered>(
    private val database: MiptvDatabase,
    private val table: String,
    private val searchColumn: String,
    private val filter: ListFilter,
    private val load: suspend (SupportSQLiteQuery) -> List<E>
) : PagingSource<ChannelKey, E>() {
    private val favorites = filter.categoryId == Category.FAVORITES.id
    private val observer = object : InvalidationTracker.Observer(if (favorites) arrayOf(table, "favorites") else arrayOf(table)) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }
    private val observing = AtomicBoolean(false)

    init { registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) } }

    override suspend fun load(params: LoadParams<ChannelKey>): LoadResult<ChannelKey, E> {
        // Observe before the first read, off the main thread (Room requires it), so no change slips between the two.
        if (observing.compareAndSet(false, true)) withContext(Dispatchers.IO) { database.invalidationTracker.addObserver(observer) }
        val key = params.key
        val rows = load(sql(key, params.loadSize)).let { if (key?.direction == ChannelKey.Direction.BEFORE) it.reversed() else it }
        if (invalid) return LoadResult.Invalid()
        val full = rows.size == params.loadSize
        val first = rows.firstOrNull()?.let { ChannelKey(it.sourceId, it.position, ChannelKey.Direction.BEFORE) }
        val last = rows.lastOrNull()?.let { ChannelKey(it.sourceId, it.position, ChannelKey.Direction.AFTER) }
        return when (key?.direction) {
            null -> LoadResult.Page(rows, prevKey = null, nextKey = last.takeIf { full })
            // Refresh in the middle: prepends bring back what precedes the keyed row, even when nothing follows it.
            ChannelKey.Direction.FROM -> LoadResult.Page(rows, prevKey = first ?: key.copy(direction = ChannelKey.Direction.BEFORE), nextKey = last.takeIf { full })
            ChannelKey.Direction.AFTER -> LoadResult.Page(rows, prevKey = null, nextKey = last.takeIf { full })
            ChannelKey.Direction.BEFORE -> LoadResult.Page(rows, prevKey = first.takeIf { full }, nextKey = null)
        }
    }

    /** Reload from the row the user was looking at; earlier ones come back through prepends. */
    override fun getRefreshKey(state: PagingState<ChannelKey, E>): ChannelKey? =
        state.anchorPosition?.let { state.closestItemToPosition(it) }?.let { ChannelKey(it.sourceId, it.position, ChannelKey.Direction.FROM) }

    private fun sql(key: ChannelKey?, limit: Int): SimpleSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        filter.appendTo(where, args, searchColumn)
        if (key != null) {
            where += when (key.direction) {
                ChannelKey.Direction.FROM -> "(sourceId, position) >= (?, ?)"
                ChannelKey.Direction.AFTER -> "(sourceId, position) > (?, ?)"
                ChannelKey.Direction.BEFORE -> "(sourceId, position) < (?, ?)"
            }
            args += key.sourceId
            args += key.position
        }
        val order = if (key?.direction == ChannelKey.Direction.BEFORE) "DESC" else "ASC"
        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return SimpleSQLiteQuery("SELECT * FROM $table$clause ORDER BY sourceId $order, position $order LIMIT $limit", args.toTypedArray())
    }
}

/** Category and text filter of a list, shared by channel and VOD queries. */
data class ListFilter(val categoryId: String, val text: String) {
    fun appendTo(where: MutableList<String>, args: MutableList<Any>, searchColumn: String) {
        when (categoryId) {
            Category.ALL.id -> Unit
            Category.FAVORITES.id -> where += "id IN (SELECT channelId FROM favorites)"
            else -> { where += "categoryId = ?"; args += categoryId }
        }
        if (text.isNotBlank()) { where += "$searchColumn LIKE ? ESCAPE '\\'"; args += likePattern(text) }
    }

    fun countSql(table: String, searchColumn: String): SimpleSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        appendTo(where, args, searchColumn)
        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM $table$clause", args.toTypedArray())
    }
}

fun ChannelQuery.filter() = ListFilter(categoryId, text)
fun VodQuery.filter() = ListFilter(categoryId, text)

@Suppress("FunctionName")
fun ChannelPagingSource(database: MiptvDatabase, query: ChannelQuery) =
    KeysetPagingSource(database, "channels", "searchName", query.filter()) { database.catalog().page(it) }

/**
 * Persistent implementation: the imported catalogue, the sources and the favourites survive restarts,
 * so no re-import is needed. Imports are serialised; a failed refresh keeps the previous content. Xtream passwords
 * go through [cipher] and are decrypted only to call the provider or build a playback URL.
 */
class RoomChannelRepository(
    private val database: MiptvDatabase,
    private val loader: PlaylistLoader,
    private val xtream: XtreamClient = HttpXtreamClient(),
    private val cipher: SecretCipher = KeystoreSecretCipher(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ChannelRepository {
    private val dao = database.catalog()
    private val mutex = Mutex()
    /** When `get_short_epg` was last fetched per channel; emptied whenever a source's guide is replaced. */
    private val shortEpgFetched = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override val categories: Flow<List<Category>> = dao.categories().map { list -> list.map { Category(it.id, it.name) } }
    override val favoriteIds: Flow<Set<String>> = dao.favoriteIds().map { it.toHashSet() }

    override fun channels(query: ChannelQuery): Flow<PagingData<Channel>> =
        Pager(PAGING) { ChannelPagingSource(database, query) }.flow.map { page -> page.map { it.toChannel() } }

    override fun count(query: ChannelQuery): Flow<Int> {
        val pattern = query.text.takeIf { it.isNotBlank() }?.let(::likePattern)
        return when (query.categoryId) {
            Category.ALL.id -> if (pattern == null) dao.countAll() else dao.countAll(pattern)
            Category.FAVORITES.id -> if (pattern == null) dao.countFavorites() else dao.countFavorites(pattern)
            else -> if (pattern == null) dao.countInCategory(query.categoryId) else dao.countInCategory(query.categoryId, pattern)
        }
    }

    override suspend fun channel(id: String): Channel? = dao.channel(id)?.toChannel()

    override val sources: Flow<List<Source>> = dao.sources().map { list -> list.map { it.toSource() } }

    override suspend fun addSource(name: String, location: String, kind: SourceKind): ImportResult = mutex.withLock {
        if (kind == SourceKind.XTREAM) return ImportResult.Failure("Une source Xtream s'ajoute avec un identifiant et un mot de passe.")
        val source = Source((dao.maxSourceId() ?: 0L) + 1, name, location, kind)
        when (val result = import(source)) {
            is ImportOutcome.Failure -> ImportResult.Failure(result.message)
            is ImportOutcome.Success -> {
                val stored = result.source.copy(epgUrl = result.live.epgUrl)
                database.withTransaction {
                    dao.insertSource(SourceEntity.from(stored))
                    dao.replaceChannels(source.id, result.live)
                }
                // The guide is a bonus: its outcome lands on the source row, not on the import result.
                if (stored.epgUrl != null) importGuide(source.id)
                ImportResult.Success(dao.source(source.id)?.toSource() ?: stored)
            }
        }
    }

    override suspend fun checkXtreamAccount(account: XtreamAccount): Fetched<XtreamAccountInfo> = xtream.check(account)

    override suspend fun addXtreamSource(name: String, account: XtreamAccount): ImportResult = mutex.withLock {
        val server = normalizeXtreamServer(account.server)
        if (server.isEmpty() || account.username.isBlank() || account.password.isEmpty()) return ImportResult.Failure("Serveur, identifiant et mot de passe sont requis.")
        val normalized = account.copy(server = server, username = account.username.trim())
        val encrypted = try {
            withContext(Dispatchers.IO) { cipher.encrypt(normalized.password) }
        } catch (error: SecretStoreException) {
            return ImportResult.Failure(error.message ?: "Stockage sécurisé indisponible.")
        }
        val source = Source((dao.maxSourceId() ?: 0L) + 1, name, server, SourceKind.XTREAM, username = normalized.username)
        when (val result = importXtream(source, normalized)) {
            is ImportOutcome.Failure -> ImportResult.Failure(result.message)
            is ImportOutcome.Success -> {
                database.withTransaction {
                    dao.insertSource(SourceEntity.from(result.source, encrypted))
                    dao.replaceChannels(source.id, result.live)
                    dao.replaceVod(source.id, result.vod)
                }
                ImportResult.Success(result.source)
            }
        }
    }

    override suspend fun refreshSource(id: Long): ImportResult = mutex.withLock {
        val entity = dao.source(id) ?: return ImportResult.Failure("Source introuvable.")
        val source = entity.toSource()
        val result = if (source.kind == SourceKind.XTREAM) {
            when (val credentials = credentials(entity)) {
                is Credentials.Unavailable -> ImportOutcome.Failure(credentials.message)
                is Credentials.Ready -> importXtream(source, credentials.account)
            }
        } else import(source)
        when (result) {
            is ImportOutcome.Failure -> {
                dao.updateSource(SourceEntity.from(source.copy(lastError = result.message), entity.encryptedPassword))
                ImportResult.Failure(result.message)
            }
            is ImportOutcome.Success -> {
                // A playlist keeps the guide address the user set; otherwise the header's one is taken.
                val stored = result.source.copy(epgUrl = source.epgUrl ?: result.live.epgUrl)
                database.withTransaction {
                    dao.updateSource(SourceEntity.from(stored, entity.encryptedPassword))
                    dao.replaceChannels(id, result.live)
                    if (source.kind == SourceKind.XTREAM) dao.replaceVod(id, result.vod)
                }
                if (stored.hasGuide) importGuide(id)
                ImportResult.Success(dao.source(id)?.toSource() ?: stored)
            }
        }
    }

    override suspend fun removeSource(id: Long) = mutex.withLock { dao.deleteSourceContent(id) }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) {
        if (favorite) dao.insertFavorite(FavoriteEntity(itemId, System.currentTimeMillis())) else dao.deleteFavorite(itemId)
    }

    override val movieCategories: Flow<List<Category>> = dao.vodCategories(VodCategoryEntity.MOVIE).map { list -> list.map { Category(it.id, it.name) } }
    override val seriesCategories: Flow<List<Category>> = dao.vodCategories(VodCategoryEntity.SERIES).map { list -> list.map { Category(it.id, it.name) } }

    override fun movies(query: VodQuery): Flow<PagingData<Movie>> =
        Pager(PAGING) { KeysetPagingSource(database, "movies", "searchTitle", query.filter()) { dao.moviePage(it) } }.flow.map { page -> page.map { it.toMovie() } }

    override fun series(query: VodQuery): Flow<PagingData<Series>> =
        Pager(PAGING) { KeysetPagingSource(database, "series", "searchTitle", query.filter()) { dao.seriesPage(it) } }.flow.map { page -> page.map { it.toSeries() } }

    override fun countMovies(query: VodQuery): Flow<Int> = dao.countMovies(query.filter().countSql("movies", "searchTitle"))
    override fun countSeries(query: VodQuery): Flow<Int> = dao.countSeries(query.filter().countSql("series", "searchTitle"))
    override fun recentMovies(limit: Int): Flow<List<Movie>> = dao.recentMovies(limit).map { list -> list.map { it.toMovie() } }
    override fun recentSeries(limit: Int): Flow<List<Series>> = dao.recentSeries(limit).map { list -> list.map { it.toSeries() } }
    override suspend fun movie(id: String): Movie? = dao.movie(id)?.toMovie()
    override suspend fun series(id: String): Series? = dao.series(id)?.toSeries()
    override suspend fun episode(id: String): Episode? = dao.episode(id)?.toEpisode()

    override suspend fun movieDetails(id: String): Fetched<Movie> {
        val entity = dao.movie(id) ?: return Fetched.Failure("Film introuvable.")
        if (entity.detailsLoadedAt != null) return Fetched.Success(entity.toMovie())
        val account = when (val credentials = credentials(entity.sourceId)) {
            is Credentials.Unavailable -> return Fetched.Failure(credentials.message)
            is Credentials.Ready -> credentials.account
        }
        val movie = try { xtream.movieDetails(account, entity.toMovie()) } catch (error: Exception) { return Fetched.Failure(describeXtreamError(error)) }
        dao.updateMovie(MovieEntity.from(movie, entity.position, System.currentTimeMillis()))
        return Fetched.Success(movie)
    }

    override suspend fun seriesInfo(id: String, refresh: Boolean): Fetched<SeriesInfo> {
        val entity = dao.series(id) ?: return Fetched.Failure("Série introuvable.")
        val cached = entity.detailsLoadedAt != null
        if (cached && !refresh) return Fetched.Success(cachedInfo(entity))
        val account = when (val credentials = credentials(entity.sourceId)) {
            is Credentials.Unavailable -> return if (cached) Fetched.Success(cachedInfo(entity)) else Fetched.Failure(credentials.message)
            is Credentials.Ready -> credentials.account
        }
        val info = try {
            xtream.seriesDetails(account, entity.toSeries())
        } catch (error: Exception) {
            return if (cached) Fetched.Success(cachedInfo(entity)) else Fetched.Failure(describeXtreamError(error))
        }
        database.withTransaction {
            dao.updateSeries(SeriesEntity.from(info.series, entity.position, System.currentTimeMillis()))
            dao.deleteSeasons(id)
            dao.deleteEpisodes(id)
            dao.insertSeasons(info.seasons.map { SeasonEntity.from(it) })
            dao.insertEpisodes(info.episodes.map { EpisodeEntity.from(it) })
        }
        return Fetched.Success(info)
    }

    private suspend fun cachedInfo(entity: SeriesEntity) =
        SeriesInfo(entity.toSeries(), dao.seasons(entity.id).map { it.toSeason() }, dao.episodes(entity.id).map { it.toEpisode() })

    override suspend fun playbackUrl(streamUrl: String): String? {
        val ref = XtreamStreamRef.parse(streamUrl) ?: return streamUrl
        return when (val credentials = credentials(ref.sourceId)) {
            is Credentials.Ready -> ref.resolve(credentials.account)
            is Credentials.Unavailable -> null
        }
    }

    override val progress: Flow<List<PlaybackProgress>> = dao.progress().map { list -> list.map { it.toProgress() } }
    override suspend fun progress(itemId: String): PlaybackProgress? = dao.progressOf(itemId)?.toProgress()
    override suspend fun saveProgress(itemId: String, positionMs: Long, durationMs: Long) =
        dao.upsertProgress(ProgressEntity(itemId, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), System.currentTimeMillis()))
    override suspend fun clearProgress(itemId: String) = dao.deleteProgress(itemId)

    // --- Programme guide ---

    override suspend fun setEpgUrl(sourceId: Long, url: String?): ImportResult = mutex.withLock {
        val entity = dao.source(sourceId) ?: return ImportResult.Failure("Source introuvable.")
        if (entity.kind == SourceKind.XTREAM.name) return ImportResult.Failure("Le guide d'un fournisseur Xtream vient de son compte.")
        val clean = url?.trim()?.ifEmpty { null }
        if (clean != null && !clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return ImportResult.Failure("Seules les adresses http:// et https:// sont acceptées.")
        }
        if (clean == null) {
            database.withTransaction {
                dao.deleteProgrammes(sourceId)
                dao.deleteProgrammes(-sourceId)
                dao.updateSource(entity.copy(epgUrl = null, epgRefreshedAt = null, epgError = null, programmeCount = 0))
            }
            return ImportResult.Success(dao.source(sourceId)!!.toSource())
        }
        dao.updateSource(entity.copy(epgUrl = clean))
        importGuide(sourceId)
    }

    override suspend fun refreshEpg(sourceId: Long): ImportResult = mutex.withLock { importGuide(sourceId) }

    /**
     * Downloads and stores the guide of a source: a playlist's XMLTV file, or the provider's `xmltv.php` kept to the
     * favourite channels. Rows are staged under `-sourceId` while the file streams, then swapped in; the outcome goes
     * on the source row (`epgRefreshedAt`, `programmeCount`, `epgError`). A failure keeps the previous programmes.
     * Callers hold [mutex].
     */
    private suspend fun importGuide(sourceId: Long): ImportResult {
        val entity = dao.source(sourceId) ?: return ImportResult.Failure("Source introuvable.")
        val now = clock()
        val xtreamSource = entity.kind == SourceKind.XTREAM.name
        try {
            val location: String
            var filter: ((String) -> Boolean)? = null
            if (xtreamSource) {
                val account = when (val credentials = credentials(entity)) {
                    is Credentials.Unavailable -> throw EpgException(credentials.message)
                    is Credentials.Ready -> credentials.account
                }
                val keys = dao.favoriteEpgKeys(sourceId).toHashSet()
                location = account.xmltvUrl()
                filter = { it in keys }
                if (keys.isEmpty()) {
                    // Nothing to download: the guide of a provider follows its favourites.
                    database.withTransaction {
                        dao.deleteProgrammes(sourceId)
                        dao.updateSource(entity.copy(epgRefreshedAt = now, epgError = null, programmeCount = 0))
                    }
                    shortEpgFetched.clear()
                    return ImportResult.Success(dao.source(sourceId)!!.toSource())
                }
            } else {
                location = entity.epgUrl ?: return ImportResult.Failure("Aucune adresse de guide pour cette source.")
            }
            dao.deleteProgrammes(-sourceId)
            val parser = XmltvParser(sourceId, fromMs = now - EpgWindow.BEFORE_MS, toMs = now + EpgWindow.AFTER_MS, channelFilter = filter)
            val stream = loader.open(location, SourceKind.URL)
            val result = withContext(Dispatchers.IO) {
                stream.use { input -> parser.parse(input) { batch -> dao.insertProgrammesNow(batch.map { ProgrammeEntity.from(it, -sourceId) }) } }
            }
            database.withTransaction {
                dao.commitProgrammes(sourceId)
                if (!xtreamSource) {
                    matchGuideKeys(sourceId, result.channels)
                    // A public guide covers far more channels than a playlist: keep only what a channel can show.
                    dao.deleteOrphanProgrammes(sourceId)
                }
                dao.purgeProgrammes(now - EpgWindow.PURGE_AFTER_MS)
                dao.updateSource(entity.copy(epgRefreshedAt = now, epgError = null, programmeCount = dao.countProgrammes(sourceId)))
            }
            shortEpgFetched.clear()
            return ImportResult.Success(dao.source(sourceId)!!.toSource())
        } catch (error: Exception) {
            dao.deleteProgrammes(-sourceId)
            val message = describeEpgError(error)
            dao.updateSource(entity.copy(epgError = message))
            return ImportResult.Failure(message)
        }
    }

    /** Playlist channels get the guide channel their `tvg-id` or display name designates (see [guideKey]). */
    private suspend fun matchGuideKeys(sourceId: Long, guideChannels: List<EpgChannel>) {
        val guideIds = guideChannels.mapTo(HashSet()) { it.id }
        val byName = guideNameIndex(guideChannels)
        for (row in dao.channelKeys(sourceId)) {
            val key = guideKey(row.tvgId, row.name, guideIds, byName)
            if (key != row.epgKey) dao.setEpgKey(row.id, key)
        }
    }

    override fun nowAndNext(nowMs: Long): Flow<Map<String, NowNext>> =
        combine(dao.onAir(nowMs), dao.upNext(nowMs)) { onAir, upNext ->
            val next = upNext.associate { it.channelId to it.programme.toProgramme() }
            onAir.associate { it.channelId to NowNext(it.programme.toProgramme(), next[it.channelId]) }
        }

    override fun programmes(channelId: String, fromMs: Long, toMs: Long): Flow<List<Programme>> =
        dao.programmesOf(channelId, fromMs, toMs).map { list -> list.map { it.toProgramme() } }

    override fun searchProgrammes(text: String, nowMs: Long, limit: Int): Flow<List<ProgrammeHit>> {
        if (text.isBlank()) return flowOf(emptyList())
        return dao.searchProgrammes(likePattern(text), nowMs, limit).map { rows ->
            val channels = dao.channelsByIds(rows.map { it.channelId }.distinct()).associate { it.id to it.toChannel() }
            rows.mapNotNull { row -> channels[row.channelId]?.let { ProgrammeHit(row.programme.toProgramme(), it) } }
        }
    }

    override fun countProgrammes(text: String, nowMs: Long): Flow<Int> =
        if (text.isBlank()) flowOf(0) else dao.countProgrammes(likePattern(text), nowMs)

    override suspend fun ensureShortEpg(channelId: String): Boolean {
        val entity = dao.channel(channelId) ?: return false
        val ref = XtreamStreamRef.parse(entity.streamUrl) ?: return false
        val key = entity.epgKey ?: "stream:${ref.streamId}".also { dao.setEpgKey(channelId, it) }
        val now = clock()
        val fetchedAt = shortEpgFetched[channelId]
        // Programmes still running (from the favourites' XMLTV, or a fresh short EPG) are enough.
        if (dao.lastStop(ref.sourceId, key, now) != null && (fetchedAt == null || now - fetchedAt < EpgWindow.SHORT_EPG_TTL_MS)) return false
        val account = (credentials(ref.sourceId) as? Credentials.Ready)?.account ?: return false
        val programmes = try { xtream.shortEpg(account, ref.sourceId, ref.streamId, key, EpgWindow.SHORT_EPG_LIMIT) } catch (_: Exception) { return false }
        shortEpgFetched[channelId] = now
        if (programmes.isEmpty()) return false
        dao.insertProgrammes(programmes.map { ProgrammeEntity.from(it, ref.sourceId) })
        return true
    }

    override suspend fun purgeProgrammes() = dao.purgeProgrammes(clock() - EpgWindow.PURGE_AFTER_MS)

    private sealed interface Credentials {
        class Ready(val account: XtreamAccount) : Credentials
        class Unavailable(val message: String) : Credentials
    }

    private suspend fun credentials(sourceId: Long): Credentials =
        dao.source(sourceId)?.let { credentials(it) } ?: Credentials.Unavailable("Source introuvable.")

    /** Decrypts the stored password; a lost key or a row without one means the user must enter them again. */
    private suspend fun credentials(entity: SourceEntity): Credentials {
        if (entity.kind != SourceKind.XTREAM.name) return Credentials.Unavailable("Cette source n'est pas un fournisseur Xtream.")
        val username = entity.username ?: return Credentials.Unavailable(CREDENTIALS_TO_REENTER)
        val encrypted = entity.encryptedPassword ?: return Credentials.Unavailable(CREDENTIALS_TO_REENTER)
        val password = withContext(Dispatchers.IO) { cipher.decrypt(encrypted) } ?: return Credentials.Unavailable(CREDENTIALS_TO_REENTER)
        return Credentials.Ready(XtreamAccount(entity.location, username, password))
    }

    private sealed interface ImportOutcome {
        class Success(val source: Source, val live: ParsedPlaylist, val vod: VodCatalog = VodCatalog.EMPTY) : ImportOutcome
        class Failure(val message: String) : ImportOutcome
    }

    private suspend fun import(source: Source): ImportOutcome = try {
        val parsed = loader.open(source.location, source.kind).use { M3uParser(source.id).parse(it) }
        if (parsed.channels.isEmpty()) ImportOutcome.Failure("Aucune chaîne trouvée dans cette playlist.")
        else ImportOutcome.Success(source.copy(lastRefreshed = System.currentTimeMillis(), channelCount = parsed.channels.size, lastError = null), parsed)
    } catch (error: Exception) {
        ImportOutcome.Failure(describeImportError(error))
    }

    /** Account check first (refused, expired or disabled accounts stop here), then the live catalogue, films and series. */
    private suspend fun importXtream(source: Source, account: XtreamAccount): ImportOutcome = try {
        val info = xtream.account(account)
        val blocker = info.blocker()
        if (blocker != null) ImportOutcome.Failure(blocker)
        else {
            val live = xtream.liveCatalog(account, source.id, info.liveExtension)
            val vod = VodCatalog(
                xtream.movieCategories(account, source.id), xtream.movies(account, source.id),
                xtream.seriesCategories(account, source.id), xtream.series(account, source.id)
            )
            if (live.channels.isEmpty() && vod.movies.isEmpty() && vod.series.isEmpty()) ImportOutcome.Failure("Ce compte ne donne accès à aucun contenu.")
            else ImportOutcome.Success(
                source.copy(
                    lastRefreshed = System.currentTimeMillis(), channelCount = live.channels.size, lastError = null,
                    expiresAt = info.expiresAt, movieCount = vod.movies.size, seriesCount = vod.series.size
                ),
                live, vod
            )
        }
    } catch (error: Exception) {
        ImportOutcome.Failure(describeXtreamError(error))
    }
}
