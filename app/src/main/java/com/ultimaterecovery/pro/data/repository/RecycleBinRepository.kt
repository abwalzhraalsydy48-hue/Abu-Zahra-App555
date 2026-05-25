package com.ultimaterecovery.pro.data.repository

import com.ultimaterecovery.pro.data.local.dao.RecycleBinItemDao
import com.ultimaterecovery.pro.data.local.entity.RecycleBinItemEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [RecycleBinItemEntity] records.
 *
 * Manages the recycle-bin lifecycle — moving items in, restoring
 * them out, permanent deletion, expiry tracking, and automatic
 * cleanup of expired entries.
 */
@Singleton
class RecycleBinRepository @Inject constructor(
    private val recycleBinItemDao: RecycleBinItemDao
) {

    companion object {
        /** Default number of days before a recycle-bin item expires. */
        const val DEFAULT_AUTO_DELETE_DAYS = 30
    }

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Moves an item into the recycle bin by persisting a
     * [RecycleBinItemEntity]. The [item] should already contain
     * the `expiryDate` computed from the `autoDeleteDays` field.
     *
     * @return [Resource] with the generated row ID.
     */
    suspend fun moveToRecycleBin(item: RecycleBinItemEntity): Resource<Long> =
        try {
            val id = recycleBinItemDao.insert(item)
            Resource.success(id)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to move item to recycle bin")
        }

    /**
     * Restores an item from the recycle bin by deleting the
     * [RecycleBinItemEntity] record. The caller is responsible for
     * moving the underlying file back to its original path.
     *
     * @return [Resource.Unit] on success.
     */
    suspend fun restoreFromRecycleBin(item: RecycleBinItemEntity): Resource<Unit> {
        return try {
            if (!item.isRestorable) {
                Resource.error("Item is not restorable", code = 400)
            } else {
                recycleBinItemDao.delete(item)
                Resource.success(Unit)
            }
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to restore item from recycle bin")
        }
    }

    /**
     * Permanently deletes an item from both the database and (in a
     * full implementation) the underlying storage.
     *
     * @return [Resource.Unit] on success.
     */
    suspend fun deletePermanent(item: RecycleBinItemEntity): Resource<Unit> =
        try {
            // TODO: Delete the actual file from storage before removing the record.
            recycleBinItemDao.delete(item)
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to permanently delete item")
        }

    /**
     * Removes all expired items from the recycle bin.
     *
     * @param currentTime The reference timestamp (defaults to
     *        `System.currentTimeMillis()`).
     * @return [Resource] with the number of items deleted, or an error.
     */
    suspend fun cleanExpired(currentTime: Long = System.currentTimeMillis()): Resource<Int> =
        try {
            // Room's @Query DELETE returns the number of affected rows,
            // but since deleteExpiredItems is a suspend Query returning Unit,
            // we perform the cleanup and return a success indicator.
            recycleBinItemDao.deleteExpiredItems(currentTime)
            Resource.success(0) // count unknown from DAO; 0 signals "completed"
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to clean expired items")
        }

    /**
     * Convenience method that runs the full auto-cleanup pipeline:
     * finds all expired items and permanently deletes them.
     *
     * @return [Resource.Unit] when cleanup completes successfully.
     */
    suspend fun autoCleanup(): Resource<Unit> =
        try {
            recycleBinItemDao.deleteExpiredItems(System.currentTimeMillis())
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Auto-cleanup failed")
        }

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits all items that have passed their expiry date as of
     * [currentTime].
     */
    fun getExpiredItems(currentTime: Long = System.currentTimeMillis()): Flow<Resource<List<RecycleBinItemEntity>>> =
        recycleBinItemDao.getExpiredItems(currentTime)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load expired items")) }

    /**
     * Emits all recycle-bin items in the given [category].
     */
    fun getByCategory(category: FileCategory): Flow<Resource<List<RecycleBinItemEntity>>> =
        recycleBinItemDao.getByCategory(category)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load recycle bin by category")) }

    /**
     * Full-text search across file names in the recycle bin.
     */
    fun searchItems(query: String): Flow<Resource<List<RecycleBinItemEntity>>> =
        recycleBinItemDao.searchItems(query)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to search recycle bin")) }
}
