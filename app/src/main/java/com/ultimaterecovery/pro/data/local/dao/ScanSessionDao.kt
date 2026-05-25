package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanStatus
import com.ultimaterecovery.pro.data.local.entity.ScanSessionEntity.ScanType
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [ScanSessionEntity].
 *
 * Provides CRUD operations and reactive queries for scan session records,
 * including filtering by status and type, as well as convenience methods
 * for progress updates and session lifecycle management.
 */
@Dao
interface ScanSessionDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ScanSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<ScanSessionEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(session: ScanSessionEntity)

    @Delete
    suspend fun delete(session: ScanSessionEntity)

    @Query("DELETE FROM ${ScanSessionEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${ScanSessionEntity.TABLE_NAME}")
    fun getAll(): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM ${ScanSessionEntity.TABLE_NAME} WHERE ${ScanSessionEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<ScanSessionEntity?>

    @Query(
        """
        SELECT * FROM ${ScanSessionEntity.TABLE_NAME}
        WHERE ${ScanSessionEntity.COLUMN_STATUS} = :status
        ORDER BY ${ScanSessionEntity.COLUMN_START_TIME} DESC
        """
    )
    fun getByStatus(status: ScanStatus): Flow<List<ScanSessionEntity>>

    @Query(
        """
        SELECT * FROM ${ScanSessionEntity.TABLE_NAME}
        WHERE ${ScanSessionEntity.COLUMN_STATUS} IN (0, 1)
        ORDER BY ${ScanSessionEntity.COLUMN_START_TIME} DESC
        LIMIT 1
        """
    )
    fun getActiveSession(): Flow<ScanSessionEntity?>

    @Query(
        """
        SELECT * FROM ${ScanSessionEntity.TABLE_NAME}
        WHERE ${ScanSessionEntity.COLUMN_STATUS} = 2
        ORDER BY ${ScanSessionEntity.COLUMN_END_TIME} DESC
        """
    )
    fun getCompletedSessions(): Flow<List<ScanSessionEntity>>

    @Query(
        """
        SELECT * FROM ${ScanSessionEntity.TABLE_NAME}
        WHERE ${ScanSessionEntity.COLUMN_SCAN_TYPE} = :scanType
        ORDER BY ${ScanSessionEntity.COLUMN_START_TIME} DESC
        """
    )
    fun getByScanType(scanType: ScanType): Flow<List<ScanSessionEntity>>

    // ──────────────────────────────────────────────
    // Targeted mutations
    // ──────────────────────────────────────────────

    @Query(
        """
        UPDATE ${ScanSessionEntity.TABLE_NAME}
        SET ${ScanSessionEntity.COLUMN_PROGRESS} = :progress,
            ${ScanSessionEntity.COLUMN_SECTORS_SCANNED} = :sectorsScanned,
            ${ScanSessionEntity.COLUMN_TOTAL_FILES_FOUND} = :totalFilesFound,
            ${ScanSessionEntity.COLUMN_TOTAL_SIZE_FOUND} = :totalSizeFound
        WHERE ${ScanSessionEntity.COLUMN_ID} = :id
        """
    )
    suspend fun updateProgress(
        id: Long,
        progress: Float,
        sectorsScanned: Long,
        totalFilesFound: Int,
        totalSizeFound: Long
    )

    @Query(
        """
        UPDATE ${ScanSessionEntity.TABLE_NAME}
        SET ${ScanSessionEntity.COLUMN_STATUS} = 2,
            ${ScanSessionEntity.COLUMN_END_TIME} = :endTime,
            ${ScanSessionEntity.COLUMN_PROGRESS} = 1.0
        WHERE ${ScanSessionEntity.COLUMN_ID} = :id
        """
    )
    suspend fun completeSession(id: Long, endTime: Long)

    @Query(
        """
        UPDATE ${ScanSessionEntity.TABLE_NAME}
        SET ${ScanSessionEntity.COLUMN_STATUS} = 3,
            ${ScanSessionEntity.COLUMN_END_TIME} = :endTime
        WHERE ${ScanSessionEntity.COLUMN_ID} = :id
        """
    )
    suspend fun cancelSession(id: Long, endTime: Long)
}
