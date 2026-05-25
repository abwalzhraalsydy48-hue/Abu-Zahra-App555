package com.ultimaterecovery.pro.data.repository

import com.ultimaterecovery.pro.data.local.dao.RecoveryHistoryDao
import com.ultimaterecovery.pro.data.local.entity.RecoveryHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for [RecoveryHistoryEntity] records.
 *
 * Provides an audit trail of all recovery operations with reactive
 * queries for filtering by file type, date range, and success/failure
 * status, as well as aggregate statistics.
 */
@Singleton
class RecoveryHistoryRepository @Inject constructor(
    private val recoveryHistoryDao: RecoveryHistoryDao
) {

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    /**
     * Persists a new recovery history record.
     *
     * @return [Resource] with the generated row ID.
     */
    suspend fun addRecord(history: RecoveryHistoryEntity): Resource<Long> =
        try {
            val id = recoveryHistoryDao.insert(history)
            Resource.success(id)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to add recovery history record")
        }

    /**
     * Removes all recovery history records from the database.
     *
     * Use with caution — this operation is irreversible.
     */
    suspend fun clearHistory(): Resource<Unit> =
        try {
            recoveryHistoryDao.deleteAll()
            Resource.success(Unit)
        } catch (e: Exception) {
            Resource.error(e.message ?: "Failed to clear recovery history")
        }

    // ──────────────────────────────────────────────
    // Reactive queries
    // ──────────────────────────────────────────────

    /**
     * Emits all recovery history records in descending recovery-date order.
     */
    fun getHistory(): Flow<Resource<List<RecoveryHistoryEntity>>> =
        recoveryHistoryDao.getAll()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load recovery history")) }

    /**
     * Emits recovery history records matching the given [fileType].
     */
    fun getByFileType(fileType: String): Flow<Resource<List<RecoveryHistoryEntity>>> =
        recoveryHistoryDao.getByFileType(fileType)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load history by file type")) }

    /**
     * Emits recovery history records whose date falls within
     * [start]..[end] (inclusive).
     */
    fun getByDateRange(start: Long, end: Long): Flow<Resource<List<RecoveryHistoryEntity>>> =
        recoveryHistoryDao.getByDateRange(start, end)
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load history by date range")) }

    /**
     * Emits only the successful recovery operations.
     */
    fun getSuccessfulRecoveries(): Flow<Resource<List<RecoveryHistoryEntity>>> =
        recoveryHistoryDao.getSuccessfulRecoveries()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load successful recoveries")) }

    /**
     * Emits only the failed recovery operations.
     */
    fun getFailedRecoveries(): Flow<Resource<List<RecoveryHistoryEntity>>> =
        recoveryHistoryDao.getFailedRecoveries()
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load failed recoveries")) }

    /**
     * Emits aggregate recovery statistics by combining the total
     * recovered size and total recovered count flows.
     */
    fun getStats(): Flow<Resource<RecoveryStats>> =
        recoveryHistoryDao.getTotalRecoveredSize()
            .combine(recoveryHistoryDao.getTotalRecoveredCount()) { totalSize, totalCount ->
                RecoveryStats(
                    totalRecoveredSize = totalSize,
                    totalRecoveredCount = totalCount
                )
            }
            .map { Resource.success(it) }
            .catch { e -> emit(Resource.error(e.message ?: "Failed to load recovery stats")) }
}

/**
 * Aggregate statistics about recovery operations.
 */
data class RecoveryStats(
    val totalRecoveredSize: Long,
    val totalRecoveredCount: Int
)
