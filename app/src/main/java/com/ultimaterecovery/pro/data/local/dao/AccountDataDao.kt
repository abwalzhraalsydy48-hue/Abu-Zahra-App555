package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.AccountDataEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [AccountDataEntity].
 *
 * Provides CRUD operations and reactive queries for recovered account
 * data records, including filtering by account type, sensitivity flag,
 * and search across account name and type.
 */
@Dao
interface AccountDataDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountDataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<AccountDataEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(account: AccountDataEntity)

    @Delete
    suspend fun delete(account: AccountDataEntity)

    @Query("DELETE FROM ${AccountDataEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${AccountDataEntity.TABLE_NAME} ORDER BY ${AccountDataEntity.COLUMN_RECOVERY_DATE} DESC")
    fun getAll(): Flow<List<AccountDataEntity>>

    @Query("SELECT * FROM ${AccountDataEntity.TABLE_NAME} WHERE ${AccountDataEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<AccountDataEntity?>

    @Query(
        """
        SELECT * FROM ${AccountDataEntity.TABLE_NAME}
        WHERE ${AccountDataEntity.COLUMN_ACCOUNT_TYPE} = :accountType
        ORDER BY ${AccountDataEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getByAccountType(accountType: String): Flow<List<AccountDataEntity>>

    @Query(
        """
        SELECT * FROM ${AccountDataEntity.TABLE_NAME}
        WHERE ${AccountDataEntity.COLUMN_IS_SENSITIVE} = 1
        ORDER BY ${AccountDataEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun getSensitiveAccounts(): Flow<List<AccountDataEntity>>

    @Query(
        """
        SELECT * FROM ${AccountDataEntity.TABLE_NAME}
        WHERE ${AccountDataEntity.COLUMN_ACCOUNT_NAME} LIKE '%' || :query || '%'
           OR ${AccountDataEntity.COLUMN_ACCOUNT_TYPE} LIKE '%' || :query || '%'
        ORDER BY ${AccountDataEntity.COLUMN_RECOVERY_DATE} DESC
        """
    )
    fun searchAccounts(query: String): Flow<List<AccountDataEntity>>
}
