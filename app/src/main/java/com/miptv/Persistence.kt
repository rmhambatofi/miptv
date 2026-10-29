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
    val lastError: String?
) {
    fun toSource() = Source(id, name, location, SourceKind.valueOf(kind), lastRefreshed, channelCount, lastError)
    companion object {
        fun from(source: Source) = SourceEntity(source.id, source.name, source.location, source.kind.name, source.lastRefreshed, source.channelCount, source.lastError)
    }
}

@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val id: String, val name: String, val position: Int)

/** Indices follow the list order (source, then playlist position), globally and within a category. */
@Entity(tableName = "channels", indices = [Index("sourceId", "position"), Index("categoryId", "sourceId", "position")])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String,
    val streamUrl: String,
    val logoUrl: String?,
    val tvgId: String?,
    val sourceId: Long,
    val position: Int,
    val userAgent: String?,
    val referer: String?,
    /** [searchKey] of [name], matched with LIKE: SQLite only folds ASCII case. */
    @ColumnInfo(defaultValue = "") val searchName: String
) {
    fun toChannel() = Channel(id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, userAgent, referer)
    companion object {
        fun from(channel: Channel, position: Int) = ChannelEntity(
            channel.id, channel.name, channel.categoryId, channel.streamUrl, channel.logoUrl, channel.tvgId, channel.sourceId, position, channel.userAgent, channel.referer,
            searchKey(channel.name)
        )
    }
}

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val channelId: String, @ColumnInfo(name = "addedAt") val addedAt: Long)

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
    @Query("DELETE FROM favorites WHERE channelId NOT IN (SELECT id FROM channels)") suspend fun pruneFavorites()

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

@Database(entities = [SourceEntity::class, CategoryEntity::class, ChannelEntity::class, FavoriteEntity::class], version = 2, exportSchema = true)
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
 * Keyset paging over the channel table: each page continues from the last (or first) row read, through the
 * `(sourceId, position)` indices, so a page costs the same at any depth and no page counts the whole query, unlike
 * Room's LIMIT/OFFSET source. It is invalidated by changes to the channels, and to the favourites for that list only.
 */
class ChannelPagingSource(private val database: MiptvDatabase, private val query: ChannelQuery) : PagingSource<ChannelKey, ChannelEntity>() {
    private val favorites = query.categoryId == Category.FAVORITES.id
    private val observer = object : InvalidationTracker.Observer(if (favorites) arrayOf("channels", "favorites") else arrayOf("channels")) {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }
    private val observing = AtomicBoolean(false)

    init { registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) } }

    override suspend fun load(params: LoadParams<ChannelKey>): LoadResult<ChannelKey, ChannelEntity> {
        // Observe before the first read, off the main thread (Room requires it), so no change slips between the two.
        if (observing.compareAndSet(false, true)) withContext(Dispatchers.IO) { database.invalidationTracker.addObserver(observer) }
        val key = params.key
        val rows = database.catalog().page(sql(key, params.loadSize)).let { if (key?.direction == ChannelKey.Direction.BEFORE) it.reversed() else it }
        if (invalid) return LoadResult.Invalid()
        val full = rows.size == params.loadSize
        val first = rows.firstOrNull()?.let { ChannelKey(it.sourceId, it.position, ChannelKey.Direction.BEFORE) }
        val last = rows.lastOrNull()?.let { ChannelKey(it.sourceId, it.position, ChannelKey.Direction.AFTER) }
        return when (key?.direction) {
            null -> LoadResult.Page(rows, prevKey = null, nextKey = last.takeIf { full })
            // Refresh in the middle: prepends bring back what precedes the keyed channel, even when nothing follows it.
            ChannelKey.Direction.FROM -> LoadResult.Page(rows, prevKey = first ?: key.copy(direction = ChannelKey.Direction.BEFORE), nextKey = last.takeIf { full })
            ChannelKey.Direction.AFTER -> LoadResult.Page(rows, prevKey = null, nextKey = last.takeIf { full })
            ChannelKey.Direction.BEFORE -> LoadResult.Page(rows, prevKey = first.takeIf { full }, nextKey = null)
        }
    }

    /** Reload from the channel the user was looking at; earlier ones come back through prepends. */
    override fun getRefreshKey(state: PagingState<ChannelKey, ChannelEntity>): ChannelKey? =
        state.anchorPosition?.let { state.closestItemToPosition(it) }?.let { ChannelKey(it.sourceId, it.position, ChannelKey.Direction.FROM) }

    private fun sql(key: ChannelKey?, limit: Int): SimpleSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        when (query.categoryId) {
            Category.ALL.id -> Unit
            Category.FAVORITES.id -> where += "id IN (SELECT channelId FROM favorites)"
            else -> { where += "categoryId = ?"; args += query.categoryId }
        }
        if (query.text.isNotBlank()) { where += "searchName LIKE ? ESCAPE '\\'"; args += likePattern(query.text) }
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
        val filter = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        return SimpleSQLiteQuery("SELECT * FROM channels$filter ORDER BY sourceId $order, position $order LIMIT $limit", args.toTypedArray())
    }
}

