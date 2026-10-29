package com.miptv

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

@Entity(tableName = "channels", indices = [Index("sourceId"), Index("categoryId")])
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
    val referer: String?
) {
    fun toChannel(favorite: Boolean) = Channel(id, name, categoryId, streamUrl, logoUrl, tvgId, sourceId, favorite, userAgent, referer)
    companion object {
        fun from(channel: Channel, position: Int) = ChannelEntity(
            channel.id, channel.name, channel.categoryId, channel.streamUrl, channel.logoUrl, channel.tvgId, channel.sourceId, position, channel.userAgent, channel.referer
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

    @Query("SELECT * FROM channels ORDER BY sourceId, position") fun channels(): Flow<List<ChannelEntity>>
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

@Database(entities = [SourceEntity::class, CategoryEntity::class, ChannelEntity::class, FavoriteEntity::class], version = 1, exportSchema = false)
abstract class MiptvDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
}

/**
 * Persistent implementation: the imported catalogue, the sources and the favourites survive restarts,
 * so no re-import is needed. Imports are serialised; a failed refresh keeps the previous channels.
 */
class RoomChannelRepository(private val database: MiptvDatabase, private val loader: PlaylistLoader) : ChannelRepository {
    private val dao = database.catalog()
    private val mutex = Mutex()

    override val catalog: Flow<Catalog> = combine(dao.categories(), dao.channels(), dao.favoriteIds()) { categories, channels, favoriteIds ->
        val favorites = favoriteIds.toHashSet()
        Catalog(categories.map { Category(it.id, it.name) }, channels.map { it.toChannel(it.id in favorites) })
    }.flowOn(Dispatchers.Default)

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
