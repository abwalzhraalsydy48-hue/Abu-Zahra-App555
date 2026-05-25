package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.FileCategory
import com.ultimaterecovery.pro.data.local.entity.RecoveredFileEntity.RecoveryStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [RecoveredFileEntity].
 *
 * Provides comprehensive CRUD operations and reactive queries for
 * recovered file records, including filtering by category, status,
 * session, extension, and full-text search on file names.
 */
@Dao
interface RecoveredFileDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: RecoveredFileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<RecoveredFileEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(file: RecoveredFileEntity)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateAll(files: List<RecoveredFileEntity>)

    @Delete
    suspend fun delete(file: RecoveredFileEntity)

    @Delete
    suspend fun deleteAll(files: List<RecoveredFileEntity>)

    @Query("DELETE FROM ${RecoveredFileEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}")
    fun getAll(): Flow<List<RecoveredFileEntity>>

    @Query("SELECT * FROM ${RecoveredFileEntity.TABLE_NAME} WHERE ${RecoveredFileEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<RecoveredFileEntity?>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_CATEGORY} = :category
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getAllByCategory(category: FileCategory): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_RECOVERY_STATUS} = :status
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getAllByStatus(status: RecoveryStatus): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_SCAN_SESSION_ID} = :sessionId
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getBySessionId(sessionId: Long): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_FILE_EXTENSION} = :ext
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByExtension(ext: String): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_FILE_NAME} LIKE '%' || :query || '%'
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun searchFiles(query: String): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_IS_FAVORITE} = 1
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getFavorites(): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} BETWEEN :start AND :end
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByDateRange(start: Long, end: Long): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_MIME_TYPE} = :mimeType
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByMimeType(mimeType: String): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        ORDER BY ${RecoveredFileEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getSortedByDate(): Flow<List<RecoveredFileEntity>>

    @Query(
        """
        SELECT * FROM ${RecoveredFileEntity.TABLE_NAME}
        ORDER BY ${RecoveredFileEntity.COLUMN_FILE_SIZE} DESC
        """
    )
    fun getSortedBySize(): Flow<List<RecoveredFileEntity>>

    // ──────────────────────────────────────────────
    // Aggregate queries
    // ──────────────────────────────────────────────

    @Query(
        """
        SELECT ${RecoveredFileEntity.COLUMN_CATEGORY} AS category,
               SUM(${RecoveredFileEntity.COLUMN_FILE_SIZE}) AS totalSize
        FROM ${RecoveredFileEntity.TABLE_NAME}
        GROUP BY ${RecoveredFileEntity.COLUMN_CATEGORY}
        """
    )
    fun getSizeByCategory(): Flow<List<CategorySizeTuple>>

    @Query(
        """
        SELECT SUM(${RecoveredFileEntity.COLUMN_FILE_SIZE})
        FROM ${RecoveredFileEntity.TABLE_NAME}
        """
    )
    fun getTotalRecoveredSize(): Flow<Long?>

    @Query(
        """
        SELECT COUNT(*)
        FROM ${RecoveredFileEntity.TABLE_NAME}
        """
    )
    fun getCount(): Flow<Int>

    // ──────────────────────────────────────────────
    // Targeted mutations
    // ──────────────────────────────────────────────

    @Query(
        """
        DELETE FROM ${RecoveredFileEntity.TABLE_NAME}
        WHERE ${RecoveredFileEntity.COLUMN_SCAN_SESSION_ID} = :sessionId
        """
    )
    suspend fun deleteBySessionId(sessionId: Long)

    @Query(
        """
        UPDATE ${RecoveredFileEntity.TABLE_NAME}
        SET ${RecoveredFileEntity.COLUMN_RECOVERY_STATUS} = :status
        WHERE ${RecoveredFileEntity.COLUMN_ID} = :id
        """
    )
    suspend fun updateStatus(id: Long, status: RecoveryStatus)

    @Query(
        """
        UPDATE ${RecoveredFileEntity.TABLE_NAME}
        SET ${RecoveredFileEntity.COLUMN_IS_FAVORITE} = :isFavorite
        WHERE ${RecoveredFileEntity.COLUMN_ID} = :id
        """
    )
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)
}

/**
 * Tuple returned by the [RecoveredFileDao.getSizeByCategory] aggregate query.
 *
 * Maps a [FileCategory] to the total file size in that category.
 * Room auto-maps the column names to these fields.
 */
data class CategorySizeTuple(
    val category: FileCategory,
    val totalSize: Long
)