/**
 * Persistent implementation: the imported catalogue, the sources and the favourites survive restarts,
 * so no re-import is needed. Imports are serialised; a failed refresh keeps the previous channels.
 */
class RoomChannelRepository(private val database: MiptvDatabase, private val loader: PlaylistLoader) : ChannelRepository {
    private val dao = database.catalog()
    private val mutex = Mutex()

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
        val source = Source((dao.maxSourceId() ?: 0L) + 1, name, location, kind)
        when (val result = import(source)) {
            is ImportOutcome.Failure -> ImportResult.Failure(result.message)
            is ImportOutcome.Success -> {
                database.withTransaction {
                    dao.insertSource(SourceEntity.from(result.source))
                    dao.replaceChannels(source.id, result.parsed)
                }
                ImportResult.Success(result.source)
            }
        }
    }

    override suspend fun refreshSource(id: Long): ImportResult = mutex.withLock {
        val source = dao.source(id)?.toSource() ?: return ImportResult.Failure("Source introuvable.")
        when (val result = import(source)) {
            is ImportOutcome.Failure -> {
                dao.updateSource(SourceEntity.from(source.copy(lastError = result.message)))
                ImportResult.Failure(result.message)
            }
            is ImportOutcome.Success -> {
                database.withTransaction {
                    dao.updateSource(SourceEntity.from(result.source))
                    dao.replaceChannels(id, result.parsed)
                }
                ImportResult.Success(result.source)
            }
        }
    }

    override suspend fun removeSource(id: Long) = mutex.withLock {
        database.withTransaction {
            dao.deleteChannels(id)
            dao.deleteSource(id)
            dao.pruneCategories()
            dao.pruneFavorites()
        }
    }

    override suspend fun setFavorite(channelId: String, favorite: Boolean) {
        if (favorite) dao.insertFavorite(FavoriteEntity(channelId, System.currentTimeMillis())) else dao.deleteFavorite(channelId)
    }

    private sealed interface ImportOutcome {
        class Success(val source: Source, val parsed: ParsedPlaylist) : ImportOutcome
        class Failure(val message: String) : ImportOutcome
    }

    private suspend fun import(source: Source): ImportOutcome = try {
        val parsed = loader.open(source.location, source.kind).use { M3uParser(source.id).parse(it) }
        if (parsed.channels.isEmpty()) ImportOutcome.Failure("Aucune chaîne trouvée dans cette playlist.")
        else ImportOutcome.Success(source.copy(lastRefreshed = System.currentTimeMillis(), channelCount = parsed.channels.size, lastError = null), parsed)
    } catch (error: Exception) {
        ImportOutcome.Failure(describeImportError(error))
    }
}
