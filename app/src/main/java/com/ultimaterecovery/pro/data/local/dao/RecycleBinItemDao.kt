package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.RecycleBinItemEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [RecycleBinItemEntity].
 *
 * Provides CRUD operations and reactive queries for recycle bin items,
 * including filtering by category, original path, expiry status,
 * date range, full-text search, and automatic expired-item cleanup.
 */
@Dao
interface RecycleBinItemDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RecycleBinItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecycleBinItemEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(item: RecycleBinItemEntity)

    @Delete
    suspend fun delete(item: RecycleBinItemEntity)

    @Query("DELETE FROM ${RecycleBinItemEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${RecycleBinItemEntity.TABLE_NAME} ORDER BY ${RecycleBinItemEntity.COLUMN_DELETED_DATE} DESC")
    fun getAll(): Flow<List<RecycleBinItemEntity>>

    @Query("SELECT * FROM ${RecycleBinItemEntity.TABLE_NAME} WHERE ${RecycleBinItemEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<RecycleBinItemEntity?>

    @Query(
        """
        SELECT * FROM ${RecycleBinItemEntity.TABLE_NAME}
        WHERE ${RecycleBinItemEntity.COLUMN_CATEGORY} = :category
        ORDER BY ${RecycleBinItemEntity.COLUMN_DELETED_DATE} DESC
        """
    )
    fun getByCategory(category: FileCategory): Flow<List<RecycleBinItemEntity>>

    @Query(
        """
        SELECT * FROM ${RecycleBinItemEntity.TABLE_NAME}
        WHERE ${RecycleBinItemEntity.COLUMN_EXPIRY_DATE} <= :currentTime
        ORDER BY ${RecycleBinItemEntity.COLUMN_EXPIRY_DATE} ASC
        """
    )
    fun getExpiredItems(currentTime: Long): Flow<List<RecycleBinItemEntity>>

    @Query(
        """
        SELECT * FROM ${RecycleBinItemEntity.TABLE_NAME}
        WHERE ${RecycleBinItemEntity.COLUMN_ORIGINAL_PATH} = :originalPath
        ORDER BY ${RecycleBinItemEntity.COLUMN_DELETED_DATE} DESC
        """
    )
    fun getByOriginalPath(originalPath: String): Flow<List<RecycleBinItemEntity>>

    @Query(
        """
        SELECT * FROM ${RecycleBinItemEntity.TABLE_NAME}
        WHERE ${RecycleBinItemEntity.COLUMN_FILE_NAME} LIKE '%' || :query || '%'
        ORDER BY ${RecycleBinItemEntity.COLUMN_DELETED_DATE} DESC
        """
    )
    fun searchItems(query: String): Flow<List<RecycleBinItemEntity>>

    @Query(
        """
        SELECT * FROM ${RecycleBinItemEntity.TABLE_NAME}
        WHERE ${RecycleBinItemEntity.COLUMN_DELETED_DATE} BETWEEN :start AND :end
        ORDER BY ${RecycleBinItemEntity.COLUMN_DELETED_DATE} DESC
        """
    )
    fun getByDateRange(start: Long, end: Long): Flow<List<RecycleBinItemEntity>>

    // ──────────────────────────────────────────────
    // Aggregate queries
    // ──────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM ${RecycleBinItemEntity.TABLE_NAME}")
    fun getCount(): Flow<Int>

    // ──────────────────────────────────────────────
    // Targeted mutations
    // ──────────────────────────────────────────────

    @Query(
        """
        DELETE FROM ${RecycleBinItemEntity.TABLE_NAME}
        WHERE ${RecycleBinItemEntity.COLUMN_EXPIRY_DATE} <= :currentTime
        """
    )
    suspend fun deleteExpiredItems(currentTime: Long)
}
