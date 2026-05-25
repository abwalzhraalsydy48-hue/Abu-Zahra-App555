package com.ultimaterecovery.pro.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity
import com.ultimaterecovery.pro.data.local.entity.SmsMessageEntity.SmsType
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [SmsMessageEntity].
 *
 * Provides CRUD operations and reactive queries for recovered SMS message
 * records, including filtering by type, address, thread, date range,
 * contact name, and full-text search on message body.
 */
@Dao
interface SmsMessageDao {

    // ──────────────────────────────────────────────
    // Insert / Update / Delete
    // ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: SmsMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<SmsMessageEntity>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(message: SmsMessageEntity)

    @Delete
    suspend fun delete(message: SmsMessageEntity)

    @Query("DELETE FROM ${SmsMessageEntity.TABLE_NAME}")
    suspend fun deleteAll()

    // ──────────────────────────────────────────────
    // Reactive queries — Flow
    // ──────────────────────────────────────────────

    @Query("SELECT * FROM ${SmsMessageEntity.TABLE_NAME} ORDER BY ${SmsMessageEntity.COLUMN_DATE} DESC")
    fun getAll(): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM ${SmsMessageEntity.TABLE_NAME} WHERE ${SmsMessageEntity.COLUMN_ID} = :id")
    fun getById(id: Long): Flow<SmsMessageEntity?>

    @Query(
        """
        SELECT * FROM ${SmsMessageEntity.TABLE_NAME}
        WHERE ${SmsMessageEntity.COLUMN_TYPE} = :type
        ORDER BY ${SmsMessageEntity.COLUMN_DATE} DESC
        """
    )
    fun getByType(type: SmsType): Flow<List<SmsMessageEntity>>

    @Query(
        """
        SELECT * FROM ${SmsMessageEntity.TABLE_NAME}
        WHERE ${SmsMessageEntity.COLUMN_ADDRESS} = :address
        ORDER BY ${SmsMessageEntity.COLUMN_DATE} DESC
        """
    )
    fun getByAddress(address: String): Flow<List<SmsMessageEntity>>

    @Query(
        """
        SELECT * FROM ${SmsMessageEntity.TABLE_NAME}
        WHERE ${SmsMessageEntity.COLUMN_BODY} LIKE '%' || :query || '%'
           OR ${SmsMessageEntity.COLUMN_ADDRESS} LIKE '%' || :query || '%'
        ORDER BY ${SmsMessageEntity.COLUMN_DATE} DESC
        """
    )
    fun searchMessages(query: String): Flow<List<SmsMessageEntity>>

    @Query(
        """
        SELECT * FROM ${SmsMessageEntity.TABLE_NAME}
        WHERE ${SmsMessageEntity.COLUMN_DATE} BETWEEN :start AND :end
        ORDER BY ${SmsMessageEntity.COLUMN_DATE} DESC
        """
    )
    fun getByDateRange(start: Long, end: Long): Flow<List<SmsMessageEntity>>

    @Query(
        """
        SELECT * FROM ${SmsMessageEntity.TABLE_NAME}
        WHERE ${SmsMessageEntity.COLUMN_THREAD_ID} = :threadId
        ORDER BY ${SmsMessageEntity.COLUMN_DATE} ASC
        """
    )
    fun getByThreadId(threadId: Long): Flow<List<SmsMessageEntity>>

    @Query(
        """
        SELECT * FROM ${SmsMessageEntity.TABLE_NAME}
        WHERE ${SmsMessageEntity.COLUMN_CONTACT_NAME} = :contactName
        ORDER BY ${SmsMessageEntity.COLUMN_DATE} DESC
        """
    )
    fun getByContactName(contactName: String): Flow<List<SmsMessageEntity>>

    // ──────────────────────────────────────────────
    // Aggregate queries
    // ──────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM ${SmsMessageEntity.TABLE_NAME}")
    fun getCount(): Flow<Int>
}
