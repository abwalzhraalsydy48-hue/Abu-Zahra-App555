package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.RecoveryHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [RecoveryHistoryEntity].
 *
 * Provides CRUD operations and reactive queries for recovery history
 * records, including filtering by file type, date range, success/failure
 * status, and aggregate totals for recovered size and count.
 */
@Dao
interface RecoveryHistoryDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: RecoveryHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(histories: List<RecoveryHistoryEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(history: RecoveryHistoryEntity)

    @Delete
    suspend fun delete(history: RecoveryHistoryEntity)

    @Query("DELETE FROM ${RecoveryHistoryEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${RecoveryHistoryEntity.TABLE_NAME} ORDER BY ${RecoveryHistoryEntity.COLUMN_RECOVERY_DATE} DESC")
    fun getAll(): Flow<List<RecoveryHistoryEntity>>

    @Query("SELECT * FROM ${RecoveryHistoryEntity.TABLE_NAME} WHERE ${RecoveryHistoryEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<RecoveryHistoryEntity?>

    @Query(
        """
        SELECT * FROM ${RecoveryHistoryEntity.TABLE_NAME}
        WHERE ${RecoveryHistoryEntity.COLUMN_FILE_TYPE} = :fileType
        ORDER BY ${RecoveryHistoryEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByFileType(fileType: String): Flow<List<RecoveryHistoryEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveryHistoryEntity.TABLE_NAME}
        WHERE ${RecoveryHistoryEntity.COLUMN_RECOVERY_DATE} BETWEEN :start AND :end
        ORDER BY ${RecoveryHistoryEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByDateRange(start: Long, end: Long): Flow<List<RecoveryHistoryEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveryHistoryEntity.TABLE_NAME}
        WHERE ${RecoveryHistoryEntity.COLUMN_IS_SUCCESSFUL} = 1
        ORDER BY ${RecoveryHistoryEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getSuccessfulRecoveries(): Flow<List<RecoveryHistoryEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveryHistoryEntity.TABLE_NAME}
        WHERE ${RecoveryHistoryEntity.COLUMN_IS_SUCCESSFUL} = 0
        ORDER BY ${RecoveryHistoryEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getFailedRecoveries(): Flow<List<RecoveryHistoryEntity>>

    // ──────────────────────────────────────────────
    // Aggregate queries
    // ──────────────────────────────────────────────

    @Query(
        """
        SELECT COALESCE(SUM(${RecoveryHistoryEntity.COLUMN_TOTAL_SIZE}), 0)
        FROM ${RecoveryHistoryEntity.TABLE_NAME}
        WHERE ${RecoveryHistoryEntity.COLUMN_IS_SUCCESSFUL} = 1
        """
    )
    fun getTotalRecoveredSize(): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(${RecoveryHistoryEntity.COLUMN_FILE_COUNT}), 0)
        FROM ${RecoveryHistoryEntity.TABLE_NAME}
        WHERE ${RecoveryHistoryEntity.COLUMN_IS_SUCCESSFUL} = 1
        """
    )
    fun getTotalRecoveredCount(): Flow<Int>
}
