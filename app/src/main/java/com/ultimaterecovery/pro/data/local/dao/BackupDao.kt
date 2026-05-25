package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.BackupEntity
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupStatus
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.BackupType
import com.ultimaterecovery.pro.data.local.entity.BackupEntity.CloudProvider
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [BackupEntity].
 *
 * Provides CRUD operations and reactive queries for backup records,
 * including filtering by type, cloud provider, status, date range,
 * and convenience methods for pending/completed backup retrieval
 * and status updates.
 */
@Dao
interface BackupDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(backup: BackupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(backups: List<BackupEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(backup: BackupEntity)

    @Delete
    suspend fun delete(backup: BackupEntity)

    @Query("DELETE FROM ${BackupEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${BackupEntity.TABLE_NAME} ORDER BY ${BackupEntity.COLUMN_BACKUP_DATE} DESC")
    fun getAll(): Flow<List<BackupEntity>>

    @Query("SELECT * FROM ${BackupEntity.TABLE_NAME} WHERE ${BackupEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<BackupEntity?>

    @Query(
        """
        SELECT * FROM ${BackupEntity.TABLE_NAME}
        WHERE ${BackupEntity.COLUMN_BACKUP_TYPE} = :type
        ORDER BY ${BackupEntity.COLUMN_BACKUP_DATE} DESC
        """
    )
    fun getByType(type: BackupType): Flow<List<BackupEntity>>

    @Query(
        """
        SELECT * FROM ${BackupEntity.TABLE_NAME}
        WHERE ${BackupEntity.COLUMN_CLOUD_PROVIDER} = :cloudProvider
        ORDER BY ${BackupEntity.COLUMN_BACKUP_DATE} DESC
        """
    )
    fun getByCloudProvider(cloudProvider: CloudProvider): Flow<List<BackupEntity>>

    @Query(
        """
        SELECT * FROM ${BackupEntity.TABLE_NAME}
        WHERE ${BackupEntity.COLUMN_STATUS} = 2
           OR ${BackupEntity.COLUMN_STATUS} = 5
        ORDER BY ${BackupEntity.COLUMN_BACKUP_DATE} DESC
        """
    )
    fun getCompletedBackups(): Flow<List<BackupEntity>>

    @Query(
        """
        SELECT * FROM ${BackupEntity.TABLE_NAME}
        WHERE ${BackupEntity.COLUMN_STATUS} = 0
           OR ${BackupEntity.COLUMN_STATUS} = 1
           OR ${BackupEntity.COLUMN_STATUS} = 4
        ORDER BY ${BackupEntity.COLUMN_BACKUP_DATE} ASC
        """
    )
    fun getPendingBackups(): Flow<List<BackupEntity>>

    @Query(
        """
        SELECT * FROM ${BackupEntity.TABLE_NAME}
        WHERE ${BackupEntity.COLUMN_BACKUP_DATE} BETWEEN :start AND :end
        ORDER BY ${BackupEntity.COLUMN_BACKUP_DATE} DESC
        """
    )
    fun getByDateRange(start: Long, end: Long): Flow<List<BackupEntity>>

    // ──────────────────────────────────────────────
    // Targeted mutations
    // ──────────────────────────────────────────────

    @Query(
        """
        UPDATE ${BackupEntity.TABLE_NAME}
        SET ${BackupEntity.COLUMN_STATUS} = :status
        WHERE ${BackupEntity.COLUMN_ID} = :id
        """
    )
    suspend fun updateStatus(id: Long, status: BackupStatus)
}
