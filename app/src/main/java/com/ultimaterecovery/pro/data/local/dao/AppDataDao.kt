package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity
import com.ultimaterecovery.pro.data.local.entity.AppDataEntity.AppDataType
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [AppDataEntity].
 *
 * Provides CRUD operations and reactive queries for recovered app data
 * records, including filtering by package name, data type, system-app
 * flag, and search across app name and package name.
 */
@Dao
interface AppDataDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(appData: AppDataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(appDataList: List<AppDataEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(appData: AppDataEntity)

    @Delete
    suspend fun delete(appData: AppDataEntity)

    @Query("DELETE FROM ${AppDataEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${AppDataEntity.TABLE_NAME} ORDER BY ${AppDataEntity.COLUMN_RECOVERY_DATE} DESC")
    fun getAll(): Flow<List<AppDataEntity>>

    @Query("SELECT * FROM ${AppDataEntity.TABLE_NAME} WHERE ${AppDataEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<AppDataEntity?>

    @Query(
        """
        SELECT * FROM ${AppDataEntity.TABLE_NAME}
        WHERE ${AppDataEntity.COLUMN_PACKAGE_NAME} = :packageName
        ORDER BY ${AppDataEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByPackageName(packageName: String): Flow<List<AppDataEntity>>

    @Query(
        """
        SELECT * FROM ${AppDataEntity.TABLE_NAME}
        WHERE ${AppDataEntity.COLUMN_DATA_TYPE} = :dataType
        ORDER BY ${AppDataEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByDataType(dataType: AppDataType): Flow<List<AppDataEntity>>

    @Query(
        """
        SELECT * FROM ${AppDataEntity.TABLE_NAME}
        WHERE ${AppDataEntity.COLUMN_APP_NAME} LIKE '%' || :query || '%'
           OR ${AppDataEntity.COLUMN_PACKAGE_NAME} LIKE '%' || :query || '%'
        ORDER BY ${AppDataEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun searchApps(query: String): Flow<List<AppDataEntity>>

    @Query(
        """
        SELECT * FROM ${AppDataEntity.TABLE_NAME}
        WHERE ${AppDataEntity.COLUMN_IS_SYSTEM_APP} = 1
        ORDER BY ${AppDataEntity.COLUMN_APP_NAME} ASC
        """
    )
    fun getSystemApps(): Flow<List<AppDataEntity>>

    @Query(
        """
        SELECT * FROM ${AppDataEntity.TABLE_NAME}
        WHERE ${AppDataEntity.COLUMN_IS_SYSTEM_APP} = 0
        ORDER BY ${AppDataEntity.COLUMN_APP_NAME} ASC
        """
    )
    fun getNonSystemApps(): Flow<List<AppDataEntity>>
}
