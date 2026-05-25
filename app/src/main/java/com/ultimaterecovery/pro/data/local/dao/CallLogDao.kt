package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity
import com.ultimaterecovery.pro.data.local.entity.CallLogEntity.CallType
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [CallLogEntity].
 *
 * Provides CRUD operations and reactive queries for recovered call log
 * records, including filtering by call type, phone number, date range,
 * contact name, and unique-number extraction.
 */
@Dao
interface CallLogDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(callLog: CallLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(callLogs: List<CallLogEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(callLog: CallLogEntity)

    @Delete
    suspend fun delete(callLog: CallLogEntity)

    @Query("DELETE FROM ${CallLogEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${CallLogEntity.TABLE_NAME} ORDER BY ${CallLogEntity.COLUMN_DATE} DESC")
    fun getAll(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM ${CallLogEntity.TABLE_NAME} WHERE ${CallLogEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<CallLogEntity?>

    @Query(
        """
        SELECT * FROM ${CallLogEntity.TABLE_NAME}
        WHERE ${CallLogEntity.COLUMN_CALL_TYPE} = :callType
        ORDER BY ${CallLogEntity.COLUMN_DATE} DESC
        """
    )
    fun getByCallType(callType: CallType): Flow<List<CallLogEntity>>

    @Query(
        """
        SELECT * FROM ${CallLogEntity.TABLE_NAME}
        WHERE ${CallLogEntity.COLUMN_NUMBER} = :number
        ORDER BY ${CallLogEntity.COLUMN_DATE} DESC
        """
    )
    fun getByNumber(number: String): Flow<List<CallLogEntity>>

    @Query(
        """
        SELECT * FROM ${CallLogEntity.TABLE_NAME}
        WHERE ${CallLogEntity.COLUMN_DATE} BETWEEN :start AND :end
        ORDER BY ${CallLogEntity.COLUMN_DATE} DESC
        """
    )
    fun getByDateRange(start: Long, end: Long): Flow<List<CallLogEntity>>

    @Query(
        """
        SELECT * FROM ${CallLogEntity.TABLE_NAME}
        WHERE ${CallLogEntity.COLUMN_CONTACT_NAME} = :contactName
        ORDER BY ${CallLogEntity.COLUMN_DATE} DESC
        """
    )
    fun getByContactName(contactName: String): Flow<List<CallLogEntity>>

    // ──────────────────────────────────────────────
    // Aggregate queries
    // ──────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM ${CallLogEntity.TABLE_NAME}")
    fun getCount(): Flow<Int>

    @Query("SELECT DISTINCT ${CallLogEntity.COLUMN_NUMBER} FROM ${CallLogEntity.TABLE_NAME}")
    fun getUniqueNumbers(): Flow<List<String>>
}
